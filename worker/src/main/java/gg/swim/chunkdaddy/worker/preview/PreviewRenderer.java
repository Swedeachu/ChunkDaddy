package gg.swim.chunkdaddy.worker.preview;

import com.hivemc.chunker.conversion.intermediate.column.ChunkerColumn;
import com.hivemc.chunker.conversion.intermediate.column.chunk.ChunkerChunk;
import com.hivemc.chunker.conversion.intermediate.column.chunk.identifier.ChunkerBlockIdentifier;
import gg.swim.chunkdaddy.worker.conversion.ArenaTemplate;
import gg.swim.chunkdaddy.worker.document.ArenaInstance;
import gg.swim.chunkdaddy.worker.document.TemplateRegistry;
import gg.swim.chunkdaddy.worker.document.WorldSnapshot;
import gg.swim.chunkdaddy.worker.util.ChunkRect;

import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Renders top-down preview tiles for the viewport.
 *
 * <p>Preview samples are derived from the same semantic data that will be exported, so
 * what the user selects is what gets written. Three column states are distinguished and
 * encoded separately, because a black square alone cannot tell the user whether a gap will
 * be serialized as generated void or left absent.
 *
 * <p>Tiles are written to a binary file rather than sent through the protocol: pushing
 * millions of pixels through JSON would be the wrong shape entirely.
 */
public final class PreviewRenderer {
    /** File magic and version, checked by the native tile loader. */
    public static final int MAGIC = 0x43444154; // "CDAT"
    public static final int FORMAT_VERSION = 1;

    public static final byte STATE_ABSENT = 0;
    public static final byte STATE_GENERATED_VOID = 1;
    public static final byte STATE_CONTENT = 2;

    /** How the visible block for a column is chosen. */
    public enum HeightMode {
        /** Highest non-air block at or below the slice height. */
        HIGHEST_SURFACE,
        /** The block exactly at the slice height, so roofs do not hide the floor. */
        SLICE
    }

    private final TemplateRegistry templates;

    public PreviewRenderer(TemplateRegistry templates) {
        this.templates = templates;
    }

    /**
     * Render a rectangle of chunk columns into a tile file.
     *
     * @param sliceY      the height slice, in blocks.
     * @param exportRect  the export rectangle, used to distinguish generated void from
     *                    absent; may be null when no rectangle has been established.
     */
    public void render(WorldSnapshot snapshot,
                       ChunkRect area,
                       int sliceY,
                       HeightMode mode,
                       ChunkRect exportRect,
                       Path output) throws IOException {
        Files.createDirectories(output.getParent());
        try (OutputStream raw = Files.newOutputStream(output);
             DataOutputStream out = new DataOutputStream(new BufferedOutputStream(raw, 1 << 16))) {
            out.writeInt(MAGIC);
            out.writeInt(FORMAT_VERSION);
            out.writeInt(area.minX());
            out.writeInt(area.minZ());
            out.writeInt(area.widthChunks());
            out.writeInt(area.lengthChunks());

            int[] pixels = new int[256];
            for (int chunkX = area.minX(); chunkX <= area.maxX(); chunkX++) {
                for (int chunkZ = area.minZ(); chunkZ <= area.maxZ(); chunkZ++) {
                    byte state;
                    if (snapshot.hasContent(chunkX, chunkZ)) {
                        state = STATE_CONTENT;
                    } else if (exportRect != null && exportRect.contains(chunkX, chunkZ)) {
                        state = STATE_GENERATED_VOID;
                    } else {
                        state = STATE_ABSENT;
                    }
                    out.writeByte(state);
                    if (state != STATE_CONTENT) continue;

                    java.util.Arrays.fill(pixels, 0);
                    renderColumn(snapshot, chunkX, chunkZ, sliceY, mode, pixels);
                    for (int pixel : pixels) {
                        out.writeInt(pixel);
                    }
                }
            }
        }
    }

