package gg.swim.chunkdaddy.worker.document;

import com.hivemc.chunker.conversion.encoding.bedrock.BedrockDataVersion;
import com.hivemc.chunker.conversion.encoding.java.JavaDataVersion;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Finds the worlds inside a dropped file or folder.
 *
 * <p>A container can hold several worlds at different depths, as the supplied
 * {@code pvp zone worlds.zip} does. Roots are identified from structure and metadata, not
 * from the extension: a {@code .zip} and a {@code .mcworld} are the same container format,
 * and a folder called anything at all can be a world.
 */
public final class SourceInspector {
    /** Guards against a container that would expand to an unreasonable size. */
    public static final long MAX_EXTRACTED_BYTES = 8L * 1024 * 1024 * 1024;

    public enum Edition {
        BEDROCK,
        JAVA
    }

    /**
     * One world found inside a source.
     *
     * @param relativePath path of the world root relative to the container root, empty
     *                     string when the container itself is the world.
     */
    public record WorldRoot(Path directory,
                            String relativePath,
                            String name,
                            Edition edition,
                            String versionDescription) {
    }

    private SourceInspector() {
    }

    /** True when the path looks like a container we should extract before scanning. */
    public static boolean isArchive(Path path) {
        String lower = path.getFileName().toString().toLowerCase(Locale.ROOT);
        return lower.endsWith(".zip") || lower.endsWith(".mcworld") || lower.endsWith(".mcpack");
    }

    /**
     * Extract a container into a staging directory.
     *
     * <p>Entry names are resolved against the destination and rejected if they escape it;
     * a crafted archive must not be able to write outside the staging area.
     */
    public static void extract(Path archive, Path destination) throws IOException {
        Files.createDirectories(destination);
        Path root = destination.toRealPath();
        long written = 0;
        try (InputStream in = Files.newInputStream(archive);
             ZipInputStream zip = new ZipInputStream(in)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                Path target = root.resolve(entry.getName()).normalize();
                if (!target.startsWith(root)) {
                    throw new IOException("Archive entry escapes the extraction directory: " + entry.getName());
                }
                if (entry.isDirectory()) {
                    Files.createDirectories(target);
                    continue;
                }
                Files.createDirectories(target.getParent());
                long copied = Files.copy(zip, target);
                written += copied;
                if (written > MAX_EXTRACTED_BYTES) {
                    throw new IOException("Archive expands beyond the " + MAX_EXTRACTED_BYTES + " byte limit");
                }
            }
        }
    }

    /** Find every world root under a directory, deepest-first within a bounded depth. */
    public static List<WorldRoot> findWorldRoots(Path root, int maxDepth) throws IOException {
        List<WorldRoot> found = new ArrayList<>();
        scan(root, root, 0, maxDepth, found);
        return found;
    }

    private static void scan(Path base, Path directory, int depth, int maxDepth, List<WorldRoot> found)
            throws IOException {
        if (depth > maxDepth || !Files.isDirectory(directory)) return;

        Optional<WorldRoot> here = classify(base, directory);
        if (here.isPresent()) {
            found.add(here.get());
            // A world root does not contain another world root; stop descending.
            return;
        }
        try (var stream = Files.list(directory)) {
            for (Path child : stream.filter(Files::isDirectory).sorted().toList()) {
                scan(base, child, depth + 1, maxDepth, found);
            }
        }
    }

    private static Optional<WorldRoot> classify(Path base, Path directory) {
        boolean hasLevelDat = Files.isRegularFile(directory.resolve("level.dat"));
        if (!hasLevelDat) return Optional.empty();

        String relative = base.relativize(directory).toString().replace('\\', '/');
        String name = readLevelName(directory).orElseGet(() ->
                directory.getFileName() == null ? "world" : directory.getFileName().toString());

        if (Files.isDirectory(directory.resolve("db"))) {
            String version = BedrockDataVersion.detect(directory.toFile())
                    .map(detected -> detected.getVersion().toString())
                    .orElse("unrecognized Bedrock version");
            return Optional.of(new WorldRoot(directory, relative, name, Edition.BEDROCK, version));
        }
        if (Files.isDirectory(directory.resolve("region"))) {
            String version = JavaDataVersion.detect(directory.toFile())
                    .map(detected -> detected.getVersion().toString())
                    .orElse("unrecognized Java version");
            return Optional.of(new WorldRoot(directory, relative, name, Edition.JAVA, version));
        }
        return Optional.empty();
    }

    private static Optional<String> readLevelName(Path directory) {
        Path levelName = directory.resolve("levelname.txt");
        if (Files.isRegularFile(levelName)) {
            try {
                String value = Files.readString(levelName).trim();
                if (!value.isEmpty()) return Optional.of(value);
            } catch (IOException ignored) {
                // Fall through to the directory name.
            }
        }
        return Optional.empty();
    }
}
