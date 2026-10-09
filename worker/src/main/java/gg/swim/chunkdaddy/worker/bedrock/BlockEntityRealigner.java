package gg.swim.chunkdaddy.worker.bedrock;

import com.hivemc.chunker.nbt.tags.Tag;
import com.hivemc.chunker.nbt.tags.collection.CompoundTag;
import org.iq80.leveldb.*;
import org.iq80.leveldb.impl.Iq80DBFactory;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Path;
import java.util.*;

/** Repairs explicitly selected legacy tile anchors against their authoritative database chunk. */
public final class BlockEntityRealigner {
    public static long realign(Path world, Set<String> allowedIds) throws Exception {
        if (allowedIds.isEmpty()) throw new IllegalArgumentException("Select block entity IDs to realign");
        List<Map.Entry<byte[], byte[]>> replacements = new ArrayList<>();
        long repaired = 0;
        try (DB db = Iq80DBFactory.factory.open(world.resolve("db").toFile(),
                VoidCleaner.databaseOptions(false))) {
            try (DBIterator iterator = db.iterator()) {
                for (iterator.seekToFirst(); iterator.hasNext();) {
                    var entry = iterator.next();
                    byte[] key = entry.getKey();
                    if (key.length != 9 || key[8] != 0x31) continue;
                    var location = ByteBuffer.wrap(key).order(ByteOrder.LITTLE_ENDIAN);
                    int cx = location.getInt(), cz = location.getInt();
                    var input = ByteBuffer.wrap(entry.getValue()).order(ByteOrder.LITTLE_ENDIAN);
                    var output = new ByteArrayOutputStream();
                    boolean changed = false;
                    while (input.hasRemaining()) {
                        CompoundTag tile = Tag.readBedrockNBT(input);
                        int x = tile.getInt("x"), z = tile.getInt("z");
                        if (Math.floorDiv(x, 16) != cx || Math.floorDiv(z, 16) != cz) {
                            if (!allowedIds.contains(tile.getString("id")))
                                throw new IllegalStateException("Unselected misplaced tile " + tile.getString("id"));
                            tile.put("x", Math.addExact(Math.multiplyExact(cx, 16), Math.floorMod(x, 16)));
                            tile.put("z", Math.addExact(Math.multiplyExact(cz, 16), Math.floorMod(z, 16)));
                            changed = true; repaired++;
                        }
                        output.write(Tag.writeBedrockNBT(tile));
                    }
                    if (changed) replacements.add(Map.entry(key, output.toByteArray()));
                }
            }
            try (WriteBatch batch = db.createWriteBatch()) {
                for (var entry : replacements) batch.put(entry.getKey(), entry.getValue());
                db.write(batch);
            }
        }
        return repaired;
    }
}
