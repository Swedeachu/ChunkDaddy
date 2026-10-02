package gg.swim.chunkdaddy.worker.document;

import com.hivemc.chunker.conversion.intermediate.column.ChunkerColumn;
import com.hivemc.chunker.conversion.intermediate.column.chunk.ChunkCoordPair;
import org.junit.jupiter.api.Test;
import java.time.Duration;
import static org.junit.jupiter.api.Assertions.*;

class WorldSnapshotTest {
    @Test
    void largeCoordinateGridFreezesQuicklyAndRemainsDetachedFromBuilder() {
        assertTimeoutPreemptively(Duration.ofSeconds(10), () -> {
            var builder = WorldSnapshot.empty().toBuilder();
            // Empty columns: this exercises coordinate indexing, not voxel allocation. They
            // cannot be one shared instance, because a column carries its own coordinate and
            // the builder refuses to file one under a key that disagrees with it.
            ChunkerColumn tracked = null;
            for (int x = -256; x < 256; x++) {
                for (int z = -256; z < 256; z++) {
                    var column = new ChunkerColumn(new ChunkCoordPair(x, z));
                    if (x == -255 && z == 254) tracked = column;
                    builder.putColumn(x, z, column);
                }
            }
            var snapshot = builder.build();
            assertEquals(262144, snapshot.materializedCount());
            assertSame(tracked, snapshot.materializedColumn(-255, 254));
            builder.removeColumn(-255, 254);
            assertSame(tracked, snapshot.materializedColumn(-255, 254));
            assertThrows(UnsupportedOperationException.class, () -> snapshot.materializedKeys().clear());
        });
    }
}
