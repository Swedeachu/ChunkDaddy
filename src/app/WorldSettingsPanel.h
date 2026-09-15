#pragma once

#include "document/Document.h"
#include "document/Workspace.h"

#include <QHash>
#include <QJsonObject>
#include <QVector>
#include <QWidget>

class QComboBox;
class QLabel;
class QPushButton;
class QTabWidget;
class QVBoxLayout;

namespace chunkdaddy {

/// Dock: the world's level settings, built from whatever the worker says exists.
///
/// The controls are generated from a schema the worker sends rather than written out here,
/// so this panel does not carry a second, drifting list of what a Minecraft world can be
/// configured with. A setting that the pinned Chunker revision gains later turns up here on
/// its own; one it loses stops being offered, instead of writing into nothing.
///
/// Nothing is sent until Apply, and only fields whose value actually changed are sent. The
/// reply always carries the settings as they now are, and that is what gets redisplayed, so
/// a value the worker clamped or refused shows its real state rather than what was typed.
class WorldSettingsPanel : public QWidget {
    Q_OBJECT

public:
    explicit WorldSettingsPanel(Workspace* workspace, QWidget* parent = nullptr);

    void setDocument(Document* document);

    /// Rebuild from the worker. Safe to call at any time; a failure leaves the last good
    /// state on screen with the reason under it.
    void reload();

signals:
    /// Something was changed that the rest of the window should notice, such as the spawn.
    void settingsChanged();

private:
    struct FieldWidget {
        QString name;
        QString kind;             ///< bool, choice, int, real, text
        bool optionsAreOrdinals = false;
        QWidget* editor = nullptr;
        QJsonValue original;      ///< the value as the worker last reported it
    };

    void buildFrom(const QJsonObject& payload);
    void clearFields();
    QJsonObject collectChanges() const;
    QJsonValue currentValue(const FieldWidget& field) const;
    void applyChanges();
    void revertToSource();
    void adoptFromSelected();
    void refreshSourceControls();
    void setStatus(const QString& text, bool problem);
    void setEditingEnabled(bool enabled);

    Workspace* m_workspace = nullptr;
    Document* m_document = nullptr;

    QTabWidget* m_tabs = nullptr;
    QLabel* m_banner = nullptr;
    QLabel* m_status = nullptr;
    QComboBox* m_adoptFrom = nullptr;
    QPushButton* m_applyButton = nullptr;
    QPushButton* m_revertButton = nullptr;
    QPushButton* m_adoptButton = nullptr;
    QPushButton* m_reloadButton = nullptr;

    QVector<FieldWidget> m_fields;
    bool m_loading = false;
};

} // namespace chunkdaddy
