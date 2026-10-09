package gg.swim.chunkdaddy.worker.preview;

import com.google.gson.JsonParser;
import com.hivemc.chunker.conversion.intermediate.column.ChunkerColumn;
import com.hivemc.chunker.conversion.intermediate.column.chunk.ChunkCoordPair;
import com.hivemc.chunker.conversion.intermediate.column.chunk.identifier.ChunkerBlockIdentifier;
import com.hivemc.chunker.conversion.intermediate.column.chunk.identifier.type.block.ChunkerVanillaBlockType;
import gg.swim.chunkdaddy.worker.document.*;
import gg.swim.chunkdaddy.worker.bedrock.TargetProfile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class SurfaceRasterTest {
    @TempDir Path directory;

    @Test void voidAndFlatWaterProduceTheSameOpaquePixel() throws Exception {
        var column = new ChunkerColumn(new ChunkCoordPair(0, 0));
        ColumnOps.writeableChunk(column, (byte) 4).set(1, 0, 0, new ChunkerBlockIdentifier(ChunkerVanillaBlockType.WATER));
        var document = new WorldDocument("sample", TargetProfile.defaultProfile().id(), new TemplateRegistry());
        document.importColumns(Map.of(0L, column));
        var request = JsonParser.parseString("{\"width\":2,\"height\":1,\"blocksPerPixel\":1,\"minX\":0,\"minZ\":0,\"colorRules\":[{\"contains\":[\"water\"],\"rgb\":4210943}]}").getAsJsonObject();
        var destination = directory.resolve("sample.rgba");
        request.addProperty("destination", destination.toString());
        var result = SurfaceRaster.write(document.snapshot(), request);
        byte[] pixels = Files.readAllBytes(destination);
        assertEquals(1, result.get("nonemptyPixels").getAsInt());
        assertArrayEquals(new byte[]{55, 55, (byte) 220, (byte) 255}, Arrays.copyOfRange(pixels, 0, 4));
        assertArrayEquals(Arrays.copyOfRange(pixels, 0, 4), Arrays.copyOfRange(pixels, 4, 8));
        SurfaceRaster.write(WorldSnapshot.empty(), request);
        assertArrayEquals(pixels, Files.readAllBytes(destination));
    }
}
