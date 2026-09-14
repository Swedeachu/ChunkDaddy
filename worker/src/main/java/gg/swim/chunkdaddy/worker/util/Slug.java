package gg.swim.chunkdaddy.worker.util;

import java.util.Locale;

/** Template slug derivation and validation. */
public final class Slug {
    private Slug() {
    }

    /**
     * Suggest a template slug from a schematic file name.
     *
     * <p>{@code 1-desert.schem} becomes {@code desert}; a leading numeric prefix is
     * stripped only when it is followed by a separator and more text. The result is
     * a suggestion shown to the user, never applied silently: duplicates must be
     * resolved before placement.
     *
     * <p>Note that a slug may itself contain hyphens ({@code tropical-ruins}), so an
     * export ID must never be split on {@code -} to recover the template family.
     */
    public static String suggestFromFileName(String fileName) {
        String base = fileName;
        int slash = Math.max(base.lastIndexOf('/'), base.lastIndexOf('\\'));
        if (slash >= 0) base = base.substring(slash + 1);
        int dot = base.lastIndexOf('.');
        if (dot > 0) base = base.substring(0, dot);

        base = base.trim().toLowerCase(Locale.ROOT);
        base = base.replaceAll("[^a-z0-9]+", "-").replaceAll("(^-+)|(-+$)", "");

        // Strip a leading numeric ordering prefix such as "1-" or "07-", but only if
        // something remains afterwards.
        String stripped = base.replaceFirst("^[0-9]+-(?=.)", "");
        if (!stripped.isEmpty()) base = stripped;

        return base.isEmpty() ? "template" : base;
    }

    /** True when a slug is safe to use as an export ID component. */
    public static boolean isValid(String slug) {
        return slug != null && slug.matches("[a-z0-9]+(-[a-z0-9]+)*");
    }
}
