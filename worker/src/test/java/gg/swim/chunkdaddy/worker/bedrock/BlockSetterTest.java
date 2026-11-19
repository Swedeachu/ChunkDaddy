package gg.swim.chunkdaddy.worker.bedrock;

import com.hivemc.chunker.nbt.tags.Tag;
import com.hivemc.chunker.nbt.tags.collection.CompoundTag;
import gg.swim.chunkdaddy.worker.util.ChunkRect;
import org.iq80.leveldb.DB;
import org.iq80.leveldb.impl.Iq80DBFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import com.google.gson.JsonParser;

import static org.junit.jupiter.api.Assertions.*;

class BlockSetterTest {
    @TempDir Path temporary;

    private Path world(String name) throws Exception {
        Path directory = temporary.resolve(name);
        Files.createDirectories(directory);
        CompoundTag tag = new CompoundTag();
        tag.put("NetworkVersion", 924);
        byte[] nbt = Tag.writeBedrockNBT(tag);
        Files.write(directory.resolve("level.dat"), ByteBuffer.allocate(8 + nbt.length).order(ByteOrder.LITTLE_ENDIAN)
                .putInt(10).putInt(nbt.length).put(nbt).array());
        try (DB ignored = open(directory, true)) {}
        return directory;
    }

    private DB open(Path directory, boolean create) throws Exception {
        return Iq80DBFactory.factory.open(directory.resolve("db").toFile(), VoidCleaner.databaseOptions(create));
    }

    private static byte[] key(int x, int z, int tag, Integer section) {
        ByteBuffer b = ByteBuffer.allocate(section == null ? 9 : 10).order(ByteOrder.LITTLE_ENDIAN).putInt(x).putInt(z).put((byte) tag);
        if (section != null) b.put((byte) (int) section);
        return b.array();
    }

    private static CompoundTag block(String name) {
        CompoundTag tag = new CompoundTag();
        tag.put("name", name);
        tag.put("states", new CompoundTag());
        tag.put("version", 18168865);
        return tag;
    }

