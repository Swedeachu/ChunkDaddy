package gg.swim.chunkdaddy.worker.bedrock;

import com.google.gson.JsonObject;
import com.hivemc.chunker.nbt.tags.Tag;
import com.hivemc.chunker.nbt.tags.collection.CompoundTag;
import gg.swim.chunkdaddy.worker.util.ChunkRect;
import org.iq80.leveldb.*;
import org.iq80.leveldb.impl.Iq80DBFactory;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.*;
import java.util.*;

/** Adds already translated/exported chunk records without round-tripping the base world. */
public final class BedrockChunkMerger {
    private record Column(int dimension, long position) {}

    public static JsonObject inspect(Path world) throws IOException {
        Set<Column> columns = new HashSet<>();
        long records = 0, worldRecords = 0;
        int minX = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        try (DB db = open(world, false); DBIterator it = db.iterator()) {
            for (it.seekToFirst(); it.hasNext();) {
                var entry = it.next();
                records++;
                var key = VoidCleaner.parse(entry.getKey());
                if (key == null) { worldRecords++; continue; }
                columns.add(new Column(key.dimension(), key.column()));
                if (key.dimension() == 0) {
                    int x = (int) (key.column() >> 32), z = (int) key.column();
                    minX = Math.min(minX, x); minZ = Math.min(minZ, z);
                    maxX = Math.max(maxX, x); maxZ = Math.max(maxZ, z);
                }
            }
        }
        JsonObject result = new JsonObject();
        result.addProperty("records", records);
        result.addProperty("worldRecords", worldRecords);
        result.addProperty("columns", columns.size());
        if (minX != Integer.MAX_VALUE) {
            JsonObject bounds = new JsonObject();
            bounds.addProperty("minChunkX", minX); bounds.addProperty("minChunkZ", minZ);
            bounds.addProperty("maxChunkX", maxX); bounds.addProperty("maxChunkZ", maxZ);
            result.add("overworldBounds", bounds);
        }
        return result;
    }

    /** Destination must be fresh staging. Only chunk records from the overlay are accepted. */
    public static JsonObject merge(Path base, Path overlay, Path destination, ChunkRect footprint) throws Exception {
        if (Files.exists(destination)) throw new IOException("Destination already exists: " + destination);
        if (networkVersion(overlay) > networkVersion(base))
            throw new IOException("Overlay requires a newer storage profile than the base; re-export the overlay for the base version");
        Set<Column> added = new HashSet<>();
        long overlayRecords = 0;
        try (DB source = open(overlay, false); DBIterator it = source.iterator()) {
            for (it.seekToFirst(); it.hasNext();) {
                var entry = it.next();
                var key = VoidCleaner.parse(entry.getKey());
                if (key == null || key.dimension() != 0)
                    throw new IOException("Overlay must contain only exported overworld chunk records");
                // Exporters emit empty digp records even when no actors were exported.
                // Nonempty references require actor records and ID remapping, which this
                // terrain/block-entity merge deliberately does not attempt.
                if (key.actorDigest() && entry.getValue().length != 0)
                    throw new IOException("Overlay has actor references; export without ordinary entities before merging");
                int x = (int) (key.column() >> 32), z = (int) key.column();
                if (!footprint.contains(x, z)) throw new IOException("Overlay chunk outside placement footprint: " + x + "," + z);
                added.add(new Column(0, key.column()));
                overlayRecords++;
            }
        }
        if (overlayRecords == 0) throw new IOException("Overlay has no chunk records");
        // Reject any base column in the whole placement footprint, including entity-only columns.
        try (DB source = open(base, false); DBIterator it = source.iterator()) {
            for (it.seekToFirst(); it.hasNext();) {
                var key = VoidCleaner.parse(it.next().getKey());
                if (key != null && key.dimension() == 0 && footprint.contains((int) (key.column() >> 32), (int) key.column()))
                    throw new IOException("Placement overlaps an existing base column");
            }
        }
        Files.createDirectories(destination);
        // Keep only base files. Overlay resource packs, settings and manifests are never copied.
        try (var files = Files.list(base)) {
            for (Path file : files.toList()) {
                if (file.getFileName().toString().equals("db")) continue;
                Path target = destination.resolve(file.getFileName());
                if (Files.isDirectory(file)) WorldPackager.copyRecursively(file, target);
                else Files.copy(file, target);
            }
        }
        long baseRecords;
        try (DB output = open(destination, true)) {
            baseRecords = copyRecords(base, output);
            long copied = copyRecords(overlay, output);
            if (copied != overlayRecords) throw new IOException("Overlay changed while merging");
        }
        // Reopen the output and independently compare EVERY source key/value, including world data.
        long verifiedBase, verifiedOverlay;
        try (DB output = open(destination, false)) {
            verifiedBase = verifyRecords(base, output);
            verifiedOverlay = verifyRecords(overlay, output);
            long count = 0;
            try (DBIterator it = output.iterator()) { for (it.seekToFirst(); it.hasNext(); it.next()) count++; }
            if (count != baseRecords + overlayRecords) throw new IOException("Unexpected output database records");
        }
        JsonObject result = new JsonObject();
        result.addProperty("baseRecordsPreserved", verifiedBase);
        result.addProperty("overlayRecordsVerified", verifiedOverlay);
        result.addProperty("addedColumns", added.size());
        result.addProperty("baseLevelDatPreserved", Arrays.equals(Files.readAllBytes(base.resolve("level.dat")), Files.readAllBytes(destination.resolve("level.dat"))));
        result.addProperty("overlayFilesCopied", false);
        return result;
    }

    private static int networkVersion(Path world) throws Exception {
        byte[] data = Files.readAllBytes(world.resolve("level.dat"));
        ByteBuffer input = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        input.position(8);
        CompoundTag level = Tag.readBedrockNBT(input);
        return level.getInt("NetworkVersion");
    }

    private static DB open(Path world, boolean create) throws IOException {
        Path database = world.resolve("db");
        if (!create && !Files.isDirectory(database)) throw new IOException("Missing world database: " + database);
        return Iq80DBFactory.factory.open(database.toFile(), VoidCleaner.databaseOptions(create));
    }

    private static long copyRecords(Path source, DB destination) throws IOException {
        long count = 0;
        try (DB input = open(source, false); DBIterator it = input.iterator()) {
            WriteBatch batch = destination.createWriteBatch();
            try {
                for (it.seekToFirst(); it.hasNext();) {
                    var entry = it.next();
                    batch.put(entry.getKey(), entry.getValue());
                    if ((++count & 4095) == 0) {
                        destination.write(batch); batch.close(); batch = destination.createWriteBatch();
                    }
                }
                destination.write(batch);
            } finally { batch.close(); }
        }
        return count;
    }

    private static long verifyRecords(Path source, DB destination) throws IOException {
        long count = 0;
        try (DB input = open(source, false); DBIterator it = input.iterator()) {
            for (it.seekToFirst(); it.hasNext();) {
                var entry = it.next();
                if (!Arrays.equals(entry.getValue(), destination.get(entry.getKey())))
                    throw new IOException("Source record changed in merged output");
                count++;
            }
        }
        return count;
    }
}
