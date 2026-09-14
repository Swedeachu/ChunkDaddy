package gg.swim.chunkdaddy.worker.bedrock;

import com.google.gson.JsonObject;
import com.hivemc.chunker.conversion.WorldConverter;
import com.hivemc.chunker.conversion.encoding.base.writer.LevelWriter;
import com.hivemc.chunker.conversion.encoding.bedrock.BedrockEncoders;
import com.hivemc.chunker.conversion.intermediate.level.ChunkerGeneratorType;
import com.hivemc.chunker.conversion.intermediate.level.ChunkerLevelSettings;
import com.hivemc.chunker.conversion.intermediate.world.Dimension;
import com.hivemc.chunker.scheduling.task.TrackedTask;
import gg.swim.chunkdaddy.worker.conversion.ResolverFactory;
import gg.swim.chunkdaddy.worker.document.ColumnComposer;
import gg.swim.chunkdaddy.worker.document.TemplateRegistry;
import gg.swim.chunkdaddy.worker.document.WorldDocument;
import gg.swim.chunkdaddy.worker.document.WorldSnapshot;
import gg.swim.chunkdaddy.worker.util.ChunkRect;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongConsumer;

/**
 * Writes a document to a Bedrock world and its companion files.
 *
 * <p>The publication order is deliberate: stage into a directory on the destination
 * filesystem, close the database, validate the structure, package, then publish. Export is
 * not a substitute for saving the project, and a failure at any point leaves the previous
 * completed export and the editable document untouched.
 */
public final class BedrockExporter {
    /** Files placed at the world root next to level.dat. */
    public static final String ARENA_JSON = "arenas.json";
    public static final String MANIFEST_JSON = "chunkdaddy-manifest.json";
    public static final String REPORT = "chunkdaddy-conversion-report.txt";

    private final TemplateRegistry templates;
    private final ResolverFactory resolverFactory;

    public BedrockExporter(TemplateRegistry templates, ResolverFactory resolverFactory) {
        this.templates = templates;
        this.resolverFactory = resolverFactory;
    }

    public ExportResult export(WorldDocument document,
                               ExportRequest request,
                               LongConsumer columnProgress,
                               AtomicReference<WorldConverter> converterHandle) throws Exception {
        WorldSnapshot snapshot = document.snapshot();
        ChunkRect rectangle = request.exportRectangle();
        List<String> warnings = new ArrayList<>();

        // Refuse before writing anything if the arena data cannot be produced. An export
        // whose world and JSON disagree is worse than no export at all.
        List<String> unresolved = ArenaJsonWriter.unresolved(snapshot, templates);
        if (!unresolved.isEmpty()) {
            throw new IllegalStateException(
                    "Export blocked; " + unresolved.size() + " arena problem(s):\n  " + String.join("\n  ", unresolved));
        }
        JsonObject arenaJson = ArenaJsonWriter.build(snapshot, templates, request.numberMode());
        String arenaJsonText = ArenaJsonWriter.serialize(arenaJson);

        if (!request.profile().fullyVerified()) {
            warnings.add("Target profile " + request.profile().displayName() + " has not completed the "
                    + "BDS, vanilla client and Tungsten acceptance procedure. See docs/TargetProfiles.md.");
        }

        Path staging = Files.createTempDirectory(
                request.destination().toAbsolutePath().getParent(), ".chunkdaddy-export-");
        Path worldDirectory = staging.resolve(sanitize(request.worldName()));
        Files.createDirectories(worldDirectory);

        long contentColumns;
        long voidColumns;
        try {
            WorldConverter converter = new WorldConverter(UUID.randomUUID());
            converterHandle.set(converter);
            configure(converter);

            Optional<? extends LevelWriter> writer = BedrockEncoders.createWriter(
                    worldDirectory.toFile(), request.profile().version(), converter);
            if (writer.isEmpty()) {
                throw new IllegalStateException(
                        "Chunker has no Bedrock writer for " + request.profile().version()
                                + " at the pinned revision " + TargetProfile.CHUNKER_COMMIT);
            }

            ChunkerLevelSettings settings = buildSettings(request);
            ColumnComposer composer = new ColumnComposer(
                    templates,
                    resolverFactory,
                    request.profile().minChunkY(),
                    request.profile().maxChunkY(),
                    Dimension.OVERWORLD.getFallbackBiome(),
                    null);

            ComposedLevelReader reader = new ComposedLevelReader(
                    snapshot, composer, rectangle, request.profile(), settings, Dimension.OVERWORLD, columnProgress);

            TrackedTask<Void> task = converter.convert(reader, writer.get());
            awaitOrThrow(task);

            contentColumns = reader.emittedContentColumns();
            voidColumns = reader.emittedVoidColumns();

            long expected = rectangle.columnCount();
            long emitted = reader.emittedColumns();
            if (emitted != expected) {
                // Every column inside the rectangle has to be emitted, including the
                // unused cells of a partly filled last grid row.
                throw new IllegalStateException(
                        "Export enumerated " + emitted + " columns but the export rectangle covers " + expected
                                + ". The generated-void contract was not met; refusing to publish.");
            }

            // The database is closed by the converter's free callback before we get here;
            // packaging a database that still has an open writer would capture a torn state.
            validateWorldDirectory(worldDirectory);

            Files.writeString(worldDirectory.resolve("levelname.txt"), request.worldName(), StandardCharsets.UTF_8);
            Files.writeString(worldDirectory.resolve(ARENA_JSON), arenaJsonText, StandardCharsets.UTF_8);
            Files.writeString(worldDirectory.resolve(MANIFEST_JSON),
                    ManifestWriter.serialize(ManifestWriter.build(document, request, templates, rectangle,
                            contentColumns, voidColumns)), StandardCharsets.UTF_8);
            Files.writeString(worldDirectory.resolve(REPORT),
                    ConversionReport.render(document, templates, converter, warnings), StandardCharsets.UTF_8);

            WorldPackager.publish(worldDirectory, request.destination(), request.mode());
        } finally {
            WorldPackager.deleteRecursively(staging);
        }

        boolean companion = false;
        if (request.writeCompanionJson()) {
            try {
                Path sidecar = request.destination().resolveSibling(
                        stripExtension(request.destination().getFileName().toString()) + ".arenas.json");
                // The same bytes as the copy inside the archive; if these ever disagree the
                // internal copy is the authoritative one.
                Files.writeString(sidecar, arenaJsonText, StandardCharsets.UTF_8);
                companion = true;
            } catch (IOException e) {
                warnings.add("The world was published but the companion arenas.json next to it was not written ("
                        + e.getMessage() + "). The copy inside the world is authoritative; regenerate the sidecar "
                        + "before relying on it.");
            }
        }

        int arenas = snapshot.instances().size();
        return new ExportResult(
                request.destination().toString(),
                contentColumns, voidColumns, contentColumns + voidColumns,
                arenas, arenas * 2, companion, warnings);
    }

