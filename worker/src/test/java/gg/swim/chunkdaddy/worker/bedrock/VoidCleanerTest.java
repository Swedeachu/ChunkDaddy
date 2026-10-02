package gg.swim.chunkdaddy.worker.bedrock;

import org.iq80.leveldb.CompressionType;
import org.iq80.leveldb.DB;
import org.iq80.leveldb.DBIterator;
import org.iq80.leveldb.Options;
import org.iq80.leveldb.impl.Iq80DBFactory;
import org.iq80.leveldb.table.BloomFilterPolicy;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The cleaner deletes records. The only interesting question about it is what it does
 * <em>not</em> delete, and a real exported world is a poor test of that because it contains
 * nothing but chunks. So this builds a world that has one of everything.
 *
 * <p>Three of the world level keys here are chosen deliberately: {@code BiomeData},
 * {@code Overworld} and {@code mVillages} are nine ASCII bytes, which is exactly the length
 * of an overworld chunk key. A cleaner that recognises chunk keys by length alone would eat
 * them.
 */
class VoidCleanerTest {
    private static final byte[] SUB_CHUNK_WITH_STONE = {
            8,            // version
            1,            // one storage
            (byte) (1 << 1), // 1 bit per block, not runtime
            0, 0, 0, 0,   // one word of block indices (truncated, only shape matters here)
            2, 0, 0, 0    // palette count 2
    };

    private static Options options(boolean create) {
        Options options = new Options();
        options.compressionType(CompressionType.ZLIB_RAW);
        options.blockSize(160 * 1024);
        options.filterPolicy(new BloomFilterPolicy(10));
        options.createIfMissing(create);
        return options;
    }

    private static byte[] ascii(String s) {
        return s.getBytes(StandardCharsets.US_ASCII);
    }

    /** An overworld chunk key: x, z little endian, then the record tag. */
    private static byte[] chunkKey(int x, int z, int tag) {
        return new byte[]{
                (byte) x, (byte) (x >> 8), (byte) (x >> 16), (byte) (x >> 24),
                (byte) z, (byte) (z >> 8), (byte) (z >> 16), (byte) (z >> 24),
                (byte) tag};
    }

    private static byte[] subChunkKey(int x, int z, int y) {
        byte[] base = chunkKey(x, z, 0x2F);
        byte[] key = Arrays.copyOf(base, 10);
        key[9] = (byte) y;
        return key;
    }

    private static byte[] digpKey(int x, int z) {
        byte[] key = new byte[12];
        System.arraycopy(ascii("digp"), 0, key, 0, 4);
        key[4] = (byte) x; key[5] = (byte) (x >> 8); key[6] = (byte) (x >> 16); key[7] = (byte) (x >> 24);
        key[8] = (byte) z; key[9] = (byte) (z >> 8); key[10] = (byte) (z >> 16); key[11] = (byte) (z >> 24);
        return key;
    }

    /** Every record the cleaner must leave alone, keyed by a readable name. */
    private static Map<String, byte[]> worldRecords() {
        Map<String, byte[]> records = new LinkedHashMap<>();
        // The three that collide with a chunk key's length.
        records.put("BiomeData", ascii("biome palette payload"));
        records.put("Overworld", ascii("overworld record"));
        records.put("mVillages", ascii("village list"));
        // The rest of a real world's furniture.
        records.put("~local_player", ascii("player nbt"));
        records.put("AutonomousEntities", ascii("autonomous entities"));
        records.put("portals", ascii("portal records"));
        records.put("scoreboard", ascii("scoreboard nbt"));
        records.put("schedulerWT", ascii("scheduler"));
        records.put("LevelChunkMetaDataDictionary", ascii("meta dictionary"));
        records.put("map_-4294967266", ascii("map item data"));
        records.put("player_server_0123abcd-ef01-2345-6789-abcdef012345", ascii("server player"));
        records.put("structuretemplate_mystructure:house", ascii("structure nbt"));
        records.put("tickingarea_9f8e7d6c-0000-1111-2222-333344445555", ascii("ticking area"));
        records.put("VILLAGE_0123456789abcdef_INFO", ascii("village info"));
        records.put("dimension0", ascii("dimension record"));
        return records;
    }

