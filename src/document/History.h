#pragma once

#include <QObject>
#include <QString>
#include <QStringList>

namespace chunkdaddy {

/// A readable log of committed operations, for the Edit menu and the report panel.
///
/// The authoritative undo state lives with the worker's document revisions; this mirrors
/// it so the UI can name the steps. It is deliberately not a second, independent undo
/// stack: two stacks that can disagree about a cross-tab transaction would be worse than
/// none.
class History : public QObject {
    Q_OBJECT

public:
    explicit History(QObject* parent = nullptr);

    void recordCommit(const QString& description, qint64 revision);
    void recordUndo(qint64 revision);
    void recordRedo(qint64 revision);
    void clear();

    const QStringList& entries() const noexcept { return m_entries; }
    QString lastDescription() const;

signals:
    void changed();

private:
    QStringList m_entries;
};

} // namespace chunkdaddy
