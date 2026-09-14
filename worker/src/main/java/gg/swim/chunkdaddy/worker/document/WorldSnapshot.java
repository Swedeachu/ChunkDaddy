package gg.swim.chunkdaddy.worker.document;

import com.hivemc.chunker.conversion.intermediate.column.ChunkerColumn;
import gg.swim.chunkdaddy.worker.util.Checked;
import gg.swim.chunkdaddy.worker.util.ChunkRect;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * An immutable view of a world's contents at one revision.
 *
 * <p>Two kinds of content coexist:
 * <ul>
 *   <li><b>Materialized columns</b> - real block data, from an imported world or from a
 *       paste that had to be baked. Held one object per chunk column.</li>
 *   <li><b>Arena instances</b> - references to a shared template plus a placement. Four
 *       hundred and fifty of these cost four hundred and fifty small records, not four
 *       hundred and fifty copies of the blocks.</li>
 * </ul>
 *
 * <p>Revisions share structure: producing a new snapshot copies the column map's
 * references, never the column contents. Callers must therefore treat a column obtained
 * from a snapshot as read-only; {@link WorldDocument} enforces this by only ever putting
 * freshly built columns into a new snapshot.
 */
public final class WorldSnapshot {
    private final Map<Long, ChunkerColumn> materialized;
    private final Map<UUID, ArenaInstance> instances;
    /** chunk key -> instances overlapping that column; derived, built once per snapshot. */
    private final Map<Long, List<ArenaInstance>> instanceIndex;

    private WorldSnapshot(Map<Long, ChunkerColumn> materialized, Map<UUID, ArenaInstance> instances) {
        this.materialized = materialized;
        this.instances = instances;
        this.instanceIndex = buildIndex(instances.values());
    }

    public static WorldSnapshot empty() {
        return new WorldSnapshot(Map.of(), Map.of());
    }

    private static Map<Long, List<ArenaInstance>> buildIndex(Collection<ArenaInstance> instances) {
        if (instances.isEmpty()) return Map.of();
        Map<Long, List<ArenaInstance>> index = new HashMap<>();
        for (ArenaInstance instance : instances) {
            ChunkRect bounds = instance.chunkBounds();
            for (int cx = bounds.minX(); cx <= bounds.maxX(); cx++) {
                for (int cz = bounds.minZ(); cz <= bounds.maxZ(); cz++) {
                    index.computeIfAbsent(Checked.chunkKey(cx, cz), k -> new ArrayList<>(1)).add(instance);
                }
            }
        }
        return index;
    }

    public @Nullable ChunkerColumn materializedColumn(int chunkX, int chunkZ) {
        return materialized.get(Checked.chunkKey(chunkX, chunkZ));
    }

    public Set<Long> materializedKeys() {
        return Collections.unmodifiableSet(materialized.keySet());
    }

    public int materializedCount() {
        return materialized.size();
    }

    public Collection<ArenaInstance> instances() {
        return Collections.unmodifiableCollection(instances.values());
    }

    public @Nullable ArenaInstance instance(UUID id) {
        return instances.get(id);
    }

    public List<ArenaInstance> instancesAt(int chunkX, int chunkZ) {
        return instanceIndex.getOrDefault(Checked.chunkKey(chunkX, chunkZ), List.of());
    }

    /** True when the column has any content at all; false means it would be void-filled. */
    public boolean hasContent(int chunkX, int chunkZ) {
        long key = Checked.chunkKey(chunkX, chunkZ);
        return materialized.containsKey(key) || instanceIndex.containsKey(key);
    }

    /**
     * Inclusive chunk bounds of all committed content, or null when the world is empty.
     * This is the default basis for the export rectangle.
     */
    public @Nullable ChunkRect contentChunkBounds() {
        ChunkRect result = null;
        for (Long key : materialized.keySet()) {
            int cx = Checked.chunkKeyX(key);
            int cz = Checked.chunkKeyZ(key);
            ChunkRect single = new ChunkRect(cx, cz, cx, cz);
            result = result == null ? single : result.union(single);
        }
        for (ArenaInstance instance : instances.values()) {
            ChunkRect bounds = instance.chunkBounds();
            result = result == null ? bounds : result.union(bounds);
        }
        return result;
    }

    // ------------------------------------------------------------------
    // Structural sharing
    // ------------------------------------------------------------------

    public Builder toBuilder() {
        return new Builder(materialized, instances);
    }

    /** Accumulates one edit and produces the next snapshot. */
    public static final class Builder {
        private final Map<Long, ChunkerColumn> columns;
        private final Map<UUID, ArenaInstance> instances;

        private Builder(Map<Long, ChunkerColumn> columns, Map<UUID, ArenaInstance> instances) {
            this.columns = new LinkedHashMap<>(columns);
            this.instances = new LinkedHashMap<>(instances);
        }

        public Builder putColumn(int chunkX, int chunkZ, ChunkerColumn column) {
            columns.put(Checked.chunkKey(chunkX, chunkZ), column);
            return this;
        }

        public Builder removeColumn(int chunkX, int chunkZ) {
            columns.remove(Checked.chunkKey(chunkX, chunkZ));
            return this;
        }

        public Builder removeColumn(long key) {
            columns.remove(key);
            return this;
        }

        public @Nullable ChunkerColumn column(long key) {
            return columns.get(key);
        }

        public Builder putInstance(ArenaInstance instance) {
            instances.put(instance.id(), instance);
            return this;
        }

        public Builder removeInstance(UUID id) {
            instances.remove(id);
            return this;
        }

        public Collection<ArenaInstance> instances() {
            return instances.values();
        }

        public WorldSnapshot build() {
            return new WorldSnapshot(Map.copyOf(columns), Map.copyOf(instances));
        }
    }
}
