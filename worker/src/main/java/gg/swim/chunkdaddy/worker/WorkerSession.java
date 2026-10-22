package gg.swim.chunkdaddy.worker;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.hivemc.chunker.conversion.WorldConverter;
import gg.swim.chunkdaddy.worker.bedrock.ArenaJsonWriter;
import gg.swim.chunkdaddy.worker.bedrock.BedrockExporter;
import gg.swim.chunkdaddy.worker.bedrock.ExportRequest;
import gg.swim.chunkdaddy.worker.bedrock.ExportResult;
import gg.swim.chunkdaddy.worker.bedrock.LevelDataPreserver;
import gg.swim.chunkdaddy.worker.bedrock.TargetProfile;
import gg.swim.chunkdaddy.worker.bedrock.VoidCleaner;
import gg.swim.chunkdaddy.worker.bedrock.WorldPackager;
import gg.swim.chunkdaddy.worker.conversion.ArenaTemplate;
import gg.swim.chunkdaddy.worker.conversion.MappingIssue;
import gg.swim.chunkdaddy.worker.conversion.ResolverFactory;
import gg.swim.chunkdaddy.worker.conversion.TemplateImporter;
import gg.swim.chunkdaddy.worker.document.ArenaInstance;
import gg.swim.chunkdaddy.worker.document.ChunkSelection;
import gg.swim.chunkdaddy.worker.document.ClipboardContent;
import gg.swim.chunkdaddy.worker.document.EditResult;
import gg.swim.chunkdaddy.worker.document.LevelSettingsSchema;
import gg.swim.chunkdaddy.worker.document.SourceInspector;
import gg.swim.chunkdaddy.worker.document.TemplateRegistry;
import gg.swim.chunkdaddy.worker.document.WorldDocument;
import gg.swim.chunkdaddy.worker.document.WorldImporter;
import gg.swim.chunkdaddy.worker.document.ProgressiveWorldImport;
import gg.swim.chunkdaddy.worker.preview.PreviewRenderer;
import gg.swim.chunkdaddy.worker.protocol.Json;
import gg.swim.chunkdaddy.worker.protocol.ProtocolIO;
import gg.swim.chunkdaddy.worker.protocol.WorkerException;
import gg.swim.chunkdaddy.worker.util.ChunkRect;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The worker's state and request handlers.
 *
 * <p>The worker owns decoded block data and every semantic transformation. The native side
 * owns the document commands, the layout arithmetic and the rendering; it never builds a
 * competing world representation and never receives millions of block records.
 */
public final class WorkerSession {
    private final ProtocolIO io;
    private final Path workspace;
    private final TemplateRegistry templates = new TemplateRegistry();
    private final Map<UUID, WorldDocument> documents = new ConcurrentHashMap<>();
    private final Map<UUID, ProgressiveWorldImport> loadingWorlds = new ConcurrentHashMap<>();
    private final Map<UUID, ClipboardContent> clipboard = new ConcurrentHashMap<>();
    private final Map<Long, AtomicReference<WorldConverter>> runningJobs = new ConcurrentHashMap<>();
    private final Map<Long, AtomicBoolean> cancelled = new ConcurrentHashMap<>();
    private final ResolverFactory resolverFactory;
    private final TemplateImporter templateImporter;
    private final WorldConverter sessionConverter = new WorldConverter(UUID.randomUUID());

    public WorkerSession(ProtocolIO io, Path workspace) {
        this.io = io;
        this.workspace = workspace;
        File scratch = workspace.resolve("resolver-scratch").toFile();
        //noinspection ResultOfMethodCallIgnored
        scratch.mkdirs();
        this.resolverFactory = new ResolverFactory(sessionConverter, scratch);
        this.templateImporter = new TemplateImporter(resolverFactory);
    }

    /** Dispatch one request. Exceptions are turned into structured error frames. */
    public void handle(JsonObject request) {
        long id = request.has("id") ? request.get("id").getAsLong() : -1;
        String type = Json.string(request, "type", "");
        try {
            // Preview and inspection remain available; commands that can consume or
            // change world data wait for the full import to publish atomically.
            if (request.has("documentId") && !List.of("preview_tiles", "document_info", "get_level_settings", "close_document").contains(type)) {
                var loading = loadingWorlds.get(Json.uuid(request, "documentId"));
                if (loading != null) {
                    cancelled.computeIfAbsent(id, key -> new AtomicBoolean());
                    loading.awaitComplete(() -> {
                        checkCancelled(id);
                        progress(id, "Finishing world loading before this operation...", loading.loadedColumns(), loading.totalColumns());
                    });
                    checkCancelled(id);
                }
            }
            JsonObject result = switch (type) {
                case "capabilities" -> capabilities();
                case "inspect_source" -> inspectSource(request);
                case "new_document" -> newDocument(request);
                case "close_document" -> closeDocument(request);
                case "document_info" -> documentInfo(request);
                case "open_world" -> openWorld(id, request);
                case "compose_regions" -> composeRegions(id, request);
                case "surface_raster" -> surfaceRaster(request);
                case "terrain_volume" -> gg.swim.chunkdaddy.worker.terrain.TerrainVolume.dump(document(request), request);
                case "replace_blocks" -> gg.swim.chunkdaddy.worker.terrain.BlockReplacement.apply(document(request), request, blockEntityResolver(document(request)));
                case "terrain_displace" -> gg.swim.chunkdaddy.worker.terrain.TerrainDisplacement.apply(document(request), request, blockEntityResolver(document(request)));
                case "survey_region" -> gg.swim.chunkdaddy.worker.document.RegionSurvey.inspect(document(request).snapshot());
                case "verify_regions" -> verifyRegions(request);
                case "realign_block_entities" -> realignBlockEntities(request);
                case "inspect_bedrock_records" -> inspectBedrockRecords(request);
                case "merge_bedrock_chunks" -> mergeBedrockChunks(request);
                case "align_bedrock_region" -> alignBedrockRegion(id, request);
                case "replace_bedrock_regions" -> replaceBedrockRegions(request);
                case "import_schematics" -> importSchematics(id, request);
                case "set_template_spawns" -> setTemplateSpawns(request);
                case "place_grid" -> placeGrid(request);
                case "copy_selection" -> copySelection(request);
                case "paste_clipboard" -> pasteClipboard(request);
                case "move_selection" -> moveSelection(request);
                case "clear_selection" -> clearSelection(request);
                case "undo" -> undo(request);
                case "redo" -> redo(request);
                case "set_export_rectangle" -> setExportRectangle(request);
                case "set_world_spawn" -> setWorldSpawn(request);
                case "get_level_settings" -> getLevelSettings(request);
                case "set_level_settings" -> setLevelSettings(request);
                case "reset_level_settings" -> resetLevelSettings(request);
                case "adopt_level_settings" -> adoptLevelSettings(request);
                case "set_document_name" -> setDocumentName(request);
                case "preview_tiles" -> previewTiles(request);
                case "validate_export" -> validateExport(request);
                case "export_world" -> exportWorld(id, request);
                case "clean_void" -> cleanVoid(id, request);
                case "cancel" -> cancel(request);
                case "shutdown" -> {
                    for (var loading : loadingWorlds.values()) if (loading.loading()) loading.close();
                    yield new JsonObject();
                }
                default -> throw new WorkerException("protocol.unknown", "Unknown request type '" + type + "'");
            };
            io.writeResult(id, result);
            if ("open_world".equals(type) && result.has("documentId")) {
                var loading = loadingWorlds.get(UUID.fromString(result.get("documentId").getAsString()));
                if (loading != null) loading.enableNotifications();
            }
        } catch (WorkerException e) {
            io.writeError(id, e.code(), e.getMessage());
        } catch (Throwable e) {
            e.printStackTrace(System.err);
            io.writeError(id, "worker.failed", describe(e));
        } finally {
            runningJobs.remove(id);
            cancelled.remove(id);
        }
    }

