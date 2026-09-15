package gg.swim.chunkdaddy.worker.document;

import com.hivemc.chunker.conversion.encoding.EncodingType;
import com.hivemc.chunker.conversion.encoding.base.Version;
import com.hivemc.chunker.conversion.encoding.base.writer.ColumnWriter;
import com.hivemc.chunker.conversion.encoding.base.writer.LevelWriter;
import com.hivemc.chunker.conversion.encoding.base.writer.WorldWriter;
import com.hivemc.chunker.conversion.intermediate.column.ChunkerColumn;
import com.hivemc.chunker.conversion.intermediate.column.biome.ChunkerBiome;
import com.hivemc.chunker.conversion.intermediate.level.ChunkerLevel;
import com.hivemc.chunker.conversion.intermediate.level.ChunkerLevelSettings;
import com.hivemc.chunker.conversion.intermediate.world.ChunkerWorld;
import com.hivemc.chunker.conversion.intermediate.world.Dimension;
import com.hivemc.chunker.nbt.tags.collection.CompoundTag;
import gg.swim.chunkdaddy.worker.util.Checked;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A writer that keeps the converted columns instead of writing them to disk.
 *
 * <p>Importing a world is the same conversion Chunker performs for a normal export, but
 * the destination is ChunkDaddy's document model. Only the editable dimension is captured;
 * other dimensions are recorded as present and are never folded into the Overworld.
 */
public final class CaptureLevelWriter implements LevelWriter {
    private final Version version;
    private final Dimension captureDimension;
    private final Map<Long, ChunkerColumn> columns = new ConcurrentHashMap<>();
    private final Set<String> otherDimensionsSeen = ConcurrentHashMap.newKeySet();
    private volatile @Nullable ChunkerLevelSettings capturedSettings;
    private volatile @Nullable CompoundTag capturedLevelData;

    public CaptureLevelWriter(Version version, Dimension captureDimension) {
        this.version = version;
        this.captureDimension = captureDimension;
    }

    public Map<Long, ChunkerColumn> columns() {
        return columns;
    }

    public Set<String> otherDimensionsSeen() {
        return otherDimensionsSeen;
    }

    public @Nullable ChunkerLevelSettings capturedSettings() {
        return capturedSettings;
    }

    /**
     * The source world's level.dat exactly as it was read.
     *
     * <p>Chunker's settings object only models the fields it knows about, so anything else
     * the world carried - most importantly the {@code experiments} compound - exists only
     * here. Keeping it is what lets the export put those tags back.
     */
    public @Nullable CompoundTag capturedLevelData() {
        return capturedLevelData;
    }

    @Override
    public WorldWriter writeLevel(ChunkerLevel chunkerLevel) {
        capturedSettings = chunkerLevel.getSettings();
        capturedLevelData = chunkerLevel.getOriginalLevelData();
        return new WorldWriter() {
            @Override
            public ColumnWriter writeWorld(ChunkerWorld chunkerWorld) {
                if (!chunkerWorld.getDimension().getIdentifier().equals(captureDimension.getIdentifier())) {
                    otherDimensionsSeen.add(chunkerWorld.getDimension().getIdentifier());
                    // Returning a writer that discards keeps the pipeline simple while
                    // guaranteeing no other dimension leaks into the edited one.
                    return column -> {
                    };
                }
                return column -> columns.put(
                        Checked.chunkKey(column.getPosition().chunkX(), column.getPosition().chunkZ()), column);
            }
        };
    }

    @Override
    public EncodingType getEncodingType() {
        return EncodingType.BEDROCK;
    }

    @Override
    public Version getVersion() {
        return version;
    }

    @Override
    public Set<ChunkerBiome.ChunkerVanillaBiome> getSupportedBiomes() {
        return Set.of(captureDimension.getFallbackBiome());
    }
}
