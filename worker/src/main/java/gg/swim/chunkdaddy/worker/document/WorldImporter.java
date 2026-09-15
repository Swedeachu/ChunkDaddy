package gg.swim.chunkdaddy.worker.document;

import com.hivemc.chunker.conversion.WorldConverter;
import com.hivemc.chunker.conversion.encoding.base.reader.LevelReader;
import com.hivemc.chunker.conversion.encoding.bedrock.BedrockEncoders;
import com.hivemc.chunker.conversion.encoding.java.JavaEncoders;
import com.hivemc.chunker.conversion.intermediate.column.ChunkerColumn;
import com.hivemc.chunker.conversion.intermediate.world.Dimension;
import com.hivemc.chunker.scheduling.task.TrackedTask;
import gg.swim.chunkdaddy.worker.bedrock.TargetProfile;

import java.nio.file.Path;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Reads an existing world into the document model.
 *
 * <p>The source is opened from an immutable working snapshot: importing never writes to
 * the file the user dropped. Only the editable dimension is captured, and the source
 * world's own settings are not inherited by the destination document; a paste carries
 * blocks, not world configuration.
 */
public final class WorldImporter {
    /** Result of an import, including what could not be brought across. */
    public record Result(Map<Long, ChunkerColumn> columns,
                         List<String> otherDimensions,
                         List<String> notices) {
    }

    private WorldImporter() {
    }

    public static Result importWorld(Path worldDirectory,
                                     SourceInspector.Edition edition,
                                     TargetProfile profile,
                                     WorldConverter converter) throws Exception {
        Optional<? extends LevelReader> reader = switch (edition) {
            case BEDROCK -> BedrockEncoders.createReader(worldDirectory.toFile(), converter);
            case JAVA -> JavaEncoders.createReader(worldDirectory.toFile(), converter);
        };
        if (reader.isEmpty()) {
            throw new IllegalArgumentException(
                    "Chunker cannot read " + worldDirectory + " as a " + edition + " world. "
                            + "Its version may be outside the range the pinned Chunker revision supports.");
        }

        CaptureLevelWriter writer = new CaptureLevelWriter(profile.version(), Dimension.OVERWORLD);

        // Entities and maps are deliberately excluded from region composition; see the
        // conversion report. Block entities are essential and stay on.
        converter.setProcessEntities(false);
        converter.setProcessMaps(false);
        converter.setProcessBlockEntities(true);
        converter.setProcessBiomes(true);
        converter.setDiscardEmptyChunks(false);

        TrackedTask<Void> task = converter.convert(reader.get(), writer);
        task.future().join();

        List<String> notices = new java.util.ArrayList<>();
        if (!writer.otherDimensionsSeen().isEmpty()) {
            notices.add("This world also contains " + String.join(", ", writer.otherDimensionsSeen())
                    + ". Only the Overworld was imported; no dimension is merged into another.");
        }
        notices.add("Ordinary entities were not imported. If the source relies on them, "
                + "re-import with entity processing enabled and review the result.");

        // Packed (x,z) longs have heavily colliding hashes on rectangular grids.
        // Map.copyOf uses linear probing and becomes quadratic here; HashMap's
        // collision trees keep large imports fast while the wrapper stays read-only.
        return new Result(Collections.unmodifiableMap(new HashMap<>(writer.columns())),
                List.copyOf(writer.otherDimensionsSeen()), notices);
    }

    /** A fresh converter per import job, so missing-mapping reports stay per source. */
    public static WorldConverter newConverter() {
        return new WorldConverter(UUID.randomUUID());
    }
}
