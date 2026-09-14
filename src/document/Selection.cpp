#include "document/Selection.h"

#include <stdexcept>

namespace chunkdaddy {

void Selection::clear() {
    m_added.clear();
    m_subtracted.clear();
}

void Selection::add(const ChunkRect& rect) {
    m_added.append(rect);
}

void Selection::subtract(const ChunkRect& rect) {
    m_subtracted.append(rect);
}

void Selection::set(const ChunkRect& rect) {
    clear();
    m_added.append(rect);
}

bool Selection::contains(int chunkX, int chunkZ) const {
    bool inside = false;
    for (const ChunkRect& rect : m_added) {
        if (rect.contains(chunkX, chunkZ)) {
            inside = true;
            break;
        }
    }
    if (!inside) {
        return false;
    }
    for (const ChunkRect& rect : m_subtracted) {
        if (rect.contains(chunkX, chunkZ)) {
            return false;
        }
    }
    return true;
}

bool Selection::containsAll(const ChunkRect& rect) const {
    for (int cx = rect.minX(); cx <= rect.maxX(); ++cx) {
        for (int cz = rect.minZ(); cz <= rect.maxZ(); ++cz) {
            if (!contains(cx, cz)) {
                return false;
            }
        }
    }
    return true;
}

bool Selection::containsAny(const ChunkRect& rect) const {
    for (int cx = rect.minX(); cx <= rect.maxX(); ++cx) {
        for (int cz = rect.minZ(); cz <= rect.maxZ(); ++cz) {
            if (contains(cx, cz)) {
                return true;
            }
        }
    }
    return false;
}

ChunkRect Selection::bounds() const {
    if (m_added.isEmpty()) {
        throw std::logic_error("Selection::bounds on an empty selection");
    }
    ChunkRect result = m_added.first();
    for (int i = 1; i < m_added.size(); ++i) {
        result = result.united(m_added.at(i));
    }
    return result;
}

std::int64_t Selection::columnCount() const {
    if (m_added.isEmpty()) {
        return 0;
    }
    // Counting over the bounding rectangle is exact because membership is evaluated per
    // column; the subtract rectangles make a simple area sum wrong.
    const ChunkRect box = bounds();
    std::int64_t count = 0;
    for (int cx = box.minX(); cx <= box.maxX(); ++cx) {
        for (int cz = box.minZ(); cz <= box.maxZ(); ++cz) {
            if (contains(cx, cz)) {
                ++count;
            }
        }
    }
    return count;
}

} // namespace chunkdaddy
