package gg.swim.chunkdaddy.worker.document;

import com.hivemc.chunker.conversion.intermediate.column.ChunkerColumn;
import com.hivemc.chunker.conversion.intermediate.column.chunk.ChunkCoordPair;
import gg.swim.chunkdaddy.worker.util.Checked;
import gg.swim.chunkdaddy.worker.util.ChunkRect;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A column is filed under a chunk key and also carries its own coordinate, and the export
 * writer takes the coordinate from the column rather than from the key. Paste used to add
 * the relative offset on top of the destination corner, so a column landed under key
 * {@code destX + rx} while declaring position {@code destX + 2rx}. Nothing noticed until
 * export, where two columns claimed one position and Chunker aborted the whole run with
 * "Duplicate chunk processed, unable to solve" - a message that names neither coordinate.
 */
class PasteRelocationTest {
    private static WorldDocument documentCovering(ChunkRect area) {
        WorldDocument document = new WorldDocument("test", "bedrock-1.26.40", new TemplateRegistry());
        Map<Long, ChunkerColumn> columns = new LinkedHashMap<>();
        for (int cx = area.minX(); cx <= area.maxX(); cx++) {
            for (int cz = area.minZ(); cz <= area.maxZ(); cz++) {
                columns.put(Checked.chunkKey(cx, cz), new ChunkerColumn(new ChunkCoordPair(cx, cz)));
            }
        }
        document.importColumns(columns);
        return document;
    }

    private static void assertEveryColumnSitsWhereItIsFiled(WorldSnapshot snapshot) {
        for (Long key : snapshot.materializedKeys()) {
            int cx = Checked.chunkKeyX(key);
            int cz = Checked.chunkKeyZ(key);
            ChunkCoordPair position = snapshot.materializedColumn(cx, cz).getPosition();
            assertEquals(cx, position.chunkX(), "column at key " + cx + "," + cz + " has wrong X");
            assertEquals(cz, position.chunkZ(), "column at key " + cx + "," + cz + " has wrong Z");
        }
    }

    @Test
    void pasteFilesEveryColumnAtTheCoordinateItDeclares() {
        // Captured far from the origin, so a doubled offset is unmistakable.
        WorldDocument source = documentCovering(new ChunkRect(100, 200, 103, 203));
        ClipboardContent clipboard =
                source.capture(new ChunkSelection().add(new ChunkRect(100, 200, 103, 203)), false, null);

        WorldDocument target = new WorldDocument("target", "bedrock-1.26.40", new TemplateRegistry());
        target.paste(clipboard, -7, 5, false);

        WorldSnapshot snapshot = target.snapshot();
        assertEquals(16, snapshot.materializedCount());
        assertEveryColumnSitsWhereItIsFiled(snapshot);
        assertEquals(new ChunkRect(-7, 5, -4, 8), snapshot.contentChunkBounds());
    }

    @Test
    void pasteIntoAPopulatedWorldEmitsEachPositionExactlyOnce() {
        // The reported failure: chunks copied out of one world into another that already
        // had content. This mirrors what ComposedLevelReader does when it walks the export
        // rectangle, which is where the collision actually surfaced.
        WorldDocument source = documentCovering(new ChunkRect(40, 40, 43, 43));
        ClipboardContent clipboard =
                source.capture(new ChunkSelection().add(new ChunkRect(40, 40, 43, 43)), false, null);

        WorldDocument target = documentCovering(new ChunkRect(0, 0, 11, 11));
        target.paste(clipboard, 2, 2, true);

        WorldSnapshot snapshot = target.snapshot();
        ChunkRect rectangle = snapshot.contentChunkBounds();
        Map<ChunkCoordPair, Integer> emitted = new HashMap<>();
        for (int cx = rectangle.minX(); cx <= rectangle.maxX(); cx++) {
            for (int cz = rectangle.minZ(); cz <= rectangle.maxZ(); cz++) {
                ChunkerColumn column = snapshot.materializedColumn(cx, cz);
                ChunkCoordPair position =
                        column != null ? column.getPosition() : new ChunkCoordPair(cx, cz);
                assertEquals(1, emitted.merge(position, 1, Integer::sum),
                        "two columns would be written to chunk " + position.chunkX() + ","
                                + position.chunkZ() + "; Chunker refuses this as a duplicate");
            }
        }
        assertEquals(rectangle.columnCount(), emitted.size());
    }

    @Test
    void movingASelectionKeepsColumnsAndKeysInStep() {
        WorldDocument document = documentCovering(new ChunkRect(0, 0, 3, 3));
        document.translateSelection(new ChunkSelection().add(new ChunkRect(0, 0, 3, 3)), 12, -9, false, null);
        assertEveryColumnSitsWhereItIsFiled(document.snapshot());
        assertEquals(new ChunkRect(12, -9, 15, -6), document.snapshot().contentChunkBounds());
    }

    @Test
    void filingAColumnUnderTheWrongKeyIsRefusedImmediately() {
        WorldSnapshot.Builder builder = WorldSnapshot.empty().toBuilder();
        ChunkerColumn stray = new ChunkerColumn(new ChunkCoordPair(3, 4));
        IllegalStateException error =
                assertThrows(IllegalStateException.class, () -> builder.putColumn(5, 4, stray));
        assertTrue(error.getMessage().contains("5,4"), error.getMessage());
        assertTrue(error.getMessage().contains("3,4"), error.getMessage());
    }
}
