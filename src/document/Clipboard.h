#pragma once

#include "layout/Geometry.h"

#include <QObject>
#include <QString>

namespace chunkdaddy {

/// The application's own chunk clipboard.
///
/// This is deliberately not the operating system clipboard. Ctrl+C, Ctrl+X and Ctrl+V in
/// the chunk viewport act on this; text fields keep their ordinary clipboard behaviour.
/// The copied content itself lives in the worker as an immutable snapshot, so later edits
/// to the source, or closing the source tab, do not invalidate it.
class Clipboard : public QObject {
    Q_OBJECT

public:
    explicit Clipboard(QObject* parent = nullptr);

    bool isEmpty() const noexcept { return m_clipboardId.isEmpty(); }
    const QString& clipboardId() const noexcept { return m_clipboardId; }
    const QString& sourceDocumentId() const noexcept { return m_sourceDocumentId; }
    const ChunkRect& relativeBounds() const noexcept { return m_relativeBounds; }
    qint64 columnCount() const noexcept { return m_columns; }
    int arenaCount() const noexcept { return m_arenas; }

    /// True while a cut is captured but not yet committed by a paste.
    bool isPendingCut() const noexcept { return m_pendingCut; }

    void set(const QString& clipboardId, const QString& sourceDocumentId,
             const ChunkRect& relativeBounds, qint64 columns, int arenas, bool pendingCut);
    /// Cancel a pending cut. The source is untouched, because a cut only clears its
    /// source when the paste commits.
    void cancelPendingCut();
    void clear();

    QString describe() const;

signals:
    void changed();

private:
    QString m_clipboardId;
    QString m_sourceDocumentId;
    ChunkRect m_relativeBounds;
    qint64 m_columns = 0;
    int m_arenas = 0;
    bool m_pendingCut = false;
};

} // namespace chunkdaddy
