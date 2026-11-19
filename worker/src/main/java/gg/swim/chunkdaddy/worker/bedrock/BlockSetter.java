package gg.swim.chunkdaddy.worker.bedrock;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.hivemc.chunker.nbt.tags.Tag;
import com.hivemc.chunker.nbt.tags.collection.CompoundTag;
import org.iq80.leveldb.DB;
import org.iq80.leveldb.WriteBatch;
import org.iq80.leveldb.impl.Iq80DBFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

/**
 * Sets individual Overworld blocks in a copy of a Bedrock world by rewriting only the
 * sub-chunk records that hold them, without decoding the world through Chunker. Every other
 * database record (entities, other dimensions, settings) is left as it is, so this is the
 * tool for small in-place repairs such as shaving a decoration off a floor.
 *
 * <p>Each edit is a target position and either {@code copyFrom} (another position whose block
 * is cloned in every storage layer, block entity included, read from the world as it was
 * before any edit) or {@code block} (a Bedrock {@code name} and {@code states}; other layers
 * become air). A target's own block entity is always removed. The source world is never
 * opened for writing: the whole world folder is copied to {@code destination} first.</p>
 */
public final class BlockSetter {
    private static final int SUBCHUNK = 0x2F;
    private static final int BLOCK_ENTITY = 0x31;
    private static final int CHECKSUMS = 0x3B;

    /** One target and what goes there. Exactly one of {@code copyFrom} and {@code block} is set. */
    public record Edit(int x, int y, int z, int[] copyFrom, CompoundTag block) {}

    /** A resolved block: its state per storage layer (null for air) and an optional block entity. */
    private record Resolved(List<CompoundTag> layers, CompoundTag blockEntity) {}

    private BlockSetter() {
    }

