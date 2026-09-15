#include "app/MainWindow.h"

#include "app/ExportDialog.h"
#include "app/InspectorPanel.h"
#include "app/NewWorldDialog.h"
#include "app/OpenWorldRootDialog.h"
#include "app/ReportPanel.h"
#include "app/SchematicImportDialog.h"
#include "app/ImportProgressDialog.h"
#include "app/OperationProgressDialog.h"
#include <QPointer>
#include <QSet>
#include "app/Settings.h"
#include "app/SpawnMarkerDialog.h"
#include "app/TemplatePanel.h"
#include "layout/InstanceNaming.h"

#include <QActionGroup>
#include <QApplication>
#include <QCloseEvent>
#include <QComboBox>
#include <QDockWidget>
#include <QDragEnterEvent>
#include <QDropEvent>
#include <QFileDialog>
#include <QFileInfo>
#include <QInputDialog>
#include <QLineEdit>
#include <QJsonArray>
#include <QLabel>
#include <QMenuBar>
#include <QMessageBox>
#include <QMimeData>
#include <QProgressBar>
#include <QFile>
#include <QPushButton>
#include <QSpinBox>
#include <QStatusBar>
#include <QTabBar>
#include <QToolBar>
#include <QVBoxLayout>

namespace chunkdaddy {

MainWindow::MainWindow(QWidget* parent) : QMainWindow(parent) {
    setWindowTitle(tr("ChunkDaddy"));
    setAcceptDrops(true);
    resize(1500, 950);

    m_view = new ChunkView(this);
    m_tabs = new QTabBar(this);
    m_tabs->setTabsClosable(true);
    m_tabs->setMovable(true);
    m_tabs->setDocumentMode(true);
    m_tabs->setExpanding(false);

    auto* central = new QWidget(this);
    auto* layout = new QVBoxLayout(central);
    layout->setContentsMargins(0, 0, 0, 0);
    layout->setSpacing(0);
    layout->addWidget(m_tabs);
    layout->addWidget(m_view, 1);
    setCentralWidget(central);

    buildMenus();
    buildDocks();
    buildStatusBar();

    connect(m_tabs, &QTabBar::currentChanged, this, &MainWindow::onTabChanged);
    connect(m_tabs, &QTabBar::tabCloseRequested, this, &MainWindow::onTabCloseRequested);
    connect(m_view, &ChunkView::cursorMoved, this, &MainWindow::onCursorMoved);
    connect(m_view, &ChunkView::tilesNeeded, this, &MainWindow::onTilesNeeded);
    connect(m_view, &ChunkView::previewFailed, this, [this](const QString& error) {
        m_reportPanel->appendLog(tr("Preview: %1").arg(error));
    });
    connect(m_view, &ChunkView::selectionChanged, this, &MainWindow::refreshActions);
    connect(m_view, &ChunkView::moveRequested, this, [this](int dx, int dz) {
        Document* document = currentDocument();
        if (!document) {
            return;
        }
        showBusy(tr("Moving selected chunks…"), -1);
        m_workspace.moveSelection(document, dx, dz, false, [this](const WorkerReply& reply) {
            hideBusy();
            if (!reply.ok) {
                reportError(tr("Move failed"), reply);
                return;
            }
            const QJsonArray invalidated = reply.result.value(QStringLiteral("invalidatedArenas")).toArray();
            if (!invalidated.isEmpty()) {
                QStringList names;
                for (const QJsonValue& value : invalidated) {
                    names << value.toString();
                }
                m_reportPanel->appendReport(
                    tr("Move landed on these arenas, which now need revalidation: %1")
                        .arg(names.join(QStringLiteral(", "))));
            }
            m_reportPanel->setHistory(m_workspace.history().entries());
        });
    });
    connect(m_view, &ChunkView::pasteCommitted, this, [this](int chunkX, int chunkZ) {
        Document* document = currentDocument();
        if (!document) {
            return;
        }
        showBusy(tr("Pasting chunks…"), -1);
        m_workspace.pasteClipboard(document, chunkX, chunkZ, false, [this](const WorkerReply& reply) {
            hideBusy();
            m_view->cancelPastePreview();
            if (!reply.ok) {
                reportError(tr("Paste failed"), reply);
                return;
            }
            m_reportPanel->setHistory(m_workspace.history().entries());
            refreshActions();
        });
    });
    connect(m_view, &ChunkView::worldSpawnPicked, this, [this](const BlockPos& block) {
        Document* document = currentDocument();
        if (!document) {
            return;
        }
        m_workspace.setWorldSpawn(document, block, [this](const WorkerReply& reply) {
            if (!reply.ok) {
                reportError(tr("Could not set the world spawn"), reply);
            }
        });
    });

    connect(&m_workspace.clipboard(), &Clipboard::changed, this, [this] {
        m_clipboardLabel->setText(m_workspace.clipboard().describe());
        refreshActions();
    });
    connect(&m_workspace.history(), &History::changed, this,
            [this] { m_reportPanel->setHistory(m_workspace.history().entries()); });
    connect(&m_workspace.worker(), &WorkerClient::logLine, this,
            [this](const QString& line) { m_reportPanel->appendLog(line); });
    connect(&m_workspace.worker(), &WorkerClient::workerFailed, this, &MainWindow::onWorkerFailed);

    refreshActions();
}

void MainWindow::initialise() {
    QString error;
    if (!m_workspace.startWorker(&error)) {
        QMessageBox::critical(this, tr("ChunkDaddy cannot start"), error);
        m_reportPanel->appendReport(error);
    }
}

// ---------------------------------------------------------------------------
// Construction
// ---------------------------------------------------------------------------

void MainWindow::buildMenus() {
    QMenu* fileMenu = menuBar()->addMenu(tr("&File"));
    fileMenu->addAction(tr("&New Void Bedrock World…"), QKeySequence::New, this,
                        &MainWindow::newVoidWorld);
    fileMenu->addAction(tr("&Open World…"), QKeySequence::Open, this, &MainWindow::openWorld);
    fileMenu->addSeparator();
    fileMenu->addAction(tr("Open &Project…"), this, &MainWindow::openProject);
    fileMenu->addAction(tr("&Save Project"), QKeySequence::Save, this, &MainWindow::saveProject);
    fileMenu->addAction(tr("Save Project &As…"), QKeySequence::SaveAs, this,
                        &MainWindow::saveProjectAs);
    fileMenu->addSeparator();
    m_exportAction = fileMenu->addAction(tr("&Export Current World…"), this,
                                         &MainWindow::exportCurrentWorld);
    m_exportAction->setShortcut(QKeySequence(QStringLiteral("Ctrl+E")));
    fileMenu->addSeparator();
    fileMenu->addAction(tr("E&xit"), QKeySequence::Quit, this, &QWidget::close);

    QMenu* editMenu = menuBar()->addMenu(tr("&Edit"));
    m_undoAction = editMenu->addAction(tr("&Undo"), QKeySequence::Undo, this, &MainWindow::undo);
    m_redoAction = editMenu->addAction(tr("&Redo"), QKeySequence::Redo, this, &MainWindow::redo);
    editMenu->addSeparator();
    // These shortcuts belong to the chunk viewport and use the application's own
    // clipboard; ordinary text fields keep their normal clipboard behaviour.
    m_copyAction = editMenu->addAction(tr("&Copy chunks"), QKeySequence::Copy, this,
                                       &MainWindow::copySelection);
    m_cutAction = editMenu->addAction(tr("Cu&t chunks"), QKeySequence::Cut, this,
                                      &MainWindow::cutSelection);
    m_pasteAction = editMenu->addAction(tr("&Paste chunks"), QKeySequence::Paste, this,
                                        &MainWindow::pasteFromClipboard);
    m_deleteAction = editMenu->addAction(tr("&Delete selected chunks"), QKeySequence::Delete, this,
                                         &MainWindow::deleteSelection);
    editMenu->addSeparator();
    editMenu->addAction(tr("Select &all content"), QKeySequence::SelectAll, this,
                        &MainWindow::selectAllContent);
    editMenu->addAction(tr("&Expand selection to whole arenas"), this,
                        &MainWindow::expandSelectionToArenas);
    editMenu->addAction(tr("Cancel pending cut"), this, [this] {
        m_workspace.clipboard().cancelPendingCut();
        statusBar()->showMessage(tr("Pending cut cancelled. The source is unchanged."), 5000);
    });

    QMenu* importMenu = menuBar()->addMenu(tr("&Import"));
    m_importAction = importMenu->addAction(tr("&Schematics…"), this, &MainWindow::importSchematics);
    m_spawnAction = importMenu->addAction(tr("Template spawn &markers…"), this,
                                          &MainWindow::editSpawnMarkers);

    QMenu* viewMenu = menuBar()->addMenu(tr("&View"));
    auto* grid = viewMenu->addAction(tr("Show chunk &grid"));
    grid->setCheckable(true);
    grid->setChecked(true);
    connect(grid, &QAction::toggled, m_view, &ChunkView::setShowChunkGrid);

    auto* labels = viewMenu->addAction(tr("Show arena &labels"));
    labels->setCheckable(true);
    labels->setChecked(true);
    connect(labels, &QAction::toggled, m_view, &ChunkView::setShowArenaLabels);

    viewMenu->addSeparator();
    viewMenu->addAction(tr("Zoom to &content"), this, [this] {
        if (Document* document = currentDocument()) {
            if (const auto bounds = document->contentBounds()) {
                m_view->zoomToFit(*bounds);
            }
        }
    });
    viewMenu->addAction(tr("Zoom to export &rectangle"), this, [this] {
        if (Document* document = currentDocument()) {
            if (const auto rectangle = document->exportRectangle()) {
                m_view->zoomToFit(*rectangle);
            }
        }
    });

    QMenu* worldMenu = menuBar()->addMenu(tr("&World"));
    auto* tools = new QActionGroup(this);
    m_selectTool = worldMenu->addAction(tr("&Select tool"));
    m_moveTool = worldMenu->addAction(tr("&Move tool"));
    m_spawnTool = worldMenu->addAction(tr("Place world s&pawn"));
    for (QAction* action : {m_selectTool, m_moveTool, m_spawnTool}) {
        action->setCheckable(true);
        tools->addAction(action);
    }
    m_selectTool->setChecked(true);
    connect(m_selectTool, &QAction::triggered, this,
            [this] { m_view->setTool(ChunkView::Tool::Select); });
    connect(m_moveTool, &QAction::triggered, this,
            [this] { m_view->setTool(ChunkView::Tool::Move); });
    connect(m_spawnTool, &QAction::triggered, this,
            [this] { m_view->setTool(ChunkView::Tool::PlaceWorldSpawn); });

    worldMenu->addSeparator();
    worldMenu->addAction(tr("&Rename world…"), this, [this] {
        Document* document = currentDocument();
        if (!document) {
            return;
        }
        bool ok = false;
        const QString name = QInputDialog::getText(this, tr("Rename world"), tr("World name"),
                                                   QLineEdit::Normal, document->name(), &ok);
        if (ok && !name.trimmed().isEmpty()) {
            m_workspace.renameDocument(document, name.trimmed(), [this](const WorkerReply& reply) {
                if (!reply.ok) {
                    reportError(tr("Rename failed"), reply);
                }
                refreshTabTitles();
            });
        }
    });

    QMenu* helpMenu = menuBar()->addMenu(tr("&Help"));
    helpMenu->addAction(tr("&About ChunkDaddy"), this, [this] {
        const QJsonObject capabilities = m_workspace.worker().capabilities();
        QMessageBox::about(
            this, tr("About ChunkDaddy"),
            tr("<h3>ChunkDaddy</h3>"
               "<p>Bedrock world composer and duel arena grid editor.</p>"
               "<p>Worker %1, protocol %2.<br>Chunker revision %3.</p>"
               "<p>Worlds are stamped with the target profile's version. A client or server older than that profile will refuse to open them; see docs/TargetProfiles.md.</p>")
                .arg(capabilities.value(QStringLiteral("workerVersion")).toString(tr("not started")))
                .arg(capabilities.value(QStringLiteral("protocolVersion")).toInt())
                .arg(capabilities.value(QStringLiteral("chunkerCommit")).toString(QStringLiteral("-"))));
    });
}

void MainWindow::buildDocks() {
    m_templatePanel = new TemplatePanel(this);
    auto* templateDock = new QDockWidget(tr("Templates and arenas"), this);
    templateDock->setWidget(m_templatePanel);
    templateDock->setObjectName(QStringLiteral("templateDock"));
    addDockWidget(Qt::LeftDockWidgetArea, templateDock);

    connect(m_templatePanel, &TemplatePanel::arenaSelected, this, [this](const QString& arenaId) {
        Document* document = currentDocument();
        if (!document) {
            return;
        }
        for (const ArenaInfo& arena : document->arenas()) {
            if (arena.id == arenaId) {
                m_view->centreOn((arena.chunkBounds.minX() + arena.chunkBounds.maxX()) / 2,
                                 (arena.chunkBounds.minZ() + arena.chunkBounds.maxZ()) / 2);
                document->selection().set(arena.chunkBounds);
                document->notifySelectionChanged();
                refreshActions();
                m_view->update();
                break;
            }
        }
    });
    connect(m_templatePanel, &TemplatePanel::editSpawnsRequested, this,
            [this](const QString&) { editSpawnMarkers(); });

    m_inspectorPanel = new InspectorPanel(this);
    auto* inspectorDock = new QDockWidget(tr("Inspector"), this);
    inspectorDock->setWidget(m_inspectorPanel);
    inspectorDock->setObjectName(QStringLiteral("inspectorDock"));
    addDockWidget(Qt::RightDockWidgetArea, inspectorDock);

    connect(m_inspectorPanel, &InspectorPanel::selectionBoundsEdited, this,
            [this](const ChunkRect& rect) {
                if (Document* document = currentDocument()) {
                    document->selection().set(rect);
                    document->notifySelectionChanged();
                    refreshActions();
                    m_view->update();
                }
            });
    connect(m_inspectorPanel, &InspectorPanel::exportRectangleEdited, this,
            [this](const ChunkRect& rect, int border) {
                if (Document* document = currentDocument()) {
                    m_workspace.setExportRectangle(document, rect, border,
                                                   [this](const WorkerReply& reply) {
                                                       if (!reply.ok) {
                                                           reportError(tr("Export rectangle"), reply);
                                                       }
                                                   });
                }
            });
    connect(m_inspectorPanel, &InspectorPanel::exportRectangleReset, this, [this](int border) {
        if (Document* document = currentDocument()) {
            m_workspace.setExportRectangle(document, std::nullopt, border,
                                           [this](const WorkerReply& reply) {
                                               if (!reply.ok) {
                                                   reportError(tr("Export rectangle"), reply);
                                               }
                                           });
        }
    });

    m_reportPanel = new ReportPanel(this);
    auto* reportDock = new QDockWidget(tr("Reports"), this);
    reportDock->setWidget(m_reportPanel);
    reportDock->setObjectName(QStringLiteral("reportDock"));
    addDockWidget(Qt::BottomDockWidgetArea, reportDock);
    reportDock->hide();
}

void MainWindow::buildStatusBar() {
    m_cursorLabel = new QLabel(tr("—"), this);
    m_selectionLabel = new QLabel(tr("No selection"), this);
    m_clipboardLabel = new QLabel(tr("Chunk clipboard is empty"), this);
    m_busyLabel = new QLabel(this);
    m_progress = new QProgressBar(this);
    m_progress->setMaximumWidth(220);
    m_progress->hide();
    m_cancelButton = new QPushButton(tr("Cancel"), this);
    m_cancelButton->hide();
    connect(m_cancelButton, &QPushButton::clicked, this, [this] {
        if (m_activeJob >= 0) {
            m_workspace.worker().cancel(m_activeJob);
            m_busyLabel->setText(tr("Cancelling…"));
        }
    });

    m_sliceSpin = new QSpinBox(this);
    m_sliceSpin->setRange(-64, 320);
    m_sliceSpin->setValue(320);
    m_sliceSpin->setPrefix(tr("Slice Y "));
    connect(m_sliceSpin, &QSpinBox::valueChanged, this,
            [this](int value) { m_view->setHeightSlice(value); });

    m_heightMode = new QComboBox(this);
    m_heightMode->addItem(tr("Highest visible surface"), QStringLiteral("HIGHEST_SURFACE"));
    m_heightMode->addItem(tr("Exactly at the slice"), QStringLiteral("SLICE"));
    connect(m_heightMode, &QComboBox::currentIndexChanged, this, [this] {
        m_view->setHeightMode(m_heightMode->currentData().toString());
    });

    statusBar()->addWidget(m_cursorLabel, 2);
    statusBar()->addWidget(m_selectionLabel, 2);
    statusBar()->addWidget(m_clipboardLabel, 3);
    statusBar()->addPermanentWidget(m_heightMode);
    statusBar()->addPermanentWidget(m_sliceSpin);
    statusBar()->addPermanentWidget(m_busyLabel);
    statusBar()->addPermanentWidget(m_progress);
    statusBar()->addPermanentWidget(m_cancelButton);
}

// ---------------------------------------------------------------------------
// Documents and tabs
// ---------------------------------------------------------------------------

Document* MainWindow::currentDocument() const {
    const int index = m_tabs->currentIndex();
    if (index < 0 || index >= m_tabOrder.size()) {
        return nullptr;
    }
    return m_tabOrder.at(index);
}

void MainWindow::addDocumentTab(Document* document) {
    m_tabOrder.append(document);
    const int index = m_tabs->addTab(document->tabTitle());
    connect(document, &Document::stateChanged, this, [this] {
        refreshTabTitles();
        refreshActions();
    });
    m_tabs->setCurrentIndex(index);
}

void MainWindow::onTabChanged(int index) {
    Document* document = (index >= 0 && index < m_tabOrder.size()) ? m_tabOrder.at(index) : nullptr;
    m_view->setDocument(document);
    m_templatePanel->setDocument(document);
    m_inspectorPanel->setDocument(document);
    if (document) {
        m_inspectorPanel->setProfile(m_workspace.profile(document->profileId()));
    }
    refreshActions();
}

void MainWindow::onTabCloseRequested(int index) {
    if (index < 0 || index >= m_tabOrder.size()) {
        return;
    }
    Document* document = m_tabOrder.at(index);
    if (document->isDirty()) {
        const auto answer = QMessageBox::question(
            this, tr("Close world"),
            tr("\"%1\" has changes that are not saved to a project.\n\n"
               "Anything you copied from it stays on the chunk clipboard. Close it anyway?")
                .arg(document->name()),
            QMessageBox::Close | QMessageBox::Cancel, QMessageBox::Cancel);
        if (answer != QMessageBox::Close) {
            return;
        }
    }
    m_tabOrder.removeAt(index);
    m_tabs->removeTab(index);
    m_workspace.closeDocument(document);
    if (m_tabOrder.isEmpty()) {
        m_view->setDocument(nullptr);
        m_templatePanel->setDocument(nullptr);
        m_inspectorPanel->setDocument(nullptr);
    }
    refreshActions();
}

void MainWindow::refreshTabTitles() {
    for (int i = 0; i < m_tabOrder.size(); ++i) {
        m_tabs->setTabText(i, m_tabOrder.at(i)->tabTitle());
    }
}

void MainWindow::refreshActions() {
    Document* document = currentDocument();
    const bool hasDocument = document != nullptr;
    const bool hasSelection = hasDocument && !document->selection().isEmpty();

    m_undoAction->setEnabled(hasDocument && document->canUndo());
    m_redoAction->setEnabled(hasDocument && document->canRedo());
    m_copyAction->setEnabled(hasSelection);
    m_cutAction->setEnabled(hasSelection);
    m_deleteAction->setEnabled(hasSelection);
    m_pasteAction->setEnabled(hasDocument && !m_workspace.clipboard().isEmpty());
    m_exportAction->setEnabled(hasDocument);
    m_importAction->setEnabled(hasDocument && !m_importProgress);
    m_spawnAction->setEnabled(hasDocument && !document->templates().isEmpty());

    if (hasSelection) {
        const ChunkRect bounds = document->selection().bounds();
        m_selectionLabel->setText(tr("%1 column(s), %2")
                                      .arg(document->selection().columnCount())
                                      .arg(bounds.describe()));
    } else {
        m_selectionLabel->setText(tr("No selection"));
    }
}

// ---------------------------------------------------------------------------
// Actions
// ---------------------------------------------------------------------------

void MainWindow::newVoidWorld() {
    const QVector<ProfileInfo> profiles = m_workspace.profiles();
    if (profiles.isEmpty()) {
        QMessageBox::warning(this, tr("No target profiles"),
                             tr("The conversion worker has not reported any target profiles yet. "
                                "Check the worker log in the Reports dock."));
        return;
    }
    NewWorldDialog dialog(profiles, this);
    if (dialog.exec() != QDialog::Accepted) {
        return;
    }
    Settings::setLastProfileId(dialog.profileId());
    m_workspace.createVoidWorld(dialog.worldName(), dialog.profileId(),
                                [this](const WorkerReply& reply) {
                                    if (!reply.ok) {
                                        reportError(tr("Could not create the world"), reply);
                                        return;
                                    }
                                    addDocumentTab(m_workspace.documents().last());
                                });
}

void MainWindow::openWorld() {
    const QString path = QFileDialog::getOpenFileName(
        this, tr("Open a world"), Settings::lastImportDirectory(),
        tr("Worlds and containers (*.mcworld *.zip);;All files (*)"));
    if (path.isEmpty()) {
        return;
    }
    Settings::setLastImportDirectory(QFileInfo(path).absolutePath());
    openPathAsWorld(path);
}

void MainWindow::openPathAsWorld(const QString& path) {
    showBusy(tr("Inspecting %1…").arg(QFileInfo(path).fileName()), -1);
    m_workspace.inspectSource(path, [this, path](const WorkerReply& reply) {
        hideBusy();
        if (!reply.ok) {
            reportError(tr("Could not inspect that file"), reply);
            return;
        }
        const QJsonArray worlds = reply.result.value(QStringLiteral("worlds")).toArray();
        if (worlds.isEmpty()) {
            QMessageBox::information(
                this, tr("No worlds found"),
                reply.result.value(QStringLiteral("note"))
                    .toString(tr("Nothing in that file looks like a Minecraft world.")));
            return;
        }

        OpenWorldRootDialog::Choice choice;
        if (worlds.size() == 1) {
            const QJsonObject world = worlds.first().toObject();
            choice.directory = world.value(QStringLiteral("directory")).toString();
            choice.edition = world.value(QStringLiteral("edition")).toString();
            choice.name = world.value(QStringLiteral("name")).toString();
        } else {
            OpenWorldRootDialog dialog(worlds, this);
            if (dialog.exec() != QDialog::Accepted) {
                return;
            }
            choice = dialog.choice();
        }
        if (choice.directory.isEmpty()) {
            return;
        }

        const QString profileId = Settings::lastProfileId().isEmpty()
                                      ? (m_workspace.profiles().isEmpty()
                                             ? QString()
                                             : m_workspace.profiles().first().id)
                                      : Settings::lastProfileId();

        showBusy(tr("Reading %1…").arg(choice.name), -1);
        m_workspace.openWorldRoot(
            choice.directory, choice.edition, choice.name, profileId,
            [this](const WorkerReply& opened) {
                hideBusy();
                if (!opened.ok) {
                    reportError(tr("Could not open that world"), opened);
                    return;
                }
                addDocumentTab(m_workspace.documents().last());

                QStringList notices;
                for (const QJsonValue& value : opened.result.value(QStringLiteral("notices")).toArray()) {
                    notices << value.toString();
                }
                if (!notices.isEmpty()) {
                    m_reportPanel->appendReport(tr("Import notices:\n  %1")
                                                    .arg(notices.join(QStringLiteral("\n  "))));
                }
            },
            [this](const WorkerProgress& progress) {
                updateBusyProgress(progress);
            });
    });
}

void MainWindow::importSchematics() {
    if (!currentDocument() || m_importProgress) return;
    const QStringList paths = QFileDialog::getOpenFileNames(
        this, tr("Import schematics"), Settings::lastImportDirectory(),
        tr("Sponge schematics (*.schem);;All files (*)"));
    importSchematicPaths(paths);
}

void MainWindow::importSchematicPaths(const QStringList& paths) {
    QPointer<Document> document = currentDocument();
    if (!document || paths.isEmpty() || m_importProgress) return;
    Settings::setLastImportDirectory(QFileInfo(paths.first()).absolutePath());
    const QStringList ordered = InstanceNaming::sortNaturally(paths);

    m_importProgress = new ImportProgressDialog(ordered.size(), this);
    connect(m_importProgress, &ImportProgressDialog::cancelRequested, this, [this] {
        if (m_activeJob >= 0) m_workspace.worker().cancel(m_activeJob);
    });
    m_importProgress->show();
    refreshActions();
    showBusy(tr("Importing %1 schematics…").arg(ordered.size()), -1);
    m_activeJob = m_workspace.importSchematics(
        ordered,
        [this, document, count = ordered.size()](const WorkerReply& reply) {
            if (!reply.ok) {
                hideBusy();
                if (reply.errorCode == QStringLiteral("job.cancelled")) {
                    statusBar()->showMessage(tr("Schematic import cancelled. No grid was placed."), 7000);
                } else {
                    reportError(tr("Import failed"), reply);
                }
                return;
            }
            QSet<QString> importedIds;
            for (const QJsonValue& value : reply.result.value("templates").toArray())
                importedIds.insert(value.toObject().value("templateId").toString());
            QStringList failures;
            for (const QJsonValue& value : reply.result.value("failures").toArray()) failures << value.toString();
            if (!failures.isEmpty() || importedIds.isEmpty()) {
                hideBusy();
                const QString summary = importedIds.isEmpty()
                    ? tr("None of the %1 selected schematics could be imported. No grid was placed.").arg(count)
                    : tr("Imported %1 of %2 schematics. Grid options will contain only the successful imports.")
                          .arg(importedIds.size()).arg(count);
                m_reportPanel->appendReport(summary + "\n" + failures.join("\n"));
                QMessageBox message(QMessageBox::Warning, tr("Schematic import results"), summary,
                                    QMessageBox::Ok, this);
                message.setDetailedText(failures.join("\n"));
                message.exec();
                if (importedIds.isEmpty()) return;
            }
            if (!document) { hideBusy(); return; }
            if (m_importProgress) m_importProgress->preparingGrid();
            QJsonObject request = Protocol::request(QStringLiteral("document_info"));
            request.insert(QStringLiteral("documentId"), document->documentId());
            m_workspace.worker().send(request, [this, document, importedIds](const WorkerReply& info) {
                hideBusy();
                if (!document) return;
                if (!info.ok) { reportError(tr("Could not refresh the world"), info); return; }
                document->applyState(info.result);
                QVector<TemplateInfo> imported;
                for (const TemplateInfo& entry : document->templates())
                    if (importedIds.contains(entry.templateId)) imported.append(entry);
                if (imported.isEmpty()) return;
                const ProfileInfo profile = m_workspace.profile(document->profileId());
                SchematicImportDialog dialog(imported, document, profile.minBlockY, profile.maxBlockY, this);
                if (dialog.exec() != QDialog::Accepted || !document) return;
                const ChunkRect gridBounds = dialog.plan().bounds;
                showBusy(tr("Placing %1 arena(s)…").arg(dialog.plan().placements.size()), -1);
                m_workspace.placeGrid(document, dialog.plan(), dialog.replaceExisting(),
                    [this, document, gridBounds](const WorkerReply& placed) {
                        hideBusy();
                        if (!placed.ok) { reportError(tr("Could not place the grid"), placed); return; }
                        refreshActions();
                        if (currentDocument() == document) m_view->zoomToFit(gridBounds);
                        statusBar()->showMessage(tr("Grid placed. Use Undo to remove this placement."), 7000);
                    });
            });
        },
        [this](const WorkerProgress& progress) {
            updateBusyProgress(progress);
            if (m_importProgress) m_importProgress->updateProgress(progress);
        });
}

void MainWindow::editSpawnMarkers() {
    Document* document = currentDocument();
    if (!document || document->templates().isEmpty()) {
        return;
    }
    if (!m_spawnDialog) {
        m_spawnDialog = new SpawnMarkerDialog(document->templates(), this);
        connect(m_spawnDialog, &SpawnMarkerDialog::validationRequested, this,
                [this, document](const QString& templateId, const QVector<double>& one,
                                 const QVector<double>& two, bool confirm) {
                    m_workspace.setTemplateSpawns(
                        templateId, one, two, confirm, [this, document, confirm](const WorkerReply& reply) {
                            if (!reply.ok) {
                                // A confirmation that fails validation is refused rather than
                                // recorded, so an unsafe spawn cannot reach the export.
                                m_spawnDialog->showWarnings({reply.errorMessage});
                                return;
                            }
                            QStringList warnings;
                            for (const QJsonValue& value :
                                 reply.result.value(QStringLiteral("warnings")).toArray()) {
                                warnings << value.toString();
                            }
                            m_spawnDialog->showWarnings(warnings);

                            QJsonObject request = Protocol::request(QStringLiteral("document_info"));
                            request.insert(QStringLiteral("documentId"), document->documentId());
                            m_workspace.worker().send(request, [this, document](const WorkerReply& info) {
                                if (info.ok) {
                                    document->applyState(info.result);
                                    m_spawnDialog->setTemplates(document->templates());
                                }
                            });
                            if (confirm) {
                                statusBar()->showMessage(tr("Spawn markers saved."), 4000);
                            }
                        });
                });
    } else {
        m_spawnDialog->setTemplates(document->templates());
    }
    m_spawnDialog->show();
    m_spawnDialog->raise();
}

void MainWindow::exportCurrentWorld() {
    Document* document = currentDocument();
    if (!document) {
        return;
    }
    m_workspace.validateExport(document, [this, document](const WorkerReply& reply) {
        if (!reply.ok) {
            reportError(tr("Could not prepare the export"), reply);
            return;
        }
        ExportDialog dialog(document, m_workspace.profiles(), document->profileId(), reply.result,
                            this);
        if (dialog.exec() != QDialog::Accepted) {
            return;
        }
        Settings::setLastExportDirectory(QFileInfo(dialog.destination()).absolutePath());

        showBusy(tr("Exporting…"), -1);
        m_progress->show();
        m_progress->setRange(0, 100);
        m_cancelButton->show();

        m_workspace.exportWorld(
            document, dialog.destination(), dialog.mode(), dialog.worldName(), dialog.numberMode(),
            dialog.arenaPreset(), dialog.profileId(),
            [this](const WorkerReply& exported) {
                hideBusy();
                if (!exported.ok) {
                    reportError(tr("Export failed"), exported);
                    return;
                }
                QStringList warnings;
                for (const QJsonValue& value :
                     exported.result.value(QStringLiteral("warnings")).toArray()) {
                    warnings << value.toString();
                }
                const bool companion =
                    exported.result.value(QStringLiteral("companionJsonWritten")).toBool();

                QString message =
                    tr("Wrote %1.\n\n%2 arena(s) and %3 spawn position(s).\n"
                       "%4 content column(s) and %5 explicitly generated void column(s).")
                        .arg(exported.result.value(QStringLiteral("path")).toString())
                        .arg(exported.result.value(QStringLiteral("arenaCount")).toInt())
                        .arg(exported.result.value(QStringLiteral("spawnPointCount")).toInt())
                        .arg(static_cast<qint64>(
                            exported.result.value(QStringLiteral("contentColumns")).toDouble()))
                        .arg(static_cast<qint64>(
                            exported.result.value(QStringLiteral("voidColumns")).toDouble()));
                if (!companion) {
                    message += tr("\n\nThe companion arenas.json next to the world was not written. "
                                  "The copy inside the world is the authoritative one.");
                }
                if (!warnings.isEmpty()) {
                    message += QStringLiteral("\n\n") + warnings.join(QStringLiteral("\n"));
                    m_reportPanel->appendReport(warnings.join(QStringLiteral("\n")));
                    m_reportPanel->showReportTab();
                }
                QMessageBox::information(this, tr("Export complete"), message);
            },
            [this](const WorkerProgress& progress) {
                updateBusyProgress(progress);
                m_progress->setValue(static_cast<int>(progress.fraction() * 100));
            });
    });
}

void MainWindow::saveProject() {
    Document* document = currentDocument();
    if (!document) {
        return;
    }
    if (document->projectPath().isEmpty()) {
        saveProjectAs();
        return;
    }
    QMessageBox::information(
        this, tr("Save project"),
        tr("Project saving is not implemented in this build. Exporting the world is available and "
           "writes the arena JSON and manifest; the manifest is enough to recover template and "
           "grid identity when reopening an exported world."));
}

void MainWindow::saveProjectAs() {
    QMessageBox::information(
        this, tr("Save project"),
        tr("Project saving is not implemented in this build. Use File > Export Current World; the "
           "manifest written alongside the world records template hashes, instance identities and "
           "the grid layout."));
}

void MainWindow::openProject() {
    QMessageBox::information(this, tr("Open project"),
                             tr("Project files are not implemented in this build."));
}

void MainWindow::copySelection() {
    Document* document = currentDocument();
    if (!document || document->selection().isEmpty()) {
        return;
    }
    showBusy(tr("Copying selected chunks…"), -1);
    m_workspace.copySelection(document, false, [this](const WorkerReply& reply) {
        hideBusy();
        if (!reply.ok) {
            reportError(tr("Copy failed"), reply);
        }
    });
}

void MainWindow::cutSelection() {
    Document* document = currentDocument();
    if (!document || document->selection().isEmpty()) {
        return;
    }
    showBusy(tr("Preparing selected chunks for cut…"), -1);
    m_workspace.copySelection(document, true, [this](const WorkerReply& reply) {
        hideBusy();
        if (!reply.ok) {
            reportError(tr("Cut failed"), reply);
            return;
        }
        statusBar()->showMessage(
            tr("Cut captured. The source is unchanged until you paste; Edit > Cancel pending cut "
               "leaves it alone."),
            8000);
    });
}

void MainWindow::pasteFromClipboard() {
    Document* document = currentDocument();
    if (!document || m_workspace.clipboard().isEmpty()) {
        return;
    }
    // The preview follows the cursor; the paste commits on click and Escape cancels it
    // without changing the document.
    m_view->beginPastePreview(m_workspace.clipboard().relativeBounds());
    statusBar()->showMessage(
        tr("Move the paste preview and click to commit. Escape cancels without changing anything."),
        8000);
}

void MainWindow::deleteSelection() {
    Document* document = currentDocument();
    if (!document || document->selection().isEmpty()) {
        return;
    }
    showBusy(tr("Deleting selected chunks…"), -1);
    m_workspace.clearSelection(document, [this](const WorkerReply& reply) {
        hideBusy();
        if (!reply.ok) {
            reportError(tr("Delete failed"), reply);
        }
    });
}

void MainWindow::selectAllContent() {
    Document* document = currentDocument();
    if (!document) {
        return;
    }
    if (const auto bounds = document->contentBounds()) {
        document->selection().set(*bounds);
        document->notifySelectionChanged();
        refreshActions();
        m_view->update();
    }
}

void MainWindow::expandSelectionToArenas() {
    Document* document = currentDocument();
    if (!document) {
        return;
    }
    if (document->expandSelectionToArenas()) {
        statusBar()->showMessage(tr("Selection expanded to cover whole arenas."), 5000);
        refreshActions();
        m_view->update();
    }
}

void MainWindow::undo() {
    if (Document* document = currentDocument()) {
        showBusy(tr("Undoing the last edit…"), -1);
        m_workspace.undo(document, [this](const WorkerReply& reply) {
            hideBusy();
            if (!reply.ok) {
                reportError(tr("Undo failed"), reply);
            }
        });
    }
}

void MainWindow::redo() {
    if (Document* document = currentDocument()) {
        showBusy(tr("Redoing the edit…"), -1);
        m_workspace.redo(document, [this](const WorkerReply& reply) {
            hideBusy();
            if (!reply.ok) {
                reportError(tr("Redo failed"), reply);
            }
        });
    }
}

// ---------------------------------------------------------------------------
// Viewport plumbing
// ---------------------------------------------------------------------------

void MainWindow::onCursorMoved(const BlockPos& block, const QPoint& chunk, ColumnState state) {
    QString stateText;
    switch (state) {
    case ColumnState::Content:
        stateText = tr("content");
        break;
    case ColumnState::GeneratedVoid:
        stateText = tr("generated void");
        break;
    case ColumnState::Absent:
        stateText = tr("absent");
        break;
    }
    m_cursorLabel->setText(tr("Block %1, %2, %3   Chunk %4, %5   %6")
                               .arg(block.x).arg(block.y).arg(block.z)
                               .arg(chunk.x()).arg(chunk.y())
                               .arg(stateText));
}

void MainWindow::onTilesNeeded(const ChunkRect& area, int pixelsPerChunk, quint64 generation) {
    Document* document = m_view->document();
    if (!document) return;
    const qint64 revision = document->revision();
    m_workspace.requestPreviewTiles(document, area, m_view->heightSlice(), m_view->heightMode(),
        [this, generation, revision](const WorkerReply& reply) {
            const QString path = reply.result.value(QStringLiteral("path")).toString();
            m_view->completePreview(generation, path,
                reply.ok ? qint64(reply.result.value(QStringLiteral("revision")).toDouble()) : revision,
                reply.ok ? QString() : reply.describeError());
            // Preview files are transient; avoid accumulating every pan/edit on disk.
            if (!path.isEmpty()) QFile::remove(path);
        }, pixelsPerChunk);
}

void MainWindow::onWorkerFailed(const QString& reason) {
    hideBusy();
    m_reportPanel->appendReport(reason);
    m_reportPanel->showReportTab();
    const auto answer = QMessageBox::critical(
        this, tr("The conversion worker stopped"),
        reason + tr("\n\nRestart it and continue from the last committed revision?"),
        QMessageBox::Yes | QMessageBox::No, QMessageBox::Yes);
    if (answer == QMessageBox::Yes) {
        QString error;
        if (!m_workspace.startWorker(&error)) {
            QMessageBox::critical(this, tr("Could not restart the worker"), error);
        }
    }
}

void MainWindow::reportError(const QString& title, const WorkerReply& reply) {
    hideBusy();
    m_reportPanel->appendReport(QStringLiteral("%1: %2").arg(title, reply.describeError()));
    QMessageBox::warning(this, title, reply.errorMessage);
}

void MainWindow::showBusy(const QString& label, qint64 jobId) {
    m_busy = true;
    m_activeJob = jobId;
    m_busyLabel->setText(label);
    m_progress->setRange(0, 0);
    m_progress->show();
    m_cancelButton->setVisible(jobId >= 0);
    m_view->setPreviewPaused(true);
    menuBar()->setEnabled(false);
    m_tabs->setEnabled(false);
    m_view->setEnabled(false);
    m_inspectorPanel->setEnabled(false);
    m_templatePanel->setEnabled(false);
    if (!m_importProgress) {
        if (!m_operationProgress) {
            m_operationProgress = new OperationProgressDialog(label, this);
        }
        m_operationProgress->show();
    }
}

void MainWindow::updateBusyProgress(const WorkerProgress& progress) {
    m_busyLabel->setText(progress.describe());
    if (progress.total > 0) {
        m_progress->setRange(0, 100);
        m_progress->setValue(int(progress.fraction() * 100));
    } else {
        m_progress->setRange(0, 0);
    }
    if (m_operationProgress) {
        m_operationProgress->updateProgress(progress);
    }
}

void MainWindow::hideBusy() {
    m_busy = false;
    if (m_operationProgress) {
        m_operationProgress->accept();
        m_operationProgress->deleteLater();
        m_operationProgress = nullptr;
    }
    menuBar()->setEnabled(true);
    m_tabs->setEnabled(true);
    m_view->setEnabled(true);
    m_inspectorPanel->setEnabled(true);
    m_templatePanel->setEnabled(true);
    m_view->setPreviewPaused(false);
    if (m_importProgress) {
        m_importProgress->accept();
        m_importProgress->deleteLater();
        m_importProgress = nullptr;
        refreshActions();
    }
    m_activeJob = -1;
    m_busyLabel->clear();
    m_progress->hide();
    m_cancelButton->hide();
    refreshActions();
}

// ---------------------------------------------------------------------------
// Window events
// ---------------------------------------------------------------------------

void MainWindow::closeEvent(QCloseEvent* event) {
    if (m_busy) { event->ignore(); return; }
    for (Document* document : m_tabOrder) {
        if (!document->isDirty()) {
            continue;
        }
        const auto answer = QMessageBox::question(
            this, tr("Quit ChunkDaddy"),
            tr("Some worlds have changes that are not saved to a project. Quit anyway?"),
            QMessageBox::Close | QMessageBox::Cancel, QMessageBox::Cancel);
        if (answer != QMessageBox::Close) {
            event->ignore();
            return;
        }
        break;
    }
    m_workspace.worker().stop();
    event->accept();
}

void MainWindow::dragEnterEvent(QDragEnterEvent* event) {
    if (event->mimeData()->hasUrls()) {
        event->acceptProposedAction();
    }
}

void MainWindow::dropEvent(QDropEvent* event) {
    if (m_busy) { event->ignore(); return; }
    QStringList schematics;
    QStringList worlds;
    for (const QUrl& url : event->mimeData()->urls()) {
        const QString path = url.toLocalFile();
        if (path.isEmpty()) {
            continue;
        }
        if (path.endsWith(QStringLiteral(".schem"), Qt::CaseInsensitive)) {
            schematics << path;
        } else {
            worlds << path;
        }
    }

    // Dropping a world opens it in its own tab; it is never merged into the active world.
    for (const QString& world : worlds) {
        openPathAsWorld(world);
    }
    if (!schematics.isEmpty()) {
        if (!currentDocument()) {
            QMessageBox::information(this, tr("No world open"),
                                     tr("Create or open a world before importing schematics."));
            return;
        }
        // Dropping schematics onto a world runs the same import dialog as the menu.
        importSchematicPaths(schematics);
    }
    event->acceptProposedAction();
}

} // namespace chunkdaddy
