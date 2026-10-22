package gg.swim.chunkdaddy.worker.terrain;

import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.hivemc.chunker.conversion.encoding.bedrock.BedrockEncoders;
import com.hivemc.chunker.conversion.intermediate.column.ChunkerColumn;
import com.hivemc.chunker.conversion.intermediate.column.blockentity.container.randomizable.BarrelBlockEntity;
import com.hivemc.chunker.conversion.intermediate.column.chunk.ChunkCoordPair;
import com.hivemc.chunker.conversion.intermediate.column.chunk.identifier.ChunkerBlockIdentifier;
import com.hivemc.chunker.conversion.intermediate.column.chunk.identifier.type.block.ChunkerVanillaBlockType;
import gg.swim.chunkdaddy.worker.bedrock.TargetProfile;
import gg.swim.chunkdaddy.worker.document.ColumnOps;
import gg.swim.chunkdaddy.worker.document.TemplateRegistry;
import gg.swim.chunkdaddy.worker.document.WorldDocument;
import gg.swim.chunkdaddy.worker.document.WorldImporter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class TerrainDisplacementTest {
    @TempDir Path directory;

    private WorldDocument document(ChunkerColumn column) {
        var document = new WorldDocument("terrain", TargetProfile.defaultProfile().id(), new TemplateRegistry());
        document.importColumns(Map.of(0L, column));
        return document;
    }

    private ChunkerColumn column() {
        return new ChunkerColumn(new ChunkCoordPair(0, 0));
    }

    private void set(ChunkerColumn column, int y, ChunkerVanillaBlockType type) {
        ColumnOps.writeableChunk(column, (byte) (y >> 4)).set(1, y & 15, 1, new ChunkerBlockIdentifier(type));
    }

    private ChunkerBlockIdentifier get(ChunkerColumn column, int y) {
        var chunk = column.getChunks().get((byte) (y >> 4));
        return chunk == null ? ChunkerBlockIdentifier.AIR : chunk.getPalette().get(1, y & 15, 1, ChunkerBlockIdentifier.AIR);
    }

    private JsonObject bounds() {
        var bounds = new JsonObject();
        bounds.addProperty("minX", 0); bounds.addProperty("minY", 0); bounds.addProperty("minZ", 0);
        bounds.addProperty("width", 16); bounds.addProperty("height", 64); bounds.addProperty("depth", 16);
        return bounds;
    }

    private JsonObject request(WorldDocument document, Map<Integer, Integer> moves) throws Exception {
        var volume = TerrainVolume.read(document, bounds());
        byte[] field = new byte[volume.blocks.length];
        for (var move : moves.entrySet()) field[volume.index(1, move.getKey(), 1)] = move.getValue().byteValue();
        Path fieldPath = directory.resolve("displacements.gz");
        try (var output = new GZIPOutputStream(Files.newOutputStream(fieldPath))) { output.write(field); }
        JsonObject plan = bounds();
        plan.addProperty("fingerprint", volume.fingerprint);
        plan.addProperty("displacementSha256", HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(field)));
        plan.addProperty("displacements", fieldPath.getFileName().toString()); plan.addProperty("maximumShift", 8);
        Path planPath = directory.resolve("plan.json"); Files.writeString(planPath, plan.toString());
        var request = new JsonObject(); request.addProperty("plan", planPath.toString()); return request;
    }

    @Test void nonAlignedRigidMoveIsOneUndoableRevisionAndDoesNotMutateItsSource() throws Exception {
        var column = column(); set(column, 10, ChunkerVanillaBlockType.STONE);
        for (int y = 11; y <= 14; y++) set(column, y, ChunkerVanillaBlockType.OAK_LOG);
        var document = document(column); long initialRevision = document.revision();
        var request = request(document, Map.of(10,-3,11,-3,12,-3,13,-3,14,-3));
        request.addProperty("validateOnly", true);
        assertTrue(TerrainDisplacement.apply(document, request, null).get("valid").getAsBoolean());
        assertEquals(initialRevision, document.revision());
        request.addProperty("validateOnly", false);
        var result = TerrainDisplacement.apply(document, request, null);
        assertEquals(5, result.get("movedBlocks").getAsInt());
        var edited = document.snapshot().materializedColumn(0,0);
        assertEquals(ChunkerVanillaBlockType.STONE, get(edited,7).getType());
        for (int y = 8; y <= 11; y++) assertEquals(ChunkerVanillaBlockType.OAK_LOG, get(edited,y).getType());
        assertTrue(get(edited,14).isAir());
        assertEquals(ChunkerVanillaBlockType.STONE, get(column,10).getType());
        document.undo(); assertEquals(initialRevision, document.revision());
        assertSame(column, document.snapshot().materializedColumn(0,0));
    }

    @Test void stationaryCollisionRejectsTheEntireEdit() throws Exception {
        var column = column(); set(column,10,ChunkerVanillaBlockType.STONE); set(column,7,ChunkerVanillaBlockType.DIRT);
        var document = document(column); long revision = document.revision();
        var request = request(document, Map.of(10,-3)); request.addProperty("validateOnly",true);
        var validation = TerrainDisplacement.apply(document,request,null);
        assertFalse(validation.get("valid").getAsBoolean()); assertEquals(1,validation.get("conflictCount").getAsInt());
        request.addProperty("validateOnly",false);
        assertThrows(IllegalArgumentException.class,()->TerrainDisplacement.apply(document,request,null));
        assertEquals(revision,document.revision()); assertSame(column,document.snapshot().materializedColumn(0,0));
    }

    @Test void twoBlocksMayNotClaimTheSameDestination() throws Exception {
        var column = column(); set(column,10,ChunkerVanillaBlockType.STONE); set(column,11,ChunkerVanillaBlockType.DIRT);
        var document = document(column); var request = request(document,Map.of(10,-3,11,-4)); request.addProperty("validateOnly",true);
        var validation = TerrainDisplacement.apply(document,request,null);
        assertFalse(validation.get("valid").getAsBoolean());
        assertEquals(1,validation.get("conflictCount").getAsInt());
    }

    @Test void staleSourceAndTamperedFieldAreRejectedBeforeEditing() throws Exception {
        var source = column(); set(source,10,ChunkerVanillaBlockType.STONE);
        var document = document(source); var request = request(document,Map.of(10,-3));
        var other = column(); set(other,10,ChunkerVanillaBlockType.DIRT); var otherDocument = document(other);
        assertThrows(IllegalArgumentException.class,()->TerrainDisplacement.apply(otherDocument,request,null));
        try (var output = new GZIPOutputStream(Files.newOutputStream(directory.resolve("displacements.gz")))) { output.write(new byte[16*64*16]); }
        long revision = document.revision();
        assertThrows(IllegalArgumentException.class,()->TerrainDisplacement.apply(document,request,null));
        assertEquals(revision,document.revision());
    }

    @Test void blockEntityPayloadAndPositionMoveWhileTheOriginalStaysUntouched() throws Exception {
        var column = column(); set(column,10,ChunkerVanillaBlockType.BARREL);
        var barrel = new BarrelBlockEntity(); barrel.setX(1); barrel.setY(10); barrel.setZ(1);
        barrel.setCustomName(new JsonPrimitive("Keep this name")); barrel.setLootTable("minecraft:chests/simple_dungeon");
        column.getBlockEntities().add(barrel);
        var document = document(column); var request = request(document,Map.of(10,-3));
        var converter = WorldImporter.newConverter();
        var writer = BedrockEncoders.createWriter(directory.resolve("resolvers").toFile(),TargetProfile.defaultProfile().version(),converter).orElseThrow();
        var resolver = writer.buildResolvers(converter).build().blockEntityResolver();
        TerrainDisplacement.apply(document,request,resolver);
        var edited = (BarrelBlockEntity)document.snapshot().materializedColumn(0,0).getBlockEntities().getFirst();
        assertEquals(7,edited.getY()); assertEquals(10,barrel.getY()); assertNotSame(barrel,edited);
        assertTrue(edited.getCustomName().toString().contains("Keep this name"));
        assertEquals(barrel.getLootTable(),edited.getLootTable());
    }
}
