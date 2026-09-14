package gg.swim.chunkdaddy.worker.document;

import gg.swim.chunkdaddy.worker.util.ChunkRect;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * What changed as a result of an edit.
 *
 * <p>The native side uses {@code changedBounds} to invalidate preview tiles and
 * {@code notices} to surface consequences the user has to know about, such as an arena
 * that a partial edit has invalidated.
 */
public record EditResult(long revision,
                         @Nullable ChunkRect changedBounds,
                         List<String> notices,
                         List<String> invalidatedArenas) {
    public static EditResult of(long revision, @Nullable ChunkRect bounds) {
        return new EditResult(revision, bounds, List.of(), List.of());
    }
}
