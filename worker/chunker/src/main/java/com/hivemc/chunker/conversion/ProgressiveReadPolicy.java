package com.hivemc.chunker.conversion;

import com.hivemc.chunker.conversion.intermediate.column.chunk.ChunkCoordPair;
import com.hivemc.chunker.conversion.intermediate.column.chunk.RegionCoordPair;
import com.hivemc.chunker.conversion.intermediate.world.Dimension;
import java.util.Map;
import java.util.Set;

/** Optional scheduling policy; it changes read order without pruning world data. */
public interface ProgressiveReadPolicy {
    /** Cooperative stop keeps the database alive until active readers finish. */
    default boolean stopping() { return false; }
    /** Optional independent, read-only preview while the full source index is collected. */
    default void preview(org.iq80.leveldb.DB database,
                         com.hivemc.chunker.conversion.encoding.bedrock.base.reader.BedrockWorldReader reader) {}
    void indexed(Dimension dimension, Map<RegionCoordPair, Set<ChunkCoordPair>> regions);
    ChunkCoordPair focus();
}