    private static String describe(Throwable throwable) {
        Throwable cause = throwable;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        String message = cause.getMessage();
        return message == null || message.isBlank()
                ? cause.getClass().getSimpleName()
                : cause.getClass().getSimpleName() + ": " + message;
    }

    // ------------------------------------------------------------------
    // Handlers
    // ------------------------------------------------------------------

    private JsonObject capabilities() {
        JsonObject result = new JsonObject();
        result.addProperty("protocolVersion", ProtocolIO.PROTOCOL_VERSION);
        result.addProperty("workerVersion", WorkerMain.VERSION);
        result.addProperty("chunkerCommit", TargetProfile.CHUNKER_COMMIT);
        result.addProperty("javaVersion", System.getProperty("java.version"));
        result.addProperty("maxHeapBytes", Runtime.getRuntime().maxMemory());

        JsonArray schemas = new JsonArray();
        schemas.add("sponge-v1");
        schemas.add("sponge-v2");
        schemas.add("sponge-v3");
        result.add("schematicSchemas", schemas);
        result.add("regionFeatures", Json.ofStrings(List.of("compose_regions", "relative-json", "shared-palettes", "surface_raster", "terrain_volume", "terrain_displace", "replace_blocks", "survey_region", "verify_regions", "realign_block_entities", "inspect_bedrock_records", "merge_bedrock_chunks", "align_bedrock_region", "replace_bedrock_regions")));

        JsonArray profiles = new JsonArray();
        for (TargetProfile profile : TargetProfile.all()) {
            JsonObject entry = new JsonObject();
            entry.addProperty("id", profile.id());
            entry.addProperty("displayName", profile.displayName());
            entry.addProperty("version", profile.version().toString());
            entry.addProperty("minChunkY", profile.minChunkY());
            entry.addProperty("maxChunkY", profile.maxChunkY());
            entry.addProperty("minBlockY", profile.minBlockY());
            entry.addProperty("maxBlockY", profile.maxBlockYInclusive());
            entry.addProperty("stable", profile.stable());
            entry.addProperty("fullyVerified", profile.fullyVerified());
            entry.addProperty("verificationSummary", profile.verificationSummary());
            entry.addProperty("notes", profile.notes());
            profiles.add(entry);
        }
        result.add("targetProfiles", profiles);
        return result;
    }

    private JsonObject realignBlockEntities(JsonObject request) throws Exception {
        Path source = Path.of(Json.string(request, "path"));
        if (!Files.isRegularFile(source) || !SourceInspector.isArchive(source))
            throw new IllegalArgumentException("Realignment requires an archive; its working extraction is disposable");
        JsonArray roots = Json.array(inspectSource(request), "worlds");
        if (roots.size() != 1) throw new IllegalArgumentException("Expected one world");
        Path directory = Path.of(Json.string(roots.get(0).getAsJsonObject(), "directory"));
        var ids = new java.util.HashSet<String>();
        for (var id : Json.array(request, "blockEntityIds")) ids.add(id.getAsString());
        long repaired = gg.swim.chunkdaddy.worker.bedrock.BlockEntityRealigner.realign(directory, ids);
        JsonObject manifest = com.google.gson.JsonParser.parseString(Files.readString(directory.resolve("regions.json"))).getAsJsonObject();
        JsonObject result = gg.swim.chunkdaddy.worker.bedrock.RegionVerifier.verify(directory, manifest);
        Path destination = Path.of(Json.string(request, "destination"));
        WorldPackager.publish(directory, destination, WorldPackager.OutputMode.ZIP);
        result.addProperty("realignedBlockEntities", repaired);
        result.addProperty("path", destination.toString());
        result.addProperty("verifiedDirectory", directory.toString());
        return result;
    }

    private JsonObject verifyRegions(JsonObject request) throws Exception {
        JsonArray roots = Json.array(inspectSource(request), "worlds");
        if (roots.size() != 1) throw new IllegalArgumentException("Expected one world");
        Path directory = Path.of(Json.string(roots.get(0).getAsJsonObject(), "directory"));
        JsonObject manifest = com.google.gson.JsonParser.parseString(Files.readString(directory.resolve("regions.json"))).getAsJsonObject();
        return gg.swim.chunkdaddy.worker.bedrock.RegionVerifier.verify(directory, manifest);
    }

    private Path singleBedrockRoot(String path) throws Exception {
        JsonObject request = new JsonObject();
        request.addProperty("path", path);
        JsonArray roots = Json.array(inspectSource(request), "worlds");
        if (roots.size() != 1 || !Json.string(roots.get(0).getAsJsonObject(), "edition").equals("BEDROCK"))
            throw new IllegalArgumentException("Expected one Bedrock world: " + path);
        return Path.of(Json.string(roots.get(0).getAsJsonObject(), "directory"));
    }

    private JsonObject inspectBedrockRecords(JsonObject request) throws Exception {
        return gg.swim.chunkdaddy.worker.bedrock.BedrockChunkMerger.inspect(singleBedrockRoot(Json.string(request, "path")));
    }

    private JsonObject mergeBedrockChunks(JsonObject request) throws Exception {
        Path base = singleBedrockRoot(Json.string(request, "basePath"));
        Path overlay = singleBedrockRoot(Json.string(request, "overlayPath"));
        ChunkRect footprint = Json.optionalChunkRect(request, "rectangle");
        if (footprint == null) throw new IllegalArgumentException("Placement rectangle is required");
        Path staged = workspace.resolve("merges").resolve(UUID.randomUUID().toString());
        JsonObject result = gg.swim.chunkdaddy.worker.bedrock.BedrockChunkMerger.merge(base, overlay, staged, footprint);
        Path destination = Path.of(Json.string(request, "destination"));
        WorldPackager.publish(staged, destination, WorldPackager.OutputMode.valueOf(Json.string(request, "mode", "ZIP")));
        result.addProperty("path", destination.toString());
        result.addProperty("verifiedDirectory", staged.toString());
        return result;
    }

