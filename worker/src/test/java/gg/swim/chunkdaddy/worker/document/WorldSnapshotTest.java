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
            // Reuse an empty value: this exercises coordinate indexing, not voxel allocation.
            var column = new ChunkerColumn(new ChunkCoordPair(0, 0));
            for (int x = -256; x < 256; x++) {
                for (int z = -256; z < 256; z++) builder.putColumn(x, z, column);
            }
            var snapshot = builder.build();
            assertEquals(262144, snapshot.materializedCount());
            assertSame(column, snapshot.materializedColumn(-255, 254));
            builder.removeColumn(-255, 254);
            assertSame(column, snapshot.materializedColumn(-255, 254));
            assertThrows(UnsupportedOperationException.class, () -> snapshot.materializedKeys().clear());
        });
    }
}
