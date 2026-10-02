package gg.swim.chunkdaddy.worker;

import gg.swim.chunkdaddy.worker.bedrock.VoidCleaner;
import gg.swim.chunkdaddy.worker.bedrock.WorldPackager;
import gg.swim.chunkdaddy.worker.document.SourceInspector;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Headless entry point for the void cleaner.
 *
 * <pre>
 *   chunkdaddy-void-clean &lt;world-or-archive&gt; [output] [options]
 *
 *     --dry-run             report what would go, write nothing
 *     --air-sub-chunks      also drop columns whose only sub-chunks are pure air
 *     --zip                 write a .zip instead of a .mcworld (same bytes, different name)
 *     --folder              write a world folder instead of an archive
 *     --quiet               only the summary line
 * </pre>
 *
 * <p>Accepts a world folder, a {@code .mcworld} or a {@code .zip}; an archive is extracted
 * to a temporary directory first. The source is never modified.
 */
public final class VoidCleanerMain {
    private VoidCleanerMain() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length == 0 || args[0].equals("--help") || args[0].equals("-h")) {
            System.err.println("""
                    chunkdaddy-void-clean <world-or-archive> [output] [options]

                      --dry-run           report what would be removed, write nothing
                      --air-sub-chunks    also drop columns whose only sub-chunks are pure air
                      --zip               write a .zip instead of a .mcworld
                      --folder            write a world folder instead of an archive
                      --quiet             print only the summary line

                    Removes columns that hold nothing from a Bedrock world, leaving every
                    other record - scoreboards, villages, maps, players, ticking areas,
                    structure templates - byte for byte as it was.""");
            System.exit(args.length == 0 ? 2 : 0);
        }

        Path input = Path.of(args[0]);
        Path output = null;
        boolean dryRun = false, airSubChunks = false, folder = false, quiet = false, zip = false;
        for (int i = 1; i < args.length; i++) {
            switch (args[i]) {
                case "--dry-run" -> dryRun = true;
                case "--air-sub-chunks" -> airSubChunks = true;
                case "--zip" -> zip = true;
                case "--folder" -> folder = true;
                case "--quiet" -> quiet = true;
                default -> {
                    if (args[i].startsWith("--")) {
                        System.err.println("Unknown option " + args[i]);
                        System.exit(2);
                    }
                    output = Path.of(args[i]);
                }
            }
        }
        if (!Files.exists(input)) {
            System.err.println("No such file or folder: " + input);
            System.exit(2);
        }

        Path scratch = Files.createTempDirectory("chunkdaddy-void-clean-");
        Path world = input;
        if (Files.isRegularFile(input) && SourceInspector.isArchive(input)) {
            Path extracted = scratch.resolve("source");
            SourceInspector.extract(input, extracted);
            List<SourceInspector.WorldRoot> roots = SourceInspector.findWorldRoots(extracted, 6);
            if (roots.isEmpty()) {
                System.err.println("No Bedrock world found inside " + input);
                System.exit(2);
            }
            world = roots.get(0).directory();
        }
        if (output == null) {
            String base = stripExtension(input.getFileName().toString());
            Path parent = input.toAbsolutePath().getParent();
            String extension = folder ? "" : (zip ? ".zip" : ".mcworld");
            output = parent.resolve(base + "-cleaned" + extension);
        }

        Path staging = folder ? output : scratch.resolve("cleaned-" + UUID.randomUUID());
        long started = System.nanoTime();
        final boolean silent = quiet;
        long[] lastReport = {0};
        VoidCleaner.Report report = VoidCleaner.clean(world, staging,
                new VoidCleaner.Settings(airSubChunks, dryRun),
                keys -> {
                    if (silent) return;
                    if (keys - lastReport[0] < 500_000) return;
                    lastReport[0] = keys;
                    System.err.printf("  %,d keys...%n", keys);
                });

        if (!dryRun && !folder) {
            // MCWORLD and ZIP are the same archive layout; the mode only picks the name
            // the packager expects, so a .zip output is a rename away, not a repack.
            WorldPackager.publish(staging, output,
                    zip ? WorldPackager.OutputMode.ZIP : WorldPackager.OutputMode.MCWORLD);
        }
        double seconds = (System.nanoTime() - started) / 1e9;

        if (!quiet) {
            System.out.printf("columns scanned        : %,d%n", report.columnsScanned());
            System.out.printf("columns kept           : %,d%n", report.columnsKept());
            System.out.printf("columns removed        : %,d  (%.2f%%)%n", report.columnsRemoved(),
                    report.columnsScanned() == 0 ? 0.0
                            : 100.0 * report.columnsRemoved() / report.columnsScanned());
            if (report.airOnlyColumnsRemoved() > 0) {
                System.out.printf("  of those, air only   : %,d%n", report.airOnlyColumnsRemoved());
            }
            if (!dryRun) {
                System.out.printf("keys removed           : %,d%n", report.keysRemoved());
                System.out.printf("value bytes removed    : %,d%n", report.valueBytesRemoved());
                System.out.printf("world records kept     : %,d%n", report.worldRecordsPreserved());
                if (!report.removedByTag().isEmpty()) {
                    System.out.println("removed by record type :");
                    for (Map.Entry<String, Long> e : report.removedByTag().entrySet()) {
                        System.out.printf("    %-24s %,12d%n", e.getKey(), e.getValue());
                    }
                }
            }
            for (String note : report.notes()) System.out.println("note: " + note);
        }
        System.out.printf("%s in %.1fs: %s%n", dryRun ? "Would clean" : "Cleaned " + output,
                seconds, report.summary());

        WorldPackager.deleteRecursively(scratch);
        System.exit(0);
    }

    private static String stripExtension(String name) {
        int dot = name.lastIndexOf('.');
        return dot <= 0 ? name : name.substring(0, dot);
    }
}
