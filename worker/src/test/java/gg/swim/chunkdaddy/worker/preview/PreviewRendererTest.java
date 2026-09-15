package gg.swim.chunkdaddy.worker.preview;

import com.hivemc.chunker.conversion.intermediate.column.ChunkerColumn;
import com.hivemc.chunker.conversion.intermediate.column.chunk.ChunkCoordPair;
import com.hivemc.chunker.conversion.intermediate.column.chunk.identifier.ChunkerBlockIdentifier;
import com.hivemc.chunker.conversion.intermediate.column.chunk.identifier.type.block.ChunkerVanillaBlockType;
import gg.swim.chunkdaddy.worker.document.ColumnOps;
import gg.swim.chunkdaddy.worker.document.TemplateRegistry;
import gg.swim.chunkdaddy.worker.document.WorldSnapshot;
import gg.swim.chunkdaddy.worker.util.ChunkRect;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.DataInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class PreviewRendererTest {
    @TempDir Path directory;

    @Test void overviewAndFullResolutionPreserveColumnStatesAndSurfaceSamples() throws Exception {
        var column = new ChunkerColumn(new ChunkCoordPair(0, 0));
        var palette = ColumnOps.writeableChunk(column, (byte) 0);
        for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++)
            palette.set(x, 0, z, new ChunkerBlockIdentifier(ChunkerVanillaBlockType.STONE));
        var snapshot = WorldSnapshot.empty().toBuilder().putColumn(0, 0, column).build();
        var renderer = new PreviewRenderer(new TemplateRegistry());
        int expectedColor = 0;
        for (int pixels : new int[]{16, 4, 1}) {
            var output = directory.resolve("preview-" + pixels);
            renderer.render(snapshot, new ChunkRect(0, 0, 2, 0), 320,
                    PreviewRenderer.HeightMode.HIGHEST_SURFACE, new ChunkRect(0, 0, 1, 0), output, pixels);
            try (var input = new DataInputStream(Files.newInputStream(output))) {
                assertEquals(0x43444154, input.readInt());
                assertEquals(pixels == 16 ? 1 : 2, input.readInt());
                assertEquals(0, input.readInt()); assertEquals(0, input.readInt());
                assertEquals(3, input.readInt()); assertEquals(1, input.readInt());
                if (pixels != 16) assertEquals(pixels, input.readInt());
                assertEquals(2, input.readByte());
                for (int i = 0; i < pixels * pixels; i++) {
                    int color = input.readInt();
                    assertNotEquals(0, color);
                    if (expectedColor == 0) expectedColor = color;
                    assertEquals(expectedColor, color);
                }
                assertEquals(1, input.readByte()); // Exported generated void.
                assertEquals(0, input.readByte()); // Outside the export rectangle.
                assertEquals(-1, input.read());
            }
        }
    }

    @Test void rejectsUnsupportedPreviewResolution() {
        var renderer = new PreviewRenderer(new TemplateRegistry());
        assertThrows(IllegalArgumentException.class, () -> renderer.render(WorldSnapshot.empty(),
                new ChunkRect(0, 0, 0, 0), 320, PreviewRenderer.HeightMode.HIGHEST_SURFACE,
                null, directory.resolve("bad"), 0));
    }
}
