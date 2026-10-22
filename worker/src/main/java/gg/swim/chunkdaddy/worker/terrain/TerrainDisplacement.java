package gg.swim.chunkdaddy.worker.terrain;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.hivemc.chunker.conversion.encoding.base.resolver.blockentity.BlockEntityResolver;
import com.hivemc.chunker.conversion.intermediate.column.ChunkerColumn;
import com.hivemc.chunker.conversion.intermediate.column.chunk.identifier.ChunkerBlockIdentifier;
import com.hivemc.chunker.conversion.intermediate.column.entity.ItemFrameEntity;
import com.hivemc.chunker.nbt.tags.collection.CompoundTag;
import gg.swim.chunkdaddy.worker.document.ColumnOps;
import gg.swim.chunkdaddy.worker.document.WorldDocument;
import gg.swim.chunkdaddy.worker.protocol.Json;
import gg.swim.chunkdaddy.worker.util.Checked;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.GZIPInputStream;

/** Atomic, collision-checked per-voxel vertical translations; rigid groups are authored in the analysis plan. */
public final class TerrainDisplacement {
    private TerrainDisplacement() { }

    public static JsonObject apply(WorldDocument document, JsonObject request, BlockEntityResolver<?, CompoundTag> resolver) throws Exception {
        Path planPath = Path.of(Json.string(request, "plan"));
        JsonObject plan = JsonParser.parseString(Files.readString(planPath)).getAsJsonObject();
        TerrainVolume volume = TerrainVolume.read(document, plan);
        if (!volume.fingerprint.equals(Json.string(plan, "fingerprint"))) throw new IllegalArgumentException("Terrain plan does not match this source volume");
        Path fieldPath = planPath.toAbsolutePath().getParent().resolve(Json.string(plan, "displacements"));
        byte[] shifts;
        try (var input = new GZIPInputStream(Files.newInputStream(fieldPath))) {
            shifts = input.readNBytes(volume.blocks.length + 1);
        }
        if (shifts.length != volume.blocks.length) throw new IllegalArgumentException("Displacement field has wrong voxel count");
        String fieldHash = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(shifts));
        if (plan.has("displacementSha256") && !fieldHash.equals(Json.string(plan, "displacementSha256")))
            throw new IllegalArgumentException("Displacement field does not match this plan's checksum");
        int maximumShift = Json.integer(plan, "maximumShift", 16);
        if (maximumShift < 1 || maximumShift > 64) throw new IllegalArgumentException("maximumShift must be 1..64");
        boolean[] occupied = new boolean[volume.blocks.length];
        JsonArray conflicts = new JsonArray();
        int conflictCount = 0, moved = 0;
        for (int i = 0; i < shifts.length; i++) {
            int dy = shifts[i];
            if (dy == 0 || volume.blocks[i] == 0) continue;
            int y = i / volume.width % volume.height;
            if (Math.abs(dy) > maximumShift || y + dy < 0 || y + dy >= volume.height) throw new IllegalArgumentException("Displacement exceeds bounded volume at voxel " + i);
            int target = i + dy * volume.width;
            if (occupied[target] || volume.blocks[target] != 0 && shifts[target] == 0) {
                conflictCount++;
                if (conflicts.size() < 32) {
                    JsonObject conflict = new JsonObject();
                    conflict.addProperty("x", volume.minX + i % volume.width);
                    conflict.addProperty("y", volume.minY + y); conflict.addProperty("z", volume.minZ + i / (volume.width * volume.height));
                    conflict.addProperty("dy", dy); conflict.addProperty("targetY", volume.minY + y + dy);
                    conflict.addProperty("type", volume.palette.get(volume.blocks[i] & 65535).getType().toString()); conflicts.add(conflict);
                }
            }
            occupied[target] = true; moved++;
        }
        JsonObject result = new JsonObject();
        result.addProperty("valid", conflictCount == 0); result.addProperty("conflictCount", conflictCount);
        result.add("conflicts", conflicts); result.addProperty("movedBlocks", moved); result.addProperty("fingerprint", volume.fingerprint);
        result.addProperty("displacementSha256", fieldHash);
        if (Json.bool(request, "validateOnly", false)) return result;
        if (conflictCount != 0) throw new IllegalArgumentException("Terrain plan has " + conflictCount + " collisions: " + conflicts);
        if (moved == 0) {
            result.addProperty("revision", document.revision()); result.addProperty("changedColumns", 0);
            result.addProperty("movedPositionRecords", 0); return result;
        }
        var source = document.snapshot();
        var builder = source.toBuilder();
        Map<Long, ChunkerColumn> changed = new LinkedHashMap<>();
        for (int z = 0; z < volume.depth; z++) for (int x = 0; x < volume.width; x++) {
            int wx = volume.minX + x, wz = volume.minZ + z;
            ChunkerColumn column = null;
            for (int y = 0; y < volume.height; y++) {
                int i = volume.index(x, y, z);
                if (shifts[i] == 0 || volume.blocks[i] == 0) continue;
                if (column == null) {
                    long key = Checked.chunkKey(wx >> 4, wz >> 4);
                    column = changed.get(key);
                    if (column == null) {
                        column = ColumnOps.relocate(source.materializedColumn(wx >> 4, wz >> 4), 0, 0, 0, true, resolver);
                        changed.put(key, column);
                    }
                }
                int wy = volume.minY + y;
                ColumnOps.writeableChunk(column, (byte) (wy >> 4)).set(wx & 15, wy & 15, wz & 15, ChunkerBlockIdentifier.AIR);
            }
        }
        for (int z = 0; z < volume.depth; z++) for (int x = 0; x < volume.width; x++) {
            int wx = volume.minX + x, wz = volume.minZ + z;
            var column = changed.get(Checked.chunkKey(wx >> 4, wz >> 4));
            if (column == null) continue;
            for (int y = 0; y < volume.height; y++) {
                int i = volume.index(x, y, z);
                if (shifts[i] == 0 || volume.blocks[i] == 0) continue;
                int wy = volume.minY + y + shifts[i];
                ColumnOps.writeableChunk(column, (byte) (wy >> 4)).set(wx & 15, wy & 15, wz & 15, volume.palette.get(volume.blocks[i] & 65535));
            }
        }
        int movedRecords = 0;
        for (var column : changed.values()) {
            for (var entity : column.getBlockEntities()) {
                int i = volume.indexWorld(entity.getX(), entity.getY(), entity.getZ());
                int dy = i < 0 ? 0 : shifts[i];
                if (dy == 0) continue;
                entity.setY(entity.getY() + dy); movedRecords++;
                if (entity.getOriginalNBT() != null) {
                    var original = entity.getOriginalNBT().clone(); original.put("y", entity.getY()); entity.setOriginalNBT(original);
                }
            }
            for (var entity : column.getEntities()) {
                if (entity instanceof ItemFrameEntity frame) {
                    int i = volume.indexWorld(frame.getTileX(), frame.getTileY(), frame.getTileZ());
                    int dy = i < 0 ? 0 : shifts[i];
                    frame.setTileY(frame.getTileY() + dy); frame.setPositionY(frame.getPositionY() + dy);
                    if (dy != 0) movedRecords++;
                }
            }
            column.setHeightMap(null); column.setLightPopulated(false);
            builder.putColumn(column.getPosition().chunkX(), column.getPosition().chunkZ(), column);
        }
        result.addProperty("revision", document.commitSnapshot(builder.build()));
        result.addProperty("changedColumns", changed.size()); result.addProperty("movedPositionRecords", movedRecords);
        return result;
    }
}
