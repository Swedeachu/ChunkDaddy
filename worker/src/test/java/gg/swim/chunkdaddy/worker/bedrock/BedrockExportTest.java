package gg.swim.chunkdaddy.worker.bedrock;

import com.google.gson.JsonParser;
import com.hivemc.chunker.conversion.WorldConverter;
import com.hivemc.chunker.conversion.intermediate.column.ChunkerColumn;
import com.hivemc.chunker.conversion.intermediate.column.blockentity.BlockEntity;
import com.hivemc.chunker.conversion.intermediate.column.blockentity.BrushableBlockEntity;
import com.hivemc.chunker.conversion.intermediate.column.blockentity.BannerBlockEntity;
import com.hivemc.chunker.conversion.intermediate.column.blockentity.container.randomizable.ChestBlockEntity;
import com.hivemc.chunker.conversion.intermediate.column.blockentity.sign.SignBlockEntity;
import com.hivemc.chunker.conversion.intermediate.column.chunk.ChunkCoordPair;
import com.hivemc.chunker.conversion.intermediate.column.chunk.identifier.ChunkerBlockIdentifier;
import com.hivemc.chunker.conversion.intermediate.column.chunk.identifier.type.block.ChunkerVanillaBlockType;
import com.hivemc.chunker.conversion.intermediate.column.chunk.itemstack.ChunkerItemStack;
import gg.swim.chunkdaddy.worker.conversion.ResolverFactory;
import gg.swim.chunkdaddy.worker.document.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class BedrockExportTest {
    @TempDir Path directory;

    @Test
    void repeatedExportPreservesImportedBlockEntitiesAndWritesEveryColumn() throws Exception {
        TargetProfile profile = TargetProfile.defaultProfile();
        TemplateRegistry templates = new TemplateRegistry();
        ResolverFactory factory = new ResolverFactory(new WorldConverter(UUID.randomUUID()), directory.toFile());
        WorldDocument document = new WorldDocument("Block entities", profile.id(), templates);
        ChunkerColumn source = new ChunkerColumn(new ChunkCoordPair(0, 0));
        BrushableBlockEntity brush = new BrushableBlockEntity();
        brush.setBrushCount(3);
        brush.setBrushDirection((byte) 2);
        brush.setItem(new ChunkerItemStack(new ChunkerBlockIdentifier(ChunkerVanillaBlockType.DIAMOND_BLOCK)));
        ChestBlockEntity chest = new ChestBlockEntity();
        chest.getItems().put((byte) 0, new ChunkerItemStack(new ChunkerBlockIdentifier(ChunkerVanillaBlockType.GOLD_BLOCK)));
        SignBlockEntity sign = new SignBlockEntity();
        sign.setWaxed(true);
        sign.getFront().setLines(List.of(JsonParser.parseString("{\"text\":\"Arena entrance\"}")));
        BannerBlockEntity banner = new BannerBlockEntity();
        BlockEntity[] entities = {brush, chest, sign, banner};
        ChunkerVanillaBlockType[] blocks = {ChunkerVanillaBlockType.SUSPICIOUS_SAND,
                ChunkerVanillaBlockType.CHEST, ChunkerVanillaBlockType.OAK_SIGN, ChunkerVanillaBlockType.RED_BANNER};
        for (int i = 0; i < entities.length; i++) {
            entities[i].setX(i); entities[i].setY(64); entities[i].setZ(0);
            ColumnOps.writeableChunk(source, (byte) 4).set(i, 0, 0, new ChunkerBlockIdentifier(blocks[i]));
            source.getBlockEntities().add(entities[i]);
        }
        document.importColumns(Map.of(0L, source));
        // Multiple regions with both content and generated void exercise concurrent counts.
        var rectangle = new gg.swim.chunkdaddy.worker.util.ChunkRect(-1, -1, 32, 32);
        BedrockExporter exporter = new BedrockExporter(templates, factory);
        for (int pass = 0; pass < 2; pass++) {
            Path output = directory.resolve("export-" + pass);
            List<Long> progress = new ArrayList<>();
            ExportResult result = exporter.export(document, new ExportRequest(output,
                    WorldPackager.OutputMode.DIRECTORY, rectangle, profile, "Regression world",
                    new int[]{0, 65, 0}, ArenaJsonWriter.NumberMode.EXACT, true, true),
                    progress::add, new AtomicReference<>());
            assertEquals(rectangle.columnCount(), result.totalColumns());
            assertEquals(1, result.contentColumns());
            assertEquals(rectangle.columnCount(), progress.getLast());
            for (int i = 1; i < progress.size(); i++) assertTrue(progress.get(i) > progress.get(i - 1));
            assertArrayEquals(Files.readAllBytes(output.resolve("arenas.json")),
                    Files.readAllBytes(directory.resolve("export-" + pass + ".arenas.json")));
            var imported = WorldImporter.importWorld(output, SourceInspector.Edition.BEDROCK,
                    profile, WorldImporter.newConverter());
            assertEquals(rectangle.columnCount(), imported.columns().size());
            ChunkerColumn roundTrip = imported.columns().get(0L);
            BrushableBlockEntity copiedBrush = (BrushableBlockEntity) roundTrip.getBlockEntity(0, 64, 0);
            assertEquals(3, copiedBrush.getBrushCount());
            assertEquals(brush.getItem().getIdentifier().getItemStackType(),
                    copiedBrush.getItem().getIdentifier().getItemStackType());
            ChestBlockEntity copiedChest = (ChestBlockEntity) roundTrip.getBlockEntity(1, 64, 0);
            assertEquals(1, copiedChest.getItems().size());
            assertEquals(ChunkerVanillaBlockType.GOLD_BLOCK,
                    copiedChest.getItems().get((byte) 0).getIdentifier().getItemStackType());
            SignBlockEntity copiedSign = (SignBlockEntity) roundTrip.getBlockEntity(2, 64, 0);
            assertTrue(copiedSign.isWaxed());
            assertTrue(copiedSign.getFront().getLines().toString().contains("Arena entrance"));
            assertEquals(ChunkerVanillaBlockType.RED_BANNER, roundTrip.getBlock(3, 64, 0).getType());
            copiedChest.getItems().clear();
            assertEquals(1, chest.getItems().size());
            assertTrue(banner.getBase().isEmpty(), "Export must not run mutating write hooks on source entities");
            assertSame(source, document.snapshot().materializedColumn(0, 0));
            assertSame(brush, source.getBlockEntities().getFirst());
            assertEquals(3, brush.getBrushCount());
        }
    }
}
