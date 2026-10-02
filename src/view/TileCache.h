#pragma once

#include "layout/Geometry.h"

#include <QHash>
#include <QImage>
#include <QObject>
#include <QSet>
#include <QString>
#include <QVector>

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

/// Holds decoded chunk tiles at several detail levels at once.
///
/// Zooming used to throw the cache away: a new zoom band picked a different tile
/// resolution, the cache was cleared, and the viewport went blank until the worker had
/// re-rendered everything that was already on screen a moment earlier. So tiles are
/// stored per detail level instead, and nothing a level holds is discarded when the view
/// moves to another one. A chunk already decoded at a finer level satisfies a request for
/// a coarser one, which is what makes a zoom-out instant and keeps it sharp: the view
/// paints the best level it has for each page, not the level it happens to be fetching.
///
/// The cache never allocates an image covering the whole composition. At 336 x 479 chunks
/// the full-resolution picture would be 5376 x 7664 pixels; instead tiles are grouped into
/// pages, each page is reduced on demand for distant zoom, and everything is held inside a
/// byte budget with the least recently seen tiles evicted first.
class TileCache : public QObject {
    Q_OBJECT

public:
    /// Chunks per side of a page. Pages are what actually get painted.
    static constexpr int kPageChunks = 16;
    /// Detail levels, in pixels per chunk, coarsest first. A fetch never asks for less
    /// than four: one pixel per chunk cannot show an arena's shape, and the difference in
    /// bytes is irrelevant beside the cost of asking the worker for it twice.
    static constexpr int kLevels[] = {1, 4, 16};
    static constexpr qint64 kDefaultBudgetBytes = 512LL * 1024 * 1024;

    explicit TileCache(QObject* parent = nullptr);

    /// Load a tile file produced by the worker. Returns false with a reason on a bad file.
    /// The file names its own resolution and is stored at that level, so a reply that
    /// arrives after the view has changed zoom is kept rather than thrown away.
    bool loadTileFile(const QString& path, QString* error);

    /// Forget everything; used when the document revision or the height slice changes and
    /// every level is genuinely stale.
    void clear();
    /// Forget the tiles covering a rectangle, at every level, after an edit changed it.
    void invalidate(const ChunkRect& area);

    /// Switch the level that `hasRegion`, `missingRegions` and `hasChunk` speak for.
    /// Nothing is discarded: the other levels stay available for painting.
    void setPixelsPerChunk(int pixels);
    int pixelsPerChunk() const noexcept { return m_pixelsPerChunk; }

    /// True when every column in `area` is held at the active resolution or better.
    bool hasRegion(const ChunkRect& area) const;
    /// Mark `area` as the hot region and evict elsewhere if the budget is exceeded.
    void retain(const ChunkRect& area);
    void setBudgetBytes(qint64 bytes);
    qint64 bytesUsed() const noexcept { return m_bytes; }

    /// True when the column is held at the active resolution or better.
    bool hasChunk(int chunkX, int chunkZ) const;
    /// Best state known for a column at any level.
    ColumnState state(int chunkX, int chunkZ) const;
    /// Best image available for a chunk, from the finest level that has one. Null when no
    /// level holds it or the column has no content.
    QImage chunkImage(int chunkX, int chunkZ) const;

    /// Levels that hold at least one tile, coarsest first. The view paints them in this
    /// order so a finer level drawn later covers the coarse one wherever it has data.
    QVector<int> populatedLevels() const;

    /// A page image for one level, covering kPageChunks x kPageChunks chunks, built on
    /// demand from that level's tiles only. Null when the level knows nothing there.
    ///
    /// `targetSide` is the width in device pixels the page will be drawn at. When that is
    /// much smaller than the page, a smoothly reduced copy is cached and returned, because
    /// letting the painter shrink a 256 pixel page to 20 with a bilinear filter is what
    /// made distant zoom look like noise.
    QImage pageImage(int level, int pageX, int pageZ, int targetSide = 0) const;

    /// Chunks in `area` that are not held at the active resolution or better, so the view
    /// can request only those.
    QVector<ChunkRect> missingRegions(const ChunkRect& area) const;

    int cachedChunkCount() const;
    int cachedChunkCount(int level) const;

signals:
    void tilesChanged(const ChunkRect& area);

private:
    struct Tile {
        ColumnState state = ColumnState::Absent;
        QImage image;
        /// Logical clock value of the last `retain` that covered this tile.
        quint32 stamp = 0;
    };

    struct Level {
        QHash<quint64, Tile> tiles;
        mutable QHash<quint64, QImage> pages;
        mutable QHash<quint64, QImage> reduced;
    };

    Level& levelFor(int pixels);
    const Level* levelIfPresent(int pixels) const;
    static qint64 tileBytes(int pixels, const Tile& tile);
    void trimToBudget();
    void dropPages(Level& level, const ChunkRect& area) const;

    QHash<int, Level> m_levels;
    int m_pixelsPerChunk = 16;
    qint64 m_bytes = 0;
    qint64 m_budgetBytes = kDefaultBudgetBytes;
    quint32 m_clock = 0;
};

} // namespace chunkdaddy
