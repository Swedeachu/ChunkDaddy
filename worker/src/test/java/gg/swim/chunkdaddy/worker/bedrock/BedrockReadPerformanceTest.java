package gg.swim.chunkdaddy.worker.bedrock;

import com.hivemc.chunker.conversion.WorldConverter;
import com.hivemc.chunker.conversion.encoding.bedrock.base.reader.BedrockColumnReader;
import com.hivemc.chunker.conversion.encoding.bedrock.base.reader.BedrockChunkReader;
import com.hivemc.chunker.conversion.encoding.bedrock.util.LevelDBChunkType;
import com.hivemc.chunker.conversion.encoding.bedrock.util.LevelDBKey;
import com.hivemc.chunker.conversion.handlers.pretransform.Edge;
import com.hivemc.chunker.conversion.handlers.pretransform.manager.PreTransformManager;
import com.hivemc.chunker.conversion.handlers.pretransform.manager.handler.block.BlockPreTransformHandler;
import com.hivemc.chunker.conversion.intermediate.column.ChunkerColumn;
import com.hivemc.chunker.conversion.intermediate.column.chunk.ChunkCoordPair;
import com.hivemc.chunker.conversion.intermediate.column.chunk.ChunkerChunk;
import com.hivemc.chunker.conversion.intermediate.column.chunk.identifier.ChunkerBlockIdentifier;
import com.hivemc.chunker.conversion.intermediate.column.chunk.identifier.type.block.ChunkerBlockType;
import com.hivemc.chunker.conversion.intermediate.column.chunk.identifier.type.block.ChunkerCustomBlockType;
import com.hivemc.chunker.conversion.intermediate.column.chunk.identifier.type.block.ChunkerVanillaBlockType;
import com.hivemc.chunker.conversion.intermediate.world.Dimension;
import com.hivemc.chunker.scheduling.task.Environment;
import com.hivemc.chunker.scheduling.task.Task;
import com.hivemc.chunker.scheduling.task.TaskWeight;
import gg.swim.chunkdaddy.worker.document.ColumnOps;
import org.iq80.leveldb.DB;
import org.iq80.leveldb.impl.Iq80DBFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class BedrockReadPerformanceTest {
    @TempDir Path directory;

    @Test void progressiveSchedulingReadsNearestRegionsAndReprioritizesWithoutDroppingAny() {
        class Policy extends WorldConverter implements com.hivemc.chunker.conversion.ProgressiveReadPolicy {
            ChunkCoordPair focus = new ChunkCoordPair(5 * 32, 0);
            int indexedColumns;
            boolean stopAfterFirst, stopping;
            Policy() { super(UUID.randomUUID()); }
            @Override public ChunkCoordPair focus() { return focus; }
            @Override public boolean stopping() { return stopping; }
            @Override public void indexed(Dimension dimension, Map<com.hivemc.chunker.conversion.intermediate.column.chunk.RegionCoordPair, Set<ChunkCoordPair>> regions) {
                indexedColumns = regions.values().stream().mapToInt(Set::size).sum();
            }
        }
        var policy = new Policy();
        var order = new ArrayList<Integer>();
        var flushed = new ArrayList<Integer>();
        var regions = new LinkedHashMap<com.hivemc.chunker.conversion.intermediate.column.chunk.RegionCoordPair, Set<ChunkCoordPair>>();
        for (int x : new int[]{0, 5, 10}) regions.put(new com.hivemc.chunker.conversion.intermediate.column.chunk.RegionCoordPair(x, 0), Set.of(new ChunkCoordPair(x * 32, 0)));
        var reader = new com.hivemc.chunker.conversion.encoding.bedrock.base.reader.BedrockWorldReader(null, policy, null, regions, Dimension.OVERWORLD) {
            @Override public void readRegion(Map.Entry<com.hivemc.chunker.conversion.intermediate.column.chunk.RegionCoordPair, Set<ChunkCoordPair>> region,
                                             com.hivemc.chunker.conversion.handlers.ColumnConversionHandler handler) {
                order.add(region.getKey().regionX());
                policy.focus = new ChunkCoordPair(10 * 32, 0);
                if (policy.stopAfterFirst) policy.stopping = true;
            }
        };
        var handler = new com.hivemc.chunker.conversion.handlers.ColumnConversionHandler() {
            @Override public void convertColumn(ChunkerColumn column) {}
            @Override public void flushRegion(com.hivemc.chunker.conversion.intermediate.column.chunk.RegionCoordPair region) { flushed.add(region.regionX()); }
            @Override public void flushColumns() {}
        };
        Environment environment = Task.environment("Progressive regions", 2, error -> { throw new AssertionError(error); }, null);
        try (environment) {
            Task.async("Schedule", TaskWeight.NORMAL, () -> { reader.readRegions(regions, handler); return null; });
        }
        environment.future().join();
        assertEquals(3, policy.indexedColumns);
        assertEquals(List.of(5, 10, 0), order);
        assertEquals(order, flushed);
        policy.focus = new ChunkCoordPair(5 * 32, 0);
        policy.stopAfterFirst = true;
        order.clear();
        flushed.clear();
        Environment stopped = Task.environment("Stopped progressive regions", 2, error -> { throw new AssertionError(error); }, null);
        try (stopped) {
            Task.async("Schedule", TaskWeight.NORMAL, () -> { reader.readRegions(regions, handler); return null; });
        }
        stopped.future().join();
        assertEquals(List.of(5), order, "Closing must drain active work without scheduling remaining regions");
        assertEquals(order, flushed);
    }

    @Test void readsOnlyExistingSubchunksAndStaysInsideColumnAndDimension() throws Exception {
        try (DB db = Iq80DBFactory.factory.open(directory.resolve("db").toFile(), VoidCleaner.databaseOptions(true))) {
            for (Dimension readDimension : List.of(Dimension.OVERWORLD, Dimension.NETHER)) {
                var position = new ChunkCoordPair(-7, 260);
                int[] expected = {-64, -4, 0, 3, 63};
                for (int y : new int[]{-65, -64, -4, 0, 3, 63, 64})
                    db.put(LevelDBKey.key(readDimension, position, (byte) y, LevelDBChunkType.SUB_CHUNK_PREFIX), new byte[]{(byte) y});
                db.put(LevelDBKey.key(readDimension, new ChunkCoordPair(-7, 261), (byte) 0, LevelDBChunkType.SUB_CHUNK_PREFIX), new byte[]{99});
                db.put(LevelDBKey.key(readDimension, position, LevelDBChunkType.BLOCK_ENTITY), new byte[]{99});
                AtomicInteger gets = new AtomicInteger(), iterators = new AtomicInteger();
                DB counted = (DB) Proxy.newProxyInstance(DB.class.getClassLoader(), new Class<?>[]{DB.class}, (proxy, method, args) -> {
                    if (method.getName().equals("get")) gets.incrementAndGet();
                    if (method.getName().equals("iterator")) iterators.incrementAndGet();
                    return method.invoke(db, args);
                });
                Map<Byte, Byte> read = new ConcurrentHashMap<>();
                var jobConverter = new WorldConverter(UUID.randomUUID());
                class Reader extends BedrockColumnReader {
                    Reader() { super(null, jobConverter, counted, readDimension, position); }
                    void readExisting(ChunkerColumn column) { readChunks(column); }
                    @Override public BedrockChunkReader createChunkReader(ChunkerChunk chunk) {
                        return new BedrockChunkReader(null, jobConverter, readDimension, chunk) {
                            @Override public void readChunk(byte[] value) { read.put(chunk.getY(), value[0]); }
                        };
                    }
                }
                var column = new ChunkerColumn(position);
                Environment environment = Task.environment("Sparse read", 2, error -> { throw new AssertionError(error); }, null);
                try (environment) {
                    Task.async("Read", TaskWeight.NORMAL, () -> { new Reader().readExisting(column); return null; });
                }
                environment.future().join();
                assertEquals(expected.length, read.size());
                assertEquals(expected.length, column.getChunks().size());
                for (int y : expected) assertEquals((byte) y, read.get((byte) y));
                assertEquals(0, gets.get(), "Sparse columns must not probe missing heights");
                assertEquals(1, iterators.get(), "One bounded seek replaces 128 queries");
            }
        }
    }

    @Test void vanillaAndCustomHandlersAndReregistrationKeepTheirBehavior() {
        class Manager extends PreTransformManager {
            Manager() { super(TargetProfile.defaultProfile().version()); }
            @Override protected void registerHandlers(com.hivemc.chunker.conversion.encoding.base.Version version) {}
            void add(BlockPreTransformHandler handler, ChunkerBlockType... types) { registerHandler(handler, List.of(types)); }
            void replace(BlockPreTransformHandler handler, ChunkerBlockType type) { registerHandler(handler, type); }
        }
        var custom = new ChunkerCustomBlockType("test:custom", Set.of());
        var manager = new Manager();
        BlockPreTransformHandler dirt = handler(ChunkerVanillaBlockType.DIRT);
        manager.add(dirt, ChunkerVanillaBlockType.STONE, custom);
        manager.replace(handler(ChunkerVanillaBlockType.GRASS_BLOCK), ChunkerVanillaBlockType.STONE);
        var column = new ChunkerColumn(new ChunkCoordPair(0, 0));
        var palette = ColumnOps.writeableChunk(column, (byte) 0);
        palette.set(0, 0, 0, new ChunkerBlockIdentifier(ChunkerVanillaBlockType.STONE));
        palette.set(1, 0, 0, new ChunkerBlockIdentifier(custom));
        palette.set(2, 0, 0, new ChunkerBlockIdentifier(ChunkerVanillaBlockType.SAND));
        manager.solve(column, true);
        assertEquals(ChunkerVanillaBlockType.GRASS_BLOCK, column.getBlock(0, 0, 0).getType());
        assertEquals(ChunkerVanillaBlockType.DIRT, column.getBlock(1, 0, 0).getType());
        assertEquals(ChunkerVanillaBlockType.SAND, column.getBlock(2, 0, 0).getType());
    }

    private static BlockPreTransformHandler handler(ChunkerBlockType replacement) {
        return new BlockPreTransformHandler() {
            @Override public Set<Edge> getRequiredEdges(ChunkerColumn column, int x, int y, int z, ChunkerBlockIdentifier identifier) { return Set.of(); }
            @Override public ChunkerBlockIdentifier handle(ChunkerColumn column, Map<Edge, ChunkerColumn> neighbours, int x, int y, int z, ChunkerBlockIdentifier identifier) {
                return new ChunkerBlockIdentifier(replacement);
            }
        };
    }
}
