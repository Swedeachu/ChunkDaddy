package gg.swim.chunkdaddy.worker.bedrock;

import gg.swim.chunkdaddy.worker.util.ChunkRect;

import java.nio.file.Path;

/** Everything the exporter needs, frozen before any bytes are written. */
public record ExportRequest(Path destination,
                            WorldPackager.OutputMode mode,
                            ChunkRect exportRectangle,
                            TargetProfile profile,
                            String worldName,
                            int[] worldSpawn,
                            ArenaJsonWriter.NumberMode numberMode,
                            /** Apply the arena-world preset: no random ticks, no mob spawning, no fire spread. */
                            boolean arenaPreset,
                            /** Also write {@code <world-name>.arenas.json} next to the output. */
                            boolean writeCompanionJson,
                            /**
                             * Write an explicit empty column for every content-free column in the
                             * export rectangle.
                             *
                             * <p>On by default, which is the original contract: the server finds a
                             * generated void rather than an ungenerated hole. It is only redundant
                             * when the written level.dat already makes ungenerated ground void, and
                             * it is never free - a void column costs a database record and the whole
                             * pre-transform pipeline walk, measured at roughly 45 bytes and 84
                             * microseconds each. On a composition whose bounding box spans far apart
                             * islands that is most of the export.
                             */
                            boolean writeVoidColumns,
                            /**
                             * Run the void cleaner over the written world before packaging.
                             *
                             * <p>This is the user facing switch, and it does two things that
                             * {@code writeVoidColumns} alone cannot. It skips writing the
                             * rectangle's empty columns in the first place, which is where the
                             * time goes, and it then removes columns that the composer counted
                             * as content but which hold no blocks at all - a world imported from
                             * a live server is full of these, because the server saves a column
                             * the moment a player flies over it. On a measured hub, 11,559 of
                             * 14,341 "content" columns were empty air.
                             */
                            boolean voidCleaner) {
    /** The original contract, for callers that have no opinion. */
    public ExportRequest(Path destination, WorldPackager.OutputMode mode, ChunkRect exportRectangle,
                         TargetProfile profile, String worldName, int[] worldSpawn,
                         ArenaJsonWriter.NumberMode numberMode, boolean arenaPreset,
                         boolean writeCompanionJson) {
        this(destination, mode, exportRectangle, profile, worldName, worldSpawn, numberMode,
             arenaPreset, writeCompanionJson, true, false);
    }
}
