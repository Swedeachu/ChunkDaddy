package gg.swim.chunkdaddy.worker.bedrock;

import com.hivemc.chunker.conversion.intermediate.column.ChunkerColumn;
import com.hivemc.chunker.conversion.intermediate.column.chunk.ChunkCoordPair;
import gg.swim.chunkdaddy.worker.document.ArenaInstance;
import gg.swim.chunkdaddy.worker.document.WorldSnapshot;
import gg.swim.chunkdaddy.worker.util.Checked;
import gg.swim.chunkdaddy.worker.util.ChunkRect;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Checks a composition against the assumptions the writer makes, before the writer runs.
 *
 * <p>Chunker's pipeline assumes each column is offered exactly once, at the coordinate the
 * column itself declares. When something upstream breaks that, the failure appears a long
 * way from the cause: a guard deep in the pre-transform handler throws
 * "Duplicate chunk processed, unable to solve", which names neither coordinate, and by
 * then the world is half written. The two things most likely to break it are a relocation
 * that mis-sets a column's position and an edit that files a column under the wrong key,
 * and both are cheap to detect by looking at the document directly.
 *
 * <p>Findings are split in two. A <b>fault</b> means the export cannot produce a correct
 * world and is refused with the coordinates in the message. A <b>note</b> is something the
 * user should know but that does not make the output wrong, such as content sitting
 * outside the export rectangle. Everything lands in the export log either way.
 */
public final class CompositionAudit {
    private final List<String> faults = new ArrayList<>();
    private final List<String> notes = new ArrayList<>();
    private int misplacedColumns;
    private int columnsOutsideRectangle;
    private long contentColumns;

    private CompositionAudit() {
    }

    public static CompositionAudit run(WorldSnapshot snapshot, ChunkRect rectangle,
                                       int minChunkY, int maxChunkY) {
        CompositionAudit audit = new CompositionAudit();
        audit.checkColumns(snapshot, rectangle);
        audit.checkInstances(snapshot, rectangle, minChunkY, maxChunkY);
        return audit;
    }

    public boolean hasFaults() {
        return !faults.isEmpty();
    }

    public List<String> faults() {
        return List.copyOf(faults);
    }

    public List<String> notes() {
        return List.copyOf(notes);
    }

    /** One line per finding, for the export log. */
    public void writeTo(ExportLog log) {
        log.section("Composition audit");
        log.line("Columns carrying a position that differs from their key: %d", misplacedColumns);
        log.line("Materialized columns outside the export rectangle: %d", columnsOutsideRectangle);
        log.line("Columns inside the rectangle that will carry content: %d", contentColumns);
        if (faults.isEmpty() && notes.isEmpty()) {
            log.line("No findings.");
            return;
        }
        for (String fault : faults) log.line("FAULT  %s", fault);
        for (String note : notes) log.line("note   %s", note);
    }

    /** The message to fail the export with, or null when there are no faults. */
    public String refusal() {
        if (faults.isEmpty()) return null;
        StringBuilder message = new StringBuilder(
                "This composition cannot be written as a correct world:");
        for (int i = 0; i < Math.min(faults.size(), 5); i++) {
            message.append("\n  - ").append(faults.get(i));
        }
        if (faults.size() > 5) {
            message.append("\n  - and ").append(faults.size() - 5).append(" more; see the export log.");
        }
        return message.toString();
    }

    // ------------------------------------------------------------------

    private void checkColumns(WorldSnapshot snapshot, ChunkRect rectangle) {
        for (Long key : snapshot.materializedKeys()) {
            int cx = Checked.chunkKeyX(key);
            int cz = Checked.chunkKeyZ(key);
            ChunkerColumn column = snapshot.materializedColumn(cx, cz);
            if (column == null) continue;

            ChunkCoordPair position = column.getPosition();
            if (position.chunkX() != cx || position.chunkZ() != cz) {
                misplacedColumns++;
                if (faults.size() < 64) {
                    faults.add("the column stored at chunk " + cx + "," + cz + " carries position "
                            + position.chunkX() + "," + position.chunkZ()
                            + "; two columns would be written to the same place");
                }
            }
            if (!rectangle.contains(cx, cz)) {
                columnsOutsideRectangle++;
            }
        }
        if (columnsOutsideRectangle > 0) {
            notes.add(columnsOutsideRectangle + " materialized column(s) lie outside the export "
                    + "rectangle " + describe(rectangle) + " and will not be written.");
        }
        if (misplacedColumns > 64) {
            faults.add("and " + (misplacedColumns - 64) + " further misplaced column(s).");
        }
    }

