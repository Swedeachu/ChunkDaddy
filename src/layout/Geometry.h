#pragma once

#include <cstdint>
#include <optional>
#include <QString>

namespace chunkdaddy {

/// Chunk coordinate containing a block coordinate, using mathematical floor.
/// Block X -1 is chunk -1 with local X 15; truncating division would say chunk 0.
constexpr int blockToChunk(int block) noexcept { return block >> 4; }

/// Local 0..15 coordinate of a block within its chunk.
constexpr int localInChunk(int block) noexcept { return block & 15; }

/// First block coordinate of a chunk.
constexpr int chunkToBlock(int chunk) noexcept { return chunk << 4; }

/// Ceiling division for non-negative values.
int ceilDiv(int value, int divisor);

/// 64-bit multiply that reports overflow instead of wrapping.
std::optional<std::int64_t> mulChecked(std::int64_t a, std::int64_t b);

/// Pack a chunk coordinate pair into a single key.
constexpr std::uint64_t chunkKey(int chunkX, int chunkZ) noexcept {
    return (static_cast<std::uint64_t>(static_cast<std::uint32_t>(chunkX)) << 32)
           | static_cast<std::uint32_t>(chunkZ);
}

constexpr int chunkKeyX(std::uint64_t key) noexcept {
    return static_cast<int>(static_cast<std::uint32_t>(key >> 32));
}

constexpr int chunkKeyZ(std::uint64_t key) noexcept {
    return static_cast<int>(static_cast<std::uint32_t>(key));
}

/// A position in block space.
struct BlockPos {
    int x = 0;
    int y = 0;
    int z = 0;

    friend bool operator==(const BlockPos&, const BlockPos&) = default;
};

/// An inclusive rectangle of chunk columns.
///
/// Inclusive bounds are what the UI shows and what the user reasons about. Iteration
/// internally derives maxima from min + size so there are no scattered +1 corrections.
class ChunkRect {
public:
    ChunkRect() = default;
    ChunkRect(int minX, int minZ, int maxX, int maxZ);

    static ChunkRect ofSize(int minX, int minZ, int widthChunks, int lengthChunks);
    /// Rectangle spanning two clicked corners, in either order.
    static ChunkRect fromCorners(int aX, int aZ, int bX, int bZ);

    int minX() const noexcept { return m_minX; }
    int minZ() const noexcept { return m_minZ; }
    int maxX() const noexcept { return m_maxX; }
    int maxZ() const noexcept { return m_maxZ; }

    int widthChunks() const noexcept { return m_maxX - m_minX + 1; }
    int lengthChunks() const noexcept { return m_maxZ - m_minZ + 1; }

    /// Column count as 64-bit: a large composition overflows a 32-bit product.
    std::int64_t columnCount() const noexcept;

    bool contains(int chunkX, int chunkZ) const noexcept;
    bool contains(const ChunkRect& other) const noexcept;
    bool intersects(const ChunkRect& other) const noexcept;

    ChunkRect united(const ChunkRect& other) const;
    ChunkRect expanded(int chunks) const;
    ChunkRect translated(int dChunkX, int dChunkZ) const;

    /// Inclusive block bounds of the rectangle.
    BlockPos minBlock() const noexcept { return {chunkToBlock(m_minX), 0, chunkToBlock(m_minZ)}; }
    BlockPos maxBlock() const noexcept {
        return {chunkToBlock(m_maxX) + 15, 0, chunkToBlock(m_maxZ) + 15};
    }

    QString describe() const;

    friend bool operator==(const ChunkRect&, const ChunkRect&) = default;

private:
    int m_minX = 0;
    int m_minZ = 0;
    int m_maxX = 0;
    int m_maxZ = 0;
};

} // namespace chunkdaddy