    private Path buildWorld(Path root) throws Exception {
        Files.createDirectories(root);
        Files.write(root.resolve("level.dat"), ascii("level.dat contents"));
        Files.write(root.resolve("levelname.txt"), ascii("Test World"));
        Path db = root.resolve("db");
        Files.createDirectories(db);

        try (DB handle = new Iq80DBFactory().open(new File(db.toString()), options(true))) {
            for (Map.Entry<String, byte[]> record : worldRecords().entrySet()) {
                handle.put(ascii(record.getKey()), record.getValue());
            }

            // (0,0): real blocks. Must survive whole.
            handle.put(subChunkKey(0, 0, 4), SUB_CHUNK_WITH_STONE);
            handle.put(chunkKey(0, 0, 0x2B), ascii("data3d for 0,0"));
            handle.put(chunkKey(0, 0, 0x2C), new byte[]{41});
            handle.put(chunkKey(0, 0, 0x31), new byte[0]);
            handle.put(digpKey(0, 0), new byte[0]);

            // (1,0): no blocks at all, only biomes and a version. The thing being removed.
            handle.put(chunkKey(1, 0, 0x2B), ascii("data3d for 1,0"));
            handle.put(chunkKey(1, 0, 0x2C), new byte[]{41});
            handle.put(chunkKey(1, 0, 0x31), new byte[0]);
            handle.put(chunkKey(1, 0, 0x3D), new byte[]{0});
            handle.put(chunkKey(1, 0, 0x40), new byte[]{0, 0});
            handle.put(chunkKey(1, 0, 0x76), new byte[]{41});
            handle.put(digpKey(1, 0), new byte[0]);

            // (2,0): no blocks, but a block entity. A chest floating in air is still content.
            handle.put(chunkKey(2, 0, 0x2B), ascii("data3d for 2,0"));
            handle.put(chunkKey(2, 0, 0x2C), new byte[]{41});
            handle.put(chunkKey(2, 0, 0x31), ascii("chest nbt"));

            // (3,0): no blocks, but an entity digest pointing at an actor.
            handle.put(chunkKey(3, 0, 0x2B), ascii("data3d for 3,0"));
            handle.put(chunkKey(3, 0, 0x2C), new byte[]{41});
            handle.put(digpKey(3, 0), new byte[]{1, 0, 0, 0, 0, 0, 0, 0});
            handle.put(ascii("actorprefix\u0001\u0000\u0000\u0000\u0000\u0000\u0000\u0000"), ascii("villager nbt"));

            // (4,0): no blocks, but pending ticks.
            handle.put(chunkKey(4, 0, 0x2B), ascii("data3d for 4,0"));
            handle.put(chunkKey(4, 0, 0x33), ascii("pending ticks"));

            // (5,0): no blocks, but hardcoded spawners.
            handle.put(chunkKey(5, 0, 0x2B), ascii("data3d for 5,0"));
            handle.put(chunkKey(5, 0, 0x39), ascii("spawner bounds"));

            // Nether (dimension 1): one empty column, one with blocks.
            handle.put(netherKey(10, 10, 0x2B), ascii("nether data3d empty"));
            handle.put(netherKey(10, 10, 0x2C), new byte[]{41});
            handle.put(netherSubChunkKey(11, 10, 2), SUB_CHUNK_WITH_STONE);
            handle.put(netherKey(11, 10, 0x2C), new byte[]{41});
        }
        return root;
    }

    private static byte[] netherKey(int x, int z, int tag) {
        byte[] key = new byte[13];
        key[0] = (byte) x; key[1] = (byte) (x >> 8); key[2] = (byte) (x >> 16); key[3] = (byte) (x >> 24);
        key[4] = (byte) z; key[5] = (byte) (z >> 8); key[6] = (byte) (z >> 16); key[7] = (byte) (z >> 24);
        key[8] = 1; // dimension: nether
        key[12] = (byte) tag;
        return key;
    }

