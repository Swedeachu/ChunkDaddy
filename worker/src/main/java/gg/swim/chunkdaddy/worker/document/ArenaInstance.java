package gg.swim.chunkdaddy.worker.document;

import gg.swim.chunkdaddy.worker.util.ChunkRect;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/**
 * One physical copy of a template placed in a world.
 *
 * <p>Identity rules from the design guide are enforced here and by
 * {@link InstanceRegistry}: the ordinal is allocated once and never reused, copying an
 * instance allocates a new name, and moving one preserves its name.
 */
public final class ArenaInstance {
    private final UUID id;
    private final UUID templateId;
    private final String templateSlug;
    private final int ordinal;
    private final String exportId;
    /** B: destination minimum corner in block coordinates. */
    private final int minX;
    private final int minY;
    private final int minZ;
    private final int sizeX;
    private final int sizeY;
    private final int sizeZ;
    private final int gridRow;
    private final int gridColumn;
    /**
     * Set when a destructive edit has touched part of this instance. Such an instance can
     * no longer claim to be a complete template copy and blocks production export until
     * the user resolves it.
     */
    private final boolean needsRevalidation;

    public ArenaInstance(UUID id, UUID templateId, String templateSlug, int ordinal, String exportId,
                         int minX, int minY, int minZ, int sizeX, int sizeY, int sizeZ,
                         int gridRow, int gridColumn, boolean needsRevalidation) {
        this.id = id;
        this.templateId = templateId;
        this.templateSlug = templateSlug;
        this.ordinal = ordinal;
        this.exportId = exportId;
        this.minX = minX;
        this.minY = minY;
        this.minZ = minZ;
        this.sizeX = sizeX;
        this.sizeY = sizeY;
        this.sizeZ = sizeZ;
        this.gridRow = gridRow;
        this.gridColumn = gridColumn;
        this.needsRevalidation = needsRevalidation;
    }

    public UUID id() {
        return id;
    }

    public UUID templateId() {
        return templateId;
    }

    public String templateSlug() {
        return templateSlug;
    }

    public int ordinal() {
        return ordinal;
    }

    /** Stable, human-readable export key such as {@code desert-7}. */
    public String exportId() {
        return exportId;
    }

    public int minX() {
        return minX;
    }

    public int minY() {
        return minY;
    }

    public int minZ() {
        return minZ;
    }

    public int sizeX() {
        return sizeX;
    }

    public int sizeY() {
        return sizeY;
    }

    public int sizeZ() {
        return sizeZ;
    }

    public int gridRow() {
        return gridRow;
    }

    public int gridColumn() {
        return gridColumn;
    }

    public boolean needsRevalidation() {
        return needsRevalidation;
    }

    /** Half-open upper bound on X; derived from min + size rather than scattered +1s. */
    public int maxXExclusive() {
        return minX + sizeX;
    }

    public int maxYExclusive() {
        return minY + sizeY;
    }

    public int maxZExclusive() {
        return minZ + sizeZ;
    }

    /** Inclusive chunk footprint occupied by this instance. */
    public ChunkRect chunkBounds() {
        return new ChunkRect(minX >> 4, minZ >> 4, (maxXExclusive() - 1) >> 4, (maxZExclusive() - 1) >> 4);
    }

    public boolean containsBlock(int x, int y, int z) {
        return x >= minX && x < maxXExclusive()
                && y >= minY && y < maxYExclusive()
                && z >= minZ && z < maxZExclusive();
    }

    /** Move keeps identity, including the export name. */
    public ArenaInstance translated(int dx, int dy, int dz) {
        return new ArenaInstance(id, templateId, templateSlug, ordinal, exportId,
                minX + dx, minY + dy, minZ + dz, sizeX, sizeY, sizeZ,
                gridRow, gridColumn, needsRevalidation);
    }

    public ArenaInstance withRevalidationNeeded() {
        if (needsRevalidation) return this;
        return new ArenaInstance(id, templateId, templateSlug, ordinal, exportId,
                minX, minY, minZ, sizeX, sizeY, sizeZ, gridRow, gridColumn, true);
    }

    /** Transform a template-local position into world space: {@code P = B + L}. */
    public double[] toWorld(@Nullable double[] local) {
        if (local == null) return null;
        return new double[]{minX + local[0], minY + local[1], minZ + local[2]};
    }
}