    public static JsonObject set(Path source, Path destination, List<Edit> edits) throws IOException {
        if (Files.exists(destination)) {
            throw new IOException("Destination already exists: " + destination);
        }
        if (!Files.isDirectory(source.resolve("db"))) {
            throw new IOException("Missing world database: " + source);
        }
        if (edits.isEmpty()) {
            throw new IllegalArgumentException("No edits");
        }
        Map<Long, Integer> seen = new HashMap<>();
        for (Edit edit : edits) {
            if (seen.put(positionKey(edit.x(), edit.y(), edit.z()), 1) != null) {
                throw new IllegalArgumentException("Position edited twice: " + edit.x() + "," + edit.y() + "," + edit.z());
            }
            if ((edit.copyFrom() == null) == (edit.block() == null)) {
                throw new IllegalArgumentException("Each edit needs exactly one of copyFrom and block");
            }
        }
        copyTree(source, destination);

        long changed = 0;
        long blockEntitiesRemoved = 0;
        long blockEntitiesCopied = 0;
        try (DB db = Iq80DBFactory.factory.open(destination.resolve("db").toFile(), VoidCleaner.databaseOptions(false))) {
            // Read phase: every source is resolved against the unedited world.
            Map<Long, VerticalShifter.Section> sections = new HashMap<>();
            Map<Long, List<CompoundTag>> blockEntities = new HashMap<>();
            List<Resolved> resolved = new ArrayList<>();
            for (Edit edit : edits) {
                if (edit.copyFrom() != null) {
                    int[] from = edit.copyFrom();
                    VerticalShifter.Section section = section(db, sections, from[0], from[1], from[2]);
                    int at = local(from[0], from[1], from[2]);
                    List<CompoundTag> layers = new ArrayList<>();
                    for (VerticalShifter.Layer layer : section.layers()) {
                        int index = layer.indices()[at];
                        layers.add(layer.keys()[index] == null ? null : layer.palette().get(index));
                    }
                    CompoundTag entity = null;
                    for (CompoundTag tag : columnEntities(db, blockEntities, from[0], from[2])) {
                        if (tag.getInt("x", 0) == from[0] && tag.getInt("y", 0) == from[1] && tag.getInt("z", 0) == from[2]) {
                            entity = tag.clone();
                            entity.put("x", edit.x());
                            entity.put("y", edit.y());
                            entity.put("z", edit.z());
                        }
                    }
                    resolved.add(new Resolved(layers, entity));
                } else {
                    List<CompoundTag> layers = new ArrayList<>();
                    layers.add(edit.block());
                    resolved.add(new Resolved(layers, null));
                }
                section(db, sections, edit.x(), edit.y(), edit.z());
                columnEntities(db, blockEntities, edit.x(), edit.z());
            }

            // Apply phase, grouped by target sub-chunk.
            Map<Long, List<Integer>> bySection = new LinkedHashMap<>();
            for (int i = 0; i < edits.size(); i++) {
                Edit edit = edits.get(i);
                bySection.computeIfAbsent(sectionKey(edit.x(), edit.y(), edit.z()), k -> new ArrayList<>()).add(i);
            }
            WriteBatch batch = db.createWriteBatch();
            try {
                for (var entry : bySection.entrySet()) {
                    Edit first = edits.get(entry.getValue().get(0));
                    VerticalShifter.Section section = sections.get(entry.getKey());
                    int layerCount = section.layers().size();
                    for (int i : entry.getValue()) {
                        layerCount = Math.max(layerCount, resolved.get(i).layers().size());
                    }
                    CompoundTag airTag = null;
                    for (VerticalShifter.Layer layer : section.layers()) {
                        if (!layer.palette().isEmpty()) {
                            airTag = VerticalShifter.air(layer.palette().get(0).getInt("version", 18168865));
                            break;
                        }
                    }
                    if (airTag == null) airTag = VerticalShifter.air(18168865);

                    List<int[]> layerIndices = new ArrayList<>();
                    List<List<CompoundTag>> palettes = new ArrayList<>();
                    for (int l = 0; l < layerCount; l++) {
                        int[] indices = new int[4096];
                        List<CompoundTag> palette = new ArrayList<>();
                        Map<String, Integer> ids = new HashMap<>();
                        palette.add(airTag);
                        ids.put("", 0);
                        if (l < section.layers().size()) {
                            VerticalShifter.Layer layer = section.layers().get(l);
                            for (int at = 0; at < 4096; at++) {
                                int index = layer.indices()[at];
                                String key = layer.keys()[index];
                                indices[at] = key == null ? 0 : ids.computeIfAbsent(key, k -> {
                                    palette.add(layer.palette().get(index));
                                    return palette.size() - 1;
                                });
                            }
                        }
                        for (int i : entry.getValue()) {
                            Edit edit = edits.get(i);
                            List<CompoundTag> states = resolved.get(i).layers();
                            CompoundTag state = l < states.size() ? states.get(l) : null;
                            int at = local(edit.x(), edit.y(), edit.z());
                            int before = indices[at];
                            int id = 0;
                            if (state != null && !"minecraft:air".equals(state.getString("name", ""))) {
                                String key = VerticalShifter.identity(state);
                                Integer existing = ids.get(key);
                                if (existing == null) {
                                    palette.add(state);
                                    existing = palette.size() - 1;
                                    ids.put(key, existing);
                                }
                                id = existing;
                            }
                            if (l == 0 && before != id) changed++;
                            indices[at] = id;
                        }
                        // Drop palette entries no block uses any more.
                        int[] remap = new int[palette.size()];
                        boolean[] used = new boolean[palette.size()];
                        used[0] = true;
                        for (int index : indices) used[index] = true;
                        List<CompoundTag> compact = new ArrayList<>();
                        for (int p = 0; p < palette.size(); p++) {
                            if (used[p]) {
                                remap[p] = compact.size();
                                compact.add(palette.get(p));
                            }
                        }
                        for (int at = 0; at < 4096; at++) indices[at] = remap[indices[at]];
                        if (l > 0 && compact.size() == 1) break; // a trailing all-air layer is dropped
                        layerIndices.add(indices);
                        palettes.add(compact);
                    }
                    int sectionY = Math.floorDiv(first.y(), 16);
                    batch.put(subChunkKey(first.x(), sectionY, first.z()),
                            VerticalShifter.encode(section.version(), sectionY, layerIndices, palettes));
                }

                // Block entities: remove the targets', add copies, rewrite each touched column once.
                Map<Long, List<CompoundTag>> columns = new TreeMap<>();
                for (int i = 0; i < edits.size(); i++) {
                    Edit edit = edits.get(i);
                    long column = columnKey(edit.x(), edit.z());
                    List<CompoundTag> list = columns.computeIfAbsent(column, k -> new ArrayList<>(blockEntities.get(k)));
                    int before = list.size();
                    list.removeIf(tag -> tag.getInt("x", 0) == edit.x() && tag.getInt("y", 0) == edit.y() && tag.getInt("z", 0) == edit.z());
                    blockEntitiesRemoved += before - list.size();
                }
                for (int i = 0; i < edits.size(); i++) {
                    CompoundTag entity = resolved.get(i).blockEntity();
                    if (entity != null) {
                        columns.get(columnKey(edits.get(i).x(), edits.get(i).z())).add(entity);
                        blockEntitiesCopied++;
                    }
                }
                for (var column : columns.entrySet()) {
                    int chunkX = (int) (column.getKey() >> 32);
                    int chunkZ = (int) (long) column.getKey();
                    byte[] key = columnRecordKey(chunkX, chunkZ, BLOCK_ENTITY);
                    List<CompoundTag> original = blockEntities.get(column.getKey());
                    if (column.getValue().equals(original)) continue;
                    if (column.getValue().isEmpty()) {
                        batch.delete(key);
                    } else {
                        ByteArrayOutputStream out = new ByteArrayOutputStream();
                        for (CompoundTag tag : column.getValue()) out.write(Tag.writeBedrockNBT(tag));
                        batch.put(key, out.toByteArray());
                    }
                }
                // Legacy per-column checksums would no longer match the rewritten sub-chunks.
                for (long section : bySection.keySet()) {
                    Edit edit = edits.get(bySection.get(section).get(0));
                    batch.delete(columnRecordKey(Math.floorDiv(edit.x(), 16), Math.floorDiv(edit.z(), 16), CHECKSUMS));
                }
                db.write(batch);
            } finally {
                batch.close();
            }
        }

        JsonObject result = new JsonObject();
        result.addProperty("path", destination.toString());
        result.addProperty("edits", edits.size());
        result.addProperty("blocksChanged", changed);
        result.addProperty("subChunksRewritten", (long) edits.stream().map(e -> sectionKey(e.x(), e.y(), e.z())).distinct().count());
        result.addProperty("blockEntitiesRemoved", blockEntitiesRemoved);
        result.addProperty("blockEntitiesCopied", blockEntitiesCopied);
        return result;
    }

