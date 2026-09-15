package gg.swim.chunkdaddy.worker.bedrock;

import com.hivemc.chunker.conversion.encoding.EncodingType;
import com.hivemc.chunker.conversion.encoding.base.Version;
import com.hivemc.chunker.conversion.encoding.base.reader.LevelReader;
import com.hivemc.chunker.conversion.handlers.ColumnConversionHandler;
import com.hivemc.chunker.conversion.handlers.LevelConversionHandler;
import com.hivemc.chunker.conversion.handlers.WorldConversionHandler;
import com.hivemc.chunker.conversion.intermediate.column.ChunkerColumn;
import com.hivemc.chunker.conversion.intermediate.column.biome.ChunkerBiome;
import com.hivemc.chunker.conversion.intermediate.column.chunk.RegionCoordPair;
import com.hivemc.chunker.conversion.intermediate.level.ChunkerLevel;
import com.hivemc.chunker.conversion.intermediate.level.ChunkerLevelSettings;
import com.hivemc.chunker.conversion.intermediate.world.ChunkerWorld;
import com.hivemc.chunker.conversion.intermediate.world.Dimension;
import com.hivemc.chunker.scheduling.task.Task;
import com.hivemc.chunker.scheduling.task.TaskWeight;
import gg.swim.chunkdaddy.worker.document.ColumnComposer;
import gg.swim.chunkdaddy.worker.document.WorldSnapshot;
import gg.swim.chunkdaddy.worker.util.ChunkRect;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongConsumer;

/**
 * Feeds a composed document into Chunker's writer pipeline as though it were a world
 * being read from disk.
 *
 * <p>This is where the generated-void contract is honoured: the reader walks the entire
 * export rectangle, region by region, and emits a column for every position in it.
 * A column either carries content or is an explicit empty column; nothing is omitted on
 * the assumption that the server will generate void later. Emitting region by region also
 * bounds the working set, and the region flushes let the pipeline release the columns it
 * held for pre-transform clustering.
 */
public final class ComposedLevelReader implements LevelReader {
    private final WorldSnapshot snapshot;
    private final ColumnComposer composer;
    private final ChunkRect exportRectangle;
    private final TargetProfile profile;
    private final ChunkerLevelSettings settings;
    private final Dimension dimension;
    private final LongConsumer progress;

    private final AtomicLong emittedContentColumns = new AtomicLong();
    private final AtomicLong emittedVoidColumns = new AtomicLong();
    private long lastReportedColumns;

    public ComposedLevelReader(WorldSnapshot snapshot,
                               ColumnComposer composer,
                               ChunkRect exportRectangle,
                               TargetProfile profile,
                               ChunkerLevelSettings settings,
                               Dimension dimension,
                               LongConsumer progress) {
        this.snapshot = snapshot;
        this.composer = composer;
        this.exportRectangle = exportRectangle;
        this.profile = profile;
        this.settings = settings;
        this.dimension = dimension;
        this.progress = progress;
    }

    @Override
    public EncodingType getEncodingType() {
        // The document is already in Bedrock's target frame; the reader's encoding type is
        // only consulted when reading level settings from NBT, which we do not do.
        return EncodingType.BEDROCK;
    }

    @Override
    public Version getVersion() {
        return profile.version();
    }

    @Override
    public Set<ChunkerBiome.ChunkerVanillaBiome> getSupportedBiomes() {
        return Set.of(dimension.getFallbackBiome());
    }

    public long emittedContentColumns() {
        return emittedContentColumns.get();
    }

    public long emittedVoidColumns() {
        return emittedVoidColumns.get();
    }

    public long emittedColumns() {
        return emittedContentColumns.get() + emittedVoidColumns.get();
    }