    /** Match only the requested old footprint; unrelated grid instances never influence alignment. */
    private JsonObject alignBedrockRegion(long requestId, JsonObject request) throws Exception {
        Path reference = singleBedrockRoot(Json.string(request, "referencePath"));
        Path source = singleBedrockRoot(Json.string(request, "sourcePath"));
        ChunkRect bounds = Json.chunkRect(request, "referenceRectangle");
        Path slice = workspace.resolve("alignment").resolve(UUID.randomUUID().toString());
        gg.swim.chunkdaddy.worker.bedrock.BedrockRegionReplacer.slice(reference, slice, bounds);
        TargetProfile profile = TargetProfile.byId(Json.string(request, "profileId", TargetProfile.defaultProfile().id()));
        progress(requestId, "Reading reference footprint", 0, 3);
        var converter = WorldImporter.newConverter();
        runningJobs.put(requestId, new AtomicReference<>(converter));
        var old = WorldImporter.importWorld(slice, SourceInspector.Edition.BEDROCK, profile, converter);
        checkCancelled(requestId);
        progress(requestId, "Reading edited terrain", 1, 3);
        converter = WorldImporter.newConverter();
        runningJobs.put(requestId, new AtomicReference<>(converter));
        var edited = WorldImporter.importWorld(source, SourceInspector.Edition.BEDROCK, profile, converter);
        checkCancelled(requestId);
        progress(requestId, "Matching distinctive terrain and verifying full footprint", 2, 3);
        return gg.swim.chunkdaddy.worker.document.RegionAlignment.align(
                gg.swim.chunkdaddy.worker.document.WorldSnapshot.loadingView(old.columns()), bounds,
                gg.swim.chunkdaddy.worker.document.WorldSnapshot.loadingView(edited.columns()),
                request.has("minimumSimilarity") ? Json.number(request, "minimumSimilarity") : 0.98);
    }

    private JsonObject replaceBedrockRegions(JsonObject request) throws Exception {
        Path base = singleBedrockRoot(Json.string(request, "basePath"));
        Path overlay = singleBedrockRoot(Json.string(request, "overlayPath"));
        List<ChunkRect> rectangles = new ArrayList<>();
        for (JsonElement entry : Json.array(request, "rectangles")) {
            JsonObject b = entry.getAsJsonObject();
            rectangles.add(new ChunkRect(Json.integer(b, "minChunkX"), Json.integer(b, "minChunkZ"),
                    Json.integer(b, "maxChunkX"), Json.integer(b, "maxChunkZ")));
        }
        Path staged = workspace.resolve("replacements").resolve(UUID.randomUUID().toString());
        JsonObject result = gg.swim.chunkdaddy.worker.bedrock.BedrockRegionReplacer.replace(base, overlay, staged, rectangles);
        Path destination = Path.of(Json.string(request, "destination"));
        WorldPackager.publish(staged, destination, WorldPackager.OutputMode.valueOf(Json.string(request, "mode", "ZIP")));
        result.addProperty("path", destination.toString());
        result.addProperty("verifiedDirectory", staged.toString());
        return result;
    }

    private JsonObject inspectSource(JsonObject request) throws Exception {
        Path path = Path.of(Json.string(request, "path"));
        if (!Files.exists(path)) {
            throw new WorkerException("source.missing", "No such file or folder: " + path);
        }

        Path scanRoot = path;
        if (Files.isRegularFile(path) && SourceInspector.isArchive(path)) {
            Path extracted = workspace.resolve("sources").resolve(UUID.randomUUID().toString());
            SourceInspector.extract(path, extracted);
            scanRoot = extracted;
        }

        List<SourceInspector.WorldRoot> roots = SourceInspector.findWorldRoots(scanRoot, 6);
        JsonArray worlds = new JsonArray();
        for (SourceInspector.WorldRoot root : roots) {
            JsonObject entry = new JsonObject();
            entry.addProperty("directory", root.directory().toString());
            entry.addProperty("relativePath", root.relativePath());
            entry.addProperty("name", root.name());
            entry.addProperty("edition", root.edition().name());
            entry.addProperty("versionDescription", root.versionDescription());
            worlds.add(entry);
        }

        JsonObject result = new JsonObject();
        result.addProperty("scanRoot", scanRoot.toString());
        result.add("worlds", worlds);
        if (roots.isEmpty()) {
            result.addProperty("note", "No world roots were found. A world root is a folder holding level.dat "
                    + "together with db/ for Bedrock or region/ for Java.");
        }
        return result;
    }

    private JsonObject newDocument(JsonObject request) {
        String name = Json.string(request, "name", "New World");
        TargetProfile profile = TargetProfile.byId(Json.string(request, "profileId", TargetProfile.defaultProfile().id()));
        WorldDocument document = new WorldDocument(name, profile.id(), templates);
        documents.put(document.id(), document);
        return describeDocument(document);
    }

    private JsonObject closeDocument(JsonObject request) {
        UUID id = Json.uuid(request, "documentId");
        var loading = loadingWorlds.remove(id);
        if (loading != null && loading.loading()) loading.close();
        documents.remove(id);
        // A clipboard entry captured from this document stays valid: closing the source tab
        // must not invalidate a copy the user already made.
        return new JsonObject();
    }

    private JsonObject documentInfo(JsonObject request) {
        return describeDocument(document(request));
    }

    private JsonObject openWorld(long requestId, JsonObject request) throws Exception {
        Path directory = Path.of(Json.string(request, "directory"));
        SourceInspector.Edition edition = SourceInspector.Edition.valueOf(Json.string(request, "edition"));
        TargetProfile profile = TargetProfile.byId(
                Json.string(request, "profileId", TargetProfile.defaultProfile().id()));
        String name = Json.string(request, "name", directory.getFileName().toString());

        if (edition == SourceInspector.Edition.BEDROCK && Json.bool(request, "progressive", false)) {
            WorldDocument document = new WorldDocument(name, profile.id(), templates);
            var loading = new ProgressiveWorldImport(directory, edition, profile, document,
                    job -> io.writeEvent(-1, "document_state", describeDocument(document)));
            runningJobs.put(requestId, new AtomicReference<>(loading.converter()));
            try {
                checkCancelled(requestId);
                loading.awaitInitialView();
                checkCancelled(requestId);
            } catch (Exception error) {
                loading.close();
                checkCancelled(requestId);
                throw error;
            }
            synchronized (document) {
                if (loading.loading()) document.adoptSourceLevel(loading.writer().capturedSettings(),
                        loading.writer().capturedLevelData(), true);
                documents.put(document.id(), document);
                loadingWorlds.put(document.id(), loading);
            }
            JsonObject result = describeDocument(document);
            result.addProperty("importedColumns", loading.totalColumns());
            result.add("notices", Json.ofStrings(List.of("Nearby chunks are ready. The rest of the world continues loading; edits and exports wait for completion.",
                    "Ordinary entities were not imported.")));
            result.add("otherDimensions", Json.ofStrings(List.copyOf(loading.writer().otherDimensionsSeen())));
            return result;
        }

        WorldConverter converter = WorldImporter.newConverter();
        runningJobs.put(requestId, new AtomicReference<>(converter));
        progress(requestId, "Reading world chunks...", 0, 0);

        WorldImporter.Result imported = WorldImporter.importWorld(directory, edition, profile, converter,
                count -> progress(requestId, "Reading world chunks... " + count + " columns ready", count, 0));

        WorldDocument document = new WorldDocument(name, profile.id(), templates);
        documents.put(document.id(), document);
        EditResult edit = document.importColumns(imported.columns());
        // The source world's own configuration comes across with it: its spawn, game mode,
        // difficulty and game rules, plus the raw level.dat tags Chunker has no field for.
        // Losing those silently is exactly the failure this handles.
        document.adoptSourceLevel(imported.sourceSettings(), imported.sourceLevelData(), imported.bedrockSource());
        // An imported source is a starting point, not unsaved work the user did.
        document.markSaved();

        JsonObject result = describeDocument(document);
        result.addProperty("importedColumns", imported.columns().size());
        result.add("sourceExperiments", Json.ofStrings(
                LevelDataPreserver.enabledExperiments(document.sourceLevelData())));
        result.add("notices", Json.ofStrings(imported.notices()));
        result.add("otherDimensions", Json.ofStrings(imported.otherDimensions()));
        if (edit.changedBounds() != null) {
            result.add("importedBounds", Json.of(edit.changedBounds()));
        }
        return result;
    }