    /** A version 9 sub-chunk with one layer: air everywhere except the given (x, y, z) -> name blocks. */
    private static byte[] subChunk(int index, Object... blocks) throws Exception {
        List<String> names = new ArrayList<>(List.of("minecraft:air"));
        int[] indices = new int[4096];
        for (int i = 0; i < blocks.length; i += 4) {
            String name = (String) blocks[i + 3];
            if (!names.contains(name)) names.add(name);
            indices[((int) blocks[i] << 8) | ((int) blocks[i + 2] << 4) | (int) blocks[i + 1]] = names.indexOf(name);
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(9);
        out.write(1);
        out.write(index);
        out.write(4 << 1);
        ByteBuffer words = ByteBuffer.allocate(512 * 4).order(ByteOrder.LITTLE_ENDIAN);
        for (int w = 0; w < 512; w++) {
            int word = 0;
            for (int i = 0; i < 8; i++) word |= indices[w * 8 + i] << (i * 4);
            words.putInt(word);
        }
        out.writeBytes(words.array());
        out.writeBytes(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(names.size()).array());
        for (String name : names) out.writeBytes(Tag.writeBedrockNBT(block(name)));
        return out.toByteArray();
    }

    /** Block name at local (x, y, z) of a stored sub-chunk's first layer. */
    private static String blockAt(byte[] value, int x, int y, int z) throws Exception {
        ByteBuffer in = ByteBuffer.wrap(value).order(ByteOrder.LITTLE_ENDIAN);
        int version = in.get();
        in.get();
        if (version == 9) in.get();
        int bits = (in.get() & 0xFF) >> 1;
        int[] indices = new int[4096];
        if (bits > 0) {
            int perWord = 32 / bits, words = (4096 + perWord - 1) / perWord, mask = (1 << bits) - 1;
            for (int w = 0; w < words; w++) {
                int word = in.getInt();
                for (int i = 0; i < perWord && w * perWord + i < 4096; i++) indices[w * perWord + i] = (word >>> (i * bits)) & mask;
            }
        }
        int size = in.getInt();
        List<String> names = new ArrayList<>();
        for (int i = 0; i < size; i++) names.add(Tag.readBedrockNBT(in).getString("name", ""));
        return names.get(indices[(x << 8) | (z << 4) | y]);
    }

    @Test void copiesAndSetsBlocksInACopyAndLeavesTheSourceAlone() throws Exception {
        Path source = world("source");
        CompoundTag barrel = new CompoundTag();
        barrel.put("id", "Barrel");
        barrel.put("x", 1);
        barrel.put("y", 3);
        barrel.put("z", 1);
        CompoundTag doomed = new CompoundTag();
        doomed.put("id", "Sign");
        doomed.put("x", 4);
        doomed.put("y", 3);
        doomed.put("z", 4);
        ByteArrayOutputStream entities = new ByteArrayOutputStream();
        entities.write(Tag.writeBedrockNBT(barrel));
        entities.write(Tag.writeBedrockNBT(doomed));
        byte[] original = subChunk(0, 1, 3, 1, "minecraft:barrel", 4, 3, 4, "minecraft:standing_sign", 2, 2, 2, "minecraft:packed_mud", 5, 5, 5, "minecraft:granite_wall");
        try (DB db = open(source, false)) {
            db.put(key(0, 0, 0x2F, 0), original);
            db.put(key(0, 0, 0x31, null), entities.toByteArray());
            db.put(key(0, 0, 0x3B, null), new byte[]{1});
        }
        var edits = BlockSetter.parse(JsonParser.parseString("""
                [{"x":4,"y":3,"z":4,"copyFrom":{"x":2,"y":2,"z":2}},
                 {"x":2,"y":2,"z":2,"copyFrom":{"x":1,"y":3,"z":1}},
                 {"x":5,"y":5,"z":5,"block":{"name":"air"}},
                 {"x":6,"y":6,"z":6,"block":{"name":"dark_oak_slab","states":{"minecraft:vertical_half":"bottom"}}}]
                """).getAsJsonArray());
        Path out = temporary.resolve("out");
        var result = BlockSetter.set(source, out, edits);
        assertEquals(4, result.get("blocksChanged").getAsLong());
        assertEquals(1, result.get("blockEntitiesRemoved").getAsLong());
        assertEquals(1, result.get("blockEntitiesCopied").getAsLong());

        try (DB db = open(out, false)) {
            byte[] value = db.get(key(0, 0, 0x2F, 0));
            assertEquals("minecraft:packed_mud", blockAt(value, 4, 3, 4), "copied from the unedited source");
            assertEquals("minecraft:barrel", blockAt(value, 2, 2, 2));
            assertEquals("minecraft:barrel", blockAt(value, 1, 3, 1));
            assertEquals("minecraft:air", blockAt(value, 5, 5, 5));
            assertEquals("minecraft:dark_oak_slab", blockAt(value, 6, 6, 6));
            ByteBuffer buffer = ByteBuffer.wrap(db.get(key(0, 0, 0x31, null))).order(ByteOrder.LITTLE_ENDIAN);
            List<String> at = new ArrayList<>();
            while (buffer.hasRemaining()) {
                CompoundTag tag = Tag.readBedrockNBT(buffer);
                at.add(tag.getString("id", "") + "@" + tag.getInt("x", 0) + "," + tag.getInt("y", 0) + "," + tag.getInt("z", 0));
            }
            assertEquals(List.of("Barrel@1,3,1", "Barrel@2,2,2"), at);
            assertNull(db.get(key(0, 0, 0x3B, null)), "checksums are dropped");
        }
        try (DB db = open(source, false)) {
            assertArrayEquals(original, db.get(key(0, 0, 0x2F, 0)), "the source world is untouched");
        }
        assertThrows(java.io.IOException.class, () -> BlockSetter.set(source, out, edits), "an existing destination is refused");
    }
}
