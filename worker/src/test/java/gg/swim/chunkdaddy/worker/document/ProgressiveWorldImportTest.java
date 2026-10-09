package gg.swim.chunkdaddy.worker.document;

import com.hivemc.chunker.conversion.encoding.bedrock.util.LevelDBChunkType;
import com.hivemc.chunker.conversion.encoding.bedrock.util.LevelDBKey;
import com.hivemc.chunker.conversion.intermediate.column.chunk.ChunkCoordPair;
import com.hivemc.chunker.conversion.intermediate.world.Dimension;
import org.iq80.leveldb.impl.Iq80DBFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class ProgressiveWorldImportTest {
    @TempDir Path directory;

    @Test void previewNeverInventsColumnsFromOtherDimensionsOrNeighbouringRecords() throws Exception {
        try (var db = Iq80DBFactory.factory.open(directory.resolve("db").toFile(), new org.iq80.leveldb.Options().createIfMissing(true))) {
            var position = new ChunkCoordPair(-4, 260);
            db.put(LevelDBKey.key(Dimension.NETHER, position, LevelDBChunkType.DATA_3D), new byte[]{1});
            db.put(LevelDBKey.key(Dimension.OVERWORLD, new ChunkCoordPair(-3, 260), LevelDBChunkType.DATA_3D), new byte[]{1});
            assertFalse(ProgressiveWorldImport.sourceColumnExists(db, position));
            // Subchunk-only worlds must also get a preview, including negative heights.
            db.put(LevelDBKey.key(Dimension.OVERWORLD, position, (byte)-4, LevelDBChunkType.SUB_CHUNK_PREFIX), new byte[]{1});
            assertTrue(ProgressiveWorldImport.sourceColumnExists(db, position));
            assertFalse(ProgressiveWorldImport.sourceColumnExists(db, new ChunkCoordPair(-5, 260)));
        }
    }
}
