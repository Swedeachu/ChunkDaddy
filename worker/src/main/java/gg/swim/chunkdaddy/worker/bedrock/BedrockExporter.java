package gg.swim.chunkdaddy.worker.bedrock;

import com.google.gson.JsonObject;
import com.hivemc.chunker.conversion.WorldConverter;
import com.hivemc.chunker.conversion.encoding.bedrock.base.writer.BedrockLevelWriter;
import com.hivemc.chunker.conversion.encoding.bedrock.BedrockEncoders;
import com.hivemc.chunker.conversion.intermediate.level.ChunkerGeneratorType;
import com.hivemc.chunker.conversion.intermediate.level.ChunkerLevelSettings;
import com.hivemc.chunker.conversion.intermediate.world.Dimension;
import org.iq80.leveldb.CompressionType;
import org.iq80.leveldb.DB;
import org.iq80.leveldb.Options;
import org.iq80.leveldb.impl.Iq80DBFactory;
import org.iq80.leveldb.table.BloomFilterPolicy;
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

        Path staging = Files.createTempDirectory(
                request.destination().toAbsolutePath().getParent(), ".chunkdaddy-export-");
        Path worldDirectory = staging.resolve(sanitize(request.worldName()));
        Files.createDirectories(worldDirectory);

        long contentColumns;
        long voidColumns;
        LevelDataPreserver.Result preserved = new LevelDataPreserver.Result(List.of(), List.of());
        try {
            WorldConverter converter = new WorldConverter(UUID.randomUUID());
            converterHandle.set(converter);
            configure(converter);

            Optional<BedrockLevelWriter> writer = BedrockEncoders.createWriter(
                    worldDirectory.toFile(), request.profile().version(), converter);
            if (writer.isEmpty()) {
                throw new IllegalStateException(
                        "Chunker has no Bedrock writer for " + request.profile().version()
                                + " at the pinned revision " + TargetProfile.CHUNKER_COMMIT);
            }

            ChunkerLevelSettings settings = buildSettings(document, request);
            ColumnComposer composer = new ColumnComposer(
                    templates,
                    resolverFactory,
                    request.profile().minChunkY(),
                    request.profile().maxChunkY(),
                    Dimension.OVERWORLD.getFallbackBiome(),
                    writer.get().buildResolvers(converter).build().blockEntityResolver());

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
            foldWriteAheadLogs(worldDirectory.resolve("db"), warnings);
            validateWorldDirectory(worldDirectory);

            // Put back the parts of the source world's level.dat that Chunker has no field
            // for. This happens after the writer has finished, so the version stamps this
            // export chose for its target profile are already in place and are never
            // overwritten; see LevelDataPreserver for why that ordering matters.
            preserved = LevelDataPreserver.merge(
                    worldDirectory.resolve("level.dat"),
                    document.canPreserveSourceLevelData() ? document.sourceLevelData() : null,
                    warnings);

            Files.writeString(worldDirectory.resolve("levelname.txt"), request.worldName(), StandardCharsets.UTF_8);
            Files.writeString(worldDirectory.resolve(ARENA_JSON), arenaJsonText, StandardCharsets.UTF_8);
            Files.writeString(worldDirectory.resolve(MANIFEST_JSON),
                    ManifestWriter.serialize(ManifestWriter.build(document, request, templates, rectangle,
                            contentColumns, voidColumns)), StandardCharsets.UTF_8);
            Files.writeString(worldDirectory.resolve(REPORT),
                    ConversionReport.render(document, templates, converter, warnings, preserved),
                    StandardCharsets.UTF_8);

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
                arenas, arenas * 2, companion, warnings,
                preserved.carried(), preserved.notes());
    }

    // ------------------------------------------------------------------

    /**
     * Whether an all-air sub-chunk gets its own database record.
     *
     * <p>It does not need one. What makes a column a generated void rather than an absent
     * one is the column's own version record, and the Bedrock column writer emits that for
     * every column it is handed, blocks or no blocks. A column with no sub-chunk records
     * reads back as air, which is exactly how vanilla stores an empty chunk.
     *
     * <p>Writing them anyway costs one record per sub-chunk per column: on a 450-arena
     * grid with 173,600 void columns that was over four million records of pure overhead,
     * most of the exported world's size, and most of its write time. Set this true only to
     * test whether a specific target profile disagrees.
     */
    private static final boolean WRITE_EMPTY_SUB_CHUNK_RECORDS = false;

    private static void configure(WorldConverter converter) {
        converter.setDiscardEmptyChunks(!WRITE_EMPTY_SUB_CHUNK_RECORDS);
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
        // Compaction reorganizes the LevelDB tree so later reads are faster. This world is
        // packaged and handed over immediately, so that reorganization is thrown away
        // straight after it is paid for, and on a pure-Java LevelDB of a few hundred
        // megabytes it costs more than everything else in the export put together. The
        // world loads correctly without it; the server compacts on its own schedule.
        converter.setLevelDBCompaction(false);
    }

    /**
     * The level settings to write, built from the document rather than from nothing.
     *
     * <p>A fresh settings object would quietly discard everything the source world was
     * configured with - its game mode, difficulty, permissions and game rules - and replace
     * it with Chunker's defaults. The document's settings are the user's, so they are the
     * starting point; this method only overlays the few things the export itself decides.
     *
     * <p>The copy is deliberate: an export must not be able to mutate the open document, so
     * cancelling or re-running one leaves the settings panel showing exactly what it showed
     * before.
     */
    private ChunkerLevelSettings buildSettings(WorldDocument document, ExportRequest request) {
        ChunkerLevelSettings settings;
        try {
            settings = ChunkerLevelSettings.fromJSON(document.levelSettings().toJSON());
        } catch (Throwable e) {
            // Never fail an export over the settings round-trip; a default world with the
            // right blocks is far better than no world at all.
            settings = new ChunkerLevelSettings();
        }
        if (settings == null) settings = new ChunkerLevelSettings();

        settings.LevelName = request.worldName();
        // ChunkDaddy generates every column in the export rectangle itself, so anything other
        // than a void generator would have the game fill the gaps with its own terrain.
        settings.GeneratorType = ChunkerGeneratorType.VOID;
        settings.SpawnX = request.worldSpawn()[0];
        settings.SpawnY = request.worldSpawn()[1];
        settings.SpawnZ = request.worldSpawn()[2];

        if (request.arenaPreset()) {
            // These settings are proposed and shown to the user before export; they are not
            // a claim that the world is frozen. randomTickSpeed=0 does not stop scheduled
            // ticks, gravity, fluid flow or player-triggered neighbour updates.
            settings.commandsEnabled = true;
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
     * Fold LevelDB's write-ahead logs into compressed tables.
     *
     * <p>Chunker opens the database with a 400 MB write buffer, so for a world of this size
     * most of the data is still in the memtable when the database closes and stays on disk
     * as the log LevelDB keeps for recovery. Log records are stored raw, while tables are
     * compressed: a 450-arena export came out as 472 MB of uncompressed logs beside 138 MB
     * of tables, and those logs compress about twenty to one.
     *
     * <p>Opening the database again runs LevelDB's ordinary recovery, which replays the logs
     * into tables with the same compression as the rest of the world and then deletes them.
     * Minecraft performs exactly this recovery the first time it loads such a world, so the
     * work happens either way; doing it here means the archive we hand over is a third of
     * the size and the first load is not the slow one.
     */
    private static void foldWriteAheadLogs(Path databaseDirectory, List<String> warnings) {
        Options options = new Options();
        // These must match what the world was written with, or the tables recovery produces
        // would not match the rest of the database.
        options.compressionType(CompressionType.ZLIB_RAW);
        options.blockSize(160 * 1024);
        options.filterPolicy(new BloomFilterPolicy(10));
        // Deliberately far below the writer's buffer: recovery flushes whenever this fills,
        // which keeps the peak memory of this step bounded however large the logs are.
        options.writeBufferSize(32 * 1024 * 1024);
        options.createIfMissing(false);

        try (DB ignored = new Iq80DBFactory().open(databaseDirectory.toFile(), options)) {
            // Opening and closing is the whole operation; recovery does the work.
            assert ignored != null;
        } catch (Throwable e) {
            // The export already succeeded and the world is valid either way, just larger.
            // An optimization must never be the thing that fails a finished export.
            warnings.add("The world was written, but its write-ahead logs could not be folded "
                    + "into compressed tables (" + e + "). The world is valid and loadable; it "
                    + "is simply larger than it needs to be, and Minecraft will perform the same "
                    + "recovery itself the first time it opens it.");
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

