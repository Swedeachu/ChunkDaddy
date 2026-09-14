#include "document/Clipboard.h"

namespace chunkdaddy {

Clipboard::Clipboard(QObject* parent) : QObject(parent) {}

void Clipboard::set(const QString& clipboardId, const QString& sourceDocumentId,
                    const ChunkRect& relativeBounds, qint64 columns, int arenas, bool pendingCut) {
    m_clipboardId = clipboardId;
    m_sourceDocumentId = sourceDocumentId;
    m_relativeBounds = relativeBounds;
    m_columns = columns;
    m_arenas = arenas;
    m_pendingCut = pendingCut;
    emit changed();
}

void Clipboard::cancelPendingCut() {
    if (!m_pendingCut) {
        return;
    }
    m_pendingCut = false;
    clear();
}

void Clipboard::clear() {
    m_clipboardId.clear();
    m_sourceDocumentId.clear();
    m_relativeBounds = ChunkRect();
    m_columns = 0;
    m_arenas = 0;
    m_pendingCut = false;
    emit changed();
}

QString Clipboard::describe() const {
    if (isEmpty()) {
        return QStringLiteral("Chunk clipboard is empty");
    }
    QString text = QStringLiteral("%1 column(s), %2 x %3 chunks")
                       .arg(m_columns)
                       .arg(m_relativeBounds.widthChunks())
                       .arg(m_relativeBounds.lengthChunks());
    if (m_arenas > 0) {
        text += QStringLiteral(", %1 arena(s)").arg(m_arenas);
    }
    if (m_pendingCut) {
        text += QStringLiteral(" — pending cut, the source clears when you paste");
    }
    return text;
}

} // namespace chunkdaddy