    /** Parses {@code edits[]}: {@code {x, y, z, copyFrom: {x, y, z}}} or {@code {x, y, z, block: {name, states}}}. */
    public static List<Edit> parse(JsonArray array) {
        List<Edit> edits = new ArrayList<>();
        for (var element : array) {
            JsonObject edit = element.getAsJsonObject();
            int[] from = null;
            CompoundTag block = null;
            if (edit.has("copyFrom")) {
                JsonObject f = edit.getAsJsonObject("copyFrom");
                from = new int[] {f.get("x").getAsInt(), f.get("y").getAsInt(), f.get("z").getAsInt()};
            }
            if (edit.has("block")) {
                JsonObject b = edit.getAsJsonObject("block");
                String name = b.get("name").getAsString();
                block = new CompoundTag();
                block.put("name", name.contains(":") ? name : "minecraft:" + name);
                CompoundTag states = new CompoundTag();
                if (b.has("states")) {
                    for (var state : b.getAsJsonObject("states").entrySet()) {
                        var value = state.getValue().getAsJsonPrimitive();
                        if (value.isBoolean()) states.put(state.getKey(), (byte) (value.getAsBoolean() ? 1 : 0));
                        else if (value.isNumber()) states.put(state.getKey(), value.getAsInt());
                        else states.put(state.getKey(), value.getAsString());
                    }
                }
                block.put("states", states);
                block.put("version", b.has("version") ? b.get("version").getAsInt() : 18168865);
            }
            edits.add(new Edit(edit.get("x").getAsInt(), edit.get("y").getAsInt(), edit.get("z").getAsInt(), from, block));
        }
        return edits;
    }

