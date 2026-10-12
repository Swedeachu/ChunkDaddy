package gg.swim.chunkdaddy.worker.bedrock;

import com.hivemc.chunker.nbt.tags.Tag;
import com.hivemc.chunker.nbt.tags.collection.CompoundTag;
import gg.swim.chunkdaddy.worker.util.ChunkRect;
import org.iq80.leveldb.*;
import org.iq80.leveldb.impl.Iq80DBFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.*;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class BedrockRegionReplacerTest {
    @TempDir Path temporary;
    private DB open(Path world, boolean create) throws Exception {
        return Iq80DBFactory.factory.open(world.resolve("db").toFile(), VoidCleaner.databaseOptions(create));
    }
    private Path world(String name) throws Exception {
        Path p = temporary.resolve(name); Files.createDirectories(p);
        var level = new CompoundTag(); level.put("NetworkVersion", 924);
        byte[] nbt = Tag.writeBedrockNBT(level);
        Files.write(p.resolve("level.dat"), ByteBuffer.allocate(8 + nbt.length).order(ByteOrder.LITTLE_ENDIAN).putInt(10).putInt(nbt.length).put(nbt).array());
        try (DB ignored = open(p, true)) {}
        return p;
    }
    private byte[] key(int x, int z, int tag) {
        var buffer = ByteBuffer.allocate(tag == 0x2f ? 10 : 9).order(ByteOrder.LITTLE_ENDIAN).putInt(x).putInt(z).put((byte) tag);
        if (tag == 0x2f) buffer.put((byte) 0);
        return buffer.array();
    }
    @Test void replacesEntireFootprintIncludingAirAndDeletedBlockEntities() throws Exception {
        Path base = world("base"), overlay = world("overlay"), output = temporary.resolve("output");
        byte[] global = "world-record".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        try (DB db = open(base, false)) {
            db.put(key(20, 20, 0x2f), new byte[]{1});
            db.put(key(20, 20, 0x31), new byte[]{2}); // removed chest entity
            db.put(key(21, 20, 0x2f), new byte[]{3}); // whole column becomes air/absent
            db.put(key(40, 40, 0x2f), new byte[]{4}); // unrelated terrain
            db.put(key(10, 40, 0x2f), new byte[]{7}); // retained keys before and after overlay
            db.put(key(-1, 40, 0x2f), new byte[]{8}); // unsigned byte-key ordering
            db.put(global, new byte[]{5});
        }
        try (DB db = open(overlay, false)) { db.put(key(20, 20, 0x2f), new byte[]{6}); }
        Files.writeString(base.resolve("regions.json"), "exact unchanged markers");
        Files.createDirectories(overlay.resolve("resource_packs"));
        Files.writeString(overlay.resolve("resource_packs/manifest.json"), "ignore");
        var result = BedrockRegionReplacer.replace(base, overlay, output, List.of(new ChunkRect(20, 20, 21, 21)));
        assertEquals(3, result.get("oldFootprintRecordsRemoved").getAsInt());
        assertEquals(4, result.get("baseRecordsPreserved").getAsInt());
        assertEquals(1, result.get("overlayRecordsVerified").getAsInt());
        assertTrue(result.get("baseLevelDatPreserved").getAsBoolean());
        try (DB db = open(output, false)) {
            assertArrayEquals(new byte[]{6}, db.get(key(20, 20, 0x2f)));
            assertNull(db.get(key(20, 20, 0x31)));
            assertNull(db.get(key(21, 20, 0x2f)));
            assertArrayEquals(new byte[]{4}, db.get(key(40, 40, 0x2f)));
            assertArrayEquals(new byte[]{7}, db.get(key(10, 40, 0x2f)));
            assertArrayEquals(new byte[]{8}, db.get(key(-1, 40, 0x2f)));
            assertArrayEquals(new byte[]{5}, db.get(global));
        }
        assertEquals("exact unchanged markers", Files.readString(output.resolve("regions.json")));
        assertFalse(Files.exists(output.resolve("resource_packs")));
    }
    @Test void refusesSpilloverAndOverlappingFootprints() throws Exception {
        Path base = world("base"), overlay = world("overlay");
        try (DB db = open(overlay, false)) { db.put(key(22, 20, 0x2f), new byte[]{1}); }
        Path output = temporary.resolve("refused");
        assertThrows(Exception.class, () -> BedrockRegionReplacer.replace(base, overlay, output, List.of(new ChunkRect(20, 20, 21, 21))));
        assertFalse(Files.exists(output));
        assertThrows(Exception.class, () -> BedrockRegionReplacer.replace(base, overlay, output, List.of(new ChunkRect(20, 20, 22, 22), new ChunkRect(21, 21, 23, 23))));
        assertFalse(Files.exists(output));
    }
}
