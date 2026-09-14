#pragma once

#include "document/Workspace.h"
#include "view/ChunkView.h"

#include <QMainWindow>
#include <QHash>

class QAction;
class QComboBox;
class QLabel;
class QProgressBar;
class QPushButton;
class QSpinBox;
class QTabBar;

namespace chunkdaddy {

class InspectorPanel;
class ReportPanel;
class SpawnMarkerDialog;
class TemplatePanel;

/// The application window: one tab per open world, a dominant top-down viewport, and
/// docks for templates, inspection and diagnostics.
class MainWindow : public QMainWindow {
    Q_OBJECT

public:
    explicit MainWindow(QWidget* parent = nullptr);

    /// Start the worker and report failure in the window rather than on the console.
    void initialise();

protected:
    void closeEvent(QCloseEvent* event) override;
    void dragEnterEvent(QDragEnterEvent* event) override;
    void dropEvent(QDropEvent* event) override;

private slots:
    void newVoidWorld();
    void openWorld();
    void importSchematics();
    void editSpawnMarkers();
    void exportCurrentWorld();
    void saveProject();
    void saveProjectAs();
    void openProject();

    void copySelection();
    void cutSelection();
    void pasteFromClipboard();
    void deleteSelection();
    void selectAllContent();
    void expandSelectionToArenas();
    void undo();
    void redo();

    void onTabChanged(int index);
    void onTabCloseRequested(int index);
    void onCursorMoved(const BlockPos& block, const QPoint& chunk, ColumnState state);
    void onTilesNeeded(const QVector<ChunkRect>& regions);
    void onWorkerFailed(const QString& reason);

private:
    void buildMenus();
    void buildDocks();
    void buildStatusBar();

    Document* currentDocument() const;
    void addDocumentTab(Document* document);
    void refreshActions();
    void refreshTabTitles();
    void openPathAsWorld(const QString& path);
    void reportError(const QString& title, const WorkerReply& reply);
    void showBusy(const QString& label, qint64 jobId);
    void hideBusy();

    Workspace m_workspace;
    ChunkView* m_view = nullptr;
    QTabBar* m_tabs = nullptr;
    TemplatePanel* m_templatePanel = nullptr;
    InspectorPanel* m_inspectorPanel = nullptr;
    ReportPanel* m_reportPanel = nullptr;
    SpawnMarkerDialog* m_spawnDialog = nullptr;

    QLabel* m_cursorLabel = nullptr;
    QLabel* m_selectionLabel = nullptr;
    QLabel* m_clipboardLabel = nullptr;
    QLabel* m_busyLabel = nullptr;
    QProgressBar* m_progress = nullptr;
    QPushButton* m_cancelButton = nullptr;
    QSpinBox* m_sliceSpin = nullptr;
    QComboBox* m_heightMode = nullptr;

    QAction* m_undoAction = nullptr;
    QAction* m_redoAction = nullptr;
    QAction* m_copyAction = nullptr;
    QAction* m_cutAction = nullptr;
    QAction* m_pasteAction = nullptr;
    QAction* m_deleteAction = nullptr;
    QAction* m_exportAction = nullptr;
    QAction* m_importAction = nullptr;
    QAction* m_spawnAction = nullptr;
    QAction* m_selectTool = nullptr;
    QAction* m_moveTool = nullptr;
    QAction* m_spawnTool = nullptr;

    QVector<Document*> m_tabOrder;
    qint64 m_activeJob = -1;
};

} // namespace chunkdaddy
