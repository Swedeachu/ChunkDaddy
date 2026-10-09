package gg.swim.chunkdaddy.worker.bedrock;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.hivemc.chunker.nbt.tags.Tag;
import com.hivemc.chunker.nbt.tags.collection.CompoundTag;
import gg.swim.chunkdaddy.worker.protocol.Json;
import gg.swim.chunkdaddy.worker.util.ChunkRect;
import org.iq80.leveldb.*;
import org.iq80.leveldb.impl.Iq80DBFactory;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Path;
import java.util.*;

/** Checks physical copies directly in an extracted output database, independently of composition. */
public final class RegionVerifier {
    private record Region(String id, String template, ChunkRect bounds) {}

    public static JsonObject verify(Path directory, JsonObject manifest) throws Exception {
        List<Region> regions = new ArrayList<>();
        Map<String, Region> first = new HashMap<>();
        Map<String, Long> counts = new LinkedHashMap<>();
        for (JsonElement item : Json.array(manifest, "instances")) {
            JsonObject instance = item.getAsJsonObject();
            JsonObject b = instance.getAsJsonObject("bounds");
            Region region = new Region(Json.string(instance, "id"), Json.string(instance, "template"),
                    new ChunkRect(Json.integer(b, "minChunkX"), Json.integer(b, "minChunkZ"),
                            Json.integer(b, "maxChunkX"), Json.integer(b, "maxChunkZ")));
            regions.add(region);
            first.putIfAbsent(region.template, region);
            counts.put(region.id, 0L);
        }
        long records = 0, blockEntities = 0;
        Options options = VoidCleaner.databaseOptions(false);
        try (DB db = Iq80DBFactory.factory.open(directory.resolve("db").toFile(), options);
             DBIterator iterator = db.iterator()) {
            for (iterator.seekToFirst(); iterator.hasNext();) {
                Map.Entry<byte[], byte[]> entry = iterator.next();
                byte[] key = entry.getKey();
                if (key.length != 9 && key.length != 10) continue;
                int tag = Byte.toUnsignedInt(key[8]);
                if (tag != 0x2F && tag != 0x2B && tag != 0x31) continue;
                ByteBuffer position = ByteBuffer.wrap(key).order(ByteOrder.LITTLE_ENDIAN);
                int x = position.getInt(), z = position.getInt();
                Region region = regions.stream().filter(r -> r.bounds.contains(x, z)).findFirst()
                        .orElseThrow(() -> new IllegalStateException("Terrain outside all region footprints: " + x + "," + z));
                Region reference = first.get(region.template);
                int rx = x - region.bounds.minX() + reference.bounds.minX();
                int rz = z - region.bounds.minZ() + reference.bounds.minZ();
                byte[] referenceKey = key.clone();
                ByteBuffer.wrap(referenceKey).order(ByteOrder.LITTLE_ENDIAN).putInt(rx).putInt(rz);
                byte[] expected = db.get(referenceKey);
                if (expected == null) throw new IllegalStateException("Missing corresponding terrain record: " + region.id);
                byte[] actual = entry.getValue();
                if (tag == 0x31) {
                    actual = normalizeEntities(actual, x, z, region);
                    expected = normalizeEntities(expected, rx, rz, reference);
                    if (actual.length > 0) blockEntities++;
                }
                if (!Arrays.equals(actual, expected))
                    throw new IllegalStateException("Translated region differs: " + region.id + " chunk " + x + "," + z + " tag " + tag);
                counts.compute(region.id, (id, count) -> count + 1);
                records++;
            }
        }
        for (Region region : regions) {
            if (counts.get(region.id) == 0 || !counts.get(region.id).equals(counts.get(first.get(region.template).id)))
                throw new IllegalStateException("Incomplete region " + region.id);
        }
        JsonObject result = new JsonObject();
        result.addProperty("verifiedInstances", regions.size());
        result.addProperty("comparedRecords", records);
        result.addProperty("blockEntityRecords", blockEntities);
        result.add("recordsPerInstance", new com.google.gson.Gson().toJsonTree(counts));
        return result;
    }

    private static byte[] normalizeEntities(byte[] value, int chunkX, int chunkZ, Region region) throws Exception {
        ByteBuffer input = ByteBuffer.wrap(value).order(ByteOrder.LITTLE_ENDIAN);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        while (input.hasRemaining()) {
            CompoundTag entity = Tag.readBedrockNBT(input);
            if (Math.floorDiv(entity.getInt("x"), 16) != chunkX || Math.floorDiv(entity.getInt("z"), 16) != chunkZ)
                throw new IllegalStateException("Block entity " + entity.getString("id") + " at "
                        + entity.getInt("x") + "," + entity.getInt("y") + "," + entity.getInt("z")
                        + " belongs to a different chunk than " + chunkX + "," + chunkZ + " in " + region.id);
            for (String field : List.of("x", "pairx")) if (entity.contains(field))
                entity.put(field, entity.getInt(field) - region.bounds.minX() * 16);
            for (String field : List.of("z", "pairz")) if (entity.contains(field))
                entity.put(field, entity.getInt(field) - region.bounds.minZ() * 16);
            output.write(Tag.writeBedrockNBT(entity));
        }
        return output.toByteArray();
    }
}