    private void checkInstances(WorldSnapshot snapshot, ChunkRect rectangle,
                                int minChunkY, int maxChunkY) {
        Map<Long, List<String>> occupancy = new LinkedHashMap<>();
        Map<String, Integer> templateCounts = new TreeMap<>();

        for (ArenaInstance instance : snapshot.instances()) {
            templateCounts.merge(instance.templateSlug(), 1, Integer::sum);
            ChunkRect footprint = instance.chunkBounds();

            if (!rectangle.intersects(footprint)) {
                notes.add("arena " + instance.exportId() + " at " + describe(footprint)
                        + " lies entirely outside the export rectangle and will not be written.");
            } else if (!rectangle.contains(footprint.minX(), footprint.minZ())
                    || !rectangle.contains(footprint.maxX(), footprint.maxZ())) {
                faults.add("arena " + instance.exportId() + " at " + describe(footprint)
                        + " runs past the edge of the export rectangle " + describe(rectangle)
                        + "; it would be written with pieces missing");
            }

            int minInstanceChunkY = instance.minY() >> 4;
            int maxInstanceChunkY = (instance.maxYExclusive() - 1) >> 4;
            if (minInstanceChunkY < minChunkY || maxInstanceChunkY > maxChunkY) {
                faults.add("arena " + instance.exportId() + " spans sub-chunk Y "
                        + minInstanceChunkY + ".." + maxInstanceChunkY
                        + ", outside the target profile's range " + minChunkY + ".." + maxChunkY);
            }

            if (instance.needsRevalidation()) {
                notes.add("arena " + instance.exportId()
                        + " is marked as needing revalidation; an edit landed on it.");
            }

            for (int cx = footprint.minX(); cx <= footprint.maxX(); cx++) {
                for (int cz = footprint.minZ(); cz <= footprint.maxZ(); cz++) {
                    occupancy.computeIfAbsent(Checked.chunkKey(cx, cz), k -> new ArrayList<>(1))
                            .add(instance.exportId());
                }
            }
        }

        // Overlapping arenas are legal - placement is exact replacement, so the last one
        // wins - but it is almost always an accident after a paste or a move, and the
        // result looks like a corrupted arena rather than a layout mistake.
        int overlapping = 0;
        List<String> examples = new ArrayList<>();
        for (Map.Entry<Long, List<String>> entry : occupancy.entrySet()) {
            if (entry.getValue().size() < 2) continue;
            overlapping++;
            if (examples.size() < 5) {
                examples.add("chunk " + Checked.chunkKeyX(entry.getKey()) + ","
                        + Checked.chunkKeyZ(entry.getKey()) + " is claimed by "
                        + String.join(" and ", entry.getValue()));
            }
        }
        if (overlapping > 0) {
            notes.add(overlapping + " column(s) are covered by more than one arena; the later "
                    + "placement overwrites the earlier one. " + String.join("; ", examples));
        }

        for (int cx = rectangle.minX(); cx <= rectangle.maxX(); cx++) {
            for (int cz = rectangle.minZ(); cz <= rectangle.maxZ(); cz++) {
                if (snapshot.hasContent(cx, cz)) contentColumns++;
            }
        }

        if (!templateCounts.isEmpty()) {
            List<String> parts = new ArrayList<>(templateCounts.size());
            templateCounts.forEach((slug, count) -> parts.add(slug + " x" + count));
            notes.add("arena inventory: " + String.join(", ", parts));
        }
    }

    private static String describe(ChunkRect rectangle) {
        return "chunks " + rectangle.minX() + "," + rectangle.minZ()
                + " to " + rectangle.maxX() + "," + rectangle.maxZ();
    }
}
