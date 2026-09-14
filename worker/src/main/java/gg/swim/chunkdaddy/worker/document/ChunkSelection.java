package gg.swim.chunkdaddy.worker.document;

import gg.swim.chunkdaddy.worker.util.Checked;
import gg.swim.chunkdaddy.worker.util.ChunkRect;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * A set of chunk columns, expressed as added and subtracted rectangles.
 *
 * <p>A selection always covers full vertical columns. Selecting part of a column's height
 * is a separate feature and is deliberately not faked here.
 */
public final class ChunkSelection {
    private final List<ChunkRect> added = new ArrayList<>();
    private final List<ChunkRect> subtracted = new ArrayList<>();

    public ChunkSelection add(ChunkRect rect) {
        added.add(rect);
        return this;
    }

    public ChunkSelection subtract(ChunkRect rect) {
        subtracted.add(rect);
        return this;
    }

    public boolean isEmpty() {
        return added.isEmpty();
    }

    public boolean contains(int chunkX, int chunkZ) {
        boolean inside = false;
        for (ChunkRect rect : added) {
            if (rect.contains(chunkX, chunkZ)) {
                inside = true;
                break;
            }
        }
        if (!inside) return false;
        for (ChunkRect rect : subtracted) {
            if (rect.contains(chunkX, chunkZ)) return false;
        }
        return true;
    }

    /** Inclusive bounding rectangle of the added rectangles. */
    public ChunkRect bounds() {
        if (added.isEmpty()) throw new IllegalStateException("Empty selection has no bounds");
        ChunkRect result = added.get(0);
        for (int i = 1; i < added.size(); i++) {
            result = result.union(added.get(i));
        }
        return result;
    }

    /** Every selected chunk key. Bounded by the selection's own rectangles. */
    public Set<Long> keys() {
        Set<Long> keys = new LinkedHashSet<>();
        for (ChunkRect rect : added) {
            for (int cx = rect.minX(); cx <= rect.maxX(); cx++) {
                for (int cz = rect.minZ(); cz <= rect.maxZ(); cz++) {
                    if (contains(cx, cz)) keys.add(Checked.chunkKey(cx, cz));
                }
            }
        }
        return keys;
    }

    public long columnCount() {
        return keys().size();
    }

    /** True when every chunk of the rectangle is selected. */
    public boolean containsAll(ChunkRect rect) {
        for (int cx = rect.minX(); cx <= rect.maxX(); cx++) {
            for (int cz = rect.minZ(); cz <= rect.maxZ(); cz++) {
                if (!contains(cx, cz)) return false;
            }
        }
        return true;
    }

    /** True when any chunk of the rectangle is selected. */
    public boolean containsAny(ChunkRect rect) {
        for (int cx = rect.minX(); cx <= rect.maxX(); cx++) {
            for (int cz = rect.minZ(); cz <= rect.maxZ(); cz++) {
                if (contains(cx, cz)) return true;
            }
        }
        return false;
    }

    public List<ChunkRect> addedRects() {
        return List.copyOf(added);
    }

    public List<ChunkRect> subtractedRects() {
        return List.copyOf(subtracted);
    }
}
