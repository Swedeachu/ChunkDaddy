#pragma once

#include "document/Document.h"

#include <QDialog>
#include <QVector>

class QCheckBox;
class QDoubleSpinBox;
class QLabel;
class QListWidget;
class QTextBrowser;

namespace chunkdaddy {

/// Authors the two duel spawn markers for a template.
///
/// The supplied schematics carry no spawn metadata. WorldEdit's origin and the schematic
/// offset are placement information, not gameplay positions, so the two positions are
/// authored once per template and saved against the source hash. Positions are player
/// feet, in schematic-local coordinates.
class SpawnMarkerDialog : public QDialog {
    Q_OBJECT

public:
    SpawnMarkerDialog(const QVector<TemplateInfo>& templates, QWidget* parent = nullptr);

    QString selectedTemplateId() const;
    QVector<double> spawnPoint1() const;
    QVector<double> spawnPoint2() const;
    bool confirmed() const;

signals:
    /// The user asked to validate the current values against the template.
    void validationRequested(const QString& templateId, const QVector<double>& spawn1,
                             const QVector<double>& spawn2, bool confirm);

public slots:
    /// Show validation warnings returned by the worker.
    void showWarnings(const QStringList& warnings);
    /// Replace the template list after an edit was saved.
    void setTemplates(const QVector<TemplateInfo>& templates);

private slots:
    void onTemplateChanged();
    void suggestOppositeEnds();

private:
    const TemplateInfo* currentTemplate() const;
    void applyTemplateRanges(const TemplateInfo& info);

    QVector<TemplateInfo> m_templates;
    QListWidget* m_list = nullptr;
    QLabel* m_details = nullptr;
    QDoubleSpinBox* m_x1 = nullptr;
    QDoubleSpinBox* m_y1 = nullptr;
    QDoubleSpinBox* m_z1 = nullptr;
    QDoubleSpinBox* m_x2 = nullptr;
    QDoubleSpinBox* m_y2 = nullptr;
    QDoubleSpinBox* m_z2 = nullptr;
    QCheckBox* m_confirm = nullptr;
    QTextBrowser* m_warnings = nullptr;
};

} // namespace chunkdaddy
