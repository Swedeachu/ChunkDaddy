package gg.swim.chunkdaddy.worker.document;

import com.hivemc.chunker.conversion.intermediate.column.ChunkerColumn;
import gg.swim.chunkdaddy.worker.util.ChunkRect;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * An immutable snapshot of a copied selection, owned by the application rather than the
 * operating system.
 *
 * <p>The contents are captured at copy time and are unaffected by later edits to the
 * source document, and closing the source tab does not invalidate them. Positions are
 * stored relative to the selection's minimum chunk corner so the paste destination is a
 * simple translation.
 */
public record ClipboardContent(UUID id,
                               UUID sourceDocumentId,
                               ChunkRect relativeBounds,
                               /** Relative chunk key -> column, already detached from the source. */
                               Map<Long, ChunkerColumn> columns,
                               /** Instances fully contained by the selection, with relative placements. */
                               List<RelativeInstance> instances,
                               /** True when this is a pending cut: the source clears on a successful paste. */
                               boolean pendingCut) {

    /**
     * An arena instance captured relative to the selection corner. The template identity
     * and local markers travel with it; the export name does not, because a paste creates
     * a new physical arena and must allocate a new one.
     */
    public record RelativeInstance(UUID templateId,
                                   String templateSlug,
                                   int relativeMinX,
                                   int minY,
                                   int relativeMinZ,
                                   int sizeX,
                                   int sizeY,
                                   int sizeZ,
                                   boolean needsRevalidation) {
    }

    public long columnCount() {
        return columns.size();
    }
}
