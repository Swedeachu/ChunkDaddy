package gg.swim.chunkdaddy.worker.bedrock;

import com.hivemc.chunker.nbt.tags.Tag;
import com.hivemc.chunker.nbt.tags.collection.CompoundTag;
import org.iq80.leveldb.*;
import org.iq80.leveldb.impl.Iq80DBFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Path;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

class BlockEntityRealignerTest {
    @TempDir Path world;

    private byte[] key(int x, int z) {
        return ByteBuffer.allocate(9).order(ByteOrder.LITTLE_ENDIAN).putInt(x).putInt(z).put((byte) 0x31).array();
    }

    private DB open() throws Exception {
        return Iq80DBFactory.factory.open(world.resolve("db").toFile(),
                new Options().createIfMissing(true).compressionType(CompressionType.ZLIB_RAW));
    }

    private byte[] tile(String id) throws Exception {
        var tile = new CompoundTag();
        tile.put("id", id); tile.put("x", 1000); tile.put("y", 87); tile.put("z", -10);
        tile.put("payload", "preserved");
        return Tag.writeBedrockNBT(tile);
    }

    @Test void translatesSelectedAnchorsAndPreservesPayloadAndNegativeLocalCoordinates() throws Exception {
        try (DB db = open()) { db.put(key(-2, 3), tile("ItemFrame")); }
        assertEquals(1, BlockEntityRealigner.realign(world, Set.of("ItemFrame")));
        try (DB db = open()) {
            var tile = Tag.readBedrockNBT(ByteBuffer.wrap(db.get(key(-2, 3))).order(ByteOrder.LITTLE_ENDIAN));
            assertEquals(-24, tile.getInt("x")); assertEquals(54, tile.getInt("z"));
            assertEquals(87, tile.getInt("y")); assertEquals("preserved", tile.getString("payload"));
        }
        assertEquals(0, BlockEntityRealigner.realign(world, Set.of("ItemFrame")));
    }

    @Test void refusesUnselectedTilesWithoutPartiallyRepairingTheDatabase() throws Exception {
        byte[] frame = tile("ItemFrame"), chest = tile("Chest");
        try (DB db = open()) { db.put(key(0, 0), frame); db.put(key(1, 0), chest); }
        assertThrows(IllegalStateException.class, () -> BlockEntityRealigner.realign(world, Set.of("ItemFrame")));
        try (DB db = open()) {
            assertArrayEquals(frame, db.get(key(0, 0))); assertArrayEquals(chest, db.get(key(1, 0)));
        }
    }
}
