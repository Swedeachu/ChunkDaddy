#include "document/Selection.h"

#include <stdexcept>
#include <algorithm>

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
    if (m_added.size() == 1 && m_subtracted.isEmpty()) return m_added.first().columnCount();
    // Membership changes only at rectangle edges. Count these cells instead of
    // walking every chunk between distant selections.
    QVector<qint64> xs, zs;
    for (const auto& list : {m_added, m_subtracted}) {
        for (const auto& r : list) {
            xs << r.minX() << qint64(r.maxX()) + 1;
            zs << r.minZ() << qint64(r.maxZ()) + 1;
        }
    }
    std::sort(xs.begin(), xs.end());
    xs.erase(std::unique(xs.begin(), xs.end()), xs.end());
    std::sort(zs.begin(), zs.end());
    zs.erase(std::unique(zs.begin(), zs.end()), zs.end());
    std::int64_t count = 0;
    for (qsizetype x = 0; x + 1 < xs.size(); ++x) {
        for (qsizetype z = 0; z + 1 < zs.size(); ++z) {
            if (contains(int(xs[x]), int(zs[z]))) {
                count += (xs[x + 1] - xs[x]) * (zs[z + 1] - zs[z]);
            }
        }
    }
    return count;
}

} // namespace chunkdaddy
