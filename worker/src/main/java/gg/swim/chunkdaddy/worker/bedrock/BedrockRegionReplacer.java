package gg.swim.chunkdaddy.worker.bedrock;

import com.google.gson.JsonObject;
import gg.swim.chunkdaddy.worker.util.ChunkRect;
import org.iq80.leveldb.*;
import org.iq80.leveldb.impl.Iq80DBFactory;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/** Full-footprint replacement, including absent/air columns, without re-encoding unrelated data. */
public final class BedrockRegionReplacer {
    private BedrockRegionReplacer() {}

    private static DB open(Path world, boolean create) throws IOException {
        if (!create && !Files.isDirectory(world.resolve("db"))) throw new IOException("Missing database: " + world);
        return Iq80DBFactory.factory.open(world.resolve("db").toFile(), VoidCleaner.databaseOptions(create));
    }

    private static boolean inside(byte[] bytes, List<ChunkRect> rectangles) {
        var key = VoidCleaner.parse(bytes);
        return key != null && key.dimension() == 0 && rectangles.stream().anyMatch(r ->
                r.contains((int) (key.column() >> 32), (int) key.column()));
    }

    /** A small, disposable reference world for decoded terrain matching; never copies packs/actors. */
    public static void slice(Path source, Path destination, ChunkRect rectangle) throws IOException {
        if (Files.exists(destination)) throw new IOException("Slice destination exists");
        Files.createDirectories(destination);
        Files.copy(source.resolve("level.dat"), destination.resolve("level.dat"));
        try (DB input = open(source, false); DB output = open(destination, true); DBIterator it = input.iterator()) {
            WriteBatch batch = output.createWriteBatch();
            int count = 0;
            try {
                for (it.seekToFirst(); it.hasNext();) {
                    var entry = it.next();
                    var key = VoidCleaner.parse(entry.getKey());
                    if (!inside(entry.getKey(), List.of(rectangle)) || key.actorDigest() || key.tag() == 0x32) continue;
                    batch.put(entry.getKey(), entry.getValue());
                    if ((++count & 4095) == 0) { output.write(batch); batch.close(); batch = output.createWriteBatch(); }
                }
                output.write(batch);
            } finally { batch.close(); }
        }
    }

