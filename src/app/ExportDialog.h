#pragma once

#include "document/Document.h"

#include <QDialog>
#include <QJsonObject>

class QCheckBox;
class QComboBox;
class QLabel;
class QLineEdit;
class QPushButton;
class QTextBrowser;

namespace chunkdaddy {

/// Export Current World.
///
/// Before anything is written this shows the export rectangle, the arena count, the
/// companion filenames and any unresolved error. It does not ask the user about chunk
/// record versions or palette encodings; those belong to the target profile.
class ExportDialog : public QDialog {
    Q_OBJECT

public:
    ExportDialog(const Document* document, const QString& profileSummary,
                 const QJsonObject& validation, QWidget* parent = nullptr);

    QString destination() const;
    /// MCWORLD, ZIP or DIRECTORY.
    QString mode() const;
    QString worldName() const;
    /// EXACT or INTEGER.
    QString numberMode() const;
    bool arenaPreset() const;

private slots:
    void browse();
    void updateSummary();

private:
    QString currentExtension() const;

    const Document* m_document = nullptr;
    QString m_profileSummary;
    QJsonObject m_validation;

    QLineEdit* m_worldName = nullptr;
    QComboBox* m_mode = nullptr;
    QLineEdit* m_destination = nullptr;
    QComboBox* m_numberMode = nullptr;
    QCheckBox* m_arenaPreset = nullptr;
    QCheckBox* m_useSystemDownloads = nullptr;
    QTextBrowser* m_summary = nullptr;
    QPushButton* m_exportButton = nullptr;
};

} // namespace chunkdaddy
