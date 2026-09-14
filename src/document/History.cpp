#include "document/History.h"

namespace chunkdaddy {

History::History(QObject* parent) : QObject(parent) {}

void History::recordCommit(const QString& description, qint64 revision) {
    m_entries.append(QStringLiteral("r%1  %2").arg(revision).arg(description));
    emit changed();
}

void History::recordUndo(qint64 revision) {
    m_entries.append(QStringLiteral("r%1  undo").arg(revision));
    emit changed();
}

void History::recordRedo(qint64 revision) {
    m_entries.append(QStringLiteral("r%1  redo").arg(revision));
    emit changed();
}

void History::clear() {
    m_entries.clear();
    emit changed();
}

QString History::lastDescription() const {
    return m_entries.isEmpty() ? QString() : m_entries.last();
}

} // namespace chunkdaddy