    private JsonObject importSchematics(long requestId, JsonObject request) throws Exception {
        List<String> paths = Json.strings(request, "paths");
        JsonArray imported = new JsonArray();
        List<String> failures = new ArrayList<>();
        List<ArenaTemplate> pendingTemplates = new ArrayList<>();

        int index = 0;
        for (String path : paths) {
            checkCancelled(requestId);
            final int completed = index;
            File file = new File(path);
            try {
                ArenaTemplate template = templateImporter.importFile(file, stage -> {
                    checkCancelled(requestId);
                    JsonObject payload = new JsonObject();
                    payload.addProperty("stage", stage);
                    payload.addProperty("fileName", file.getName());
                    payload.addProperty("done", completed);
                    payload.addProperty("total", paths.size());
                    io.writeEvent(requestId, "progress", payload);
                });
                pendingTemplates.add(template);
                imported.add(describeTemplate(template));
            } catch (WorkerException e) {
                if ("job.cancelled".equals(e.code())) throw e;
                failures.add(file.getName() + ": " + describe(e));
            } catch (Exception e) {
                failures.add(file.getName() + ": " + describe(e));
            }
            index++;
            progress(requestId, "Files processed", index, paths.size());
        }

        checkCancelled(requestId);
        for (ArenaTemplate template : pendingTemplates) templates.add(template);
        JsonObject result = new JsonObject();
        result.add("templates", imported);
        result.add("failures", Json.ofStrings(failures));
        return result;
    }

    private JsonObject setTemplateSpawns(JsonObject request) {
        UUID templateId = Json.uuid(request, "templateId");
        ArenaTemplate template = templates.require(templateId);
        double[] one = Json.optionalTriple(request, "spawnPoint1");
        double[] two = Json.optionalTriple(request, "spawnPoint2");
        boolean confirmed = Json.bool(request, "confirmed", false);

        List<String> problems = validateSpawn(template, one, "spawnPoint1");
        problems.addAll(validateSpawn(template, two, "spawnPoint2"));
        if (confirmed && !problems.isEmpty()) {
            throw new WorkerException("spawns.invalid", String.join("; ", problems));
        }

        // Checking unchanged automatic positions must not turn them into an
        // unconfirmed manual draft just because Validate was clicked.
        if (!template.spawnsAutomatic() || confirmed
                || !java.util.Arrays.equals(one, template.spawnPoint1())
                || !java.util.Arrays.equals(two, template.spawnPoint2())) {
            template.setSpawns(one, two, confirmed);
        }
        JsonObject result = describeTemplate(template);
        result.add("warnings", Json.ofStrings(problems));
        return result;
    }

    /**
     * Bounds and support checks for a marker. These are conservative: a full-block air
     * test cannot reason about partial blocks, so the user still confirms.
     */
    private static List<String> validateSpawn(ArenaTemplate template, @Nullable double[] local, String label) {
        List<String> problems = new ArrayList<>();
        if (local == null) {
            problems.add(label + " is not set");
            return problems;
        }
        if (local[0] < 0 || local[0] > template.sizeX()
                || local[1] < 0 || local[1] > template.sizeY()
                || local[2] < 0 || local[2] > template.sizeZ()) {
            problems.add(label + " is outside the template bounds");
            return problems;
        }
        int x = (int) Math.floor(local[0]);
        int y = (int) Math.floor(local[1]);
        int z = (int) Math.floor(local[2]);
        if (y - 1 >= 0 && template.blockAt(x, y - 1, z).isAir()) {
            problems.add(label + " has no supporting block beneath it");
        }
        if (!template.blockAt(x, y, z).isAir()) {
            problems.add(label + " is inside a block");
        } else if (y + 1 < template.sizeY() && !template.blockAt(x, y + 1, z).isAir()) {
            problems.add(label + " does not have room for a player's head");
        }
        return problems;
    }

    private JsonObject placeGrid(JsonObject request) {
        WorldDocument document = document(request);
        TargetProfile profile = TargetProfile.byId(document.targetProfileId());
        boolean replace = Json.bool(request, "replaceExisting", false);

        List<WorldDocument.PlacementRequest> placements = new ArrayList<>();
        for (JsonElement element : Json.array(request, "placements")) {
            JsonObject entry = element.getAsJsonObject();
            placements.add(new WorldDocument.PlacementRequest(
                    Json.uuid(entry, "templateId"),
                    Json.string(entry, "slug"),
                    entry.has("exportId") && !entry.get("exportId").isJsonNull()
                            ? entry.get("exportId").getAsString() : null,
                    Json.integer(entry, "ordinal", 0),
                    Json.integer(entry, "minX"),
                    Json.integer(entry, "minY"),
                    Json.integer(entry, "minZ"),
                    Json.integer(entry, "gridRow", -1),
                    Json.integer(entry, "gridColumn", -1)));
        }

        EditResult edit = document.placeInstances(placements, replace, profile.minChunkY(), profile.maxChunkY());
        return describeEdit(document, edit);
    }

    private JsonObject copySelection(JsonObject request) {
        WorldDocument document = document(request);
        ChunkSelection selection = selection(request);
        boolean cut = Json.bool(request, "cut", false);
        ClipboardContent content = document.capture(selection, cut, blockEntityResolver(document));
        clipboard.put(content.id(), content);

        JsonObject result = new JsonObject();
        result.addProperty("clipboardId", content.id().toString());
        result.addProperty("columns", content.columnCount());
        result.addProperty("arenas", content.instances().size());
        result.addProperty("pendingCut", content.pendingCut());
        result.add("relativeBounds", Json.of(content.relativeBounds()));
        if (cut) {
            result.addProperty("note", "The source is highlighted but unchanged. It is cleared only when the "
                    + "paste commits; cancelling the pending cut leaves it untouched.");
        }
        return result;
    }

    private JsonObject pasteClipboard(JsonObject request) {
        WorldDocument document = document(request);
        UUID clipboardId = Json.uuid(request, "clipboardId");
        ClipboardContent content = clipboard.get(clipboardId);
        if (content == null) {
            throw new WorkerException("clipboard.missing", "No clipboard entry " + clipboardId);
        }
        int destChunkX = Json.integer(request, "destChunkX");
        int destChunkZ = Json.integer(request, "destChunkZ");
        boolean replace = Json.bool(request, "replaceExisting", false);

        EditResult edit = document.paste(content, destChunkX, destChunkZ, replace, blockEntityResolver(document));

        JsonObject result = describeEdit(document, edit);
        if (content.pendingCut()) {
            WorldDocument source = documents.get(content.sourceDocumentId());
            if (source != null) {
                // A cut across two tabs is one workspace transaction: the source clears
                // only now that the paste has committed.
                ChunkSelection sourceSelection = new ChunkSelection();
                for (Long key : content.columns().keySet()) {
                    int cx = gg.swim.chunkdaddy.worker.util.Checked.chunkKeyX(key) + content.relativeBounds().minX();
                    int cz = gg.swim.chunkdaddy.worker.util.Checked.chunkKeyZ(key) + content.relativeBounds().minZ();
                    sourceSelection.add(new ChunkRect(cx, cz, cx, cz));
                }
                if (!sourceSelection.isEmpty()) {
                    EditResult cleared = source.clearSelection(sourceSelection);
                    result.addProperty("sourceDocumentId", source.id().toString());
                    result.addProperty("sourceRevision", cleared.revision());
                }
            }
            clipboard.remove(clipboardId);
        }
        return result;
    }

