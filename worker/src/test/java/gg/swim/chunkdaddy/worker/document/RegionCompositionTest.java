package gg.swim.chunkdaddy.worker.document;

import com.google.gson.JsonParser;
import com.hivemc.chunker.conversion.WorldConverter;
import com.hivemc.chunker.conversion.intermediate.column.ChunkerColumn;
import com.hivemc.chunker.conversion.intermediate.column.blockentity.container.randomizable.TrappedChestBlockEntity;
import com.hivemc.chunker.conversion.intermediate.column.chunk.ChunkCoordPair;
import com.hivemc.chunker.conversion.intermediate.column.chunk.identifier.ChunkerBlockIdentifier;
import com.hivemc.chunker.conversion.intermediate.column.chunk.identifier.type.block.ChunkerVanillaBlockType;
import gg.swim.chunkdaddy.worker.bedrock.TargetProfile;
import gg.swim.chunkdaddy.worker.util.Checked;
import gg.swim.chunkdaddy.worker.util.ChunkRect;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class RegionCompositionTest {
    @TempDir Path directory;

    @Test void copiesDetachChestCoordinatesAndPreserveFractionalMetadata() {
        var metadata = JsonParser.parseString("{\"spawns\":[{\"x\":-30.5,\"y\":64,\"z\":49.25,\"yaw\":90}],\"setting\":7}").getAsJsonObject();
        var selectors = JsonParser.parseString("[{\"path\":\"/spawns/*\"}]").getAsJsonArray();
        var placements = JsonParser.parseString("[{\"id\":\"a\",\"destChunkX\":0,\"destChunkZ\":0},{\"id\":\"b\",\"destChunkX\":20,\"destChunkZ\":30}]").getAsJsonArray();
        var column = new ChunkerColumn(new ChunkCoordPair(-2, 3));
        var chest = new TrappedChestBlockEntity();
        chest.setX(-31); chest.setY(64); chest.setZ(49);
        column.getBlockEntities().add(chest);
        ColumnOps.writeableChunk(column, (byte) 4).set(1, 0, 1, new ChunkerBlockIdentifier(ChunkerVanillaBlockType.TRAPPED_CHEST));
        var document = new WorldDocument("source", TargetProfile.defaultProfile().id(), new TemplateRegistry());
        document.importColumns(Map.of(Checked.chunkKey(-2, 3), column));
        var converter = new WorldConverter(UUID.randomUUID());
        var writer = com.hivemc.chunker.conversion.encoding.bedrock.BedrockEncoders.createWriter(directory.toFile(), TargetProfile.defaultProfile().version(), converter).orElseThrow();
        var resolver = writer.buildResolvers(converter).build().blockEntityResolver();
        var composer = new RegionComposer();
        composer.add("par72", document.snapshot(), new ChunkRect(-2, 3, -2, 3), metadata, selectors, placements,
                resolver);
        var a = composer.snapshot().materializedColumn(0, 0).getBlockEntities().getFirst();
        var b = composer.snapshot().materializedColumn(20, 30).getBlockEntities().getFirst();
        assertEquals(1, a.getX()); assertEquals(1, a.getZ());
        assertEquals(321, b.getX()); assertEquals(481, b.getZ());
        assertEquals(-31, chest.getX()); assertEquals(49, chest.getZ());
        assertNotSame(a, b);
        var biomeList = new java.util.ArrayList<com.hivemc.chunker.conversion.intermediate.column.chunk.palette.Palette<com.hivemc.chunker.conversion.intermediate.column.biome.ChunkerBiome>>();
        biomeList.add(new com.hivemc.chunker.conversion.intermediate.column.chunk.palette.SingleValuePalette<>(16,
                com.hivemc.chunker.conversion.intermediate.column.biome.ChunkerBiome.ChunkerVanillaBiome.PLAINS));
        column.setBiomes(new com.hivemc.chunker.conversion.intermediate.column.biome.layout.ChunkerPaletteBasedBiomes(biomeList));
        var writable = ColumnOps.relocate(column, 0, 0, 0, true, resolver);
        writable.getBiomes().asPalette().add(biomeList.getFirst());
        assertEquals(1, column.getBiomes().asPalette().size(), "Writer padding must not change source or sibling biomes");
        var local = composer.manifest("world").getAsJsonObject("templates").getAsJsonObject("par72").getAsJsonObject("metadata");
        assertEquals(1.5, local.getAsJsonArray("spawns").get(0).getAsJsonObject().get("x").getAsDouble());
        assertEquals(1.25, local.getAsJsonArray("spawns").get(0).getAsJsonObject().get("z").getAsDouble());
        assertEquals(90, local.getAsJsonArray("spawns").get(0).getAsJsonObject().get("yaw").getAsInt());
        assertEquals(7, local.get("setting").getAsInt());
        assertEquals(-30.5, metadata.getAsJsonArray("spawns").get(0).getAsJsonObject().get("x").getAsDouble());
        assertThrows(IllegalArgumentException.class, () -> composer.add("other", document.snapshot(),
                new ChunkRect(-2, 3, -2, 3), metadata, selectors, placements, resolver));
    }
}