    private static VerticalShifter.Section section(DB db, Map<Long, VerticalShifter.Section> cache, int x, int y, int z) throws IOException {
        long key = sectionKey(x, y, z);
        VerticalShifter.Section section = cache.get(key);
        if (section == null) {
            byte[] value = db.get(subChunkKey(x, Math.floorDiv(y, 16), z));
            if (value == null) {
                throw new IOException("No stored sub-chunk holds " + x + "," + y + "," + z);
            }
            section = VerticalShifter.decode(value);
            cache.put(key, section);
        }
        return section;
    }

    private static List<CompoundTag> columnEntities(DB db, Map<Long, List<CompoundTag>> cache, int x, int z) throws IOException {
        long column = columnKey(x, z);
        List<CompoundTag> list = cache.get(column);
        if (list == null) {
            list = new ArrayList<>();
            byte[] value = db.get(columnRecordKey(Math.floorDiv(x, 16), Math.floorDiv(z, 16), BLOCK_ENTITY));
            if (value != null) {
                ByteBuffer buffer = ByteBuffer.wrap(value).order(ByteOrder.LITTLE_ENDIAN);
                while (buffer.hasRemaining()) {
                    CompoundTag tag = Tag.readBedrockNBT(buffer);
                    if (tag == null) break;
                    list.add(tag);
                }
            }
            cache.put(column, list);
        }
        return list;
    }

    private static int local(int x, int y, int z) {
        return (Math.floorMod(x, 16) << 8) | (Math.floorMod(z, 16) << 4) | Math.floorMod(y, 16);
    }

    private static long columnKey(int x, int z) {
        return ((long) Math.floorDiv(x, 16) << 32) | (Math.floorDiv(z, 16) & 0xFFFFFFFFL);
    }

    private static long sectionKey(int x, int y, int z) {
        long cx = Math.floorDiv(x, 16) & 0x1FFFFFL;
        long cy = Math.floorDiv(y, 16) & 0x3FFL;
        long cz = Math.floorDiv(z, 16) & 0x1FFFFFL;
        return (cx << 31) | (cy << 21) | cz;
    }

    private static long positionKey(int x, int y, int z) {
        return ((long) (x & 0x3FFFFFF) << 38) | ((long) (y & 0xFFF) << 26) | (z & 0x3FFFFFF);
    }

    private static byte[] subChunkKey(int x, int sectionY, int z) {
        return ByteBuffer.allocate(10).order(ByteOrder.LITTLE_ENDIAN)
                .putInt(Math.floorDiv(x, 16)).putInt(Math.floorDiv(z, 16)).put((byte) SUBCHUNK).put((byte) sectionY).array();
    }

    private static byte[] columnRecordKey(int chunkX, int chunkZ, int tag) {
        return ByteBuffer.allocate(9).order(ByteOrder.LITTLE_ENDIAN).putInt(chunkX).putInt(chunkZ).put((byte) tag).array();
    }

    private static void copyTree(Path source, Path destination) throws IOException {
        try (Stream<Path> paths = Files.walk(source)) {
            for (Path path : (Iterable<Path>) paths::iterator) {
                Path target = destination.resolve(source.relativize(path).toString());
                if (Files.isDirectory(path)) {
                    Files.createDirectories(target);
                } else {
                    Files.copy(path, target);
                }
            }
        }
    }
}
