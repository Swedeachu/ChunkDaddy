#include "layout/Geometry.h"

#include <algorithm>
#include <limits>
#include <stdexcept>

namespace chunkdaddy {

int ceilDiv(int value, int divisor) {
    if (divisor <= 0) {
        throw std::invalid_argument("ceilDiv divisor must be positive");
    }
    if (value < 0) {
        throw std::invalid_argument("ceilDiv value must be non-negative");
    }
    return (value + divisor - 1) / divisor;
}

std::optional<std::int64_t> mulChecked(std::int64_t a, std::int64_t b) {
    // Signed overflow is undefined behaviour, so a post-hoc division check can legally be
    // optimized away. Use the compiler's overflow builtin where it exists and a range
    // check against the limits otherwise.
#if defined(__GNUC__) || defined(__clang__)
    std::int64_t result = 0;
    if (__builtin_mul_overflow(a, b, &result)) {
        return std::nullopt;
    }
    return result;
#else
    if (a == 0 || b == 0) {
        return std::int64_t{0};
    }
    constexpr std::int64_t kMax = std::numeric_limits<std::int64_t>::max();
    constexpr std::int64_t kMin = std::numeric_limits<std::int64_t>::min();
    if (a > 0) {
        if (b > 0) {
            if (a > kMax / b) return std::nullopt;
        } else {
            if (b < kMin / a) return std::nullopt;
        }
    } else {
        if (b > 0) {
            if (a < kMin / b) return std::nullopt;
        } else {
            if (a < kMax / b) return std::nullopt;
        }
    }
    return a * b;
#endif
}

ChunkRect::ChunkRect(int minX, int minZ, int maxX, int maxZ)
    : m_minX(minX), m_minZ(minZ), m_maxX(maxX), m_maxZ(maxZ) {
    if (maxX < minX || maxZ < minZ) {
        throw std::invalid_argument("ChunkRect has an inverted extent");
    }
}

ChunkRect ChunkRect::ofSize(int minX, int minZ, int widthChunks, int lengthChunks) {
    if (widthChunks <= 0 || lengthChunks <= 0) {
        throw std::invalid_argument("ChunkRect size must be positive");
    }
    return ChunkRect(minX, minZ, minX + widthChunks - 1, minZ + lengthChunks - 1);
}

ChunkRect ChunkRect::fromCorners(int aX, int aZ, int bX, int bZ) {
    return ChunkRect(std::min(aX, bX), std::min(aZ, bZ), std::max(aX, bX), std::max(aZ, bZ));
}

std::int64_t ChunkRect::columnCount() const noexcept {
    return static_cast<std::int64_t>(widthChunks()) * static_cast<std::int64_t>(lengthChunks());
}

bool ChunkRect::contains(int chunkX, int chunkZ) const noexcept {
    return chunkX >= m_minX && chunkX <= m_maxX && chunkZ >= m_minZ && chunkZ <= m_maxZ;
}

bool ChunkRect::contains(const ChunkRect& other) const noexcept {
    return other.m_minX >= m_minX && other.m_maxX <= m_maxX
           && other.m_minZ >= m_minZ && other.m_maxZ <= m_maxZ;
}

bool ChunkRect::intersects(const ChunkRect& other) const noexcept {
    return m_minX <= other.m_maxX && m_maxX >= other.m_minX
           && m_minZ <= other.m_maxZ && m_maxZ >= other.m_minZ;
}

ChunkRect ChunkRect::united(const ChunkRect& other) const {
    return ChunkRect(std::min(m_minX, other.m_minX), std::min(m_minZ, other.m_minZ),
                     std::max(m_maxX, other.m_maxX), std::max(m_maxZ, other.m_maxZ));
}

ChunkRect ChunkRect::expanded(int chunks) const {
    if (chunks < 0) {
        throw std::invalid_argument("Border must be non-negative");
    }
    return ChunkRect(m_minX - chunks, m_minZ - chunks, m_maxX + chunks, m_maxZ + chunks);
}

ChunkRect ChunkRect::translated(int dChunkX, int dChunkZ) const {
    return ChunkRect(m_minX + dChunkX, m_minZ + dChunkZ, m_maxX + dChunkX, m_maxZ + dChunkZ);
}

QString ChunkRect::describe() const {
    return QStringLiteral("chunks %1,%2 to %3,%4 (%5 x %6)")
        .arg(m_minX).arg(m_minZ).arg(m_maxX).arg(m_maxZ)
        .arg(widthChunks()).arg(lengthChunks());
}

} // namespace chunkdaddy
