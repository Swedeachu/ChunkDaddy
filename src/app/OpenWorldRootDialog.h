#pragma once

#include <QDialog>
#include <QJsonArray>
#include <QString>

class QTreeWidget;

namespace chunkdaddy {

/// Chooses which world to open when a container holds more than one.
///
/// The supplied `pvp zone worlds.zip` nests several worlds at different depths, so this
/// is a normal case rather than an edge case. Roots are identified from structure and
/// metadata; the extension is only a hint.
class OpenWorldRootDialog : public QDialog {
    Q_OBJECT

public:
    struct Choice {
        QString directory;
        QString edition;
        QString name;
    };

    OpenWorldRootDialog(const QJsonArray& worlds, QWidget* parent = nullptr);

    /// The selected world, or an empty directory when nothing was chosen.
    Choice choice() const;

private:
    QTreeWidget* m_tree = nullptr;
};

} // namespace chunkdaddy
