package gg.swim.chunkdaddy.worker.document;

import com.hivemc.chunker.conversion.encoding.base.resolver.blockentity.BlockEntityResolver;
import com.hivemc.chunker.conversion.intermediate.column.ChunkerColumn;
import com.hivemc.chunker.nbt.tags.collection.CompoundTag;
import gg.swim.chunkdaddy.worker.util.Checked;
import gg.swim.chunkdaddy.worker.util.ChunkRect;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * One open world: its content revisions, its export rectangle and its arena identities.
 *
 * <p>Every content-changing operation updates blocks and placement metadata in the same
 * revision, so the arena JSON that is exported always describes the same committed state
 * as the world bytes.
 */
public final class WorldDocument {
    /**
     * How many revisions of history to keep. Each revision shares column objects with its
     * neighbours, so the cost is one map of references per step, not one copy of the world.
     */
    public static final int MAX_HISTORY = 64;

    private final UUID id = UUID.randomUUID();
    private final TemplateRegistry templates;
    private final InstanceRegistry instanceRegistry = new InstanceRegistry();

    private String name;
    private String targetProfileId;
    private final List<WorldSnapshot> history = new ArrayList<>();
    private int historyIndex;
    private long revisionCounter;
    private final List<Long> revisionIds = new ArrayList<>();

    private @Nullable ChunkRect explicitExportRectangle;
    private int exportBorderChunks;
    private int[] worldSpawn = {0, 64, 0};
    private boolean dirty;

    public WorldDocument(String name, String targetProfileId, TemplateRegistry templates) {
        this.name = name;
        this.targetProfileId = targetProfileId;
        this.templates = templates;
        history.add(WorldSnapshot.empty());
        revisionIds.add(0L);
        historyIndex = 0;
    }

    public UUID id() {
        return id;
    }