    private static byte[] netherSubChunkKey(int x, int z, int y) {
        byte[] key = Arrays.copyOf(netherKey(x, z, 0x2F), 14);
        key[13] = (byte) y;
        return key;
    }

    private static Map<String, byte[]> readAll(Path db) throws Exception {
        Map<String, byte[]> all = new TreeMap<>();
        try (DB handle = new Iq80DBFactory().open(new File(db.toString()), options(false));
             DBIterator it = handle.iterator()) {
            for (it.seekToFirst(); it.hasNext(); it.next()) {
                Map.Entry<byte[], byte[]> entry = it.peekNext();
                all.put(hex(entry.getKey()), entry.getValue());
            }
        }
        return all;
    }

    private static String hex(byte[] bytes) {
        StringBuilder out = new StringBuilder();
        for (byte b : bytes) out.append(String.format("%02x", b));
        return out.toString();
    }

    @Test
    void keepsEveryWorldLevelRecordAndEveryColumnThatHoldsSomething() throws Exception {
        Path temp = Files.createTempDirectory("void-cleaner-test-");
        Path source = buildWorld(temp.resolve("source"));
        Path cleaned = temp.resolve("cleaned");

        VoidCleaner.Report report = VoidCleaner.clean(source, cleaned, VoidCleaner.Settings.safe(), null);

        Map<String, byte[]> before = readAll(source.resolve("db"));
        Map<String, byte[]> after = readAll(cleaned.resolve("db"));

        // --- every world level record survives, byte for byte ---
        for (Map.Entry<String, byte[]> record : worldRecords().entrySet()) {
            String key = hex(ascii(record.getKey()));
            assertTrue(after.containsKey(key), "world record was deleted: " + record.getKey());
            assertArrayEquals(record.getValue(), after.get(key),
                    "world record changed: " + record.getKey());
        }
        assertArrayEquals(ascii("villager nbt"),
                after.get(hex(ascii("actorprefix\u0001\u0000\u0000\u0000\u0000\u0000\u0000\u0000"))),
                "an actor referenced by a surviving digest was deleted");

        // --- columns that hold something survive whole ---
        assertTrue(after.containsKey(hex(subChunkKey(0, 0, 4))), "a column with blocks lost its sub-chunk");
        assertTrue(after.containsKey(hex(chunkKey(0, 0, 0x2B))), "a column with blocks lost its biomes");
        assertTrue(after.containsKey(hex(chunkKey(2, 0, 0x31))), "a block entity column was deleted");
        assertTrue(after.containsKey(hex(digpKey(3, 0))), "an entity column was deleted");
        assertTrue(after.containsKey(hex(chunkKey(4, 0, 0x33))), "a pending ticks column was deleted");
        assertTrue(after.containsKey(hex(chunkKey(5, 0, 0x39))), "a spawner column was deleted");
        assertTrue(after.containsKey(hex(netherSubChunkKey(11, 10, 2))), "a nether column with blocks was deleted");

        // --- the empty ones, and only the empty ones, are gone ---
        for (int tag : new int[]{0x2B, 0x2C, 0x31, 0x3D, 0x40, 0x76}) {
            assertFalse(after.containsKey(hex(chunkKey(1, 0, tag))),
                    "an empty column kept its " + VoidCleaner.tagName(tag) + " record");
        }
        assertFalse(after.containsKey(hex(digpKey(1, 0))), "an empty column kept its actor digest");
        assertFalse(after.containsKey(hex(netherKey(10, 10, 0x2B))), "an empty nether column survived");

        assertEquals(2, report.columnsRemoved(), "expected exactly the two empty columns to go");
        // (0,0) blocks, (2,0) block entity, (3,0) entity, (4,0) ticks, (5,0) spawners,
        // plus the nether column with blocks.
        assertEquals(6, report.columnsKept());
        assertEquals(worldRecords().size() + 1, report.worldRecordsPreserved(),
                "world records plus the one actorprefix");

        // --- nothing appeared from nowhere ---
        for (String key : after.keySet()) {
            assertTrue(before.containsKey(key), "the cleaned world gained a key that was not in the source");
            assertArrayEquals(before.get(key), after.get(key), "a surviving record's value changed");
        }

        // --- files beside the database come across ---
        assertArrayEquals(Files.readAllBytes(source.resolve("level.dat")),
                Files.readAllBytes(cleaned.resolve("level.dat")));
        assertArrayEquals(Files.readAllBytes(source.resolve("levelname.txt")),
                Files.readAllBytes(cleaned.resolve("levelname.txt")));
    }

