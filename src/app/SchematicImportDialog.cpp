#include "app/SchematicImportDialog.h"

#include "app/Settings.h"

#include <QCheckBox>
#include <QComboBox>
#include <QDialogButtonBox>
#include <QFormLayout>
#include <QGroupBox>
#include <QHBoxLayout>
#include <QHeaderView>
#include <QLabel>
#include <QLineEdit>
#include <QPushButton>
#include <QSpinBox>
#include <QTableWidget>
#include <QTextBrowser>
#include <QVBoxLayout>

namespace chunkdaddy {
namespace {

enum Column {
    ColumnInclude = 0,
    ColumnFile,
    ColumnSlug,
    ColumnDimensions,
    ColumnFormat,
    ColumnPalette,
    ColumnMarkers,
    ColumnCopies,
    ColumnMinY,
    ColumnCount
};

QString formatBytes(qint64 bytes) {
    if (bytes < 1024) return QStringLiteral("%1 B").arg(bytes);
    if (bytes < 1024LL * 1024) return QStringLiteral("%1 KiB").arg(bytes / 1024);
    if (bytes < 1024LL * 1024 * 1024) return QStringLiteral("%1 MiB").arg(bytes / (1024 * 1024));
    return QStringLiteral("%1 GiB").arg(bytes / (1024.0 * 1024 * 1024), 0, 'f', 1);
}

} // namespace

SchematicImportDialog::SchematicImportDialog(const QVector<TemplateInfo>& templates,
                                             const Document* document, int profileMinBlockY,
                                             int profileMaxBlockY, QWidget* parent)
    : QDialog(parent),
      m_templates(templates),
      m_document(document),
      m_profileMinBlockY(profileMinBlockY),
      m_profileMaxBlockY(profileMaxBlockY) {
    setWindowTitle(tr("Place schematics in a grid"));
    setModal(true);

    m_table = new QTableWidget(this);
    m_table->setColumnCount(ColumnCount);
    m_table->setHorizontalHeaderLabels({tr("Use"), tr("File"), tr("Slug"), tr("Size (X/Y/Z)"),
                                        tr("Format"), tr("Palette"), tr("Markers"), tr("Copies"),
                                        tr("Min Y")});
    m_table->verticalHeader()->setVisible(false);
    m_table->setSelectionBehavior(QAbstractItemView::SelectRows);

    m_gapX = new QSpinBox(this);
    m_gapX->setRange(0, 512);
    m_gapX->setValue(Settings::defaultGapChunks());
    m_gapX->setSuffix(tr(" chunks"));
    m_gapZ = new QSpinBox(this);
    m_gapZ->setRange(0, 512);
    m_gapZ->setValue(Settings::defaultGapChunks());
    m_gapZ->setSuffix(tr(" chunks"));

    m_autoColumns = new QCheckBox(tr("Suggest automatically"), this);
    m_autoColumns->setChecked(true);
    m_columns = new QSpinBox(this);
    m_columns->setRange(1, 4096);
    m_columns->setValue(20);
    m_columns->setEnabled(false);

    m_originX = new QSpinBox(this);
    m_originX->setObjectName("gridOriginX");
    m_originX->setRange(-1'800'000, 1'800'000);
    m_originX->setSuffix(tr(" chunk X"));
    m_originZ = new QSpinBox(this);
    m_originZ->setObjectName("gridOriginZ");
    m_originZ->setRange(-1'800'000, 1'800'000);
    m_originZ->setSuffix(tr(" chunk Z"));

    m_border = new QSpinBox(this);
    m_border->setRange(0, 512);
    m_border->setSuffix(tr(" chunks"));

    m_placementMode = new QComboBox(this);
    m_placementMode->addItem(tr("Minimum corner (recommended)"), QStringLiteral("min-corner"));

    m_pastePolicy = new QComboBox(this);
    m_pastePolicy->addItem(tr("Exact region replacement, including schematic air"),
                           QStringLiteral("exact"));

    m_replaceExisting = new QCheckBox(tr("Replace existing content where the grid overlaps"), this);
    connect(m_replaceExisting, &QCheckBox::toggled, this, &SchematicImportDialog::recomputePlan);

    // Start beside the loaded world with the requested gap, so the first preview is safe.
    if (m_document) {
        if (const auto bounds = m_document->contentBounds()) {
            m_originX->setValue(bounds->maxX() + m_gapX->value() + 1);
            m_originZ->setValue(bounds->minZ());
        }
    }

    m_summary = new QTextBrowser(this);
    m_summary->setMinimumHeight(170);

    auto* options = new QGroupBox(tr("Grid"), this);
    auto* optionsForm = new QFormLayout(options);
    auto* gapRow = new QHBoxLayout;
    gapRow->addWidget(new QLabel(tr("X"), this));
    gapRow->addWidget(m_gapX);
    gapRow->addSpacing(12);
    gapRow->addWidget(new QLabel(tr("Z"), this));
    gapRow->addWidget(m_gapZ);
    gapRow->addStretch();
    optionsForm->addRow(tr("Empty chunks between footprints"), gapRow);

    auto* columnsRow = new QHBoxLayout;
    columnsRow->addWidget(m_columns);
    columnsRow->addWidget(m_autoColumns);
    columnsRow->addStretch();
    optionsForm->addRow(tr("Columns"), columnsRow);

    auto* originRow = new QHBoxLayout;
    originRow->addWidget(m_originX);
    originRow->addWidget(m_originZ);
    originRow->addStretch();
    optionsForm->addRow(tr("Grid minimum (chunk aligned)"), originRow);
    optionsForm->addRow(tr("Export border around the grid"), m_border);
    optionsForm->addRow(tr("Placement mode"), m_placementMode);
    optionsForm->addRow(tr("Paste policy"), m_pastePolicy);
    optionsForm->addRow(QString(), m_replaceExisting);

    auto* buttons = new QDialogButtonBox(this);
    auto* previewButton = buttons->addButton(tr("Preview Grid"), QDialogButtonBox::ActionRole);
    m_placeButton = buttons->addButton(tr("Place Grid"), QDialogButtonBox::AcceptRole);
    buttons->addButton(QDialogButtonBox::Cancel);

    connect(previewButton, &QPushButton::clicked, this, &SchematicImportDialog::recomputePlan);
    connect(buttons, &QDialogButtonBox::accepted, this, [this] {
        recomputePlan();
        if (m_plan.valid && m_placeButton->isEnabled()) {
            Settings::setDefaultGapChunks(m_gapX->value());
            accept();
        }
    });
    connect(buttons, &QDialogButtonBox::rejected, this, &QDialog::reject);
    connect(m_autoColumns, &QCheckBox::toggled, this, [this](bool automatic) {
        m_columns->setEnabled(!automatic);
        recomputePlan();
    });
    for (QSpinBox* box : {m_gapX, m_gapZ, m_columns, m_originX, m_originZ, m_border}) {
        connect(box, &QSpinBox::valueChanged, this, &SchematicImportDialog::recomputePlan);
    }

    auto* layout = new QVBoxLayout(this);
    auto* explanation = new QLabel(
        tr("Each selected schematic is placed the requested number of times into a uniform grid. "
           "The gap is the number of completely empty chunk columns between neighbouring arena "
           "footprints, not a distance between centres."),
        this);
    explanation->setWordWrap(true);
    layout->addWidget(explanation);
    layout->addWidget(m_table, 1);
    layout->addWidget(options);
    layout->addWidget(m_summary);
    layout->addWidget(buttons);

    populateTable();
    recomputePlan();
    resize(1040, 760);
}

void SchematicImportDialog::populateTable() {
    m_table->setRowCount(m_templates.size());
    for (int row = 0; row < m_templates.size(); ++row) {
        const TemplateInfo& info = m_templates.at(row);

        auto* include = new QTableWidgetItem;
        include->setFlags(Qt::ItemIsUserCheckable | Qt::ItemIsEnabled);
        // A file that looks like a collection of arenas is never enabled by default; the
        // user includes it deliberately rather than getting a sixteenth duel map.
        include->setCheckState(info.aggregateCandidate || info.blockingIssueCount > 0 ? Qt::Unchecked : Qt::Checked);
        if (info.blockingIssueCount > 0) {
            include->setFlags(Qt::NoItemFlags);
            include->setToolTip(tr("This schematic has blocking import issues. See the Palette column."));
        }
        if (info.aggregateCandidate) {
            include->setToolTip(tr("This file's footprint is much larger than a single arena. "
                                   "It looks like an aggregate of several maps; include it only "
                                   "if you mean to place the whole collection as one unit."));
        }
        m_table->setItem(row, ColumnInclude, include);

        m_table->setItem(row, ColumnFile, new QTableWidgetItem(info.sourceFile));
        m_table->item(row, ColumnFile)->setFlags(Qt::ItemIsEnabled);

        auto* slug = new QTableWidgetItem(info.slug);
        slug->setFlags(Qt::ItemIsEnabled | Qt::ItemIsEditable | Qt::ItemIsSelectable);
        m_table->setItem(row, ColumnSlug, slug);

        auto* dimensions = new QTableWidgetItem(
            tr("%1 x %2 x %3  (%4 x %5 chunks)")
                .arg(info.sizeX).arg(info.sizeY).arg(info.sizeZ)
                .arg(info.footprintChunksX).arg(info.footprintChunksZ));
        dimensions->setFlags(Qt::ItemIsEnabled);
        m_table->setItem(row, ColumnDimensions, dimensions);

        auto* format = new QTableWidgetItem(
            tr("Sponge v%1, DataVersion %2").arg(info.spongeVersion).arg(info.javaDataVersion));
        format->setFlags(Qt::ItemIsEnabled);
        m_table->setItem(row, ColumnFormat, format);

        auto* palette = new QTableWidgetItem(
            info.blockingIssueCount == 0
                ? tr("%1 states, %2 block entities").arg(info.paletteSize).arg(info.blockEntityCount)
                : tr("%1 blocking issue(s)").arg(info.blockingIssueCount));
        palette->setFlags(Qt::ItemIsEnabled);
        if (info.blockingIssueCount > 0) {
            palette->setForeground(QColor(200, 60, 60));
            palette->setToolTip(info.issueLines.join(QLatin1Char('\n')));
        }
        m_table->setItem(row, ColumnPalette, palette);

        auto* markers = new QTableWidgetItem(info.spawnsAutomatic ? tr("automatic centre/surface")
                                            : info.spawnsConfirmed ? tr("confirmed") : tr("not confirmed"));
        markers->setFlags(Qt::ItemIsEnabled);
        if (info.spawnsAutomatic) {
            markers->setToolTip(tr("Both spawn entries use the same centre/surface fallback. "
                                   "Export is available; edit the markers for separate duel positions."));
        } else if (!info.spawnsConfirmed) {
            markers->setForeground(QColor(200, 140, 40));
            markers->setToolTip(tr("Two spawn markers have to be authored and confirmed before "
                                   "this template's arenas can be exported. Placement can happen "
                                   "first; the arena JSON cannot."));
        }
        m_table->setItem(row, ColumnMarkers, markers);

        auto* copies = new QSpinBox(m_table);
        copies->setRange(0, 10000);
        copies->setValue(info.aggregateCandidate ? 1 : Settings::defaultCopyCount());
        connect(copies, &QSpinBox::valueChanged, this, &SchematicImportDialog::recomputePlan);
        m_table->setCellWidget(row, ColumnCopies, copies);

        auto* minY = new QSpinBox(m_table);
        minY->setRange(m_profileMinBlockY, m_profileMaxBlockY);
        minY->setSingleStep(16);
        // Default to the bottom of the build range, rounded to a sub-chunk boundary so
        // composition can reuse prepared sections instead of repartitioning them.
        minY->setValue(m_profileMinBlockY - (m_profileMinBlockY & 15));
        connect(minY, &QSpinBox::valueChanged, this, &SchematicImportDialog::recomputePlan);
        m_table->setCellWidget(row, ColumnMinY, minY);
    }
    connect(m_table, &QTableWidget::itemChanged, this, &SchematicImportDialog::recomputePlan);
    m_table->horizontalHeader()->setSectionResizeMode(QHeaderView::ResizeToContents);
}

QVector<GridTemplate> SchematicImportDialog::collectTemplates() const {
    QVector<GridTemplate> result;
    for (int row = 0; row < m_templates.size(); ++row) {
        const QTableWidgetItem* include = m_table->item(row, ColumnInclude);
        if (!include || include->checkState() != Qt::Checked) {
            continue;
        }
        const auto* copies = qobject_cast<QSpinBox*>(m_table->cellWidget(row, ColumnCopies));
        const auto* minY = qobject_cast<QSpinBox*>(m_table->cellWidget(row, ColumnMinY));
        if (!copies || copies->value() == 0) {
            continue;
        }
        const TemplateInfo& info = m_templates.at(row);

        GridTemplate entry;
        entry.templateId = info.templateId;
        entry.slug = m_table->item(row, ColumnSlug)->text().trimmed();
        entry.footprintChunksX = info.footprintChunksX;
        entry.footprintChunksZ = info.footprintChunksZ;
        entry.sizeY = info.sizeY;
        entry.copyCount = copies->value();
        entry.minY = minY ? minY->value() : 0;
        result.append(entry);
    }
    return result;
}

void SchematicImportDialog::recomputePlan() {
    const QVector<GridTemplate> templates = collectTemplates();

    GridOptions options;
    options.gapChunksX = m_gapX->value();
    options.gapChunksZ = m_gapZ->value();
    options.columns = m_autoColumns->isChecked() ? 0 : m_columns->value();
    options.originChunkX = m_originX->value();
    options.originChunkZ = m_originZ->value();
    options.borderChunks = m_border->value();

    for (const GridTemplate& entry : templates) {
        if (entry.minY < m_profileMinBlockY || entry.minY + entry.sizeY - 1 > m_profileMaxBlockY) {
            m_plan.valid = false;
            m_plan.error = tr("%1 does not fit the world's build height. Adjust Min Y.").arg(entry.slug);
            m_summary->setPlainText(m_plan.error);
            m_placeButton->setEnabled(false);
            return;
        }
    }

    m_plan = GridPlanner::plan(templates, options);

    QStringList report;
    if (!m_plan.valid) {
        report << tr("<b style='color:#c04040'>Cannot place this grid.</b>") << m_plan.error;
        m_summary->setHtml(report.join(QStringLiteral("<br>")));
        m_placeButton->setEnabled(false);
        return;
    }
    if (m_autoColumns->isChecked()) {
        QSignalBlocker blocker(m_columns);
        m_columns->setValue(m_plan.columns);
    }

    report << tr("<b>%1 arena instance(s)</b> in %2 columns by %3 rows.")
                  .arg(m_plan.totalInstances).arg(m_plan.columns).arg(m_plan.rows);
    report << tr("Uniform cell %1 x %2 chunks, pitch %3 x %4 chunks.")
                  .arg(m_plan.cellChunksX).arg(m_plan.cellChunksZ)
                  .arg(m_plan.pitchChunksX).arg(m_plan.pitchChunksZ);
    report << tr("Grid %1 x %2 chunks (%3 x %4 blocks).")
                  .arg(m_plan.widthChunks).arg(m_plan.lengthChunks)
                  .arg(m_plan.widthChunks * 16).arg(m_plan.lengthChunks * 16);
    report << tr("Export rectangle %1, %2 explicitly generated column(s), roughly %3 on disk.")
                  .arg(m_plan.bounds.describe())
                  .arg(m_plan.generatedColumns)
                  .arg(formatBytes(m_plan.estimatedBytes));

    // Collisions against existing content and registered arenas, before anything is placed.
    QStringList collisions;
    if (m_document) {
        for (const ArenaInfo& arena : m_document->arenas()) {
            if (m_plan.bounds.intersects(arena.chunkBounds)) {
                collisions << arena.exportId;
            }
        }
        if (const auto content = m_document->contentBounds()) {
            if (content->intersects(m_plan.bounds) && collisions.isEmpty()) {
                collisions << tr("existing world content");
            }
        }
    }
    if (!collisions.isEmpty()) {
        report << tr("<b style='color:#c07030'>Overlaps %1.</b> Move the grid origin, or tick "
                     "\"Replace existing content\" to state that this is intended.")
                      .arg(collisions.mid(0, 6).join(QStringLiteral(", "))
                           + (collisions.size() > 6
                                  ? tr(" and %1 more").arg(collisions.size() - 6)
                                  : QString()));
    }

    QStringList unconfirmed;
    for (const GridTemplate& entry : templates) {
        for (const TemplateInfo& info : m_templates) {
            if (info.templateId == entry.templateId && !info.spawnsReady()) {
                unconfirmed << info.slug;
            }
        }
    }
    if (!unconfirmed.isEmpty()) {
        report << tr("Spawn markers are not confirmed for: %1. The grid can be placed now, but "
                     "the arena JSON cannot be written until they are.")
                      .arg(unconfirmed.join(QStringLiteral(", ")));
    }
    for (const QString& warning : m_plan.warnings) {
        report << tr("Note: %1").arg(warning);
    }

    m_summary->setHtml(report.join(QStringLiteral("<br>")));
    const bool blocked = !collisions.isEmpty() && !m_replaceExisting->isChecked();
    m_placeButton->setEnabled(!blocked);
}

bool SchematicImportDialog::replaceExisting() const {
    return m_replaceExisting->isChecked();
}

} // namespace chunkdaddy
