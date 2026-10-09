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
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

class BedrockChunkMergerTest {
    @TempDir Path temporary;

    private Path world(String name, int network) throws Exception {
        Path directory = temporary.resolve(name);
        Files.createDirectories(directory);
        CompoundTag tag = new CompoundTag();
        tag.put("NetworkVersion", network);
        tag.put("SpawnX", 85);
        byte[] nbt = Tag.writeBedrockNBT(tag);
        Files.write(directory.resolve("level.dat"), ByteBuffer.allocate(8 + nbt.length).order(ByteOrder.LITTLE_ENDIAN)
                .putInt(10).putInt(nbt.length).put(nbt).array());
        try (DB ignored = open(directory, true)) {}
        return directory;
    }

    private DB open(Path directory, boolean create) throws Exception {
        return Iq80DBFactory.factory.open(directory.resolve("db").toFile(), VoidCleaner.databaseOptions(create));
    }

    private byte[] key(int x, int z) {
        return ByteBuffer.allocate(10).order(ByteOrder.LITTLE_ENDIAN).putInt(x).putInt(z).put((byte) 0x2f).put((byte) 0).array();
    }

    @Test void preservesBaseRecordsFilesAndSettingsAndIgnoresOverlayPacks() throws Exception {
        Path base = world("base", 924), overlay = world("overlay", 924), output = temporary.resolve("merged");
        byte[] worldKey = "actorprefixNPC".getBytes(StandardCharsets.UTF_8);
        byte[] netherKey = ByteBuffer.allocate(14).order(ByteOrder.LITTLE_ENDIAN)
                .putInt(1).putInt(2).putInt(1).put((byte) 0x2f).put((byte) 0).array();
        try (DB db = open(base, false)) {
            db.put(key(0, 0), new byte[]{1, 2, 3});
            db.put(worldKey, new byte[]{4, 5});
            db.put(netherKey, new byte[]{6, 7});
        }
        byte[] emptyActors = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN)
                .put("digp".getBytes(StandardCharsets.US_ASCII)).putInt(50).putInt(50).array();
        try (DB db = open(overlay, false)) {
            db.put(key(50, 50), new byte[]{8, 9});
            db.put(emptyActors, new byte[0]);
        }
        Files.writeString(base.resolve("existing.json"), "keep me");
        Files.createDirectories(overlay.resolve("resource_packs"));
        Files.writeString(overlay.resolve("resource_packs/manifest.json"), "ignore me");
        var result = BedrockChunkMerger.merge(base, overlay, output, new ChunkRect(50, 50, 52, 52));
        assertEquals(3, result.get("baseRecordsPreserved").getAsLong());
        assertEquals(2, result.get("overlayRecordsVerified").getAsLong());
        assertTrue(Arrays.equals(Files.readAllBytes(base.resolve("level.dat")), Files.readAllBytes(output.resolve("level.dat"))));
        assertEquals("keep me", Files.readString(output.resolve("existing.json")));
        assertFalse(Files.exists(output.resolve("resource_packs")));
        try (DB db = open(output, false)) {
            assertArrayEquals(new byte[]{1, 2, 3}, db.get(key(0, 0)));
            assertArrayEquals(new byte[]{4, 5}, db.get(worldKey));
            assertArrayEquals(new byte[]{6, 7}, db.get(netherKey));
            assertArrayEquals(new byte[]{8, 9}, db.get(key(50, 50)));
            assertArrayEquals(new byte[0], db.get(emptyActors));
        }
        assertEquals(50, BedrockChunkMerger.inspect(output).getAsJsonObject("overworldBounds").get("maxChunkX").getAsInt());
        try (DB db = open(overlay, false)) { db.put(emptyActors, new byte[8]); }
        Path refusedActors = temporary.resolve("refused-actors");
        assertThrows(Exception.class, () -> BedrockChunkMerger.merge(base, overlay, refusedActors, new ChunkRect(50, 50, 52, 52)));
        assertFalse(Files.exists(refusedActors));
    }

    @Test void refusesOverlapNewerFormatAndOutOfBoundsBeforeCreatingOutput() throws Exception {
        Path base = world("base", 924), overlay = world("overlay", 924), newer = world("newer", 1000);
        try (DB db = open(base, false)) { db.put(key(50, 50), new byte[]{1}); }
        try (DB db = open(overlay, false)) { db.put(key(50, 50), new byte[]{2}); }
        Path output = temporary.resolve("refused");
        assertThrows(Exception.class, () -> BedrockChunkMerger.merge(base, overlay, output, new ChunkRect(50, 50, 52, 52)));
        assertFalse(Files.exists(output));
        assertThrows(Exception.class, () -> BedrockChunkMerger.merge(base, newer, output, new ChunkRect(50, 50, 52, 52)));
        assertFalse(Files.exists(output));
        assertThrows(Exception.class, () -> BedrockChunkMerger.merge(base, overlay, output, new ChunkRect(60, 60, 62, 62)));
        assertFalse(Files.exists(output));
    }
}
