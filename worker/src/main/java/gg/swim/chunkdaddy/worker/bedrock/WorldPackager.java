package gg.swim.chunkdaddy.worker.bedrock;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Packages a finished world directory.
 *
 * <p>A world archive is a {@code .mcworld} or {@code .zip} whose root holds
 * {@code level.dat}, {@code levelname.txt} and {@code db/} directly, with no enclosing
 * folder. {@code .mcpack} is a resource pack extension and is never offered for a world.
 *
 * <p>The database must be closed before packaging. LevelDB's manifest, table and log files
 * are all part of the world; a file is never skipped because its name contains
 * {@code LOG}.
 */
public final class WorldPackager {
    public enum OutputMode {
        /** ZIP-compatible world archive for the vanilla Bedrock import flow. */
        MCWORLD,
        /** The same layout under a .zip name, convenient for deployment. */
        ZIP,
        /** A plain world folder for BDS or Tungsten. */
        DIRECTORY
    }

    private WorldPackager() {
    }

    public static String extensionFor(OutputMode mode) {
        return switch (mode) {
            case MCWORLD -> ".mcworld";
            case ZIP -> ".zip";
            case DIRECTORY -> "";
        };
    }

    /**
     * Publish a staged world directory to its final destination.
     *
     * <p>Writes to a temporary sibling first and renames on success, so a cancelled or
     * failed export leaves any previous completed output in place.
     */
    public static void publish(Path stagedWorldDirectory, Path destination, OutputMode mode) throws IOException {
        Files.createDirectories(destination.toAbsolutePath().getParent());
        if (mode == OutputMode.DIRECTORY) {
            Path temporary = siblingTemp(destination);
            deleteRecursively(temporary);
            copyRecursively(stagedWorldDirectory, temporary);
            if (Files.exists(destination)) {
                Path previous = siblingTemp(destination);
                Files.move(destination, previous, StandardCopyOption.ATOMIC_MOVE);
                deleteRecursively(previous);
            }
            Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE);
            return;
        }

        Path temporary = siblingTemp(destination);
        try {
            zipDirectory(stagedWorldDirectory, temporary);
            Files.move(temporary, destination,
                    StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static Path siblingTemp(Path destination) {
        return destination.resolveSibling(destination.getFileName() + ".chunkdaddy-partial");
    }

    /**
     * Zip the directory's contents at the archive root. Entries are written in a stable
     * sorted order so two exports of the same revision produce comparable archives.
     */
    public static void zipDirectory(Path directory, Path archive) throws IOException {
        List<Path> files = new ArrayList<>();
        try (var stream = Files.walk(directory)) {
            stream.filter(Files::isRegularFile).forEach(files::add);
        }
        files.sort(Comparator.comparing(path -> directory.relativize(path).toString()));

        try (OutputStream out = Files.newOutputStream(archive);
             ZipOutputStream zip = new ZipOutputStream(out)) {
            for (Path file : files) {
                String name = directory.relativize(file).toString().replace('\\', '/');
                ZipEntry entry = new ZipEntry(name);
                entry.setTime(0L);
                zip.putNextEntry(entry);
                Files.copy(file, zip);
                zip.closeEntry();
            }
        }
    }

    public static void copyRecursively(Path source, Path target) throws IOException {
        Files.walkFileTree(source, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                Files.createDirectories(target.resolve(source.relativize(dir).toString()));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.copy(file, target.resolve(source.relativize(file).toString()),
                        StandardCopyOption.REPLACE_EXISTING);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    public static void deleteRecursively(Path path) throws IOException {
        if (!Files.exists(path)) return;
        try (var stream = Files.walk(path)) {
            for (Path entry : stream.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(entry);
            }
        }
    }
}
