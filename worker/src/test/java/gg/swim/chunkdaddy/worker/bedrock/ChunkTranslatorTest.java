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

import static org.junit.jupiter.api.Assertions.*;

class ChunkTranslatorTest {
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

    @Test void liftsBlocksBlockEntitiesAndHeightMapByAnyAmount() throws Exception {
        Path source = world("source");
        CompoundTag sign = new CompoundTag();
        sign.put("id", "Sign");
        sign.put("x", 3);
        sign.put("y", 14);
        sign.put("z", 4);
        byte[] heights = new byte[512 + 4];
        ByteBuffer.wrap(heights).order(ByteOrder.LITTLE_ENDIAN).putShort(0, (short) 80);
        try (DB db = open(source, false)) {
            // y 5 stone, y 14 a sign and y 15 glass in sub-chunk 0; y -3 dirt in sub-chunk -1.
            db.put(key(0, 0, 0x2F, 0), subChunk(0, 1, 5, 2, "minecraft:stone", 3, 14, 4, "minecraft:standing_sign", 7, 15, 7, "minecraft:glass"));
            db.put(key(0, 0, 0x2F, -1), subChunk(-1, 0, 13, 0, "minecraft:dirt"));
            db.put(key(0, 0, 0x31, null), Tag.writeBedrockNBT(sign));
            db.put(key(0, 0, 0x2B, null), heights);
            db.put(key(0, 0, 0x3B, null), new byte[]{1, 2, 3});
            db.put(key(5, 5, 0x2F, 0), subChunk(0, 0, 0, 0, "minecraft:stone"));
        }

        Path out = temporary.resolve("out");
        var result = ChunkTranslator.translate(source, out, 0, 0, 92, new ChunkRect(0, 0, 0, 0), List.of());
        assertEquals(2, result.get("subChunksIn").getAsLong());

        try (DB db = open(out, false)) {
            // y 5 + 92 = 97: sub-chunk 6, local y 1. y 14 + 92 = 106: sub-chunk 6, local 10.
            byte[] six = db.get(key(0, 0, 0x2F, 6));
            assertNotNull(six);
            assertEquals(6, six[2]);
            assertEquals("minecraft:stone", blockAt(six, 1, 1, 2));
            assertEquals("minecraft:standing_sign", blockAt(six, 3, 10, 4));
            assertEquals("minecraft:glass", blockAt(six, 7, 11, 7));
            assertEquals("minecraft:air", blockAt(six, 1, 5, 2));
            // y -3 + 92 = 89: sub-chunk 5, local 9.
            assertEquals("minecraft:dirt", blockAt(db.get(key(0, 0, 0x2F, 5)), 0, 9, 0));
            assertNull(db.get(key(0, 0, 0x2F, 0)));
            assertNull(db.get(key(0, 0, 0x2F, -1)));
            CompoundTag moved = Tag.readBedrockNBT(ByteBuffer.wrap(db.get(key(0, 0, 0x31, null))).order(ByteOrder.LITTLE_ENDIAN));
            assertEquals(106, moved.getInt("y", 0));
            assertEquals(172, ByteBuffer.wrap(db.get(key(0, 0, 0x2B, null))).order(ByteOrder.LITTLE_ENDIAN).getShort(0));
            assertNull(db.get(key(0, 0, 0x3B, null)), "checksums are dropped");
            assertNull(db.get(key(5, 5, 0x2F, 6)), "outside the rectangle is not copied");
        }
    }

    @Test void refusesBlocksLeavingTheBuildRange() throws Exception {
        Path source = world("high");
        try (DB db = open(source, false)) {
            db.put(key(0, 0, 0x2F, 19), subChunk(19, 0, 10, 0, "minecraft:stone"));
        }
        assertThrows(Exception.class, () -> ChunkTranslator.translate(source, temporary.resolve("o"), 0, 0, 20, null, List.of()));
    }
}
