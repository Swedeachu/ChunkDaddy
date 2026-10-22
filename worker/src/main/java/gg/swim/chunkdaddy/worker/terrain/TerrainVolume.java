package gg.swim.chunkdaddy.worker.terrain;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.hivemc.chunker.conversion.intermediate.column.chunk.identifier.ChunkerBlockIdentifier;
import gg.swim.chunkdaddy.worker.document.RegionSurvey;
import gg.swim.chunkdaddy.worker.document.WorldDocument;
import gg.swim.chunkdaddy.worker.protocol.Json;
import gg.swim.chunkdaddy.worker.util.ChunkRect;

import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.GZIPOutputStream;

/** Lossless bounded semantic block volume for reproducible terrain analysis, not a second world importer. */
public final class TerrainVolume {
    public final int minX, minY, minZ, width, height, depth;
    public final short[] blocks;
    public final List<ChunkerBlockIdentifier> palette = new ArrayList<>();
    public final JsonArray paletteJson = new JsonArray();
    public final String fingerprint;

    private TerrainVolume(WorldDocument document, JsonObject request) throws Exception {
        var snapshot = document.snapshot();
        if (!snapshot.instances().isEmpty()) throw new IllegalArgumentException("Materialize instances before terrain editing");
        var survey = RegionSurvey.inspect(snapshot);
        ChunkRect bounds = Json.chunkRect(survey, "bounds");
        minX = Json.integer(request, "minX", bounds.minX() * 16);
        minZ = Json.integer(request, "minZ", bounds.minZ() * 16);
        minY = Json.integer(request, "minY", -64);
        width = Json.integer(request, "width", bounds.widthChunks() * 16);
        depth = Json.integer(request, "depth", bounds.lengthChunks() * 16);
        height = Json.integer(request, "height", 384);
        long size = (long) width * height * depth;
        if (width < 1 || depth < 1 || height < 1 || size > 128L * 1024 * 1024
                || minY < -64 || minY + height > 320) throw new IllegalArgumentException("Terrain volume bounds exceed limits");
        blocks = new short[(int) size];
        Map<ChunkerBlockIdentifier, Integer> ids = new HashMap<>();
        ids.put(ChunkerBlockIdentifier.AIR, 0);
        palette.add(ChunkerBlockIdentifier.AIR);
        for (int z = 0; z < depth; z++) for (int x = 0; x < width; x++) {
            int wx = minX + x, wz = minZ + z;
            var column = snapshot.materializedColumn(wx >> 4, wz >> 4);
            if (column == null) continue;
            for (var chunk : column.getChunks().values()) {
                int start = Math.max(minY, chunk.getY() * 16);
                int end = Math.min(minY + height, chunk.getY() * 16 + 16);
                for (int wy = start; wy < end; wy++) {
                    var block = chunk.getPalette().get(wx & 15, wy & 15, wz & 15, ChunkerBlockIdentifier.AIR);
                    if (block == null || block.isAir()) continue;
                    Integer id = ids.get(block);
                    if (id == null) {
                        id = palette.size();
                        if (id > 65535) throw new IllegalArgumentException("Too many block states for terrain volume");
                        ids.put(block, id); palette.add(block);
                    }
                    blocks[index(x, wy - minY, z)] = (short) (int) id;
                }
            }
        }
        var digest = MessageDigest.getInstance("SHA-256");
        for (var block : palette) {
            JsonObject entry = new JsonObject();
            entry.addProperty("type", block.getType().toString());
            TreeMap<String, String> states = new TreeMap<>();
            block.getPresentStates().forEach((key, value) -> states.put(key.getName(), value.toString()));
            entry.add("states", new com.google.gson.Gson().toJsonTree(states));
            entry.addProperty("rgb", block.hasRGBColor() ? block.getRGBColor() : 0x909090);
            paletteJson.add(entry);
            digest.update(entry.toString().getBytes(StandardCharsets.UTF_8));
        }
        for (short block : blocks) { digest.update((byte) (block >>> 8)); digest.update((byte) block); }
        fingerprint = HexFormat.of().formatHex(digest.digest());
    }

    public int index(int x, int y, int z) { return (z * height + y) * width + x; }

    public int indexWorld(int x, int y, int z) {
        if (x < minX || x >= minX + width || y < minY || y >= minY + height || z < minZ || z >= minZ + depth) return -1;
        return index(x - minX, y - minY, z - minZ);
    }

    public JsonObject metadata() {
        JsonObject result = new JsonObject();
        result.addProperty("schemaVersion", 1);
        result.addProperty("minX", minX); result.addProperty("minY", minY); result.addProperty("minZ", minZ);
        result.addProperty("width", width); result.addProperty("height", height); result.addProperty("depth", depth);
        result.addProperty("order", "z,y,x"); result.addProperty("encoding", "gzip-big-endian-uint16");
        result.addProperty("fingerprint", fingerprint); result.add("palette", paletteJson);
        return result;
    }

    public static TerrainVolume read(WorldDocument document, JsonObject request) throws Exception { return new TerrainVolume(document, request); }

    public static JsonObject dump(WorldDocument document, JsonObject request) throws Exception {
        TerrainVolume volume = read(document, request);
        Path directory = Path.of(Json.string(request, "directory"));
        Files.createDirectories(directory);
        Path binary = directory.resolve("blocks.u16.gz"), metadata = directory.resolve("volume.json");
        try (var out = new DataOutputStream(new BufferedOutputStream(new GZIPOutputStream(Files.newOutputStream(binary))))) {
            for (short block : volume.blocks) out.writeShort(block & 65535);
        }
        JsonObject result = volume.metadata();
        result.addProperty("documentRevision", document.revision());
        result.addProperty("blocksPath", binary.toAbsolutePath().toString());
        JsonArray entities = new JsonArray();
        for (long key : document.snapshot().materializedKeys()) {
            var column = document.snapshot().materializedColumn(gg.swim.chunkdaddy.worker.util.Checked.chunkKeyX(key), gg.swim.chunkdaddy.worker.util.Checked.chunkKeyZ(key));
            for (var entity : column.getBlockEntities()) {
                JsonObject point = new JsonObject();
                point.addProperty("x", entity.getX()); point.addProperty("y", entity.getY()); point.addProperty("z", entity.getZ());
                point.addProperty("type", entity.getClass().getSimpleName()); entities.add(point);
            }
        }
        result.add("blockEntities", entities);
        Files.writeString(metadata, new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(result));
        JsonObject reply = new JsonObject();
        reply.addProperty("metadataPath", metadata.toAbsolutePath().toString()); reply.addProperty("fingerprint", volume.fingerprint);
        reply.addProperty("voxels", volume.blocks.length); reply.addProperty("paletteStates", volume.palette.size());
        return reply;
    }
}
