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
    if (version != kFormatVersion) {
        if (error) {
            *error = QStringLiteral("Tile file %1 is format version %2; this build reads version %3. "
                                    "The worker and the application are out of step.")
                         .arg(path).arg(version).arg(kFormatVersion);
        }
        return false;
    }
    if (widthChunks <= 0 || lengthChunks <= 0 || widthChunks > 4096 || lengthChunks > 4096) {
        if (error) {
            *error = QStringLiteral("Tile file %1 declares an implausible area of %2 x %3 chunks.")
                         .arg(path).arg(widthChunks).arg(lengthChunks);
        }
        return false;
    }

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
            tile.state = static_cast<ColumnState>(rawState);
            if (tile.state == ColumnState::Content) {
                QImage image(16, 16, QImage::Format_ARGB32);
                for (int z = 0; z < 16; ++z) {
                    for (int x = 0; x < 16; ++x) {
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
            m_tiles.insert(chunkKey(chunkX, chunkZ), tile);
        }
    }

    const ChunkRect area = ChunkRect::ofSize(minChunkX, minChunkZ, widthChunks, lengthChunks);
    // Pages overlapping the new tiles have to be rebuilt.
    m_pages.clear();
    emit tilesChanged(area);
    return true;
}

void TileCache::clear() {
    m_tiles.clear();
    m_pages.clear();
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

    QImage page(kPageChunks * 16, kPageChunks * 16, QImage::Format_ARGB32_Premultiplied);
    page.fill(Qt::transparent);
    {
        QPainter painter(&page);
        for (int dx = 0; dx < kPageChunks; ++dx) {
            for (int dz = 0; dz < kPageChunks; ++dz) {
                const QImage tile = chunkImage(pageX * kPageChunks + dx, pageZ * kPageChunks + dz);
                if (!tile.isNull()) {
                    painter.drawImage(dx * 16, dz * 16, tile);
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