    private void renderColumn(WorldSnapshot snapshot, int chunkX, int chunkZ,
                              int sliceY, HeightMode mode, int[] pixels) {
        ChunkerColumn materialized = snapshot.materializedColumn(chunkX, chunkZ);
        var instances = snapshot.instancesAt(chunkX, chunkZ);

        for (int localX = 0; localX < 16; localX++) {
            for (int localZ = 0; localZ < 16; localZ++) {
                int worldX = (chunkX << 4) + localX;
                int worldZ = (chunkZ << 4) + localZ;
                int argb = 0;

                // Instances are drawn over materialized content, matching the exact region
                // replacement that composition performs.
                for (ArenaInstance instance : instances) {
                    if (worldX < instance.minX() || worldX >= instance.maxXExclusive()
                            || worldZ < instance.minZ() || worldZ >= instance.maxZExclusive()) {
                        continue;
                    }
                    ArenaTemplate template = templates.get(instance.templateId());
                    if (template == null) continue;
                    argb = sampleTemplate(template, instance, worldX, worldZ, sliceY, mode);
                    break;
                }
                if (argb == 0 && materialized != null) {
                    argb = sampleColumn(materialized, localX, localZ, sliceY, mode);
                }
                pixels[localZ * 16 + localX] = argb;
            }
        }
    }

    private static int sampleTemplate(ArenaTemplate template, ArenaInstance instance,
                                      int worldX, int worldZ, int sliceY, HeightMode mode) {
        int localX = worldX - instance.minX();
        int localZ = worldZ - instance.minZ();
        int topLocalY = Math.min(sliceY - instance.minY(), template.sizeY() - 1);
        if (topLocalY < 0) return 0;

        if (mode == HeightMode.SLICE) {
            return shade(template.blockAt(localX, topLocalY, localZ), instance.minY() + topLocalY);
        }
        for (int localY = topLocalY; localY >= 0; localY--) {
            ChunkerBlockIdentifier block = template.blockAt(localX, localY, localZ);
            if (!block.isAir()) {
                return shade(block, instance.minY() + localY);
            }
        }
        return 0;
    }

    private static int sampleColumn(ChunkerColumn column, int localX, int localZ, int sliceY, HeightMode mode) {
        if (mode == HeightMode.SLICE) {
            return shade(blockAt(column, localX, sliceY, localZ), sliceY);
        }
        var chunks = column.getChunks();
        if (chunks.isEmpty()) return 0;
        int topChunkY = Math.min(sliceY >> 4, chunks.lastByteKey());
        for (int chunkY = topChunkY; chunkY >= chunks.firstByteKey(); chunkY--) {
            ChunkerChunk chunk = chunks.get((byte) chunkY);
            if (chunk == null) continue;
            int startY = (chunkY == (sliceY >> 4)) ? (sliceY & 15) : 15;
            for (int y = startY; y >= 0; y--) {
                ChunkerBlockIdentifier block = chunk.getPalette()
                        .get(localX, y, localZ, ChunkerBlockIdentifier.AIR);
                if (block != null && !block.isAir()) {
                    return shade(block, (chunkY << 4) + y);
                }
            }
        }
        return 0;
    }

    private static ChunkerBlockIdentifier blockAt(ChunkerColumn column, int localX, int worldY, int localZ) {
        ChunkerChunk chunk = column.getChunks().get((byte) (worldY >> 4));
        if (chunk == null) return ChunkerBlockIdentifier.AIR;
        ChunkerBlockIdentifier block = chunk.getPalette().get(localX, worldY & 15, localZ, ChunkerBlockIdentifier.AIR);
        return block == null ? ChunkerBlockIdentifier.AIR : block;
    }

    /**
     * Apply elevation shading so relief is readable. Blocks Chunker has no color for fall
     * back to a neutral grey rather than disappearing, which would read as a hole.
     */
    private static int shade(ChunkerBlockIdentifier block, int worldY) {
        if (block == null || block.isAir()) return 0;
        int rgb = block.hasRGBColor() ? block.getRGBColor() : 0x9A9A9A;
        double factor = 0.65 + 0.35 * clamp((worldY + 64) / 384.0);
        int r = (int) Math.min(255, ((rgb >> 16) & 0xFF) * factor);
        int g = (int) Math.min(255, ((rgb >> 8) & 0xFF) * factor);
        int b = (int) Math.min(255, (rgb & 0xFF) * factor);
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    private static double clamp(double value) {
        return value < 0 ? 0 : Math.min(value, 1);
    }
}
