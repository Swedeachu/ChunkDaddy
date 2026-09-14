#pragma once

#include "document/Document.h"
#include "layout/GridPlanner.h"

#include <QDialog>
#include <QVector>

class QCheckBox;
class QComboBox;
class QLabel;
class QSpinBox;
class QTableWidget;
class QTextBrowser;

namespace chunkdaddy {

/// Import > Schematics: per-template counts, spacing, columns and a grid preview.
///
/// Every number the user needs before committing is on this dialog: the grid extent, the
/// instance count, the number of columns that will be generated, and any collision with
/// existing content. Placement is a single undoable command.
class SchematicImportDialog : public QDialog {
    Q_OBJECT

public:
    SchematicImportDialog(const QVector<TemplateInfo>& templates,
                          const Document* document,
                          int profileMinBlockY,
                          int profileMaxBlockY,
                          QWidget* parent = nullptr);

    /// The plan the user approved. Only valid after the dialog is accepted.
    const GridPlan& plan() const noexcept { return m_plan; }
    bool replaceExisting() const;

private slots:
    void recomputePlan();

private:
    QVector<GridTemplate> collectTemplates() const;
    void populateTable();

    QVector<TemplateInfo> m_templates;
    const Document* m_document = nullptr;
    int m_profileMinBlockY = -64;
    int m_profileMaxBlockY = 319;
    GridPlan m_plan;

    QTableWidget* m_table = nullptr;
    QSpinBox* m_gapX = nullptr;
    QSpinBox* m_gapZ = nullptr;
    QSpinBox* m_columns = nullptr;
    QCheckBox* m_autoColumns = nullptr;
    QSpinBox* m_originX = nullptr;
    QSpinBox* m_originZ = nullptr;
    QSpinBox* m_border = nullptr;
    QComboBox* m_placementMode = nullptr;
    QComboBox* m_pastePolicy = nullptr;
    QCheckBox* m_replaceExisting = nullptr;
    QTextBrowser* m_summary = nullptr;
    QPushButton* m_placeButton = nullptr;
};

} // namespace chunkdaddy
