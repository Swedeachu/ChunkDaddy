package gg.swim.chunkdaddy.worker.document;

import com.hivemc.chunker.conversion.intermediate.column.ChunkerColumn;
import com.hivemc.chunker.conversion.intermediate.column.chunk.ChunkCoordPair;
import com.hivemc.chunker.conversion.intermediate.column.chunk.identifier.ChunkerBlockIdentifier;
import com.hivemc.chunker.conversion.intermediate.column.chunk.identifier.type.block.ChunkerVanillaBlockType;
import gg.swim.chunkdaddy.worker.util.ChunkRect;
import org.junit.jupiter.api.Test;
import java.util.Random;
import static org.junit.jupiter.api.Assertions.*;

class RegionAlignmentTest {
    private static final ChunkerBlockIdentifier STONE = new ChunkerBlockIdentifier(ChunkerVanillaBlockType.STONE);
    private static final ChunkerBlockIdentifier DIRT = new ChunkerBlockIdentifier(ChunkerVanillaBlockType.DIRT);
    private static final ChunkerBlockIdentifier CHEST = new ChunkerBlockIdentifier(ChunkerVanillaBlockType.CHEST);

    private WorldSnapshot terrain(int dx, int dz, boolean edited, boolean unrelated) {
        var builder = WorldSnapshot.empty().toBuilder();
        for (int cx = -3; cx <= 3; cx++) for (int cz = -3; cz <= 3; cz++) {
            var column = new ChunkerColumn(new ChunkCoordPair(cx + dx, cz + dz));
            var random = new Random(cx * 100009L + cz * 170003L + (unrelated ? 5432981L : 0));
            for (int x = 0; x < 16; x++) for (int y = 0; y < 16; y++) for (int z = 0; z < 16; z++) {
                int n = random.nextInt(3);
                column.setBlock(x, y, z, n == 0 ? ChunkerBlockIdentifier.AIR : n == 1 ? STONE : DIRT);
            }
            if (cx == 0 && cz == 0) {
                column.setBlock(1, 7, 1, edited ? ChunkerBlockIdentifier.AIR : CHEST);
                column.setBlock(2, 7, 2, edited ? CHEST : ChunkerBlockIdentifier.AIR);
            }
            builder.putColumn(cx + dx, cz + dz, column);
        }
        if (edited) {
            var distant = new ChunkerColumn(new ChunkCoordPair(10000, 10000));
            distant.setBlock(0, 0, 0, STONE);
            builder.putColumn(10000, 10000, distant);
        }
        return builder.build();
    }

    @Test void findsTranslatedEditedTerrainAndIgnoresDistantLoadedChunks() {
        var result = RegionAlignment.align(terrain(100, 200, false, false), new ChunkRect(97, 197, 103, 203), terrain(-8, 4, true, false), 0.98);
        var offset = result.getAsJsonObject("sourceToReferenceOffset");
        assertEquals(108 * 16, offset.get("x").getAsInt());
        assertEquals(196 * 16, offset.get("z").getAsInt());
        assertEquals(0, offset.get("y").getAsInt());
        assertEquals(-11, result.getAsJsonObject("sourceRectangle").get("minChunkX").getAsInt());
        assertEquals(1, result.get("oldChestBlocks").getAsInt());
        assertEquals(1, result.get("newChestBlocks").getAsInt());
        assertEquals(1, result.get("removedChestBlocks").getAsInt());
        assertEquals(1, result.get("addedChestBlocks").getAsInt());
        assertEquals(2, result.get("changedBlockTypes").getAsInt());
        assertEquals(1, result.get("ignoredSourceColumnsOutsideFootprint").getAsInt());
        assertTrue(result.get("matchingColumns").getAsInt() >= 40);
    }

    @Test void refusesUnrelatedOrFeaturelessTerrain() {
        var reference = terrain(100, 200, false, false);
        assertThrows(IllegalArgumentException.class, () -> RegionAlignment.align(reference, new ChunkRect(97, 197, 103, 203), terrain(-8, 4, false, true), 0.98));
        assertThrows(IllegalArgumentException.class, () -> RegionAlignment.align(reference, new ChunkRect(97, 197, 103, 203), WorldSnapshot.empty(), 0.98));
    }

    @Test void fullFootprintCheckRejectsLargeDeletionEvenWhenRemainingAnchorsAlign() {
        var reference = terrain(100, 200, false, false);
        var damaged = terrain(-8, 4, true, false).toBuilder();
        for (int x = -9; x <= -7; x++) for (int z = 2; z <= 6; z++) damaged.removeColumn(x, z);
        assertThrows(IllegalArgumentException.class, () -> RegionAlignment.align(reference, new ChunkRect(97, 197, 103, 203), damaged.build(), 0.98));
    }

    @Test void duplicateCopiesAreAmbiguousAndMustNeverBeGuessed() {
        var source = terrain(-8, 4, false, false).toBuilder();
        var second = terrain(40, 4, false, false);
        for (long key : second.materializedKeys()) {
            var c = second.materializedColumn((int) (key >> 32), (int) key);
            source.putColumn(c.getPosition().chunkX(), c.getPosition().chunkZ(), c);
        }
        assertThrows(IllegalArgumentException.class, () -> RegionAlignment.align(terrain(100, 200, false, false), new ChunkRect(97, 197, 103, 203), source.build(), 0.98));
    }
}
