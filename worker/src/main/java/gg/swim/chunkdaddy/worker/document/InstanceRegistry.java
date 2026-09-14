package gg.swim.chunkdaddy.worker.document;

import java.util.HashMap;
import java.util.Map;

/**
 * Allocates arena ordinals per template slug.
 *
 * <p>Ordinals are allocated once and never recycled. Deleting {@code desert-2} must not
 * renumber {@code desert-3}: coordinates derived from these names may already be deployed
 * on a server.
 */
public final class InstanceRegistry {
    private final Map<String, Integer> nextOrdinal = new HashMap<>();

    public int allocate(String slug) {
        int ordinal = nextOrdinal.getOrDefault(slug, 1);
        nextOrdinal.put(slug, ordinal + 1);
        return ordinal;
    }

    /** Record an ordinal seen when reopening a project, so new copies never collide. */
    public void observe(String slug, int ordinal) {
        nextOrdinal.merge(slug, ordinal + 1, Math::max);
    }

    public static String exportId(String slug, int ordinal) {
        return slug + "-" + ordinal;
    }
}
