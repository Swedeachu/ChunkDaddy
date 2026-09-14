package gg.swim.chunkdaddy.worker.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GeometryTest {
    @Test
    void negativeBlockCoordinatesUseFloorDivision() {
        // Block -1 belongs to chunk -1 with local X 15. Truncating division says chunk 0.
        assertEquals(-1, Checked.blockToChunk(-1));
        assertEquals(15, Checked.localInChunk(-1));
        assertEquals(-1, Checked.blockToChunk(-16));
        assertEquals(0, Checked.localInChunk(-16));
        assertEquals(-2, Checked.blockToChunk(-17));
        assertEquals(15, Checked.localInChunk(-17));
    }

    @Test
    void chunkKeysRoundTripThroughNegatives() {
        int[] values = {Integer.MIN_VALUE, -70000, -1, 0, 1, 70000, Integer.MAX_VALUE};
        for (int x : values) {
            for (int z : values) {
                long key = Checked.chunkKey(x, z);
                assertEquals(x, Checked.chunkKeyX(key));
                assertEquals(z, Checked.chunkKeyZ(key));
            }
        }
    }

    @Test
    void footprintCeilingMatchesTheSuppliedTemplates() {
        assertEquals(13, Checked.ceilDiv(198, 16));
        assertEquals(16, Checked.ceilDiv(249, 16));
        assertEquals(13, Checked.ceilDiv(208, 16));
        assertEquals(17, Checked.ceilDiv(264, 16));
        assertEquals(11, Checked.ceilDiv(173, 16));
    }

    @Test
    void volumeIsComputedAsSixtyFourBit() {
        // 8-cyberpunk is 198 x 161 x 249; the product overflows nothing only because it
        // is computed as a long.
        assertEquals(198L * 161 * 249, Checked.volume(198, 161, 249));
        // The largest dimensions Sponge can express still fit in a long, and must not be
        // silently truncated to an int on the way.
        assertEquals(65535L * 65535L * 65535L, Checked.volume(65535, 65535, 65535));
        assertThrows(IllegalArgumentException.class, () -> Checked.volume(-1, 1, 1));
    }

    @Test
    void checkedMultiplyRefusesToWrap() {
        assertEquals(160944L, Checked.mulExact(336, 479));
        assertThrows(ArithmeticException.class,
                () -> Checked.mulExact(Long.MAX_VALUE / 2, 4));
    }

    @Test
    void exportRectangleCountsColumnsWithoutOverflow() {
        // The worked example: 20 columns, 23 rows, four-chunk gaps.
        ChunkRect grid = ChunkRect.ofSize(0, 0, 336, 479);
        assertEquals(160944L, grid.columnCount());

        ChunkRect huge = ChunkRect.ofSize(-50000, -50000, 100000, 100000);
        assertEquals(10_000_000_000L, huge.columnCount());
    }

    @Test
    void rectangleMembershipAndUnion() {
        ChunkRect rect = new ChunkRect(-5, -5, 5, 5);
        assertTrue(rect.contains(-5, -5));
        assertTrue(rect.contains(5, 5));
        assertFalse(rect.contains(6, 0));
        assertEquals(11, rect.widthChunks());

        ChunkRect other = new ChunkRect(10, 10, 12, 12);
        assertFalse(rect.intersects(other));
        assertEquals(new ChunkRect(-5, -5, 12, 12), rect.union(other));
    }

    @Test
    void invertedRectangleIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> new ChunkRect(5, 0, 1, 0));
    }
}
