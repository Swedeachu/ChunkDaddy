package gg.swim.chunkdaddy.worker.bedrock;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.IdentityHashMap;
import java.util.Map;

/**
 * A plain text record of one export attempt, written beside the world it produced.
 *
 * <p>An export that fails inside Chunker surfaces as a single sentence in a dialog, and
 * that sentence is often about Chunker's own invariants rather than about the composition
 * that violated them. "Duplicate chunk processed, unable to solve" names no coordinate, no
 * arena and no edit. Reconstructing what the document actually looked like afterwards is
 * guesswork, and the user has usually moved on by then.
 *
 * <p>So every export writes this log as it goes, flushing each line, and keeps it whether
 * the export succeeded or not. On failure it also carries the full exception chain and the
 * pre-flight audit, which is the part that usually contains the answer. The file is the
 * thing to ask for when someone reports an export that will not run.
 */
public final class ExportLog implements AutoCloseable {
    private static final DateTimeFormatter TIMESTAMP =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");

    private final Path path;
    private final BufferedWriter writer;
    private final Instant started = Instant.now();

    private ExportLog(Path path, BufferedWriter writer) {
        this.path = path;
        this.writer = writer;
    }

    /**
     * Open a log for a world.
     *
     * <p>Tries beside the export destination first, because that is where someone looking
     * for it will look. Falls back to the worker's own workspace when that directory does
     * not exist or cannot be written, which is itself a plausible reason for the export to
     * have failed.
     */
    public static ExportLog open(Path preferredDirectory, Path fallbackDirectory, String worldName) {
        String name = sanitize(worldName) + "-export.log";
        IOException firstFailure = null;
        for (Path directory : new Path[]{preferredDirectory, fallbackDirectory}) {
            if (directory == null) continue;
            try {
                Files.createDirectories(directory);
                Path candidate = directory.resolve(name);
                BufferedWriter writer = Files.newBufferedWriter(candidate, StandardCharsets.UTF_8);
                ExportLog log = new ExportLog(candidate, writer);
                log.line("ChunkDaddy export log");
                log.line("Opened %s", ZonedDateTime.now().format(TIMESTAMP));
                log.line("Chunker pinned at %s", TargetProfile.CHUNKER_COMMIT);
                return log;
            } catch (IOException e) {
                if (firstFailure == null) firstFailure = e;
            }
        }
        // A log that cannot be written must not be the reason an export fails.
        return new ExportLog(null, null);
    }

    /** Where the log landed, or null when no location could be written. */
    public Path path() {
        return path;
    }

    public void section(String title) {
        line("");
        line("== %s ==", title);
    }

    public void line(String format, Object... args) {
        if (writer == null) return;
        String text = args.length == 0 ? format : String.format(format, args);
        try {
            writer.write(text);
            writer.newLine();
            // Flushed per line deliberately: the interesting exports are the ones that
            // die, and a buffered tail is exactly the part that would be lost.
            writer.flush();
        } catch (IOException ignored) {
            // Logging must never mask the failure it is describing.
        }
    }

    /** A line prefixed with the elapsed time, for phase boundaries. */
    public void milestone(String format, Object... args) {
        long millis = Duration.between(started, Instant.now()).toMillis();
        line("[%6.1fs] %s", millis / 1000.0, args.length == 0 ? format : String.format(format, args));
    }

    /** Record a failure with its whole cause chain, innermost cause last. */
    public void failure(Throwable throwable) {
        section("Failure");
        line("Failed after %.1f seconds", Duration.between(started, Instant.now()).toMillis() / 1000.0);
        Map<Throwable, Boolean> seen = new IdentityHashMap<>();
        for (Throwable current = throwable; current != null && seen.put(current, Boolean.TRUE) == null;
             current = current.getCause()) {
            line("");
            line("%s: %s", current.getClass().getName(), current.getMessage());
            StringWriter trace = new StringWriter();
            current.printStackTrace(new PrintWriter(trace));
            line(trace.toString().stripTrailing());
            if (current.getCause() == current) break;
        }
    }

    @Override
    public void close() {
        if (writer == null) return;
        line("");
        line("Closed %s after %.1f seconds", ZonedDateTime.now().format(TIMESTAMP),
                Duration.between(started, Instant.now()).toMillis() / 1000.0);
        try {
            writer.close();
        } catch (IOException ignored) {
            // Nothing useful remains to be done with a log that will not close.
        }
    }

    private static String sanitize(String name) {
        if (name == null || name.isBlank()) return "world";
        StringBuilder out = new StringBuilder(name.length());
        for (char c : name.trim().toCharArray()) {
            out.append(Character.isLetterOrDigit(c) || c == '-' || c == '_' ? c : '-');
        }
        String cleaned = out.toString().replaceAll("-{2,}", "-").replaceAll("^-|-$", "");
        return cleaned.isEmpty() ? "world" : cleaned;
    }
}
