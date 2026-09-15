#pragma once

#include "layout/Geometry.h"

#include <QHash>
#include <QImage>
#include <QObject>
#include <QSet>
#include <QString>

namespace chunkdaddy {

/// Column state, kept separate from colour so the viewport can tell the user the
/// difference between a gap that will be written and one that will not.
enum class ColumnState : quint8 {
    /// Outside the materialized document, or not imported. Nothing will be written here.
    Absent = 0,
    /// Inside the export rectangle with no content: an explicit empty column will be written.
    GeneratedVoid = 1,
    /// Real content.
    Content = 2
};

/// Holds decoded 16x16 chunk tiles and the reduced images used at distant zoom.
///
/// The cache never allocates an image covering the whole composition. At 336 x 479
/// chunks the full-resolution picture would be 5376 x 7664 pixels; instead tiles are
/// grouped into pages and a reduced level is kept for zoomed-out drawing.
class TileCache : public QObject {
    Q_OBJECT

public:
    /// Chunks per side of a page. Pages are what actually get painted.
    static constexpr int kPageChunks = 16;

    explicit TileCache(QObject* parent = nullptr);

    /// Load a tile file produced by the worker. Returns false with a reason on a bad file.
    bool loadTileFile(const QString& path, QString* error);

    /// Forget everything; used when the document revision changes wholesale.
    void clear();
    /// Forget the tiles covering a rectangle, after an edit changed it.
    void invalidate(const ChunkRect& area);
    void setPixelsPerChunk(int pixels);
    bool hasRegion(const ChunkRect& area) const;
    void retain(const ChunkRect& area);

    bool hasChunk(int chunkX, int chunkZ) const;
    ColumnState state(int chunkX, int chunkZ) const;
    /// 16x16 image for a chunk, or a null image when it is not loaded or has no content.
    QImage chunkImage(int chunkX, int chunkZ) const;

    /// A page image covering kPageChunks x kPageChunks chunks, built on demand.
    QImage pageImage(int pageX, int pageZ) const;

    /// Chunks in `area` that are not cached, so the view can request only those.
    QVector<ChunkRect> missingRegions(const ChunkRect& area) const;

    int cachedChunkCount() const { return m_tiles.size(); }

signals:
    void tilesChanged(const ChunkRect& area);

private:
    struct Tile {
        ColumnState state = ColumnState::Absent;
        QImage image;
    };

    QHash<quint64, Tile> m_tiles;
    mutable QHash<quint64, QImage> m_pages;
    int m_pixelsPerChunk = 16;
};

} // namespace chunkdaddy