    private JsonObject moveSelection(JsonObject request) {
        WorldDocument document = document(request);
        ChunkSelection selection = selection(request);
        EditResult edit = document.translateSelection(
                selection,
                Json.integer(request, "deltaChunkX"),
                Json.integer(request, "deltaChunkZ"),
                Json.bool(request, "replaceExisting", false),
                blockEntityResolver(document));
        return describeEdit(document, edit);
    }

    private JsonObject clearSelection(JsonObject request) {
        WorldDocument document = document(request);
        EditResult edit = document.clearSelection(selection(request));
        return describeEdit(document, edit);
    }

    private com.hivemc.chunker.conversion.encoding.base.resolver.blockentity.BlockEntityResolver<?, com.hivemc.chunker.nbt.tags.collection.CompoundTag>
    blockEntityResolver(WorldDocument document) {
        TargetProfile profile = TargetProfile.byId(document.targetProfileId());
        var writer = com.hivemc.chunker.conversion.encoding.bedrock.BedrockEncoders.createWriter(
                workspace.resolve("copy-resolvers").toFile(), profile.version(), sessionConverter).orElseThrow();
        return writer.buildResolvers(sessionConverter).build().blockEntityResolver();
    }

    /** A whole composition is one transaction; failed imports leave no partial document. */
    private JsonObject composeRegions(long requestId, JsonObject request) throws Exception {
        String name = Json.string(request, "name", "Composed Regions");
        TargetProfile profile = TargetProfile.byId(Json.string(request, "profileId", TargetProfile.defaultProfile().id()));
        WorldDocument destination = new WorldDocument(name, profile.id(), templates);
        // All template biome lists below use the modern negative-Y frame.
        destination.levelSettings().CavesAndCliffs = true;
        var resolver = blockEntityResolver(destination);
        var composer = new gg.swim.chunkdaddy.worker.document.RegionComposer();
        JsonArray sources = Json.array(request, "sources");
        int index = 0;
        for (JsonElement element : sources) {
            JsonObject source = element.getAsJsonObject();
            String templateName = Json.string(source, "name");
            progress(requestId, "Importing region " + templateName, index++, sources.size());
            checkCancelled(requestId);
            JsonObject inspect = new JsonObject();
            inspect.addProperty("path", Json.string(source, "path"));
            JsonArray roots = Json.array(inspectSource(inspect), "worlds");
            if (roots.size() != 1) throw new IllegalArgumentException("Source must contain exactly one world: " + templateName);
            JsonObject root = roots.get(0).getAsJsonObject();
            WorldConverter converter = WorldImporter.newConverter();
            runningJobs.put(requestId, new AtomicReference<>(converter));
            WorldImporter.Result imported = WorldImporter.importWorld(Path.of(Json.string(root, "directory")),
                    SourceInspector.Edition.valueOf(Json.string(root, "edition")), profile, converter);
            if (imported.sourceSettings() != null && !imported.sourceSettings().CavesAndCliffs) {
                for (var column : imported.columns().values()) {
                    if (column.getBiomes() == null) continue;
                    var palettes = new java.util.ArrayList<>(column.getBiomes().asPalette());
                    if (palettes.isEmpty()) continue;
                    var bottom = palettes.getFirst();
                    for (int i = 0; i < 4; i++) palettes.add(0, bottom);
                    column.setBiomes(new com.hivemc.chunker.conversion.intermediate.column.biome.layout.ChunkerPaletteBasedBiomes(palettes));
                }
            }
            WorldDocument temporary = new WorldDocument(templateName, profile.id(), templates);
            temporary.importColumns(imported.columns());
            ChunkRect bounds = Json.optionalChunkRect(source, "rectangle");
            if (bounds == null) bounds = temporary.snapshot().contentChunkBounds();
            if (bounds == null) throw new IllegalArgumentException("Empty source " + templateName);
            JsonObject metadata = Json.object(source, "metadata");
            composer.add(templateName, temporary.snapshot(), bounds, metadata,
                    Json.array(source, "coordinatePaths"), Json.array(source, "placements"), resolver);
            if (source.has("raster")) {
                JsonObject raster = source.getAsJsonObject("raster");
                gg.swim.chunkdaddy.worker.preview.SurfaceRaster.write(temporary.snapshot(), raster);
            }
        }
        destination.commitSnapshot(composer.snapshot());
        JsonObject manifest = composer.manifest(name);
        if (request.has("layout")) manifest.add("layout", request.get("layout").deepCopy());
        destination.setRegionManifest(manifest);
        documents.put(destination.id(), destination);
        Path manifestPath = workspace.resolve("regions-" + destination.id() + ".json");
        Files.writeString(manifestPath, new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(manifest));
        JsonObject result = describeDocument(destination);
        result.addProperty("regionManifestPath", manifestPath.toString());
        result.addProperty("regionCount", manifest.getAsJsonArray("instances").size());
        result.addProperty("regionTemplateCount", manifest.getAsJsonObject("templates").size());
        return result;
    }

    private JsonObject surfaceRaster(JsonObject request) throws Exception {
        return gg.swim.chunkdaddy.worker.preview.SurfaceRaster.write(document(request).snapshot(), request);
    }

    private JsonObject undo(JsonObject request) {
        WorldDocument document = document(request);
        document.undo();
        return describeDocument(document);
    }

    private JsonObject redo(JsonObject request) {
        WorldDocument document = document(request);
        document.redo();
        return describeDocument(document);
    }

    private JsonObject setExportRectangle(JsonObject request) {
        WorldDocument document = document(request);
        document.setExportRectangle(Json.optionalChunkRect(request, "rectangle"));
        document.setExportBorderChunks(Json.integer(request, "borderChunks", document.exportBorderChunks()));
        return describeDocument(document);
    }

    private JsonObject setWorldSpawn(JsonObject request) {
        WorldDocument document = document(request);
        document.setWorldSpawn(
                Json.integer(request, "x"), Json.integer(request, "y"), Json.integer(request, "z"));
        return describeDocument(document);
    }

    private JsonObject setDocumentName(JsonObject request) {
        WorldDocument document = document(request);
        document.setName(Json.string(request, "name"));
        return describeDocument(document);
    }

    /**
     * The level settings, described well enough for a generic panel to build controls.
     *
     * <p>The schema travels with the values, so the panel never has a hard-coded idea of
     * which settings exist; a field Chunker gains at a later pinned revision shows up on its
     * own rather than being quietly uneditable.
     */
    private JsonObject getLevelSettings(JsonObject request) {
        WorldDocument document = document(request);
        JsonObject result = LevelSettingsSchema.describe(document.levelSettings());
        result.addProperty("documentId", document.id().toString());
        result.addProperty("revision", document.revision());
        result.addProperty("hasSource", document.hasSourceLevelSettings());
        result.addProperty("preservesSourceLevelData", document.canPreserveSourceLevelData());
        result.add("sourceExperiments", Json.ofStrings(
                LevelDataPreserver.enabledExperiments(document.sourceLevelData())));
        return result;
    }

