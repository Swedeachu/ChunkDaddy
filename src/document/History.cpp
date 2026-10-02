#include "document/History.h"

#include <algorithm>

namespace chunkdaddy {
namespace {

const QVector<HistoryStep> kNoSteps;

} // namespace

History::History(QObject* parent) : QObject(parent) {}

void History::recordCommit(const QString& documentId, const QString& description, qint64 revision) {
    Stack& stack = m_stacks[documentId];
    // A commit made after an undo discards the redo tail, here and on the worker.
    if (stack.cursor < stack.steps.size()) {
        stack.steps.remove(stack.cursor, stack.steps.size() - stack.cursor);
    }
    stack.steps.append(HistoryStep{description, revision});
    stack.cursor = stack.steps.size();
    m_log.append(QStringLiteral("r%1  %2").arg(revision).arg(description));
    emit changed();
}

void History::recordUndo(const QString& documentId, qint64 revision) {
    Stack& stack = m_stacks[documentId];
    stack.cursor = std::max(0, stack.cursor - 1);
    m_log.append(QStringLiteral("r%1  undo").arg(revision));
    emit changed();
}

void History::recordRedo(const QString& documentId, qint64 revision) {
    Stack& stack = m_stacks[documentId];
    stack.cursor = std::min(int(stack.steps.size()), stack.cursor + 1);
    m_log.append(QStringLiteral("r%1  redo").arg(revision));
    emit changed();
}

void History::forget(const QString& documentId) {
    if (m_stacks.remove(documentId) > 0) {
        emit changed();
    }
}

void History::clear() {
    m_stacks.clear();
    m_log.clear();
    emit changed();
}

const QVector<HistoryStep>& History::steps(const QString& documentId) const {
    const auto it = m_stacks.constFind(documentId);
    return it == m_stacks.constEnd() ? kNoSteps : it->steps;
}

int History::cursor(const QString& documentId) const {
    const auto it = m_stacks.constFind(documentId);
    return it == m_stacks.constEnd() ? 0 : it->cursor;
}

int History::distanceTo(const QString& documentId, int appliedSteps) const {
    return cursor(documentId) - appliedSteps;
}

QStringList History::entries() const {
    return m_log;
}

QString History::lastDescription(const QString& documentId) const {
    const QVector<HistoryStep>& list = steps(documentId);
    const int at = cursor(documentId);
    return at > 0 && at <= list.size() ? list.at(at - 1).description : QString();
}

} // namespace chunkdaddy
