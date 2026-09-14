#pragma once

#include "layout/Geometry.h"

#include <QVector>
#include <cstdint>

namespace chunkdaddy {

/// A set of chunk columns built from added and subtracted rectangles.
///
/// A selection always covers the full vertical column. Selecting part of a column's
/// height is a separate feature and is not approximated here.
class Selection {
public:
    void clear();
    void add(const ChunkRect& rect);
    void subtract(const ChunkRect& rect);
    /// Replace the whole selection with one rectangle.
    void set(const ChunkRect& rect);

    bool isEmpty() const noexcept { return m_added.isEmpty(); }
    bool contains(int chunkX, int chunkZ) const;
    bool containsAll(const ChunkRect& rect) const;
    bool containsAny(const ChunkRect& rect) const;

    /// Inclusive bounding rectangle; only valid when the selection is not empty.
    ChunkRect bounds() const;
    std::int64_t columnCount() const;

    const QVector<ChunkRect>& addedRects() const noexcept { return m_added; }
    const QVector<ChunkRect>& subtractedRects() const noexcept { return m_subtracted; }

private:
    QVector<ChunkRect> m_added;
    QVector<ChunkRect> m_subtracted;
};

} // namespace chunkdaddy
