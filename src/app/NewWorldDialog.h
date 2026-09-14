#pragma once

#include "document/Workspace.h"

#include <QDialog>

class QComboBox;
class QLabel;
class QLineEdit;

namespace chunkdaddy {

/// File > New Void Bedrock World.
///
/// Asks only for a name and a target profile. The new document has a void generator
/// policy, no terrain and an editable world spawn; it can be saved before spawns are
/// configured, but a production export requires a safe world spawn. ChunkDaddy never adds
/// a hidden platform to make a spawn safe, because that would change the composition.
class NewWorldDialog : public QDialog {
    Q_OBJECT

public:
    explicit NewWorldDialog(const QVector<ProfileInfo>& profiles, QWidget* parent = nullptr);

    QString worldName() const;
    QString profileId() const;

private:
    void updateProfileNotes();

    QLineEdit* m_name = nullptr;
    QComboBox* m_profile = nullptr;
    QLabel* m_notes = nullptr;
    QVector<ProfileInfo> m_profiles;
};

} // namespace chunkdaddy