    /**
     * Apply edited settings.
     *
     * <p>Fields are applied individually and a value that cannot be used is refused by name,
     * so a panel that is out of step with the worker cannot corrupt the rest of the settings
     * or fail the request. The reply always carries the settings as they now actually are,
     * which is what the panel re-displays, so what is on screen is never a guess.
     */
    private JsonObject setLevelSettings(JsonObject request) {
        WorldDocument document = document(request);
        JsonObject values = Json.optionalObject(request, "values");
        LevelSettingsSchema.ApplyResult applied =
                LevelSettingsSchema.apply(document.levelSettings(), values == null ? new JsonObject() : values);
        if (!applied.applied().isEmpty()) {
            document.markLevelSettingsChanged();
        }
        JsonObject result = getLevelSettings(request);
        result.add("applied", Json.ofStrings(applied.applied()));
        result.add("rejected", Json.ofStrings(applied.rejected()));
        return result;
    }

    /** Put the settings back to whatever the source world had. */
    private JsonObject resetLevelSettings(JsonObject request) {
        WorldDocument document = document(request);
        boolean reverted = document.revertLevelSettingsToSource();
        JsonObject result = getLevelSettings(request);
        result.addProperty("reverted", reverted);
        if (!reverted) {
            result.add("rejected", Json.ofStrings(List.of(
                    "This world was not opened from an existing world, so it has no source settings to go back to.")));
        }
        return result;
    }

    /** Copy another open world's settings, for building several worlds the same way. */
    private JsonObject adoptLevelSettings(JsonObject request) {
        WorldDocument document = document(request);
        UUID sourceId = Json.uuid(request, "fromDocumentId");
        WorldDocument source = documents.get(sourceId);
        if (source == null) {
            throw new WorkerException("document.missing", "No open document " + sourceId);
        }
        if (source.id().equals(document.id())) {
            throw new WorkerException("settings.self", "That is the same world.");
        }
        document.adoptLevelSettingsFrom(source);
        JsonObject result = getLevelSettings(request);
        result.addProperty("adoptedFrom", source.name());
        return result;
    }

    private JsonObject previewTiles(JsonObject request) throws Exception {
        WorldDocument document = document(request);
        ChunkRect area = Json.chunkRect(request, "area");
        if (area.columnCount() > 64 * 64) {
            throw new WorkerException("preview.tooLarge",
                    "Preview requests are limited to 4096 columns per call; the viewport should request "
                            + "the tiles it can actually show.");
        }
        int sliceY = Json.integer(request, "sliceY", 320);
        int pixelsPerChunk = Json.integer(request, "pixelsPerChunk", 16);
        PreviewRenderer.HeightMode mode = PreviewRenderer.HeightMode.valueOf(
                Json.string(request, "heightMode", PreviewRenderer.HeightMode.HIGHEST_SURFACE.name()));

        Path output = workspace.resolve("tiles").resolve(UUID.randomUUID() + ".cdat");
        long renderRevision;
        var loading = loadingWorlds.get(document.id());
        if (loading != null && loading.loading()) {
            synchronized (document) { renderRevision = document.revision(); }
            loading.focus(area.minX() + area.widthChunks() / 2, area.minZ() + area.lengthChunks() / 2);
            new PreviewRenderer(templates).render(loading.preview(), area, sliceY, mode,
                    loading.bounds(), output, pixelsPerChunk, loading::pending);
        } else {
            if (loading != null && loading.failure() != null) throw new WorkerException("world.loadFailed", describe(loading.failure()));
            synchronized (document) {
                renderRevision = document.revision();
                new PreviewRenderer(templates).render(document.snapshot(), area, sliceY, mode,
                        document.exportRectangle(), output, pixelsPerChunk);
            }
        }

        JsonObject result = new JsonObject();
        result.addProperty("path", output.toString());
        result.addProperty("formatVersion", pixelsPerChunk == 16 ? PreviewRenderer.FORMAT_VERSION : 2);
        result.add("area", Json.of(area));
        result.addProperty("revision", renderRevision);
        return result;
    }

    private JsonObject validateExport(JsonObject request) {
        WorldDocument document = document(request);
        TargetProfile profile = TargetProfile.byId(
                Json.string(request, "profileId", document.targetProfileId()));
        ChunkRect rectangle = document.exportRectangle();

        JsonObject result = new JsonObject();
        List<String> problems = new ArrayList<>(ArenaJsonWriter.unresolved(document.snapshot(), templates));
        if (rectangle == null) {
            problems.add("The world has no content, so there is nothing to export.");
        } else {
            result.add("exportRectangle", Json.of(rectangle));
            long columns = rectangle.columnCount();
            result.addProperty("columnCount", columns);
            // A rough figure, deliberately conservative, so the user sees the scale before
            // committing to a very large rectangle.
            result.addProperty("estimatedBytes", columns * 2048L);
        }
        result.addProperty("arenaCount", document.snapshot().instances().size());
        result.addProperty("spawnPointCount", document.snapshot().instances().size() * 2);
        long automaticArenas = document.snapshot().instances().stream()
                .filter(instance -> templates.get(instance.templateId()) != null
                        && templates.get(instance.templateId()).spawnsAutomatic()).count();
        result.addProperty("automaticSpawnArenaCount", automaticArenas);
        result.add("problems", Json.ofStrings(problems));
        result.addProperty("profileId", profile.id());
        result.addProperty("profileMinimumClientVersion", profile.minimumClientVersion());
        result.addProperty("profileFullyVerified", profile.fullyVerified());
        result.addProperty("profileVerificationSummary", profile.verificationSummary());
        return result;
    }

