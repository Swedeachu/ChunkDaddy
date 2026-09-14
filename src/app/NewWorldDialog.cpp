#include "app/NewWorldDialog.h"

#include "app/Settings.h"

#include <QComboBox>
#include <QDialogButtonBox>
#include <QFormLayout>
#include <QLabel>
#include <QLineEdit>
#include <QVBoxLayout>

namespace chunkdaddy {

NewWorldDialog::NewWorldDialog(const QVector<ProfileInfo>& profiles, QWidget* parent)
    : QDialog(parent), m_profiles(profiles) {
    setWindowTitle(tr("New Void Bedrock World"));
    setModal(true);

    m_name = new QLineEdit(tr("PVP Zone"), this);
    m_profile = new QComboBox(this);
    for (const ProfileInfo& profile : m_profiles) {
        QString label = profile.displayName;
        if (profile.fullyVerified) {
            label = tr("%1 — verified").arg(label);
        }
        m_profile->addItem(label, profile.id);
    }
    const QString remembered = Settings::lastProfileId();
    if (!remembered.isEmpty()) {
        const int index = m_profile->findData(remembered);
        if (index >= 0) {
            m_profile->setCurrentIndex(index);
        }
    }

    m_notes = new QLabel(this);
    m_notes->setWordWrap(true);
    m_notes->setTextFormat(Qt::PlainText);

    auto* form = new QFormLayout;
    form->addRow(tr("World name"), m_name);
    form->addRow(tr("Target profile"), m_profile);

    auto* buttons = new QDialogButtonBox(QDialogButtonBox::Ok | QDialogButtonBox::Cancel, this);
    connect(buttons, &QDialogButtonBox::accepted, this, &QDialog::accept);
    connect(buttons, &QDialogButtonBox::rejected, this, &QDialog::reject);
    connect(m_profile, &QComboBox::currentIndexChanged, this, [this] { updateProfileNotes(); });

    auto* layout = new QVBoxLayout(this);
    layout->addLayout(form);
    layout->addWidget(m_notes);
    layout->addStretch();
    layout->addWidget(buttons);

    updateProfileNotes();
    resize(520, 260);
}

void NewWorldDialog::updateProfileNotes() {
    const QString id = profileId();
    for (const ProfileInfo& profile : m_profiles) {
        if (profile.id != id) {
            continue;
        }
        QString text = tr("Bedrock %1. Build range Y %2 to %3.\n\n%4")
                           .arg(profile.version)
                           .arg(profile.minBlockY)
                           .arg(profile.maxBlockY)
                           .arg(profile.verificationSummary);
        if (!profile.notes.isEmpty()) {
            text += QStringLiteral("\n\n") + profile.notes;
        }
        m_notes->setText(text);
        return;
    }
    m_notes->clear();
}

QString NewWorldDialog::worldName() const {
    const QString name = m_name->text().trimmed();
    return name.isEmpty() ? tr("New World") : name;
}

QString NewWorldDialog::profileId() const {
    return m_profile->currentData().toString();
}

} // namespace chunkdaddy
