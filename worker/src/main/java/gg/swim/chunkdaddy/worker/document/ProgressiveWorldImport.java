package gg.swim.chunkdaddy.worker.document;

import com.hivemc.chunker.conversion.ProgressiveReadPolicy;
import com.hivemc.chunker.conversion.WorldConverter;
import com.hivemc.chunker.conversion.encoding.bedrock.base.reader.BedrockWorldReader;
import com.hivemc.chunker.conversion.intermediate.column.ChunkerColumn;
import com.hivemc.chunker.conversion.handlers.ColumnConversionHandler;
import com.hivemc.chunker.nbt.tags.Tag;
import com.hivemc.chunker.scheduling.task.Task;
import com.hivemc.chunker.scheduling.task.TaskWeight;
import org.iq80.leveldb.DB;
import com.hivemc.chunker.conversion.intermediate.column.chunk.ChunkCoordPair;
import com.hivemc.chunker.conversion.intermediate.column.chunk.RegionCoordPair;
import com.hivemc.chunker.conversion.intermediate.world.Dimension;
import gg.swim.chunkdaddy.worker.bedrock.TargetProfile;
import gg.swim.chunkdaddy.worker.util.Checked;
import gg.swim.chunkdaddy.worker.util.ChunkRect;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;

import java.nio.file.Path;
import java.util.Map;
import java.util.AbstractMap;
import java.util.HashMap;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

/** Independent spawn preview, then fully converted columns; editing waits for atomic publication. */
public final class ProgressiveWorldImport {
    private final Path source;
    private final CaptureLevelWriter writer;
    private final Map<Long, ChunkerColumn> earlyColumns = new ConcurrentHashMap<>();
    private final Map<Long, ChunkerColumn> previewColumns;
    private final Policy converter = new Policy();
    private final CompletableFuture<WorldImporter.Result> completion = new CompletableFuture<>();
    private final WorldSnapshot view;
    private final Consumer<ProgressiveWorldImport> changed;
    private volatile LongOpenHashSet sourceKeys = new LongOpenHashSet();
    private volatile boolean indexed;
    private volatile ChunkRect bounds;
    private volatile ChunkRect initialView;
    private volatile boolean notifications;
    private volatile boolean closed;
    private volatile Throwable failure;

    public ProgressiveWorldImport(Path source, SourceInspector.Edition edition, TargetProfile profile,
                                  WorldDocument document, Consumer<ProgressiveWorldImport> changed) {
        writer = new CaptureLevelWriter(profile.version(), Dimension.OVERWORLD);
        this.source = source;
        this.changed = changed;
        previewColumns = new AbstractMap<>() {
            @Override public ChunkerColumn get(Object key) {
                ChunkerColumn converted = writer.columns().get(key);
                return converted == null ? earlyColumns.get(key) : converted;
            }
            @Override public boolean containsKey(Object key) { return writer.columns().containsKey(key) || earlyColumns.containsKey(key); }
            @Override public Set<Entry<Long, ChunkerColumn>> entrySet() {
                var combined = new HashMap<>(earlyColumns);
                combined.putAll(writer.columns());
                return combined.entrySet();
            }
        };
        view = WorldSnapshot.loadingView(previewColumns);
        Thread.ofPlatform().daemon().name("chunkdaddy-world-load-" + document.id()).start(() -> {
            try {
                var result = WorldImporter.importWorld(source, edition, profile, converter, count -> {
                    if (notifications && !closed) changed.accept(this);
                }, writer);
                if (closed) throw new IllegalStateException("World was closed while loading");
                synchronized (document) {
                    document.importColumns(result.columns());
                    document.adoptSourceLevel(result.sourceSettings(), result.sourceLevelData(), result.bedrockSource());
                    document.markSaved();
                }
                completion.complete(result);
                earlyColumns.clear();
            } catch (Throwable error) {
                failure = error;
                completion.completeExceptionally(error);
            } finally {
                if (notifications && !closed) changed.accept(this);
            }
        });
    }

    /** Wait for useful, complete columns near spawn, rather than the entire world. */
    public void awaitInitialView() throws Exception {
        while (!completion.isDone() && (bounds == null || Math.max(writer.columns().size(), earlyColumns.size()) < 64)) {
            try { completion.get(50, TimeUnit.MILLISECONDS); }
            catch (TimeoutException ignored) { /* the first region is still decoding */ }
        }
        if (completion.isCompletedExceptionally()) completion.join();
    }

    public WorldImporter.Result awaitComplete(Runnable waiting) throws Exception {
        while (!completion.isDone()) {
            waiting.run();
            try { completion.get(1, TimeUnit.SECONDS); }
            catch (TimeoutException ignored) { /* keep the operation's progress active */ }
        }
        return completion.join();
    }

    public void enableNotifications() {
        notifications = true;
        // Completion may race the first reply; always deliver a final state afterwards.
        if (completion.isDone() && !closed) changed.accept(this);
    }
    public boolean loading() { return !completion.isDone(); }
    public Throwable failure() { return failure; }
    public int loadedColumns() { return Math.max(writer.columns().size(), earlyColumns.size()); }
    public int totalColumns() { return sourceKeys.size(); }
    public ChunkRect bounds() { return bounds; }
    public ChunkRect initialView() { return initialView; }
    public WorldSnapshot preview() { return view; }
    public CaptureLevelWriter writer() { return writer; }
    public WorldConverter converter() { return converter; }
    public boolean pending(long key) { return !previewColumns.containsKey(key) && (!indexed || sourceKeys.contains(key)); }
    public void focus(int x, int z) { converter.focus = new ChunkCoordPair(x, z); }
    public void close() { closed = true; converter.cancel(null); }