    public static JsonObject replace(Path base, Path overlay, Path destination, List<ChunkRect> rectangles) throws Exception {
        if (rectangles.isEmpty()) throw new IOException("At least one replacement footprint is required");
        for (int i = 0; i < rectangles.size(); i++) for (int j = 0; j < i; j++)
            if (rectangles.get(i).intersects(rectangles.get(j))) throw new IOException("Overlapping replacement footprints");
        if (Files.exists(destination)) throw new IOException("Replacement destination already exists");
        if (BedrockChunkMerger.networkVersion(overlay) > BedrockChunkMerger.networkVersion(base))
            throw new IOException("Overlay storage profile is newer than the base");
        long overlayCount = 0;
        long[] perRegion = new long[rectangles.size()];
        // Reject spillover and ordinary entity identities before creating any output.
        try (DB input = open(overlay, false); DBIterator it = input.iterator()) {
            for (it.seekToFirst(); it.hasNext();) {
                var entry = it.next();
                var key = VoidCleaner.parse(entry.getKey());
                if (!inside(entry.getKey(), rectangles) || key.tag() == 0x32
                        || (key.actorDigest() && entry.getValue().length != 0))
                    throw new IOException("Overlay must contain only actor-free Overworld chunk records inside the replacement footprints");
                for (int i = 0; i < rectangles.size(); i++) if (rectangles.get(i).contains((int) (key.column() >> 32), (int) key.column())) perRegion[i]++;
                overlayCount++;
            }
        }
        for (long count : perRegion) if (count == 0) throw new IOException("An overlay footprint has no records; refusing a likely incomplete export");
        Files.createDirectories(destination);
        try (var files = Files.list(base)) {
            for (Path file : files.toList()) {
                if (file.getFileName().toString().equals("db")) continue;
                Path target = destination.resolve(file.getFileName());
                if (Files.isDirectory(file)) WorldPackager.copyRecursively(file, target);
                else Files.copy(file, target);
            }
        }
        long kept = 0;
        long[] removed = {0};
        // Merge the two sorted streams before writing. Writing all base records followed
        // by overlay records creates overlapping table ranges and bloats large databases.
        try (DB output = open(destination, true); DB original = open(base, false); DB additions = open(overlay, false);
             DBIterator baseIterator = original.iterator(); DBIterator overlayIterator = additions.iterator()) {
            baseIterator.seekToFirst(); overlayIterator.seekToFirst();
            Map.Entry<byte[], byte[]> oldEntry = nextRetained(baseIterator, rectangles, removed);
            Map.Entry<byte[], byte[]> newEntry = overlayIterator.hasNext() ? overlayIterator.next() : null;
            WriteBatch batch = output.createWriteBatch();
            long writes = 0;
            try {
                while (oldEntry != null || newEntry != null) {
                    if (oldEntry != null && newEntry != null && compareKeys(oldEntry.getKey(), newEntry.getKey()) == 0)
                        throw new IOException("Overlay collides with a retained base record");
                    if (oldEntry != null && (newEntry == null || compareKeys(oldEntry.getKey(), newEntry.getKey()) < 0)) {
                        batch.put(oldEntry.getKey(), oldEntry.getValue()); kept++;
                        oldEntry = nextRetained(baseIterator, rectangles, removed);
                    } else {
                        batch.put(newEntry.getKey(), newEntry.getValue());
                        newEntry = overlayIterator.hasNext() ? overlayIterator.next() : null;
                    }
                    if ((++writes & 4095) == 0) { output.write(batch); batch.close(); batch = output.createWriteBatch(); }
                }
                output.write(batch);
            } finally { batch.close(); }
        }
        // Reopen independently. Compare all retained and inserted bytes and total key count;
        // this also proves stale chest entities/subchunks absent from the overlay were deleted.
        long verified = 0;
        try (DB output = open(destination, false)) {
            for (Path path : List.of(base, overlay)) {
                try (DB input = open(path, false); DBIterator it = input.iterator()) {
                    for (it.seekToFirst(); it.hasNext();) {
                        var entry = it.next();
                        if (path.equals(base) && inside(entry.getKey(), rectangles)) continue;
                        if (!Arrays.equals(entry.getValue(), output.get(entry.getKey()))) throw new IOException("Replacement record verification failed");
                        verified++;
                    }
                }
            }
            long count = 0;
            try (DBIterator it = output.iterator()) { for (it.seekToFirst(); it.hasNext(); it.next()) count++; }
            if (count != verified || count != kept + overlayCount) throw new IOException("Unexpected records after replacement");
        }
        JsonObject result = new JsonObject();
        result.addProperty("baseRecordsPreserved", kept);
        result.addProperty("oldFootprintRecordsRemoved", removed[0]);
        result.addProperty("overlayRecordsVerified", overlayCount);
        result.addProperty("replacementFootprints", rectangles.size());
        result.addProperty("baseLevelDatPreserved", Arrays.equals(Files.readAllBytes(base.resolve("level.dat")), Files.readAllBytes(destination.resolve("level.dat"))));
        result.addProperty("overlayFilesCopied", false);
        return result;
    }

    private static Map.Entry<byte[], byte[]> nextRetained(DBIterator iterator, List<ChunkRect> rectangles, long[] removed) throws IOException {
        while (iterator.hasNext()) {
            var entry = iterator.next();
            if (!inside(entry.getKey(), rectangles)) return entry;
            var key = VoidCleaner.parse(entry.getKey());
            if ((key.actorDigest() || key.tag() == 0x32) && entry.getValue().length != 0)
                throw new IOException("Base replacement footprint contains ordinary entities; remove them before terrain replacement");
            removed[0]++;
        }
        return null;
    }

    private static int compareKeys(byte[] a, byte[] b) {
        return Arrays.compareUnsigned(a, b);
    }
}
