package gg.swim.chunkdaddy.worker.terrain;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.hivemc.chunker.conversion.intermediate.column.ChunkerColumn;
import com.hivemc.chunker.conversion.intermediate.column.blockentity.container.randomizable.BarrelBlockEntity;
import com.hivemc.chunker.conversion.intermediate.column.chunk.ChunkCoordPair;
import com.hivemc.chunker.conversion.intermediate.column.chunk.identifier.ChunkerBlockIdentifier;
import com.hivemc.chunker.conversion.intermediate.column.chunk.identifier.type.block.ChunkerVanillaBlockType;
import com.hivemc.chunker.conversion.intermediate.column.chunk.identifier.type.block.states.vanilla.VanillaBlockStates;
import com.hivemc.chunker.conversion.intermediate.column.chunk.identifier.type.block.states.vanilla.types.Bool;
import gg.swim.chunkdaddy.worker.bedrock.TargetProfile;
import gg.swim.chunkdaddy.worker.document.ColumnOps;
import gg.swim.chunkdaddy.worker.document.TemplateRegistry;
import gg.swim.chunkdaddy.worker.document.WorldDocument;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static com.hivemc.chunker.conversion.intermediate.column.chunk.identifier.type.block.ChunkerVanillaBlockType.*;
import static org.junit.jupiter.api.Assertions.*;

class BlockReplacementTest {
    @TempDir Path directory;

    private ChunkerColumn column() { return new ChunkerColumn(new ChunkCoordPair(0, 0)); }

    private WorldDocument document(ChunkerColumn column) {
        var doc = new WorldDocument("replacement", TargetProfile.defaultProfile().id(), new TemplateRegistry());
        doc.importColumns(Map.of(0L, column)); return doc;
    }

    private void set(ChunkerColumn column, int x, int y, int z, ChunkerVanillaBlockType type) {
        ColumnOps.writeableChunk(column, (byte) (y >> 4)).set(x, y & 15, z, new ChunkerBlockIdentifier(type));
    }

    private ChunkerBlockIdentifier get(ChunkerColumn column, int x, int y, int z) {
        var chunk = column.getChunks().get((byte) (y >> 4));
        return chunk == null ? ChunkerBlockIdentifier.AIR : chunk.getPalette().get(x, y & 15, z, ChunkerBlockIdentifier.AIR);
    }

    private JsonObject request(String operations) throws Exception {
        String recipe = "{\"minX\":0,\"minY\":0,\"minZ\":0,\"width\":16,\"height\":32,\"depth\":16,\"operations\":" + operations + "}";
        Path file = directory.resolve("recipe.json"); Files.writeString(file, recipe);
        var request = new JsonObject(); request.addProperty("recipe", file.toString()); return request;
    }

    @Test void shavesEntireExposedShelfButKeepsOpenTopBasinAndOneUndoRestoresEverything() throws Exception {
        var original = column();
        for (int x = 4; x <= 10; x++) for (int z = 4; z <= 10; z++) {
            set(original,x,9,z,STONE);
            set(original,x,10,z, x == 4 || x == 10 || z == 4 || z == 10 ? STONE : WATER);
            if (x > 4 && x < 10 && z > 4 && z < 10) set(original,x,11,z,WATER);
        }
        var doc = document(original);
        var request = request("[{\"mode\":\"erode\",\"match\":[\"WATER\"]},{\"match\":[\"WATER\"],\"palette\":[{\"type\":\"CYAN_STAINED_GLASS\"}]}]");
        long revision = doc.revision(); request.addProperty("validateOnly", true);
        var preview = BlockReplacement.apply(doc, request, null);
        assertEquals(50, preview.get("changedBlocks").getAsInt());
        assertEquals(revision, doc.revision());
        request.addProperty("validateOnly", false);
        var applied = BlockReplacement.apply(doc, request, null);
        assertEquals(25, applied.getAsJsonArray("operations").get(0).getAsJsonObject().get("changedBlocks").getAsInt());
        var edited = doc.snapshot().materializedColumn(0,0);
        for (int x = 5; x < 10; x++) for (int z = 5; z < 10; z++) {
            assertTrue(get(edited,x,11,z).isAir());
            assertEquals(CYAN_STAINED_GLASS, get(edited,x,10,z).getType());
            assertEquals(STONE, get(edited,x,9,z).getType());
            assertEquals(WATER, get(original,x,11,z).getType());
        }
        doc.undo(); assertEquals(WATER, get(doc.snapshot().materializedColumn(0,0),7,11,7).getType());
    }

