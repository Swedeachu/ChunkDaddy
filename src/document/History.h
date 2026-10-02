#pragma once

#include <QHash>
#include <QObject>
#include <QString>
#include <QStringList>
#include <QVector>

namespace chunkdaddy {

/// One committed operation, as the UI names it.
struct HistoryStep {
    QString description;
    /// Document revision this step produced.
    qint64 revision = 0;
};

/// A per-document mirror of the worker's undo stack, so the UI can name the steps and
/// offer to walk back to any of them.
///
/// The authoritative undo state lives with the worker's document revisions; this is not a
/// second, independent stack that could disagree with it. What it adds is shape: a list of
/// steps and a cursor saying how many of them are currently applied. Undo moves the cursor
/// back rather than appending an "undo" line, so the list reads like an editor's history
/// instead of a transcript, and a step below the cursor can be jumped to by issuing the
/// difference as undo or redo calls.
///
/// Stacks are per document because the worker's undo is: undoing in one tab must not
/// consume a step belonging to another.
class History : public QObject {
    Q_OBJECT

public:
    explicit History(QObject* parent = nullptr);

    /// Record a committed edit. Anything previously undone is dropped, because committing
    /// after an undo is what discards the redo tail on the worker side too.
    void recordCommit(const QString& documentId, const QString& description, qint64 revision);
    void recordUndo(const QString& documentId, qint64 revision);
    void recordRedo(const QString& documentId, qint64 revision);
    /// Drop a closed document's stack.
    void forget(const QString& documentId);
    void clear();

    const QVector<HistoryStep>& steps(const QString& documentId) const;
    /// How many steps are applied. Steps at this index and beyond have been undone.
    int cursor(const QString& documentId) const;
    /// How many undo calls reach `index` steps applied; negative means redo calls.
    int distanceTo(const QString& documentId, int appliedSteps) const;

    /// Readable lines for the whole workspace, newest last.
    QStringList entries() const;
    QString lastDescription(const QString& documentId) const;

signals:
    void changed();

private:
    struct Stack {
        QVector<HistoryStep> steps;
        int cursor = 0;
    };

    QHash<QString, Stack> m_stacks;
    /// Insertion order, so the readable log is chronological across tabs.
    QStringList m_log;
};

} // namespace chunkdaddy
