package gg.swim.chunkdaddy.worker.bedrock;

import java.util.List;

/**
 * What an export actually produced.
 *
 * <p>{@code companionJsonWritten} is reported separately from the world because a
 * filesystem rename does not atomically publish several unrelated files. If the archive
 * lands and the sidecar does not, the JSON inside the archive is authoritative and the
 * incomplete companion state has to be visible rather than assumed.
 */
public record ExportResult(String worldPath,
                           long contentColumns,
                           long voidColumns,
                           long totalColumns,
                           int arenaCount,
                           int spawnPointCount,
                           boolean companionJsonWritten,
                           List<String> warnings) {
    public long expectedColumns() {
        return contentColumns + voidColumns;
    }
}
