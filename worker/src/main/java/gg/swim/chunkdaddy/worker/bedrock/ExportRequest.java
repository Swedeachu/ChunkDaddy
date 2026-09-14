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
                            boolean writeCompanionJson) {
}