    // ------------------------------------------------------------------

    private static void configure(WorldConverter converter) {
        // Explicitly requested void columns must survive to the database: an optimization
        // that drops empty sub-chunks would turn a generated gap back into an absent one.
        converter.setDiscardEmptyChunks(false);
        converter.setProcessBiomes(true);
        converter.setProcessHeightMap(true);
        converter.setProcessLighting(true);
        converter.setProcessBlockEntities(true);
        converter.setProcessColumnPreTransform(true);
        // Ordinary entities and in-game maps are not part of a static arena composition;
        // this is a recorded policy rather than a silent omission, and it appears in the
        // conversion report.
        converter.setProcessEntities(false);
        converter.setProcessMaps(false);
        converter.setLevelDBCompaction(true);
    }

    private ChunkerLevelSettings buildSettings(ExportRequest request) {
        ChunkerLevelSettings settings = new ChunkerLevelSettings();
        settings.LevelName = request.worldName();
        settings.GeneratorType = ChunkerGeneratorType.VOID;
        settings.SpawnX = request.worldSpawn()[0];
        settings.SpawnY = request.worldSpawn()[1];
        settings.SpawnZ = request.worldSpawn()[2];
        settings.commandsEnabled = true;

        if (request.arenaPreset()) {
            // These settings are proposed and shown to the user before export; they are not
            // a claim that the world is frozen. randomTickSpeed=0 does not stop scheduled
            // ticks, gravity, fluid flow or player-triggered neighbour updates.
            settings.randomtickspeed = 0;
            settings.domobspawning = false;
            settings.spawnMobs = false;
            settings.dofiretick = false;
            settings.doweathercycle = false;
            settings.dodaylightcycle = false;
            settings.doentitydrops = false;
            settings.dotiledrops = false;
            settings.domobloot = false;
            settings.mobgriefing = false;
            settings.commandblocksenabled = false;
            settings.Difficulty = 0;
        }
        return settings;
    }

    /** Wait for the conversion, surfacing the real cause rather than a wrapper. */
    private static void awaitOrThrow(TrackedTask<Void> task) throws Exception {
        CompletableFuture<Void> future = task.future();
        try {
            future.join();
        } catch (Exception e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            if (cause instanceof Exception exception) throw exception;
            throw new RuntimeException(cause);
        }
    }

    /**
     * Structural check before packaging. This does not prove a server will load the world;
     * it catches the case where the database never materialized at all.
     */
    private static void validateWorldDirectory(Path worldDirectory) throws IOException {
        Path levelDat = worldDirectory.resolve("level.dat");
        if (!Files.isRegularFile(levelDat) || Files.size(levelDat) == 0) {
            throw new IOException("No level.dat was produced in " + worldDirectory);
        }
        Path db = worldDirectory.resolve("db");
        if (!Files.isDirectory(db)) {
            throw new IOException("No db directory was produced in " + worldDirectory);
        }
        try (var stream = Files.list(db)) {
            if (stream.findAny().isEmpty()) {
                throw new IOException("The db directory in " + worldDirectory + " is empty");
            }
        }
    }

    private static String sanitize(String name) {
        String cleaned = name.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
        return cleaned.isEmpty() ? "world" : cleaned;
    }

    private static String stripExtension(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot > 0 ? fileName.substring(0, dot) : fileName;
    }
}
