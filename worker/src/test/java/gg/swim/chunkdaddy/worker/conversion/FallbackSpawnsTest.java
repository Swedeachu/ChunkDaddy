package gg.swim.chunkdaddy.worker.conversion;

import com.hivemc.chunker.conversion.intermediate.column.chunk.identifier.ChunkerBlockIdentifier;
import com.hivemc.chunker.conversion.intermediate.column.chunk.identifier.type.block.ChunkerVanillaBlockType;
import gg.swim.chunkdaddy.worker.bedrock.ArenaJsonWriter;
import gg.swim.chunkdaddy.worker.document.ArenaInstance;
import gg.swim.chunkdaddy.worker.document.TemplateRegistry;
import gg.swim.chunkdaddy.worker.document.WorldSnapshot;
import gg.swim.chunkdaddy.worker.schematic.SpongeSchematic;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class FallbackSpawnsTest {
    private ArenaTemplate template(int x, int y, int z, int[][] blocks) {
        int[] indices = new int[x * y * z];
        for (int[] block : blocks) indices[block[0] + block[2] * x + block[1] * x * z] = 1;
        var schematic = new SpongeSchematic(3, 4189, x, y, z, new int[]{100, 200, 300},
                null, null, List.of(), indices, List.of(), null);
        return new ArenaTemplate("test", "test.schem", "current-source", schematic,
                new ChunkerBlockIdentifier[]{ChunkerBlockIdentifier.AIR,
                        new ChunkerBlockIdentifier(ChunkerVanillaBlockType.STONE)}, List.of(), false);
    }

    @Test void centreUsesHighestBlockInItsColumnAndPlayerFeetAboveIt() {
        var template = template(4, 8, 5, new int[][]{{2, 2, 2}, {2, 6, 2}, {0, 7, 0}});
        assertTrue(template.generateFallbackSpawns());
        assertArrayEquals(new double[]{2, 7, 2.5}, template.spawnPoint1());
        assertArrayEquals(template.spawnPoint1(), template.spawnPoint2());
        assertTrue(template.spawnsAutomatic());
        assertTrue(template.spawnsReady());
        assertFalse(template.spawnsConfirmed()); // Never claims a human confirmed them.
        assertFalse(template.generateFallbackSpawns());
    }

    @Test void emptyCentreFindsSurfaceAndEmptySchematicStaysUnresolved() {
        var narrow = template(9, 4, 1, new int[][]{{0, 3, 0}});
        assertTrue(narrow.generateFallbackSpawns());
        assertArrayEquals(new double[]{0.5, 4, 0.5}, narrow.spawnPoint1());
        var empty = template(7, 8, 3, new int[][]{});
        assertFalse(empty.generateFallbackSpawns());
        assertFalse(empty.spawnsReady());
        assertNull(empty.spawnPoint1());
    }

    @Test void authoredMarkersArePreservedAndCanReplaceAutomaticOnes() {
        var template = template(4, 8, 5, new int[][]{{2, 6, 2}});
        template.generateFallbackSpawns();
        double[] one = {1.5, 7, 2.5}, two = {3.5, 7, 2.5};
        template.setSpawns(one, two, true);
        assertFalse(template.spawnsAutomatic());
        assertTrue(template.spawnsConfirmed());
        assertFalse(template.generateFallbackSpawns());
        assertArrayEquals(one, template.spawnPoint1());
        assertArrayEquals(two, template.spawnPoint2());
        template.setSpawns(one, null, false);
        assertFalse(template.generateFallbackSpawns());
        assertFalse(template.spawnsReady());
    }

    @Test void all450InstancesExportBothTranslatedSpawnsAndMissingMarkersAreGrouped() {
        var template = template(4, 8, 5, new int[][]{{2, 6, 2}});
        template.generateFallbackSpawns();
        var registry = new TemplateRegistry();
        registry.add(template);
        var builder = WorldSnapshot.empty().toBuilder();
        for (int n = 1; n <= 450; n++) {
            builder.putInstance(new ArenaInstance(UUID.randomUUID(), template.id(), template.slug(), n, "test-" + n,
                    n * 160 - 1000, -64, -n * 160, 4, 8, 5, 0, n, false));
        }
        var snapshot = builder.build();
        assertTrue(ArenaJsonWriter.unresolved(snapshot, registry).isEmpty());
        var json = ArenaJsonWriter.build(snapshot, registry, ArenaJsonWriter.NumberMode.EXACT);
        assertEquals(450, json.size());
        for (int n = 1; n <= 450; n++) {
            var arena = json.getAsJsonObject("test-" + n);
            assertEquals(arena.get("spawnPoint1"), arena.get("spawnPoint2"));
            var spawn = arena.getAsJsonObject("spawnPoint1");
            assertEquals(n * 160 - 998, spawn.get("x").getAsDouble());
            assertEquals(-57, spawn.get("y").getAsDouble());
            assertEquals(-n * 160 + 2.5, spawn.get("z").getAsDouble());
        }
        template.setSpawns(null, null, false);
        var problems = ArenaJsonWriter.unresolved(snapshot, registry);
        assertEquals(1, problems.size());
        assertTrue(problems.getFirst().contains("450 arena(s)"));
        assertThrows(IllegalStateException.class, () -> ArenaJsonWriter.build(snapshot, registry, ArenaJsonWriter.NumberMode.EXACT));
    }
}
