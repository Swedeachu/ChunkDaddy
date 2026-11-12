package gg.swim.chunkdaddy.worker.bedrock;

import com.google.gson.JsonObject;
import com.hivemc.chunker.nbt.tags.Tag;
import com.hivemc.chunker.nbt.tags.collection.CompoundTag;
import gg.swim.chunkdaddy.worker.util.ChunkRect;
import org.iq80.leveldb.DB;
import org.iq80.leveldb.DBIterator;
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
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Copies a Bedrock world's Overworld chunk records to a new world shifted by whole chunks,
 * without decoding terrain through Chunker. Sub-chunk bytes are position independent, so
 * blocks newer than any conversion profile survive unchanged. Block entity coordinates are
 * rewritten; ordinary entities, pending/random ticks and structure bounds are dropped.
 *
 * <p>Optional {@code removeBlocks} positions (source coordinates) become air in every
 * storage layer and lose their block entity, which is how label signs placed by a map
 * designer are cleared while the map is moved. The output is an overlay for
 * {@link BedrockChunkMerger#merge} or {@link BedrockRegionReplacer#replace}.</p>
 *
 * <p>{@code deltaY} also moves every block up (or down) by any number of blocks: each
 * column's sub-chunks are re-encoded by {@link VerticalShifter}, block entity {@code y}
 * moves with them, the Data3D/Data2D heightmap moves, and sub-chunk checksums are
 * dropped. An optional source {@code rectangle} limits the copy to those columns.</p>
 */
public final class ChunkTranslator {
    private static final int SUBCHUNK = 0x2F;
    private static final int BLOCK_ENTITY = 0x31;
    private static final Set<Integer> DROPPED = Set.of(0x32, 0x33, 0x39, 0x3A);
    private static final int DATA_3D = 0x2B;
    private static final int DATA_2D = 0x2D;
    private static final int CHECKSUMS = 0x3B;
    private static final int[] BIT_SIZES = {1, 2, 3, 4, 5, 6, 8, 16};

    private record Position(int x, int y, int z) {}

    private ChunkTranslator() {
    }

    public static JsonObject translate(
            Path source,
            Path destination,
            int deltaChunkX,
            int deltaChunkZ,
            List<int[]> removeBlocks
    ) throws IOException {
        return translate(source, destination, deltaChunkX, deltaChunkZ, 0, null, removeBlocks);
    }

    /**
     * @param deltaY    blocks to move every block up (negative: down); 0 keeps heights
     * @param rectangle source chunk columns to copy, or null for every Overworld column
     */
    public static JsonObject translate(
            Path source,
            Path destination,
            int deltaChunkX,
            int deltaChunkZ,
            int deltaY,
            ChunkRect rectangle,
            List<int[]> removeBlocks
    ) throws IOException {
        if (Files.exists(destination)) {
            throw new IOException("Destination already exists: " + destination);
        }

        Set<Position> removals = new HashSet<>();
        Map<Long, List<Position>> removalsBySection = new HashMap<>();
        for (int[] block : removeBlocks) {
            Position position = new Position(block[0], block[1], block[2]);
            removals.add(position);
            removalsBySection.computeIfAbsent(sectionKey(block[0], block[1], block[2]), k -> new ArrayList<>()).add(position);
        }

        Files.createDirectories(destination);
        Files.copy(source.resolve("level.dat"), destination.resolve("level.dat"));
        int deltaX = deltaChunkX * 16;
        int deltaZ = deltaChunkZ * 16;
        long records = 0;
        long dropped = 0;
        long blockEntities = 0;
        long blockEntitiesRemoved = 0;
        long blocksRemoved = 0;
        long sectionsIn = 0;
        long sectionsOut = 0;
        // With a vertical shift, sub-chunks are gathered per column and re-encoded at the end.
        Map<Long, Map<Integer, byte[]>> columns = new TreeMap<>();
        Map<Long, byte[]> keyPrefixes = new HashMap<>();
        int minX = Integer.MAX_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int maxZ = Integer.MIN_VALUE;

        try (DB input = open(source, false);
             DB output = open(destination, true);
             DBIterator it = input.iterator()) {
            WriteBatch batch = output.createWriteBatch();
            try {
                for (it.seekToFirst(); it.hasNext();) {
                    var entry = it.next();
                    var key = VoidCleaner.parse(entry.getKey());
                    if (key == null || key.dimension() != 0 || key.actorDigest() || DROPPED.contains(key.tag())) {
                        dropped++;
                        continue;
                    }

                    int chunkX = (int) (key.column() >> 32);
                    int chunkZ = (int) key.column();
                    if (rectangle != null && !rectangle.contains(chunkX, chunkZ)) {
                        continue;
                    }
                    if (deltaY != 0 && key.tag() == CHECKSUMS) {
                        dropped++;
                        continue;
                    }
                    byte[] value = entry.getValue();
                    if (key.tag() == BLOCK_ENTITY) {
                        ByteArrayOutputStream rewritten = new ByteArrayOutputStream();
                        ByteBuffer buffer = ByteBuffer.wrap(value).order(ByteOrder.LITTLE_ENDIAN);
                        while (buffer.hasRemaining()) {
                            CompoundTag tag = Tag.readBedrockNBT(buffer);
                            if (tag == null) {
                                break;
                            }
                            blockEntities++;
                            Position at = new Position(tag.getInt("x", 0), tag.getInt("y", 0), tag.getInt("z", 0));
                            if (removals.contains(at)) {
                                blockEntitiesRemoved++;
                                continue;
                            }
                            tag.put("x", at.x() + deltaX);
                            tag.put("y", at.y() + deltaY);
                            tag.put("z", at.z() + deltaZ);
                            if (tag.contains("pairy")) {
                                tag.put("pairy", tag.getInt("pairy", 0) + deltaY);
                            }
                            if (tag.contains("pairx")) {
                                tag.put("pairx", tag.getInt("pairx", 0) + deltaX);
                            }
                            if (tag.contains("pairz")) {
                                tag.put("pairz", tag.getInt("pairz", 0) + deltaZ);
                            }
                            rewritten.write(Tag.writeBedrockNBT(tag));
                        }
                        if (rewritten.size() == 0) {
                            continue;
                        }
                        value = rewritten.toByteArray();
                    } else if (key.tag() == SUBCHUNK) {
                        int section = entry.getKey()[entry.getKey().length - 1];
                        List<Position> inSection = removalsBySection.get(sectionKey(chunkX * 16, section * 16, chunkZ * 16));
                        if (inSection != null) {
                            value = clearBlocks(value, inSection);
                            blocksRemoved += inSection.size();
                        }
                        if (deltaY != 0) {
                            sectionsIn++;
                            columns.computeIfAbsent(key.column(), k -> new TreeMap<>()).put(section, value);
                            keyPrefixes.putIfAbsent(key.column(), entry.getKey().clone());
                            continue;
                        }
                    } else if (deltaY != 0 && (key.tag() == DATA_3D || key.tag() == DATA_2D)) {
                        value = VerticalShifter.shiftHeightMap(value, deltaY);
                    }

                    byte[] newKey = entry.getKey().clone();
                    writeLe32(newKey, 0, chunkX + deltaChunkX);
                    writeLe32(newKey, 4, chunkZ + deltaChunkZ);
                    batch.put(newKey, value);
                    minX = Math.min(minX, chunkX + deltaChunkX);
                    minZ = Math.min(minZ, chunkZ + deltaChunkZ);
                    maxX = Math.max(maxX, chunkX + deltaChunkX);
                    maxZ = Math.max(maxZ, chunkZ + deltaChunkZ);
                    if ((++records & 4095) == 0) {
                        output.write(batch);
                        batch.close();
                        batch = output.createWriteBatch();
                    }
                }
                for (var column : columns.entrySet()) {
                    int chunkX = (int) (column.getKey() >> 32);
                    int chunkZ = (int) (long) column.getKey();
                    Map<Integer, byte[]> shifted;
                    try {
                        shifted = VerticalShifter.shiftColumn(column.getValue(), deltaY);
                    } catch (IOException e) {
                        throw new IOException("Chunk " + chunkX + "," + chunkZ + ": " + e.getMessage(), e);
                    }
                    for (var section : shifted.entrySet()) {
                        byte[] newKey = keyPrefixes.get(column.getKey()).clone();
                        writeLe32(newKey, 0, chunkX + deltaChunkX);
                        writeLe32(newKey, 4, chunkZ + deltaChunkZ);
                        newKey[newKey.length - 1] = (byte) (int) section.getKey();
                        batch.put(newKey, section.getValue());
                        sectionsOut++;
                        minX = Math.min(minX, chunkX + deltaChunkX);
                        minZ = Math.min(minZ, chunkZ + deltaChunkZ);
                        maxX = Math.max(maxX, chunkX + deltaChunkX);
                        maxZ = Math.max(maxZ, chunkZ + deltaChunkZ);
                        if ((++records & 4095) == 0) {
                            output.write(batch);
                            batch.close();
                            batch = output.createWriteBatch();
                        }
                    }
                }
                output.write(batch);
            } finally {
                batch.close();
            }
        }

        if (blocksRemoved != removals.size()) {
            throw new IOException("Only " + blocksRemoved + " of " + removals.size() + " removal positions were inside stored sub-chunks");
        }

        JsonObject result = new JsonObject();
        result.addProperty("path", destination.toString());
        result.addProperty("records", records);
        result.addProperty("recordsDropped", dropped);
        result.addProperty("blockEntities", blockEntities);
        result.addProperty("blockEntitiesRemoved", blockEntitiesRemoved);
        result.addProperty("blocksRemoved", blocksRemoved);
        result.addProperty("deltaY", deltaY);
        result.addProperty("subChunksIn", sectionsIn);
        result.addProperty("subChunksOut", sectionsOut);
        JsonObject bounds = new JsonObject();
        bounds.addProperty("minChunkX", minX);
        bounds.addProperty("minChunkZ", minZ);
        bounds.addProperty("maxChunkX", maxX);
        bounds.addProperty("maxChunkZ", maxZ);
        result.add("rectangle", bounds);
        return result;
    }

    /** Re-encodes a version 8/9 sub-chunk with the given positions set to air in every layer. */
    private static byte[] clearBlocks(byte[] value, List<Position> positions) throws IOException {
        ByteBuffer input = ByteBuffer.wrap(value).order(ByteOrder.LITTLE_ENDIAN);
        int version = input.get() & 0xFF;
        if (version != 8 && version != 9) {
            throw new IOException("Unsupported sub-chunk version " + version + " for block removal");
        }
        int layers = input.get() & 0xFF;
        byte yIndex = version == 9 ? input.get() : 0;

        ByteBuffer out = ByteBuffer.allocate(value.length + 4096 * 2 * layers + 1024).order(ByteOrder.LITTLE_ENDIAN);
        out.put((byte) version);
        out.put((byte) layers);
        if (version == 9) {
            out.put(yIndex);
        }

        for (int layer = 0; layer < layers; layer++) {
            int header = input.get() & 0xFF;
            if ((header & 1) != 0) {
                throw new IOException("Runtime-ID sub-chunk palettes are not stored in worlds");
            }
            int bits = header >> 1;
            int[] indices = new int[4096];
            if (bits != 0) {
                int perWord = 32 / bits;
                int words = (4096 + perWord - 1) / perWord;
                int mask = (1 << bits) - 1;
                for (int w = 0; w < words; w++) {
                    int word = input.getInt();
                    for (int i = 0; i < perWord; i++) {
                        int index = w * perWord + i;
                        if (index < 4096) {
                            indices[index] = (word >>> (i * bits)) & mask;
                        }
                    }
                }
            }
            int paletteSize = input.getInt();
            List<CompoundTag> palette = new ArrayList<>();
            for (int i = 0; i < paletteSize; i++) {
                palette.add(Tag.readBedrockNBT(input));
            }

            int air = -1;
            for (int i = 0; i < palette.size(); i++) {
                if ("minecraft:air".equals(palette.get(i).getString("name", ""))) {
                    air = i;
                    break;
                }
            }
            if (air < 0) {
                CompoundTag airTag = new CompoundTag();
                airTag.put("name", "minecraft:air");
                airTag.put("states", new CompoundTag());
                airTag.put("version", palette.isEmpty() ? 18168865 : palette.get(0).getInt("version", 18168865));
                palette.add(airTag);
                air = palette.size() - 1;
            }
            for (Position p : positions) {
                indices[(Math.floorMod(p.x(), 16) << 8) | (Math.floorMod(p.z(), 16) << 4) | Math.floorMod(p.y(), 16)] = air;
            }

            int needed = 1;
            while ((1 << needed) < palette.size()) {
                needed++;
            }
            int newBits = 16;
            for (int size : BIT_SIZES) {
                if (size >= needed) {
                    newBits = size;
                    break;
                }
            }
            out.put((byte) (newBits << 1));
            int perWord = 32 / newBits;
            int words = (4096 + perWord - 1) / perWord;
            for (int w = 0; w < words; w++) {
                int word = 0;
                for (int i = 0; i < perWord; i++) {
                    int index = w * perWord + i;
                    if (index < 4096) {
                        word |= indices[index] << (i * newBits);
                    }
                }
                out.putInt(word);
            }
            out.putInt(palette.size());
            for (CompoundTag entry : palette) {
                out.put(Tag.writeBedrockNBT(entry));
            }
        }

        byte[] result = new byte[out.position()];
        out.flip();
        out.get(result);
        return result;
    }

    private static long sectionKey(int x, int y, int z) {
        long cx = Math.floorDiv(x, 16) & 0x1FFFFFL;
        long cy = Math.floorDiv(y, 16) & 0x3FFL;
        long cz = Math.floorDiv(z, 16) & 0x1FFFFFL;
        return (cx << 31) | (cy << 21) | cz;
    }

    private static DB open(Path world, boolean create) throws IOException {
        if (!create && !Files.isDirectory(world.resolve("db"))) {
            throw new IOException("Missing world database: " + world);
        }
        return Iq80DBFactory.factory.open(world.resolve("db").toFile(), VoidCleaner.databaseOptions(create));
    }

    private static void writeLe32(byte[] out, int offset, int value) {
        out[offset] = (byte) value;
        out[offset + 1] = (byte) (value >> 8);
        out[offset + 2] = (byte) (value >> 16);
        out[offset + 3] = (byte) (value >> 24);
    }
}