    @Test
    void dryRunReportsTheSameCountsAndWritesNothing() throws Exception {
        Path temp = Files.createTempDirectory("void-cleaner-dry-");
        Path source = buildWorld(temp.resolve("source"));
        Path destination = temp.resolve("not-written");

        VoidCleaner.Report dry = VoidCleaner.clean(source, destination,
                new VoidCleaner.Settings(false, true), null);
        assertFalse(Files.exists(destination), "a dry run created the destination");
        assertEquals(2, dry.columnsRemoved());
        assertTrue(dry.keysRemoved() > 0, "a dry run must still say how much it would remove");

        VoidCleaner.Report wet = VoidCleaner.clean(source, temp.resolve("cleaned"),
                VoidCleaner.Settings.safe(), null);
        assertEquals(wet.columnsRemoved(), dry.columnsRemoved());
        assertEquals(wet.keysRemoved(), dry.keysRemoved());
        assertEquals(wet.valueBytesRemoved(), dry.valueBytesRemoved());
    }

    @Test
    void onlyARealSingleEntryAirPaletteCountsAsEmpty() {
        // bits per block 0, palette of one, naming air.
        byte[] air = concat(new byte[]{8, 1, 0}, intLE(1), ascii("\n\u0000\u0000\u0004nameminecraft:air"));
        assertTrue(VoidCleaner.isAirOnlySubChunk(air));

        // Same shape but a different block.
        byte[] stone = concat(new byte[]{8, 1, 0}, intLE(1), ascii("\n\u0000\u0000\u0004nameminecraft:stone"));
        assertFalse(VoidCleaner.isAirOnlySubChunk(stone));

        // Air plus something else in the palette: not empty.
        byte[] two = concat(new byte[]{8, 1, 0}, intLE(2), ascii("minecraft:air"));
        assertFalse(VoidCleaner.isAirOnlySubChunk(two));

        // Two storages means a liquid layer; not empty whatever the first one says.
        byte[] layered = concat(new byte[]{8, 2, 0}, intLE(1), ascii("minecraft:air"));
        assertFalse(VoidCleaner.isAirOnlySubChunk(layered));

        // More than one bit per block means more than one distinct block.
        byte[] packed = concat(new byte[]{8, 1, (byte) (1 << 1)}, intLE(1), ascii("minecraft:air"));
        assertFalse(VoidCleaner.isAirOnlySubChunk(packed));

        // Unknown versions and truncated records are never empty.
        assertFalse(VoidCleaner.isAirOnlySubChunk(new byte[]{99, 1, 0, 1, 0, 0, 0}));
        assertFalse(VoidCleaner.isAirOnlySubChunk(new byte[]{8}));
        assertFalse(VoidCleaner.isAirOnlySubChunk(new byte[0]));
    }

    private static byte[] intLE(int value) {
        return new byte[]{(byte) value, (byte) (value >> 8), (byte) (value >> 16), (byte) (value >> 24)};
    }

    private static byte[] concat(byte[]... parts) {
        int length = 0;
        for (byte[] part : parts) length += part.length;
        byte[] out = new byte[length];
        int offset = 0;
        for (byte[] part : parts) {
            System.arraycopy(part, 0, out, offset, part.length);
            offset += part.length;
        }
        return out;
    }
}
