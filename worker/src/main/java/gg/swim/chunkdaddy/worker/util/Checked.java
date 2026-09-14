package gg.swim.chunkdaddy.worker.util;

/**
 * Checked coordinate arithmetic.
 *
 * <p>Two classes of bug are common in chunk editors and both are addressed here:
 * silent 32-bit overflow when multiplying extents, and truncating division for
 * negative coordinates. Block X {@code -1} belongs to chunk {@code -1} with local
 * X {@code 15}; {@code -1 / 16} in Java is {@code 0}, which is wrong.
 */
public final class Checked {
    private Checked() {
    }

    /** Chunk coordinate containing a block coordinate, using mathematical floor. */
    public static int blockToChunk(int block) {
        return block >> 4;
    }

    /** Local 0..15 coordinate of a block within its chunk. */
    public static int localInChunk(int block) {
        return block & 15;
    }

    /** First block coordinate of a chunk. */
    public static int chunkToBlock(int chunk) {
        return chunk << 4;
    }

    /** Ceiling division for non-negative values. */
    public static int ceilDiv(int value, int divisor) {
        if (divisor <= 0) throw new IllegalArgumentException("divisor must be positive: " + divisor);
        if (value < 0) throw new IllegalArgumentException("value must be non-negative: " + value);
        return (value + divisor - 1) / divisor;
    }

    /** Multiply as 64-bit and fail rather than wrap. */
    public static long mulExact(long a, long b) {
        return Math.multiplyExact(a, b);
    }

    /** Volume of a box, as a long, refusing negative extents. */
    public static long volume(int sizeX, int sizeY, int sizeZ) {
        if (sizeX < 0 || sizeY < 0 || sizeZ < 0) {
            throw new IllegalArgumentException("Negative extent: " + sizeX + "x" + sizeY + "x" + sizeZ);
        }
        return mulExact(mulExact(sizeX, sizeY), sizeZ);
    }

    /** Pack a chunk coordinate pair into a long key. */
    public static long chunkKey(int chunkX, int chunkZ) {
        return ((long) chunkX << 32) | (chunkZ & 0xFFFFFFFFL);
    }

    public static int chunkKeyX(long key) {
        return (int) (key >> 32);
    }

    public static int chunkKeyZ(long key) {
        return (int) key;
    }

    /** Fail with a clear message when a value exceeds what the caller can handle. */
    public static int requireInt(long value, String what) {
        if (value > Integer.MAX_VALUE || value < Integer.MIN_VALUE) {
            throw new IllegalArgumentException(what + " does not fit in 32 bits: " + value);
        }
        return (int) value;
    }
}