    private JsonObject exportWorld(long requestId, JsonObject request) throws Exception {
        WorldDocument document = document(request);
        // The profile can be changed at export time. A composition is expensive to rebuild
        // and the right profile is often only discovered when a client refuses the world,
        // so switching it re-runs the writer over the same committed revision instead of
        // making the user start again. The document keeps the choice.
        TargetProfile profile = TargetProfile.byId(
                Json.string(request, "profileId", document.targetProfileId()));
        if (!profile.id().equals(document.targetProfileId())) {
            document.setTargetProfileId(profile.id());
        }
        ChunkRect rectangle = Json.optionalChunkRect(request, "rectangle");
        if (rectangle == null) rectangle = document.exportRectangle();
        if (rectangle == null) {
            throw new WorkerException("export.empty", "This world has no content to export.");
        }

        ExportRequest exportRequest = new ExportRequest(
                Path.of(Json.string(request, "destination")),
                WorldPackager.OutputMode.valueOf(Json.string(request, "mode", "MCWORLD")),
                rectangle,
                profile,
                Json.string(request, "worldName", document.name()),
                document.worldSpawn(),
                ArenaJsonWriter.NumberMode.valueOf(Json.string(request, "numberMode", "EXACT")),
                Json.bool(request, "arenaPreset", true),
                Json.bool(request, "writeCompanionJson", true),
                // Default keeps the original generated-void contract. Switching it off is
                // only sound when the written level.dat already generates void.
                Json.bool(request, "writeVoidColumns", !Json.bool(request, "voidCleaner", true)),
                Json.bool(request, "voidCleaner", true));

        AtomicReference<WorldConverter> handle = new AtomicReference<>();
        runningJobs.put(requestId, handle);
        long total = rectangle.columnCount();

        BedrockExporter exporter = new BedrockExporter(templates, resolverFactory);
        Path logFallback = workspace.resolve("logs");
        ExportResult exported;
        try {
            exported = exporter.export(document, exportRequest,
                done -> {
                    if (done == total) {
                        // Enumeration finishes before database flush, compaction and
                        // packaging. Keep the UI active until the final reply arrives.
                        progress(requestId, "Finishing world database and packaging export...", 0, 0);
                    } else {
                        progress(requestId, "Preparing world columns", done, total);
                    }
                }, handle, logFallback);
        } catch (BedrockExporter.ExportFailure failure) {
            // The useful detail is in the log, and the dialog only has room for a sentence.
            // Point at the file rather than truncating a stack trace into the message.
            Throwable cause = failure.getCause() == null ? failure : failure.getCause();
            String where = failure.logPath() == null
                    ? "No export log could be written."
                    : "Full details: " + failure.logPath();
            throw new WorkerException("export.failed", describe(cause) + "\n\n" + where, cause);
        }

        JsonObject result = new JsonObject();
        result.addProperty("path", exported.worldPath());
        result.addProperty("contentColumns", exported.contentColumns());
        result.addProperty("voidColumns", exported.voidColumns());
        result.addProperty("totalColumns", exported.totalColumns());
        result.addProperty("arenaCount", exported.arenaCount());
        result.addProperty("spawnPointCount", exported.spawnPointCount());
        result.addProperty("companionJsonWritten", exported.companionJsonWritten());
        result.add("warnings", Json.ofStrings(exported.warnings()));
        result.add("preservedTags", Json.ofStrings(exported.preservedTags()));
        result.add("preservedNotes", Json.ofStrings(exported.preservedNotes()));
        if (exported.logPath() != null) {
            result.addProperty("logPath", exported.logPath());
        }
        result.addProperty("revision", document.revision());
        return result;
    }

    /**
     * Run the void cleaner over a world on disk, without opening it as a document.
     *
     * <p>Deliberately separate from the document model: this is a database operation on a
     * finished world, and routing it through import and re-export would drop every record
     * that is not a chunk.
     */
    private JsonObject cleanVoid(long requestId, JsonObject request) throws Exception {
        Path source = Path.of(Json.string(request, "path"));
        if (!Files.exists(source)) {
            throw new WorkerException("source.missing", "No such file or folder: " + source);
        }
        boolean dryRun = Json.bool(request, "dryRun", false);
        boolean airSubChunks = Json.bool(request, "airSubChunks", false);

        Path scratch = workspace.resolve("void-clean").resolve(UUID.randomUUID().toString());
        Files.createDirectories(scratch);
        Path world = source;
        if (Files.isRegularFile(source) && SourceInspector.isArchive(source)) {
            Path extracted = scratch.resolve("source");
            SourceInspector.extract(source, extracted);
            List<SourceInspector.WorldRoot> roots = SourceInspector.findWorldRoots(extracted, 6);
            if (roots.isEmpty()) {
                throw new WorkerException("source.empty", "No Bedrock world found inside " + source);
            }
            world = roots.get(0).directory();
        }

        String destinationPath = Json.string(request, "destination", "");
        Path destination = destinationPath.isEmpty()
                ? scratch.resolve("cleaned.mcworld") : Path.of(destinationPath);
        WorldPackager.OutputMode mode =
                WorldPackager.OutputMode.valueOf(Json.string(request, "mode", "MCWORLD"));
        Path staged = mode == WorldPackager.OutputMode.DIRECTORY ? destination : scratch.resolve("staged");

        VoidCleaner.Report report = VoidCleaner.clean(world, staged,
                new VoidCleaner.Settings(airSubChunks, dryRun),
                keys -> progress(requestId, "Scanning world database", keys, 0));
        if (!dryRun && mode != WorldPackager.OutputMode.DIRECTORY) {
            WorldPackager.publish(staged, destination, mode);
        }

        JsonObject result = new JsonObject();
        if (!dryRun) result.addProperty("path", destination.toString());
        result.addProperty("dryRun", dryRun);
        result.addProperty("keysScanned", report.keysScanned());
        result.addProperty("columnsScanned", report.columnsScanned());
        result.addProperty("columnsKept", report.columnsKept());
        result.addProperty("columnsRemoved", report.columnsRemoved());
        result.addProperty("keysRemoved", report.keysRemoved());
        result.addProperty("valueBytesRemoved", report.valueBytesRemoved());
        result.addProperty("airOnlyColumnsRemoved", report.airOnlyColumnsRemoved());
        result.addProperty("worldRecordsPreserved", report.worldRecordsPreserved());
        result.addProperty("summary", report.summary());
        JsonObject byTag = new JsonObject();
        report.removedByTag().forEach(byTag::addProperty);
        result.add("removedByRecordType", byTag);
        JsonObject keptBytes = new JsonObject();
        report.valueBytesKeptByTag().forEach(keptBytes::addProperty);
        result.add("valueBytesKeptByRecordType", keptBytes);
        result.add("notes", Json.ofStrings(report.notes()));
        return result;
    }

    private JsonObject cancel(JsonObject request) {
        long jobId = request.get("jobId").getAsLong();
        boolean waiting = cancelled.containsKey(jobId);
        cancelled.computeIfAbsent(jobId, key -> new AtomicBoolean()).set(true);
        AtomicReference<WorldConverter> handle = runningJobs.get(jobId);
        JsonObject result = new JsonObject();
        if (handle != null && handle.get() != null) {
            handle.get().cancel(null);
            result.addProperty("state", "cancelling");
        } else {
            result.addProperty("state", waiting ? "cancelling" : "notRunning");
        }
        return result;
    }

    // ------------------------------------------------------------------
    // Shared helpers
    // ------------------------------------------------------------------

    private void checkCancelled(long requestId) {
        AtomicBoolean flag = cancelled.get(requestId);
        if (flag != null && flag.get()) {
            throw new WorkerException("job.cancelled", "Cancelled");
        }
    }

    private void progress(long requestId, String stage, long done, long total) {
        JsonObject payload = new JsonObject();
        payload.addProperty("stage", stage);
        payload.addProperty("done", done);
        payload.addProperty("total", total);
        io.writeEvent(requestId, "progress", payload);
    }

    private WorldDocument document(JsonObject request) {
        UUID id = Json.uuid(request, "documentId");
        WorldDocument document = documents.get(id);
        if (document == null) {
            throw new WorkerException("document.missing", "No open document " + id);
        }
        return document;
    }

