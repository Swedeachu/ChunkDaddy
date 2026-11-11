package gg.swim.chunkdaddy.worker.preview;

import com.google.gson.JsonParser;
import com.hivemc.chunker.conversion.intermediate.column.ChunkerColumn;
import com.hivemc.chunker.conversion.intermediate.column.chunk.ChunkCoordPair;
import com.hivemc.chunker.conversion.intermediate.column.chunk.identifier.ChunkerBlockIdentifier;
import com.hivemc.chunker.conversion.intermediate.column.chunk.identifier.type.block.ChunkerVanillaBlockType;
import gg.swim.chunkdaddy.worker.bedrock.TargetProfile;
import gg.swim.chunkdaddy.worker.document.ColumnOps;
import gg.swim.chunkdaddy.worker.document.TemplateRegistry;
import gg.swim.chunkdaddy.worker.document.WorldDocument;
import gg.swim.chunkdaddy.worker.util.Checked;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MapRendererTest {
    @TempDir Path directory;

    private static ChunkerColumn stone(int x, int z) {
        var column = new ChunkerColumn(new ChunkCoordPair(x, z));
        var chunk = ColumnOps.writeableChunk(column, (byte) 0);
        for (int lx = 0; lx < 16; lx++)
            for (int lz = 0; lz < 16; lz++)
                chunk.set(lx, 4, lz, new ChunkerBlockIdentifier(ChunkerVanillaBlockType.STONE));
        return column;
    }

    @Test void markersLabelTheirClusterAndStrayChunksAreReported() throws Exception {
        Map<Long, ChunkerColumn> columns = new HashMap<>();
        // Arena A: chunks 0..1 x 0..1. Arena B: 10..11 x 0..1. A stray chunk at 30,30.
        for (int[] c : new int[][]{{0, 0}, {1, 0}, {0, 1}, {1, 1}, {10, 0}, {11, 0}, {10, 1}, {11, 1}, {30, 30}})
            columns.put(Checked.chunkKey(c[0], c[1]), stone(c[0], c[1]));
        var document = new WorldDocument("sample", TargetProfile.defaultProfile().id(), new TemplateRegistry());
        document.importColumns(columns);

        var request = JsonParser.parseString("""
                {"blocksPerPixel":4,"marginChunks":1,
                 "markers":[{"label":"a-1","group":"alpha","x":8,"z":8},
                            {"label":"b-1","group":"beta","x":170,"z":20},
                            {"label":"nowhere","group":"beta","x":-900,"z":-900}],
                 "groups":[{"name":"alpha","rgb":"#ff0000"}]}""").getAsJsonObject();
        Path destination = directory.resolve("map.png");
        request.addProperty("destination", destination.toString());
        var result = MapRenderer.render(document.snapshot(), document.name(), request);

        assertEquals(9, result.get("contentColumns").getAsInt());
        assertEquals(3, result.get("clusters").getAsInt());
        assertEquals(1, result.getAsJsonArray("unlabeled").size());
        assertEquals(30, result.getAsJsonArray("unlabeled").get(0).getAsJsonObject().get("minChunkX").getAsInt());
        assertEquals("nowhere", result.getAsJsonArray("missedMarkers").get(0).getAsJsonObject().get("label").getAsString());
        assertEquals("#FF0000", result.getAsJsonArray("groups").get(0).getAsJsonObject().get("rgb").getAsString());

        var image = ImageIO.read(destination.toFile());
        assertEquals(result.get("width").getAsInt(), image.getWidth());
        // 32 chunks across with the margin, 4 px per chunk, plus the axis gutter and the legend.
        assertTrue(image.getWidth() > 32 * 4);
    }
}
