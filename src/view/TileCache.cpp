#include "view/TileCache.h"

#include <QDataStream>
#include <QFile>
#include <QPainter>

#include <algorithm>
#include <cmath>
#include <limits>

namespace chunkdaddy {
namespace {

// Matches PreviewRenderer on the worker side.
constexpr quint32 kMagic = 0x43444154; // "CDAT"
constexpr quint32 kFormatVersion = 1;

bool isKnownLevel(int pixels) {
    for (int level : TileCache::kLevels) {
        if (level == pixels) {
            return true;
        }
    }
    return false;
}

/// Round up to a power of two, so a page is reduced to a handful of distinct sizes
/// instead of a new one for every pixel of zoom.
int roundUpPowerOfTwo(int value) {
    int side = 1;
    while (side < value) {
        side <<= 1;
    }
    return side;
}

} // namespace

TileCache::TileCache(QObject* parent) : QObject(parent) {}

TileCache::Level& TileCache::levelFor(int pixels) {
    return m_levels[pixels];
}

const TileCache::Level* TileCache::levelIfPresent(int pixels) const {
    const auto it = m_levels.constFind(pixels);
    return it == m_levels.constEnd() ? nullptr : &it.value();
}

qint64 TileCache::tileBytes(int pixels, const Tile& tile) {
    // A column with no content carries no image; only the state is worth a few bytes.
    return tile.image.isNull() ? 16 : qint64(pixels) * pixels * 4 + 16;
}

void TileCache::dropPages(Level& level, const ChunkRect& area) const {
    for (int px = area.minX() >> 4; px <= area.maxX() >> 4; ++px) {
        for (int pz = area.minZ() >> 4; pz <= area.maxZ() >> 4; ++pz) {
            const quint64 key = chunkKey(px, pz);
            level.pages.remove(key);
            level.reduced.remove(key);
        }
    }
}

bool TileCache::loadTileFile(const QString& path, QString* error) {
    QFile file(path);
    if (!file.open(QIODevice::ReadOnly)) {
        if (error) {
            *error = QStringLiteral("Could not open preview tiles at %1: %2")
                         .arg(path, file.errorString());
        }
        return false;
    }

    QDataStream stream(&file);
    stream.setByteOrder(QDataStream::BigEndian);

    quint32 magic = 0;
    quint32 version = 0;
    qint32 minChunkX = 0;
    qint32 minChunkZ = 0;
    qint32 widthChunks = 0;
    qint32 lengthChunks = 0;
    stream >> magic >> version >> minChunkX >> minChunkZ >> widthChunks >> lengthChunks;

    if (magic != kMagic) {
        if (error) {
            *error = QStringLiteral("%1 is not a ChunkDaddy tile file.").arg(path);
        }
        return false;
    }
    if (version != kFormatVersion && version != 2) {
        if (error) {
            *error = QStringLiteral("Tile file %1 is format version %2; this build reads version %3. "
                                    "The worker and the application are out of step.")
                         .arg(path).arg(version).arg(kFormatVersion);
        }
        return false;
    }
    qint32 pixels = 16;
    if (version == 2) stream >> pixels;
    // The file says which level it belongs to and is stored there. A reply that arrives
    // after the view changed zoom is still worth keeping: it is real decoded data, and the
    // view paints whichever level has the best coverage of a page.
    if (!isKnownLevel(pixels)) {
        if (error) *error = QStringLiteral("Preview resolution %1 is not a detail level.").arg(pixels);
        return false;
    }
    if (widthChunks <= 0 || lengthChunks <= 0 || qint64(widthChunks) * lengthChunks > 4096
        || qint64(minChunkX) + widthChunks > std::numeric_limits<int>::max()
        || qint64(minChunkZ) + lengthChunks > std::numeric_limits<int>::max()) {
        if (error) {
            *error = QStringLiteral("Tile file %1 declares an implausible area of %2 x %3 chunks.")
                         .arg(path).arg(widthChunks).arg(lengthChunks);
        }
        return false;
    }

    QHash<quint64, Tile> decoded;
    for (int chunkX = minChunkX; chunkX < minChunkX + widthChunks; ++chunkX) {
        for (int chunkZ = minChunkZ; chunkZ < minChunkZ + lengthChunks; ++chunkZ) {
            quint8 rawState = 0;
            stream >> rawState;
            if (stream.status() != QDataStream::Ok) {
                if (error) {
                    *error = QStringLiteral("Tile file %1 ended early.").arg(path);
                }
                return false;
            }
            Tile tile;
            if (rawState > 2) {
                if (error) *error = QStringLiteral("Invalid preview column state.");
                return false;
            }
            tile.state = static_cast<ColumnState>(rawState);
            if (tile.state == ColumnState::Content) {
                QImage image(pixels, pixels, QImage::Format_ARGB32);
                for (int z = 0; z < pixels; ++z) {
                    for (int x = 0; x < pixels; ++x) {
                        quint32 argb = 0;
                        stream >> argb;
                        image.setPixel(x, z, argb);
                    }
                }
                if (stream.status() != QDataStream::Ok) {
                    if (error) {
                        *error = QStringLiteral("Tile file %1 ended inside a chunk.").arg(path);
                    }
                    return false;
                }
                tile.image = image;
            }
            tile.stamp = m_clock;
            decoded.insert(chunkKey(chunkX, chunkZ), tile);
        }
    }

    const ChunkRect area = ChunkRect::ofSize(minChunkX, minChunkZ, widthChunks, lengthChunks);
    Level& level = levelFor(pixels);
    for (auto it = decoded.cbegin(); it != decoded.cend(); ++it) {
        const auto existing = level.tiles.constFind(it.key());
        if (existing != level.tiles.constEnd()) {
            m_bytes -= tileBytes(pixels, existing.value());
        }
        level.tiles.insert(it.key(), it.value());
        m_bytes += tileBytes(pixels, it.value());
    }
    // Rebuild only pages touched by this batch; keep other painted pages warm.
    dropPages(level, area);
    trimToBudget();
    emit tilesChanged(area);
    return true;
}

void TileCache::clear() {
    m_levels.clear();
    m_bytes = 0;
}

void TileCache::setPixelsPerChunk(int pixels) {
    // Only the level the view is fetching for changes. Discarding the others here is what
    // used to blank the viewport on every zoom step.
    m_pixelsPerChunk = pixels;
}

void TileCache::setBudgetBytes(qint64 bytes) {
    m_budgetBytes = std::max<qint64>(bytes, 16LL * 1024 * 1024);
    trimToBudget();
}

bool TileCache::hasRegion(const ChunkRect& area) const {
    for (int x = area.minX(); x <= area.maxX(); ++x)
        for (int z = area.minZ(); z <= area.maxZ(); ++z)
            if (!hasChunk(x, z)) return false;
    return true;
}

void TileCache::retain(const ChunkRect& area) {
    ++m_clock;
    qint64 totalTiles = 0;
    for (auto it = m_levels.cbegin(); it != m_levels.cend(); ++it) {
        totalTiles += it.value().tiles.size();
    }
    // Stamping by walking the visible rectangle is cheaper than walking every cached tile,
    // right up until the rectangle is the larger of the two.
    const bool walkArea = area.columnCount() <= totalTiles;
    for (auto levelIt = m_levels.begin(); levelIt != m_levels.end(); ++levelIt) {
        Level& level = levelIt.value();
        if (walkArea) {
            for (int x = area.minX(); x <= area.maxX(); ++x) {
                for (int z = area.minZ(); z <= area.maxZ(); ++z) {
                    const auto tile = level.tiles.find(chunkKey(x, z));
                    if (tile != level.tiles.end()) tile->stamp = m_clock;
                }
            }
        } else {
            for (auto tile = level.tiles.begin(); tile != level.tiles.end(); ++tile) {
                if (area.contains(chunkKeyX(tile.key()), chunkKeyZ(tile.key()))) {
                    tile->stamp = m_clock;
                }
            }
        }
    }
    trimToBudget();
}

void TileCache::trimToBudget() {
    if (m_bytes <= m_budgetBytes) {
        return;
    }
    // Composited pages are derived data and cost nothing to rebuild, so they go first.
    for (auto it = m_levels.begin(); it != m_levels.end(); ++it) {
        it.value().pages.clear();
        it.value().reduced.clear();
    }
    if (m_bytes <= m_budgetBytes) {
        return;
    }

    // Evict the tiles least recently covered by the visible area, whatever level they are
    // at, down to comfortably under the budget so this does not run on every frame.
    QVector<quint32> stamps;
    for (auto it = m_levels.cbegin(); it != m_levels.cend(); ++it) {
        for (auto tile = it.value().tiles.cbegin(); tile != it.value().tiles.cend(); ++tile) {
            stamps.append(tile.value().stamp);
        }
    }
    if (stamps.isEmpty()) {
        return;
    }
    const double keepFraction = double(m_budgetBytes) * 0.85 / double(m_bytes);
    const qsizetype keep = qsizetype(stamps.size() * std::clamp(keepFraction, 0.0, 1.0));
    const qsizetype dropCount = stamps.size() - keep;
    if (dropCount <= 0) {
        return;
    }
    std::nth_element(stamps.begin(), stamps.begin() + dropCount, stamps.end());
    const quint32 cutoff = stamps.at(dropCount);

    for (auto levelIt = m_levels.begin(); levelIt != m_levels.end(); ++levelIt) {
        const int pixels = levelIt.key();
        Level& level = levelIt.value();
        for (auto tile = level.tiles.begin(); tile != level.tiles.end();) {
            if (tile.value().stamp < cutoff) {
                m_bytes -= tileBytes(pixels, tile.value());
                tile = level.tiles.erase(tile);
            } else {
                ++tile;
            }
        }
    }
}

void TileCache::invalidate(const ChunkRect& area) {
    for (auto levelIt = m_levels.begin(); levelIt != m_levels.end(); ++levelIt) {
        const int pixels = levelIt.key();
        Level& level = levelIt.value();
        for (int cx = area.minX(); cx <= area.maxX(); ++cx) {
            for (int cz = area.minZ(); cz <= area.maxZ(); ++cz) {
                const auto tile = level.tiles.constFind(chunkKey(cx, cz));
                if (tile != level.tiles.constEnd()) {
                    m_bytes -= tileBytes(pixels, tile.value());
                    level.tiles.erase(tile);
                }
            }
        }
        level.pages.clear();
        level.reduced.clear();
    }
}

bool TileCache::hasChunk(int chunkX, int chunkZ) const {
    // A finer level answers for a coarser one: a chunk already decoded at sixteen pixels
    // is better than the four pixel version the view would otherwise go and fetch.
    const quint64 key = chunkKey(chunkX, chunkZ);
    for (int level : kLevels) {
        if (level < m_pixelsPerChunk) continue;
        if (const Level* data = levelIfPresent(level)) {
            if (data->tiles.contains(key)) return true;
        }
    }
    return false;
}

ColumnState TileCache::state(int chunkX, int chunkZ) const {
    const quint64 key = chunkKey(chunkX, chunkZ);
    for (int i = int(std::size(kLevels)) - 1; i >= 0; --i) {
        if (const Level* data = levelIfPresent(kLevels[i])) {
            const auto it = data->tiles.constFind(key);
            if (it != data->tiles.constEnd()) return it->state;
        }
    }
    return ColumnState::Absent;
}

QImage TileCache::chunkImage(int chunkX, int chunkZ) const {
    const quint64 key = chunkKey(chunkX, chunkZ);
    // Finest first: showing a sharper tile than the view asked for is never wrong.
    for (int i = int(std::size(kLevels)) - 1; i >= 0; --i) {
        if (const Level* data = levelIfPresent(kLevels[i])) {
            const auto it = data->tiles.constFind(key);
            if (it != data->tiles.constEnd() && !it->image.isNull()) return it->image;
        }
    }
    return {};
}

QVector<int> TileCache::populatedLevels() const {
    QVector<int> levels;
    for (int level : kLevels) {
        const Level* data = levelIfPresent(level);
        if (data && !data->tiles.isEmpty()) levels.append(level);
    }
    return levels;
}

QImage TileCache::pageImage(int level, int pageX, int pageZ, int targetSide) const {
    const Level* data = levelIfPresent(level);
    if (!data) {
        return {};
    }
    const quint64 key = chunkKey(pageX, pageZ);
    const int fullSide = kPageChunks * level;

    QImage page;
    const auto cached = data->pages.constFind(key);
    if (cached != data->pages.constEnd()) {
        page = cached.value();
    } else {
        // Do not allocate empty images for the unbounded space outside the world.
        bool known = false;
        for (int dx = 0; dx < kPageChunks && !known; ++dx) {
            for (int dz = 0; dz < kPageChunks && !known; ++dz) {
                known = data->tiles.contains(chunkKey(pageX * kPageChunks + dx, pageZ * kPageChunks + dz));
            }
        }
        if (!known) return {};
        page = QImage(fullSide, fullSide, QImage::Format_ARGB32_Premultiplied);
        page.fill(Qt::transparent);
        {
            QPainter painter(&page);
            for (int dx = 0; dx < kPageChunks; ++dx) {
                for (int dz = 0; dz < kPageChunks; ++dz) {
                    const auto tile =
                        data->tiles.constFind(chunkKey(pageX * kPageChunks + dx, pageZ * kPageChunks + dz));
                    if (tile == data->tiles.constEnd()) continue;
                    if (!tile->image.isNull()) {
                        painter.drawImage(dx * level, dz * level, tile->image);
                    } else if (tile->state == ColumnState::GeneratedVoid) {
                        painter.fillRect(dx * level, dz * level, level, level, QColor(38, 40, 52));
                    }
                }
            }
        }
        data->pages.insert(key, page);
    }

    if (targetSide <= 0 || targetSide * 2 > fullSide) {
        return page;
    }
    // Reduce once, properly, and keep it. Letting the painter shrink a 256 pixel page to
    // twenty with a bilinear filter is what made a zoomed-out world look like static.
    const int reducedSide = std::clamp(roundUpPowerOfTwo(targetSide), 1, fullSide);
    const auto reduced = data->reduced.constFind(key);
    if (reduced != data->reduced.constEnd() && reduced.value().width() == reducedSide) {
        return reduced.value();
    }
    const QImage scaled =
        page.scaled(reducedSide, reducedSide, Qt::IgnoreAspectRatio, Qt::SmoothTransformation);
    data->reduced.insert(key, scaled);
    return scaled;
}

QVector<ChunkRect> TileCache::missingRegions(const ChunkRect& area) const {
    // Report missing chunks grouped into row runs: one request per run keeps the number
    // of worker round trips proportional to the visible area, not to its chunk count.
    QVector<ChunkRect> runs;
    for (int cz = area.minZ(); cz <= area.maxZ(); ++cz) {
        int runStart = std::numeric_limits<int>::min();
        for (int cx = area.minX(); cx <= area.maxX(); ++cx) {
            const bool missing = !hasChunk(cx, cz);
            if (missing && runStart == std::numeric_limits<int>::min()) {
                runStart = cx;
            } else if (!missing && runStart != std::numeric_limits<int>::min()) {
                runs.append(ChunkRect(runStart, cz, cx - 1, cz));
                runStart = std::numeric_limits<int>::min();
            }
        }
        if (runStart != std::numeric_limits<int>::min()) {
            runs.append(ChunkRect(runStart, cz, area.maxX(), cz));
        }
    }
    return runs;
}

int TileCache::cachedChunkCount() const {
    int total = 0;
    for (auto it = m_levels.cbegin(); it != m_levels.cend(); ++it) {
        total += it.value().tiles.size();
    }
    return total;
}

int TileCache::cachedChunkCount(int level) const {
    const Level* data = levelIfPresent(level);
    return data ? data->tiles.size() : 0;
}

} // namespace chunkdaddy
