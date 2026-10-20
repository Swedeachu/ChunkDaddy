package gg.swim.chunkdaddy.worker.document;

import com.google.gson.JsonObject;
import com.hivemc.chunker.conversion.intermediate.column.ChunkerColumn;
import com.hivemc.chunker.conversion.intermediate.column.chunk.identifier.ChunkerBlockIdentifier;
import gg.swim.chunkdaddy.worker.protocol.Json;
import gg.swim.chunkdaddy.worker.util.Checked;
import gg.swim.chunkdaddy.worker.util.ChunkRect;

/** Geometry bounds and a spectator viewpoint without mistaking generated empty columns for a map. */
public final class RegionSurvey {
    private RegionSurvey() {}

    public static JsonObject inspect(WorldSnapshot snapshot) {
        ChunkRect bounds = null;
        int contentColumns = 0, highestY = Integer.MIN_VALUE;
        for (long key : snapshot.materializedKeys()) {
            int x = Checked.chunkKeyX(key), z = Checked.chunkKeyZ(key);
            var column = snapshot.materializedColumn(x, z);
            boolean content = !column.getBlockEntities().isEmpty() || !column.getEntities().isEmpty();
            for (var chunk : column.getChunks().values()) {
                if (chunk.getPalette().containsValue(block -> !block.isAir())) {
                    content = true;
                    highestY = Math.max(highestY, chunk.getY() * 16 + 15);
                }
            }
            if (!content) continue;
            contentColumns++;
            ChunkRect single = new ChunkRect(x, z, x, z);
            bounds = bounds == null ? single : bounds.union(single);
        }
        if (bounds == null) throw new IllegalArgumentException("World has no map blocks or block entities");
        int centerX = Math.toIntExact(((long) bounds.minX() + bounds.maxX() + 1) * 8);
        int centerZ = Math.toIntExact(((long) bounds.minZ() + bounds.maxZ() + 1) * 8);
        // Sample roofs around the exact horizontal center so a spectator arrives above the build.
        int roof = Integer.MIN_VALUE;
        for (int dx = -16; dx <= 16; dx += 4) for (int dz = -16; dz <= 16; dz += 4) {
            int x = centerX + dx, z = centerZ + dz;
            roof = Math.max(roof, highest(snapshot.materializedColumn(x >> 4, z >> 4), x & 15, z & 15));
        }
        if (roof == Integer.MIN_VALUE) roof = highestY;
        if (roof == Integer.MIN_VALUE) roof = 64;
        JsonObject center = new JsonObject();
        center.addProperty("x", centerX); center.addProperty("y", roof + 10); center.addProperty("z", centerZ);
        JsonObject result = new JsonObject();
        result.add("bounds", Json.of(bounds));
        result.add("center", center);
        result.addProperty("contentColumns", contentColumns);
        result.addProperty("importedColumns", snapshot.materializedCount());
        result.addProperty("highestSectionBlockY", highestY);
        return result;
    }

    private static int highest(ChunkerColumn column, int x, int z) {
        if (column == null || column.getChunks().isEmpty()) return Integer.MIN_VALUE;
        for (int cy = column.getChunks().lastByteKey(); cy >= column.getChunks().firstByteKey(); cy--) {
            var chunk = column.getChunks().get((byte) cy);
            if (chunk == null) continue;
            for (int y = 15; y >= 0; y--) if (!chunk.getPalette().get(x, y, z, ChunkerBlockIdentifier.AIR).isAir()) return cy * 16 + y;
        }
        return Integer.MIN_VALUE;
    }
}
