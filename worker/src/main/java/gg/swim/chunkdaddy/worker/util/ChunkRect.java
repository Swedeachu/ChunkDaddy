package gg.swim.chunkdaddy.worker.util;

/**
 * An inclusive rectangle of chunk columns.
 *
 * <p>The UI displays inclusive chunk bounds; iteration internally is half-open.
 * Both are available here so neither side has to scatter {@code +1} corrections.
 */
public record ChunkRect(int minX, int minZ, int maxX, int maxZ) {
    public ChunkRect {
        if (maxX < minX || maxZ < minZ) {
            throw new IllegalArgumentException("Empty chunk rectangle: " + minX + "," + minZ + " .. " + maxX + "," + maxZ);
        }
    }

    public static ChunkRect ofSize(int minX, int minZ, int widthChunks, int lengthChunks) {
        if (widthChunks <= 0 || lengthChunks <= 0) {
            throw new IllegalArgumentException("Size must be positive: " + widthChunks + "x" + lengthChunks);
        }
        return new ChunkRect(minX, minZ, minX + widthChunks - 1, minZ + lengthChunks - 1);
    }

    public int widthChunks() {
        return maxX - minX + 1;
    }

    public int lengthChunks() {
        return maxZ - minZ + 1;
    }

    /** Number of chunk columns covered, as a long: a large rectangle overflows an int. */
    public long columnCount() {
        return Checked.mulExact(widthChunks(), lengthChunks());
    }

    public boolean contains(int chunkX, int chunkZ) {
        return chunkX >= minX && chunkX <= maxX && chunkZ >= minZ && chunkZ <= maxZ;
    }

    public boolean intersects(ChunkRect other) {
        return minX <= other.maxX && maxX >= other.minX && minZ <= other.maxZ && maxZ >= other.minZ;
    }

    public ChunkRect union(ChunkRect other) {
        return new ChunkRect(
                Math.min(minX, other.minX),
                Math.min(minZ, other.minZ),
                Math.max(maxX, other.maxX),
                Math.max(maxZ, other.maxZ));
    }

    public ChunkRect expand(int chunks) {
        if (chunks < 0) throw new IllegalArgumentException("Border must be non-negative: " + chunks);
        return new ChunkRect(minX - chunks, minZ - chunks, maxX + chunks, maxZ + chunks);
    }

    public ChunkRect translated(int dChunkX, int dChunkZ) {
        return new ChunkRect(minX + dChunkX, minZ + dChunkZ, maxX + dChunkX, maxZ + dChunkZ);
    }
}