    private final class Policy extends WorldConverter implements ProgressiveReadPolicy {
        private volatile ChunkCoordPair focus = new ChunkCoordPair(0, 0);
        Policy() { super(UUID.randomUUID()); }
        @Override public ChunkCoordPair focus() { return focus; }
        @Override public boolean stopping() { return closed; }
        @Override public CompletableFuture<Void> cancel(Throwable fatalException) {
            if (fatalException != null) return super.cancel(fatalException);
            // Upstream cancellation can free the DB before its running iterator stops.
            // Stop scheduling cooperatively and let the existing task tree drain first.
            closed = true;
            return completion.handle((result, error) -> null);
        }
        @Override public void preview(DB database, BedrockWorldReader reader) {
            try {
                var level = Tag.readBedrockNBT(source.resolve("level.dat").toFile());
                int spawnX = level == null ? 0 : level.getInt("SpawnX", 0) >> 4;
                int spawnZ = level == null ? 0 : level.getInt("SpawnZ", 0) >> 4;
                // Keep this speculative view small. It never enters the capture writer
                // or editable document; the normal pipeline independently converts it.
                synchronized (ProgressiveWorldImport.this) {
                    if (!indexed) {
                        initialView = new ChunkRect(spawnX - 8, spawnZ - 8, spawnX + 8, spawnZ + 8);
                        bounds = initialView;
                        focus = new ChunkCoordPair(spawnX, spawnZ);
                    }
                }
                ColumnConversionHandler capture = new ColumnConversionHandler() {
                    @Override public void convertColumn(ChunkerColumn column) {
                        earlyColumns.put(Checked.chunkKey(column.getPosition().chunkX(), column.getPosition().chunkZ()), column);
                    }
                    @Override public void flushRegion(RegionCoordPair region) {}
                    @Override public void flushColumns() {}
                };
                // Small batches keep the initial preview ahead of the background scan.
                for (int radius = 0; radius <= 8; radius++) {
                    for (int dx = -radius; dx <= radius; dx++) for (int dz = -radius; dz <= radius; dz++) {
                        if (closed) return;
                        if (Math.max(Math.abs(dx), Math.abs(dz)) != radius) continue;
                        var position = new ChunkCoordPair(spawnX + dx, spawnZ + dz);
                        if (sourceColumnExists(database, position))
                            Task.async("Reading spawn preview column", TaskWeight.HIGHER,
                                    () -> reader.createColumnReader(position).readColumn(capture));
                    }
                }
            } catch (Exception error) {
                // A preview failure must not weaken or replace the authoritative import.
                System.err.println("Spawn preview unavailable; waiting for converted chunks: " + error.getMessage());
            }
        }
        @Override public void indexed(Dimension dimension, Map<RegionCoordPair, Set<ChunkCoordPair>> regions) {
            if (dimension != Dimension.OVERWORLD) return;
            var settings = ProgressiveWorldImport.this.writer.capturedSettings();
            int spawnX = settings == null ? 0 : settings.SpawnX >> 4;
            int spawnZ = settings == null ? 0 : settings.SpawnZ >> 4;
            LongOpenHashSet keys = new LongOpenHashSet();
            int minX = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
            long nearestDistance = Long.MAX_VALUE;
            ChunkCoordPair nearest = new ChunkCoordPair(spawnX, spawnZ);
            for (var columns : regions.values()) for (var position : columns) {
                int x = position.chunkX(), z = position.chunkZ();
                keys.add(Checked.chunkKey(x, z));
                minX = Math.min(minX, x); minZ = Math.min(minZ, z);
                maxX = Math.max(maxX, x); maxZ = Math.max(maxZ, z);
                long distance = Math.abs((long) x - spawnX) + Math.abs((long) z - spawnZ);
                if (distance < nearestDistance) { nearestDistance = distance; nearest = position; }
            }
            synchronized (ProgressiveWorldImport.this) {
                sourceKeys = keys; // Publish the complete, immutable index before preview requests.
                if (earlyColumns.isEmpty()) {
                    focus = nearest;
                    if (!keys.isEmpty()) initialView = new ChunkRect(Math.max(minX, nearest.chunkX() - 16), Math.max(minZ, nearest.chunkZ() - 16),
                            Math.min(maxX, nearest.chunkX() + 16), Math.min(maxZ, nearest.chunkZ() + 16));
                }
                if (!keys.isEmpty()) bounds = new ChunkRect(minX, minZ, maxX, maxZ);
                indexed = true;
            }
        }
    }

    /** Check exact Overworld record keys; never invent content in absent columns. */
    static boolean sourceColumnExists(DB database, ChunkCoordPair position) throws Exception {
        byte[] prefix = java.nio.ByteBuffer.allocate(8).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                .putInt(position.chunkX()).putInt(position.chunkZ()).array();
        try (var iterator = database.iterator()) {
            iterator.seek(prefix);
            while (iterator.hasNext()) {
                byte[] key = iterator.next().getKey();
                if (key.length < 8 || !java.util.Arrays.equals(prefix, 0, 8, key, 0, 8)) break;
                if (key.length != 9 && key.length != 10) continue;
                int type = key[8] & 255;
                if (type == 0x2d || type == 0x2b || type == 0x2f || type == 0x31 || type == 0x32) return true;
            }
        }
        return false;
    }
}