    @Test void noiseIsCoherentDeterministicAndUsesGlobalCoordinatesAcrossSelections() throws Exception {
        var original = column();
        for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++) set(original,x,10,z,SOUL_SAND);
        var whole = document(original); var cropped = document(original);
        var request = request("[{\"match\":[\"SOUL_SAND\"],\"seed\":73022,\"scale\":5,\"palette\":[{\"type\":\"DIRT\"},{\"type\":\"COARSE_DIRT\"}]}]");
        BlockReplacement.apply(whole, request, null);
        Path file = Path.of(request.get("recipe").getAsString());
        var recipe = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
        recipe.addProperty("minX",8); recipe.addProperty("width",8); Files.writeString(file,recipe.toString());
        BlockReplacement.apply(cropped, request, null);
        int transitions = 0, dirt = 0, coarse = 0;
        var a = whole.snapshot().materializedColumn(0,0); var b = cropped.snapshot().materializedColumn(0,0);
        for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++) {
            var type = get(a,x,10,z).getType();
            if (type == DIRT) dirt++; else if (type == COARSE_DIRT) coarse++;
            if (x >= 8) assertEquals(get(a,x,10,z),get(b,x,10,z));
            else assertEquals(SOUL_SAND,get(b,x,10,z).getType());
            if (x > 0 && type != get(a,x-1,10,z).getType()) transitions++;
        }
        assertTrue(dirt > 10 && coarse > 10);
        assertTrue(transitions < 65, "Splotches should be spatially coherent, not independent per-block noise");
        assertEquals(BlockReplacement.noise(-17,-3,5,123),BlockReplacement.noise(-17,-3,5,123));
    }

    @Test void invalidLaterOperationAndBlockEntityReplacementRejectAtomically() throws Exception {
        var original = column(); set(original,1,10,1,COBWEB); set(original,2,10,2,BARREL);
        var barrel = new BarrelBlockEntity(); barrel.setX(2); barrel.setY(10); barrel.setZ(2); original.getBlockEntities().add(barrel);
        var doc = document(original); long revision = doc.revision();
        var invalid = request("[{\"match\":[\"COBWEB\"],\"palette\":[{\"type\":\"AIR\"}]},{\"match\":[\"NOT_A_BLOCK\"],\"palette\":[{\"type\":\"AIR\"}]}]");
        assertThrows(IllegalArgumentException.class, () -> BlockReplacement.apply(doc,invalid,null));
        var entity = request("[{\"match\":[\"BARREL\"],\"palette\":[{\"type\":\"AIR\"}]}]");
        assertThrows(IllegalArgumentException.class, () -> BlockReplacement.apply(doc,entity,null));
        assertEquals(revision,doc.revision()); assertEquals(COBWEB,get(original,1,10,1).getType());
    }

    @Test void clearsWaterloggingWithoutRemovingTheStructure() throws Exception {
        var original = column();
        var wet = new ChunkerBlockIdentifier(OAK_STAIRS).copyWith(VanillaBlockStates.WATERLOGGED,Bool.TRUE);
        ColumnOps.writeableChunk(original,(byte)0).set(1,10,1,wet);
        var doc = document(original); var request = request("[]");
        Path file = Path.of(request.get("recipe").getAsString());
        var recipe = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
        recipe.addProperty("clearWaterlogged",true); Files.writeString(file,recipe.toString());
        var result = BlockReplacement.apply(doc,request,null);
        assertEquals(1,result.get("driedBlocks").getAsInt());
        var dry = get(doc.snapshot().materializedColumn(0,0),1,10,1);
        assertEquals(OAK_STAIRS,dry.getType()); assertEquals(Bool.FALSE,dry.getState(VanillaBlockStates.WATERLOGGED));
        assertEquals(Bool.TRUE,get(original,1,10,1).getState(VanillaBlockStates.WATERLOGGED));
    }
}