    private static ChunkSelection selection(JsonObject request) {
        ChunkSelection selection = new ChunkSelection();
        for (JsonElement element : Json.array(request, "add")) {
            JsonObject rect = element.getAsJsonObject();
            selection.add(new ChunkRect(
                    Json.integer(rect, "minChunkX"), Json.integer(rect, "minChunkZ"),
                    Json.integer(rect, "maxChunkX"), Json.integer(rect, "maxChunkZ")));
        }
        JsonElement subtract = request.get("subtract");
        if (subtract != null && subtract.isJsonArray()) {
            for (JsonElement element : subtract.getAsJsonArray()) {
                JsonObject rect = element.getAsJsonObject();
                selection.subtract(new ChunkRect(
                        Json.integer(rect, "minChunkX"), Json.integer(rect, "minChunkZ"),
                        Json.integer(rect, "maxChunkX"), Json.integer(rect, "maxChunkZ")));
            }
        }
        if (selection.isEmpty()) {
            throw new WorkerException("selection.empty", "No chunks selected");
        }
        return selection;
    }

    private JsonObject describeEdit(WorldDocument document, EditResult edit) {
        JsonObject result = describeDocument(document);
        result.addProperty("revision", edit.revision());
        if (edit.changedBounds() != null) {
            result.add("changedBounds", Json.of(edit.changedBounds()));
        }
        result.add("notices", Json.ofStrings(edit.notices()));
        result.add("invalidatedArenas", Json.ofStrings(edit.invalidatedArenas()));
        return result;
    }

    private JsonObject describeDocument(WorldDocument document) {
        synchronized (document) {
        JsonObject result = new JsonObject();
        result.addProperty("documentId", document.id().toString());
        result.addProperty("name", document.name());
        result.addProperty("profileId", document.targetProfileId());
        result.addProperty("revision", document.revision());
        result.addProperty("dirty", document.isDirty());
        result.addProperty("canUndo", document.canUndo());
        result.addProperty("canRedo", document.canRedo());
        result.addProperty("materializedColumns", document.snapshot().materializedCount());
        result.addProperty("arenaCount", document.snapshot().instances().size());
        result.addProperty("hasExplicitExportRectangle", document.hasExplicitExportRectangle());
        result.addProperty("exportBorderChunks", document.exportBorderChunks());

        ChunkRect content = document.snapshot().contentChunkBounds();
        if (content != null) result.add("contentBounds", Json.of(content));
        ChunkRect export = document.exportRectangle();
        if (export != null) result.add("exportRectangle", Json.of(export));

        int[] spawn = document.worldSpawn();
        JsonObject spawnObject = new JsonObject();
        spawnObject.addProperty("x", spawn[0]);
        spawnObject.addProperty("y", spawn[1]);
        spawnObject.addProperty("z", spawn[2]);
        result.add("worldSpawn", spawnObject);
        result.addProperty("gameType", document.levelSettings().GameType);
        result.addProperty("difficulty", document.levelSettings().Difficulty);
        result.addProperty("commandsEnabled", document.levelSettings().commandsEnabled);
        result.addProperty("hasSourceLevelSettings", document.hasSourceLevelSettings());
        result.addProperty("preservesSourceLevelData", document.canPreserveSourceLevelData());
        result.add("sourceExperiments", Json.ofStrings(
                LevelDataPreserver.enabledExperiments(document.sourceLevelData())));

        JsonArray arenas = new JsonArray();
        for (ArenaInstance instance : document.snapshot().instances()) {
            JsonObject entry = new JsonObject();
            entry.addProperty("id", instance.id().toString());
            entry.addProperty("exportId", instance.exportId());
            entry.addProperty("templateId", instance.templateId().toString());
            entry.addProperty("templateSlug", instance.templateSlug());
            entry.addProperty("minX", instance.minX());
            entry.addProperty("minY", instance.minY());
            entry.addProperty("minZ", instance.minZ());
            entry.add("chunkBounds", Json.of(instance.chunkBounds()));
            entry.addProperty("needsRevalidation", instance.needsRevalidation());
            arenas.add(entry);
        }
        result.add("arenas", arenas);

        JsonArray templateArray = new JsonArray();
        for (ArenaTemplate template : templates.all()) {
            templateArray.add(describeTemplate(template));
        }
        result.add("templates", templateArray);
        var loading = loadingWorlds.get(document.id());
        if (loading != null) {
            result.addProperty("loading", loading.loading());
            result.addProperty("loadedColumns", loading.loadedColumns());
            result.addProperty("totalColumns", loading.totalColumns());
            if (loading.initialView() != null) result.add("initialViewBounds", Json.of(loading.initialView()));
            if (loading.loading()) {
                result.addProperty("materializedColumns", loading.loadedColumns());
                if (loading.bounds() != null) {
                    result.add("contentBounds", Json.of(loading.bounds()));
                    result.add("exportRectangle", Json.of(loading.bounds()));
                }
                result.addProperty("canUndo", false);
                result.addProperty("canRedo", false);
            }
            if (loading.failure() != null) result.addProperty("loadError", describe(loading.failure()));
        }
        return result;
        }
    }

    private JsonObject describeTemplate(ArenaTemplate template) {
        JsonObject entry = new JsonObject();
        entry.addProperty("templateId", template.id().toString());
        entry.addProperty("slug", template.slug());
        entry.addProperty("sourceFile", template.sourceFileName());
        entry.addProperty("sourceSha256", template.sourceSha256());
        entry.addProperty("spongeVersion", template.schematic().spongeVersion());
        entry.addProperty("javaDataVersion", template.schematic().javaDataVersion());
        entry.addProperty("sizeX", template.sizeX());
        entry.addProperty("sizeY", template.sizeY());
        entry.addProperty("sizeZ", template.sizeZ());
        entry.addProperty("footprintChunksX", template.footprintChunksX());
        entry.addProperty("footprintChunksZ", template.footprintChunksZ());
        entry.addProperty("blockEntityCount", template.blockEntities().size());
        entry.addProperty("paletteSize", template.schematic().palette().size());
        entry.addProperty("aggregateCandidate", template.aggregateCandidate());
        entry.addProperty("spawnsConfirmed", template.spawnsConfirmed());
        entry.addProperty("spawnsAutomatic", template.spawnsAutomatic());
        entry.addProperty("hasWorldEditOrigin", template.schematic().worldEditOrigin() != null);

        if (template.spawnPoint1() != null) entry.add("spawnPoint1", triple(template.spawnPoint1()));
        if (template.spawnPoint2() != null) entry.add("spawnPoint2", triple(template.spawnPoint2()));

        JsonObject offset = new JsonObject();
        offset.addProperty("x", template.schematic().offset()[0]);
        offset.addProperty("y", template.schematic().offset()[1]);
        offset.addProperty("z", template.schematic().offset()[2]);
        entry.add("schematicOffset", offset);

        JsonArray issues = new JsonArray();
        for (MappingIssue issue : template.issues()) {
            JsonObject object = new JsonObject();
            object.addProperty("severity", issue.severity().name());
            object.addProperty("kind", issue.kind().name());
            object.addProperty("identifier", issue.identifier());
            object.addProperty("detail", issue.detail());
            issues.add(object);
        }
        entry.add("issues", issues);
        entry.addProperty("blockingIssueCount", template.blockingIssues().size());
        return entry;
    }

    private static JsonObject triple(double[] values) {
        JsonObject object = new JsonObject();
        object.addProperty("x", values[0]);
        object.addProperty("y", values[1]);
        object.addProperty("z", values[2]);
        return object;
    }

    /** Documents currently open, for shutdown reporting. */
    public Map<UUID, WorldDocument> documents() {
        return new LinkedHashMap<>(documents);
    }
}
