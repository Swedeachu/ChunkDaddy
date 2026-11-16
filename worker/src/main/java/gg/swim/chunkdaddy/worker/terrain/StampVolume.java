package gg.swim.chunkdaddy.worker.terrain;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.hivemc.chunker.conversion.intermediate.column.ChunkerColumn;
import com.hivemc.chunker.conversion.intermediate.column.chunk.identifier.ChunkerBlockIdentifier;
import com.hivemc.chunker.conversion.intermediate.column.chunk.identifier.type.block.ChunkerVanillaBlockType;
import com.hivemc.chunker.conversion.intermediate.column.chunk.identifier.type.block.states.BlockState;
import com.hivemc.chunker.conversion.intermediate.column.chunk.identifier.type.block.states.BlockStateValue;
import com.hivemc.chunker.conversion.intermediate.column.chunk.ChunkCoordPair;
import com.hivemc.chunker.conversion.intermediate.world.Dimension;
import gg.swim.chunkdaddy.worker.document.ColumnOps;
import gg.swim.chunkdaddy.worker.document.WorldDocument;
import gg.swim.chunkdaddy.worker.protocol.Json;
import gg.swim.chunkdaddy.worker.util.Checked;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.zip.GZIPInputStream;

/**
 * The inverse of {@code terrain_volume}: writes a semantic volume (volume.json + blocks.u16.gz,
 * z,y,x order, palette of Chunker vanilla enum names with optional states) into a document as one
 * undoable revision. Palette index 0 is "leave alone" unless {@code writeAir} is set, so a
 * sculpted volume can be layered over existing terrain. This is how generated maps enter a world.
 */
public final class StampVolume {
    private StampVolume() { }

    public static JsonObject apply(WorldDocument document, JsonObject request) throws Exception {
        Path directory = Path.of(Json.string(request, "directory"));
        JsonObject meta = JsonParser.parseString(Files.readString(directory.resolve("volume.json"))).getAsJsonObject();
        if (!"z,y,x".equals(Json.string(meta, "order", "z,y,x")))
            throw new IllegalArgumentException("Volume order must be z,y,x");
        int minX = Json.integer(meta, "minX", 0) + Json.integer(request, "offsetX", 0);
        int minY = Json.integer(meta, "minY", 0) + Json.integer(request, "offsetY", 0);
        int minZ = Json.integer(meta, "minZ", 0) + Json.integer(request, "offsetZ", 0);
        int width = Json.integer(meta, "width", 0), height = Json.integer(meta, "height", 0), depth = Json.integer(meta, "depth", 0);
        long size = (long) width * height * depth;
        if (width < 1 || height < 1 || depth < 1 || size > 256L * 1024 * 1024)
            throw new IllegalArgumentException("Volume dimensions out of range");
        if (minY < -64 || minY + height > 320)
            throw new IllegalArgumentException("Volume Y range " + minY + ".." + (minY + height - 1) + " leaves the Overworld build range");
        boolean writeAir = Json.bool(request, "writeAir", false);

        var paletteJson = meta.getAsJsonArray("palette");
        ChunkerBlockIdentifier[] palette = new ChunkerBlockIdentifier[paletteJson.size()];
        for (int i = 0; i < palette.length; i++) palette[i] = parse(paletteJson.get(i).getAsJsonObject());

        var source = document.snapshot();
        var builder = source.toBuilder();
        Map<Long, ChunkerColumn> touched = new LinkedHashMap<>();
        long written = 0;
        String blocksName = Json.string(meta, "blocksFile", "blocks.u16.gz");
        try (var in = new DataInputStream(new BufferedInputStream(new GZIPInputStream(Files.newInputStream(directory.resolve(blocksName))), 1 << 16))) {
            for (int z = 0; z < depth; z++) for (int y = 0; y < height; y++) for (int x = 0; x < width; x++) {
                int id = in.readUnsignedShort();
                if (id == 0 && !writeAir) continue;
                if (id >= palette.length) throw new IllegalArgumentException("Palette index " + id + " out of range");
                int wx = minX + x, wy = minY + y, wz = minZ + z;
                long key = Checked.chunkKey(wx >> 4, wz >> 4);
                ChunkerColumn column = touched.get(key);
                if (column == null) {
                    ChunkerColumn existing = source.materializedColumn(wx >> 4, wz >> 4);
                    column = existing == null
                            ? ColumnOps.voidColumn(new ChunkCoordPair(wx >> 4, wz >> 4), Dimension.OVERWORLD.getFallbackBiome())
                            : ColumnOps.relocate(existing, 0, 0, 0, true, null);
                    touched.put(key, column);
                }
                ColumnOps.writeableChunk(column, (byte) (wy >> 4)).set(wx & 15, wy & 15, wz & 15, palette[id]);
                written++;
            }
        }
        for (var column : touched.values()) {
            column.setHeightMap(null); column.setLightPopulated(false);
            builder.putColumn(column.getPosition().chunkX(), column.getPosition().chunkZ(), column);
        }
        JsonObject result = new JsonObject();
        result.addProperty("blocksWritten", written);
        result.addProperty("columns", touched.size());
        result.addProperty("revision", touched.isEmpty() ? document.revision() : document.commitSnapshot(builder.build()));
        return result;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    static ChunkerBlockIdentifier parse(JsonObject entry) {
        String typeName = Json.string(entry, "type").toUpperCase(Locale.ROOT).replace("MINECRAFT:", "");
        ChunkerVanillaBlockType type = ChunkerVanillaBlockType.valueOf(typeName);
        if (!entry.has("states") || entry.getAsJsonObject("states").isEmpty()) return new ChunkerBlockIdentifier(type);
        Map<BlockState<?>, BlockStateValue> states = new HashMap<>();
        for (var e : entry.getAsJsonObject("states").entrySet()) {
            BlockState<?> state = type.getStates().stream().filter(s -> s.getName().equals(e.getKey())).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException(typeName + " has no state " + e.getKey()));
            String wanted = e.getValue().getAsString();
            BlockStateValue value = null;
            for (BlockStateValue candidate : state.getValues())
                if (candidate.toString().equalsIgnoreCase(wanted) || candidate.toString().equalsIgnoreCase("_" + wanted)) value = candidate;
            if (value == null) throw new IllegalArgumentException(typeName + "." + e.getKey() + " has no value " + wanted);
            states.put(state, value);
        }
        return new ChunkerBlockIdentifier(type, states);
    }
}