    public String name() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
        dirty = true;
    }

    public String targetProfileId() {
        return targetProfileId;
    }

    public void setTargetProfileId(String targetProfileId) {
        this.targetProfileId = targetProfileId;
        dirty = true;
    }

    public WorldSnapshot snapshot() {
        return history.get(historyIndex);
    }

    public long revision() {
        return revisionIds.get(historyIndex);
    }

    public boolean isDirty() {
        return dirty;
    }

    public TemplateRegistry templates() {
        return templates;
    }

    public InstanceRegistry instanceRegistry() {
        return instanceRegistry;
    }

    public int[] worldSpawn() {
        return worldSpawn.clone();
    }

    public void setWorldSpawn(int x, int y, int z) {
        worldSpawn = new int[]{x, y, z};
        dirty = true;
    }

    public void setExportRectangle(@Nullable ChunkRect rectangle) {
        this.explicitExportRectangle = rectangle;
        dirty = true;
    }

    public void setExportBorderChunks(int chunks) {
        if (chunks < 0) throw new IllegalArgumentException("Export border must be non-negative");
        this.exportBorderChunks = chunks;
        dirty = true;
    }

    public int exportBorderChunks() {
        return exportBorderChunks;
    }

    /**
     * The authoritative rectangle that will be fully generated on export.
     *
     * <p>Defaults to the bounding rectangle of committed content plus the configured
     * border. When content is far apart the rectangle is large by definition; the caller
     * is expected to show the column count before committing rather than quietly writing
     * millions of columns.
     */
    public @Nullable ChunkRect exportRectangle() {
        if (explicitExportRectangle != null) return explicitExportRectangle;
        ChunkRect content = snapshot().contentChunkBounds();
        if (content == null) return null;
        return exportBorderChunks > 0 ? content.expand(exportBorderChunks) : content;
    }

    public boolean hasExplicitExportRectangle() {
        return explicitExportRectangle != null;
    }

    // ------------------------------------------------------------------
    // History
    // ------------------------------------------------------------------

    private long commit(WorldSnapshot next) {
        // Drop any redo branch.
        while (history.size() > historyIndex + 1) {
            history.remove(history.size() - 1);
            revisionIds.remove(revisionIds.size() - 1);
        }
        history.add(next);
        revisionIds.add(++revisionCounter);
        historyIndex = history.size() - 1;

        while (history.size() > MAX_HISTORY) {
            history.remove(0);
            revisionIds.remove(0);
            historyIndex--;
        }
        dirty = true;
        return revision();
    }

    public boolean canUndo() {
        return historyIndex > 0;
    }

    public boolean canRedo() {
        return historyIndex < history.size() - 1;
    }

    public long undo() {
        if (!canUndo()) throw new IllegalStateException("Nothing to undo");
        historyIndex--;
        dirty = true;
        return revision();
    }

    public long redo() {
        if (!canRedo()) throw new IllegalStateException("Nothing to redo");
        historyIndex++;
        dirty = true;
        return revision();
    }

    // ------------------------------------------------------------------
    // Content operations
    // ------------------------------------------------------------------

    /** Install imported columns as the document's materialized content. */
    public EditResult importColumns(Map<Long, ChunkerColumn> columns) {
        WorldSnapshot.Builder builder = snapshot().toBuilder();
        ChunkRect bounds = null;
        for (Map.Entry<Long, ChunkerColumn> entry : columns.entrySet()) {
            int cx = Checked.chunkKeyX(entry.getKey());
            int cz = Checked.chunkKeyZ(entry.getKey());
            builder.putColumn(cx, cz, entry.getValue());
            ChunkRect single = new ChunkRect(cx, cz, cx, cz);
            bounds = bounds == null ? single : bounds.union(single);
        }
        return EditResult.of(commit(builder.build()), bounds);
    }

    /**
     * Place arena instances computed by the layout planner.
     *
     * <p>The plan itself is produced by the native layout code; the worker independently
     * re-checks the invariants that make the plan meaningful, because a plan that violates
     * them would produce a world that does not match the exported JSON.
     */
    public EditResult placeInstances(List<PlacementRequest> placements, boolean replaceExisting, int minChunkY, int maxChunkY) {
        WorldSnapshot current = snapshot();
        List<String> notices = new ArrayList<>();

        // Validate before touching anything, so a rejected plan leaves no partial state.
        Set<Long> claimed = new HashSet<>();
        for (PlacementRequest placement : placements) {
            var template = templates.require(placement.templateId());
            if ((placement.minX() & 15) != 0 || (placement.minZ() & 15) != 0) {
                throw new IllegalArgumentException(
                        "Arena placement for " + placement.exportId() + " is not chunk aligned: "
                                + placement.minX() + "," + placement.minZ());
            }
            int minChunkYUsed = placement.minY() >> 4;
            int maxChunkYUsed = (placement.minY() + template.sizeY() - 1) >> 4;
            if (minChunkYUsed < minChunkY || maxChunkYUsed > maxChunkY) {
                throw new IllegalArgumentException(
                        "Arena " + placement.exportId() + " spans Y " + placement.minY() + ".."
                                + (placement.minY() + template.sizeY() - 1)
                                + ", outside the target profile's build range. Refusing to clip it.");
            }
            ChunkRect footprint = new ChunkRect(
                    placement.minX() >> 4,
                    placement.minZ() >> 4,
                    (placement.minX() + template.sizeX() - 1) >> 4,
                    (placement.minZ() + template.sizeZ() - 1) >> 4);
            for (int cx = footprint.minX(); cx <= footprint.maxX(); cx++) {
                for (int cz = footprint.minZ(); cz <= footprint.maxZ(); cz++) {
                    long key = Checked.chunkKey(cx, cz);
                    if (!claimed.add(key)) {
                        throw new IllegalArgumentException(
                                "Two arenas in this plan overlap at chunk " + cx + "," + cz
                                        + " (" + placement.exportId() + ").");
                    }
                    if (!replaceExisting && current.hasContent(cx, cz)) {
                        throw new IllegalArgumentException(
                                "Arena " + placement.exportId() + " would overlap existing content at chunk "
                                        + cx + "," + cz + ". Choose a different grid origin or enable replace.");
                    }
                }
            }
        }

        WorldSnapshot.Builder builder = current.toBuilder();
        ChunkRect bounds = null;
        for (PlacementRequest placement : placements) {
            var template = templates.require(placement.templateId());
            int ordinal = placement.ordinal() > 0
                    ? placement.ordinal()
                    : instanceRegistry.allocate(placement.slug());
            instanceRegistry.observe(placement.slug(), ordinal);
            String exportId = placement.exportId() != null
                    ? placement.exportId()
                    : InstanceRegistry.exportId(placement.slug(), ordinal);

            ArenaInstance instance = new ArenaInstance(
                    UUID.randomUUID(), template.id(), placement.slug(), ordinal, exportId,
                    placement.minX(), placement.minY(), placement.minZ(),
                    template.sizeX(), template.sizeY(), template.sizeZ(),
                    placement.gridRow(), placement.gridColumn(), false);
            builder.putInstance(instance);

            ChunkRect footprint = instance.chunkBounds();
            bounds = bounds == null ? footprint : bounds.union(footprint);

            // Replacing means the destination's own materialized columns inside the
            // footprint are superseded; leaving them would make composition order matter.
            if (replaceExisting) {
                for (int cx = footprint.minX(); cx <= footprint.maxX(); cx++) {
                    for (int cz = footprint.minZ(); cz <= footprint.maxZ(); cz++) {
                        if (builder.column(Checked.chunkKey(cx, cz)) != null) {
                            builder.removeColumn(cx, cz);
                            notices.add("Replaced existing content at chunk " + cx + "," + cz);
                        }
                    }
                }
            }
        }
        return new EditResult(commit(builder.build()), bounds, notices, List.of());
    }

    /** A single placement produced by the layout planner. */
    public record PlacementRequest(UUID templateId,
                                   String slug,
                                   @Nullable String exportId,
                                   int ordinal,
                                   int minX,
                                   int minY,
                                   int minZ,
                                   int gridRow,
                                   int gridColumn) {
    }

    /**
     * Capture a selection into an application clipboard entry.
     *
     * <p>Copy takes an immutable snapshot; later edits to the source do not reach into it.
     * Cut is captured the same way but only clears the source when the paste commits, so a
     * cancelled cut leaves the source untouched.
     */
    public ClipboardContent capture(ChunkSelection selection,
                                    boolean pendingCut,
                                    @Nullable BlockEntityResolver<?, CompoundTag> resolver) {
        if (selection.isEmpty()) throw new IllegalArgumentException("Nothing selected");
        ChunkRect bounds = selection.bounds();
        WorldSnapshot current = snapshot();

        Map<Long, ChunkerColumn> columns = new LinkedHashMap<>();
        for (Long key : selection.keys()) {
            int cx = Checked.chunkKeyX(key);
            int cz = Checked.chunkKeyZ(key);
            ChunkerColumn column = current.materializedColumn(cx, cz);
            if (column == null) continue;
            ChunkerColumn copy = ColumnOps.relocate(column, -bounds.minX(), -bounds.minZ(), 0, true, resolver);
            columns.put(Checked.chunkKey(cx - bounds.minX(), cz - bounds.minZ()), copy);
        }

        List<ClipboardContent.RelativeInstance> instances = new ArrayList<>();
        for (ArenaInstance instance : current.instances()) {
            if (!selection.containsAny(instance.chunkBounds())) continue;
            if (!selection.containsAll(instance.chunkBounds())) {
                // Partial arena capture is a deliberate, separate action; silently taking
                // half an arena would produce an instance that is not a template copy.
                throw new IllegalArgumentException(
                        "Selection covers only part of arena " + instance.exportId()
                                + ". Expand the selection to its bounds, or bake it first if you intend a partial edit.");
            }
            instances.add(new ClipboardContent.RelativeInstance(
                    instance.templateId(), instance.templateSlug(),
                    instance.minX() - (bounds.minX() << 4), instance.minY(), instance.minZ() - (bounds.minZ() << 4),
                    instance.sizeX(), instance.sizeY(), instance.sizeZ(),
                    instance.needsRevalidation()));
        }

        return new ClipboardContent(UUID.randomUUID(), id, new ChunkRect(0, 0,
                bounds.widthChunks() - 1, bounds.lengthChunks() - 1), columns, instances, pendingCut);
    }

    /** Paste clipboard content so its minimum corner lands at the given chunk position. */
    public EditResult paste(ClipboardContent clipboard, int destChunkX, int destChunkZ, boolean replaceExisting) {
        WorldSnapshot current = snapshot();
        ChunkRect destination = clipboard.relativeBounds().translated(destChunkX, destChunkZ);

        if (!replaceExisting) {
            for (int cx = destination.minX(); cx <= destination.maxX(); cx++) {
                for (int cz = destination.minZ(); cz <= destination.maxZ(); cz++) {
                    if (current.hasContent(cx, cz)) {
                        throw new IllegalArgumentException(
                                "Paste would overwrite existing content at chunk " + cx + "," + cz);
                    }
                }
            }
        }

        WorldSnapshot.Builder builder = current.toBuilder();
        List<String> invalidated = new ArrayList<>();

        // An arena that the paste lands on top of cannot keep claiming to be intact.
        for (ArenaInstance instance : current.instances()) {
            if (destination.intersects(instance.chunkBounds())) {
                if (destination.contains(instance.chunkBounds().minX(), instance.chunkBounds().minZ())
                        && destination.contains(instance.chunkBounds().maxX(), instance.chunkBounds().maxZ())) {
                    builder.removeInstance(instance.id());
                } else {
                    builder.putInstance(instance.withRevalidationNeeded());
                    invalidated.add(instance.exportId());
                }
            }
        }

        for (Map.Entry<Long, ChunkerColumn> entry : clipboard.columns().entrySet()) {
            int rx = Checked.chunkKeyX(entry.getKey());
            int rz = Checked.chunkKeyZ(entry.getKey());
            ChunkerColumn placed = ColumnOps.relocate(entry.getValue(), destChunkX + rx, destChunkZ + rz, 0, true, null);
            builder.putColumn(destChunkX + rx, destChunkZ + rz, placed);
        }

        for (ClipboardContent.RelativeInstance relative : clipboard.instances()) {
            // A paste creates a new physical arena, so it gets a new identity and a new
            // export name even though it is the same template.
            int ordinal = instanceRegistry.allocate(relative.templateSlug());
            ArenaInstance instance = new ArenaInstance(
                    UUID.randomUUID(), relative.templateId(), relative.templateSlug(), ordinal,
                    InstanceRegistry.exportId(relative.templateSlug(), ordinal),
                    (destChunkX << 4) + relative.relativeMinX(), relative.minY(),
                    (destChunkZ << 4) + relative.relativeMinZ(),
                    relative.sizeX(), relative.sizeY(), relative.sizeZ(),
                    -1, -1, relative.needsRevalidation());
            builder.putInstance(instance);
        }

        return new EditResult(commit(builder.build()), destination, List.of(), invalidated);
    }

    /**
     * Translate a selection on the chunk lattice.
     *
     * <p>Reads come from the pre-operation snapshot so an overlapping move cannot
     * overwrite its own source, and the vacated area is cleared rather than left holding a
     * duplicate of the moved content.
     */
    public EditResult translateSelection(ChunkSelection selection, int dChunkX, int dChunkZ,
                                         boolean replaceExisting,
                                         @Nullable BlockEntityResolver<?, CompoundTag> resolver) {
        if (selection.isEmpty()) throw new IllegalArgumentException("Nothing selected");
        if (dChunkX == 0 && dChunkZ == 0) {
            return EditResult.of(revision(), null);
        }
        WorldSnapshot current = snapshot();
        Set<Long> sourceKeys = selection.keys();
        ChunkRect sourceBounds = selection.bounds();
        ChunkRect destinationBounds = sourceBounds.translated(dChunkX, dChunkZ);

        if (!replaceExisting) {
            for (Long key : sourceKeys) {
                int cx = Checked.chunkKeyX(key) + dChunkX;
                int cz = Checked.chunkKeyZ(key) + dChunkZ;
                if (sourceKeys.contains(Checked.chunkKey(cx - dChunkX, cz - dChunkZ)) && selection.contains(cx, cz)) {
                    continue; // Moving onto our own source is fine.
                }
                if (current.hasContent(cx, cz) && !selection.contains(cx, cz)) {
                    throw new IllegalArgumentException(
                            "Move would overwrite existing content at chunk " + cx + "," + cz);
                }
            }
        }

        WorldSnapshot.Builder builder = current.toBuilder();
        List<String> invalidated = new ArrayList<>();

        // Instances first: a fully selected arena moves as a unit, keeping its name,
        // markers and grid record. A partially selected one cannot.
        for (ArenaInstance instance : current.instances()) {
            ChunkRect footprint = instance.chunkBounds();
            if (selection.containsAll(footprint)) {
                builder.removeInstance(instance.id());
                builder.putInstance(instance.translated(dChunkX << 4, 0, dChunkZ << 4));
            } else if (selection.containsAny(footprint)) {
                throw new IllegalArgumentException(
                        "Selection covers only part of arena " + instance.exportId()
                                + ". Moving part of an arena would leave its recorded spawns pointing at nothing; "
                                + "expand the selection to its bounds first.");
            } else if (destinationBounds.intersects(footprint)) {
                builder.putInstance(instance.withRevalidationNeeded());
                invalidated.add(instance.exportId());
            }
        }

        // Then columns: read every source from the pre-operation snapshot.
        Map<Long, ChunkerColumn> moved = new LinkedHashMap<>();
        for (Long key : sourceKeys) {
            int cx = Checked.chunkKeyX(key);
            int cz = Checked.chunkKeyZ(key);
            ChunkerColumn column = current.materializedColumn(cx, cz);
            if (column == null) continue;
            moved.put(Checked.chunkKey(cx + dChunkX, cz + dChunkZ),
                    ColumnOps.relocate(column, dChunkX, dChunkZ, 0, false, resolver));
        }
        // Vacate the source, then write the destination, so an overlap keeps the moved copy.
        for (Long key : sourceKeys) {
            builder.removeColumn(key);
        }
        for (Map.Entry<Long, ChunkerColumn> entry : moved.entrySet()) {
            builder.putColumn(Checked.chunkKeyX(entry.getKey()), Checked.chunkKeyZ(entry.getKey()), entry.getValue());
        }

        return new EditResult(commit(builder.build()), sourceBounds.union(destinationBounds), List.of(), invalidated);
    }

    /** Clear a selection back to absent columns. */
    public EditResult clearSelection(ChunkSelection selection) {
        if (selection.isEmpty()) throw new IllegalArgumentException("Nothing selected");
        WorldSnapshot current = snapshot();
        WorldSnapshot.Builder builder = current.toBuilder();
        List<String> invalidated = new ArrayList<>();

        for (ArenaInstance instance : current.instances()) {
            ChunkRect footprint = instance.chunkBounds();
            if (selection.containsAll(footprint)) {
                builder.removeInstance(instance.id());
            } else if (selection.containsAny(footprint)) {
                builder.putInstance(instance.withRevalidationNeeded());
                invalidated.add(instance.exportId());
            }
        }
        for (Long key : selection.keys()) {
            builder.removeColumn(key);
        }
        return new EditResult(commit(builder.build()), selection.bounds(), List.of(), invalidated);
    }

    /** Commit an externally built snapshot, for operations composed by the caller. */
    public long commitSnapshot(WorldSnapshot snapshot) {
        return commit(snapshot);
    }

    public void markSaved() {
        dirty = false;
    }
}