    @Override
    public void readLevel(@NotNull LevelConversionHandler levelConversionHandler) {
        ChunkerLevel level = new ChunkerLevel();
        level.setSettings(settings);
        level.setPlayer(null);
        // Chunker filters these collections in place during dimension remapping.
        level.setMaps(new ArrayList<>());
        level.setPortals(new ArrayList<>());
        level.setOriginalLevelData(null);

        levelConversionHandler
                .convertLevel(level)
                .thenConsume("Writing worlds", TaskWeight.HIGHEST, this::readWorlds)
                .then("Flushing Level", TaskWeight.MEDIUM, levelConversionHandler::flushLevel);
    }

    private void readWorlds(WorldConversionHandler worldConversionHandler) {
        if (worldConversionHandler == null) return;

        Set<RegionCoordPair> regions = regionsOf(exportRectangle);
        ChunkerWorld world = new ChunkerWorld(dimension, new LinkedHashSet<>(regions));

        Task<ColumnConversionHandler> convertWorld = worldConversionHandler.convertWorld(world);
        Task<Void> columns = convertWorld.thenConsume("Writing regions", TaskWeight.HIGHEST, handler -> {
            if (handler == null) return;
            Task.async("Emitting columns", TaskWeight.HIGHEST, () -> emitRegions(regions, handler))
                    .then("Flushing columns", TaskWeight.MEDIUM, handler::flushColumns);
        });
        columns.then("Flushing world", TaskWeight.MEDIUM, () -> {
            worldConversionHandler.flushWorld(world);
            worldConversionHandler.flushWorlds();
        });
    }

    /** Every 32x32 chunk region touched by the export rectangle. */
    static Set<RegionCoordPair> regionsOf(ChunkRect rectangle) {
        Set<RegionCoordPair> regions = new LinkedHashSet<>();
        int minRegionX = rectangle.minX() >> 5;
        int maxRegionX = rectangle.maxX() >> 5;
        int minRegionZ = rectangle.minZ() >> 5;
        int maxRegionZ = rectangle.maxZ() >> 5;
        for (int rx = minRegionX; rx <= maxRegionX; rx++) {
            for (int rz = minRegionZ; rz <= maxRegionZ; rz++) {
                regions.add(new RegionCoordPair(rx, rz));
            }
        }
        return regions;
    }

    private void emitRegions(Set<RegionCoordPair> regions, ColumnConversionHandler handler) {
        List<Task<Void>> tasks = new ArrayList<>(regions.size());
        for (RegionCoordPair region : regions) {
            tasks.add(Task.async("Region " + region.regionX() + "," + region.regionZ(), TaskWeight.NORMAL,
                            () -> emitRegion(region, handler))
                    .then("Region flush", TaskWeight.MEDIUM, () -> handler.flushRegion(region)));
        }
        Task.join(tasks);
    }

    private void emitRegion(RegionCoordPair region, ColumnConversionHandler handler) {
        int minX = Math.max(exportRectangle.minX(), region.regionX() << 5);
        int maxX = Math.min(exportRectangle.maxX(), (region.regionX() << 5) + 31);
        int minZ = Math.max(exportRectangle.minZ(), region.regionZ() << 5);
        int maxZ = Math.min(exportRectangle.maxZ(), (region.regionZ() << 5) + 31);

        for (int chunkX = minX; chunkX <= maxX; chunkX++) {
            for (int chunkZ = minZ; chunkZ <= maxZ; chunkZ++) {
                ChunkerColumn column;
                if (composer.hasContent(snapshot, chunkX, chunkZ)) {
                    column = composer.compose(snapshot, chunkX, chunkZ);
                    emittedContentColumns.incrementAndGet();
                } else {
                    column = composer.composeVoid(chunkX, chunkZ);
                    emittedVoidColumns.incrementAndGet();
                }
                handler.convertColumn(column);
                reportProgress();
            }
        }
    }

    // Region tasks run concurrently. Keep progress ordered and avoid flooding the UI
    // with hundreds of thousands of messages for a large grid.
    private synchronized void reportProgress() {
        long done = emittedColumns();
        if (done - lastReportedColumns >= 128 || done == exportRectangle.columnCount()) {
            if (done <= lastReportedColumns) return;
            lastReportedColumns = done;
            progress.accept(done);
        }
    }
}
