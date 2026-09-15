#include "view/TileCache.h"

#include <QDataStream>
#include <QFile>
#include <QPainter>

namespace chunkdaddy {
namespace {

// Matches PreviewRenderer on the worker side.
constexpr quint32 kMagic = 0x43444154; // "CDAT"
constexpr quint32 kFormatVersion = 1;

} // namespace

TileCache::TileCache(QObject* parent) : QObject(parent) {}

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
    if ((pixels != 1 && pixels != 4 && pixels != 16) || pixels != m_pixelsPerChunk) {
        if (error) *error = QStringLiteral("Preview resolution does not match the current view.");
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
            decoded.insert(chunkKey(chunkX, chunkZ), tile);
        }
    }

    const ChunkRect area = ChunkRect::ofSize(minChunkX, minChunkZ, widthChunks, lengthChunks);
    for (auto it = decoded.cbegin(); it != decoded.cend(); ++it) m_tiles.insert(it.key(), it.value());
    // Rebuild only pages touched by this batch; keep other painted pages warm.
    for (int px = area.minX() >> 4; px <= area.maxX() >> 4; ++px)
        for (int pz = area.minZ() >> 4; pz <= area.maxZ() >> 4; ++pz)
            m_pages.remove(chunkKey(px, pz));
    emit tilesChanged(area);
    return true;
}

void TileCache::clear() {
    m_tiles.clear();
    m_pages.clear();
}

void TileCache::setPixelsPerChunk(int pixels) {
    if (pixels == m_pixelsPerChunk) return;
    m_pixelsPerChunk = pixels;
    clear();
}

bool TileCache::hasRegion(const ChunkRect& area) const {
    for (int x = area.minX(); x <= area.maxX(); ++x)
        for (int z = area.minZ(); z <= area.maxZ(); ++z)
            if (!hasChunk(x, z)) return false;
    return true;
}

void TileCache::retain(const ChunkRect& area) {
    for (auto it = m_tiles.begin(); it != m_tiles.end();) {
        if (!area.contains(chunkKeyX(it.key()), chunkKeyZ(it.key()))) it = m_tiles.erase(it);
        else ++it;
    }
    for (auto it = m_pages.begin(); it != m_pages.end();) {
        const auto page = ChunkRect::ofSize(chunkKeyX(it.key()) * kPageChunks,
                                          chunkKeyZ(it.key()) * kPageChunks, kPageChunks, kPageChunks);
        if (!area.intersects(page)) it = m_pages.erase(it);
        else ++it;
    }
}

void TileCache::invalidate(const ChunkRect& area) {
    for (int cx = area.minX(); cx <= area.maxX(); ++cx) {
        for (int cz = area.minZ(); cz <= area.maxZ(); ++cz) {
            m_tiles.remove(chunkKey(cx, cz));
        }
    }
    m_pages.clear();
}

bool TileCache::hasChunk(int chunkX, int chunkZ) const {
    return m_tiles.contains(chunkKey(chunkX, chunkZ));
}

ColumnState TileCache::state(int chunkX, int chunkZ) const {
    const auto it = m_tiles.constFind(chunkKey(chunkX, chunkZ));
    return it == m_tiles.constEnd() ? ColumnState::Absent : it->state;
}

QImage TileCache::chunkImage(int chunkX, int chunkZ) const {
    const auto it = m_tiles.constFind(chunkKey(chunkX, chunkZ));
    return it == m_tiles.constEnd() ? QImage() : it->image;
}

QImage TileCache::pageImage(int pageX, int pageZ) const {
    const quint64 key = chunkKey(pageX, pageZ);
    const auto cached = m_pages.constFind(key);
    if (cached != m_pages.constEnd()) {
        return cached.value();
    }

    // Do not allocate empty images for the unbounded space outside the world.
    bool known = false;
    for (int dx = 0; dx < kPageChunks && !known; ++dx)
        for (int dz = 0; dz < kPageChunks && !known; ++dz)
            known = hasChunk(pageX * kPageChunks + dx, pageZ * kPageChunks + dz);
    if (!known) return {};
    QImage page(kPageChunks * m_pixelsPerChunk, kPageChunks * m_pixelsPerChunk, QImage::Format_ARGB32_Premultiplied);
    page.fill(Qt::transparent);
    {
        QPainter painter(&page);
        for (int dx = 0; dx < kPageChunks; ++dx) {
            for (int dz = 0; dz < kPageChunks; ++dz) {
                const QImage tile = chunkImage(pageX * kPageChunks + dx, pageZ * kPageChunks + dz);
                if (!tile.isNull()) {
                    painter.drawImage(dx * m_pixelsPerChunk, dz * m_pixelsPerChunk, tile);
                } else if (state(pageX * kPageChunks + dx, pageZ * kPageChunks + dz) == ColumnState::GeneratedVoid) {
                    painter.fillRect(dx * m_pixelsPerChunk, dz * m_pixelsPerChunk,
                                     m_pixelsPerChunk, m_pixelsPerChunk, QColor(38, 40, 52));
                }
            }
        }
    }
    m_pages.insert(key, page);
    return page;
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

} // namespace chunkdaddy
