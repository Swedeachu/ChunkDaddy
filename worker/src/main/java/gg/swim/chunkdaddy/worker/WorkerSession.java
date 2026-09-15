package gg.swim.chunkdaddy.worker;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.hivemc.chunker.conversion.WorldConverter;
import gg.swim.chunkdaddy.worker.bedrock.ArenaJsonWriter;
import gg.swim.chunkdaddy.worker.bedrock.BedrockExporter;
import gg.swim.chunkdaddy.worker.bedrock.ExportRequest;
import gg.swim.chunkdaddy.worker.bedrock.ExportResult;
import gg.swim.chunkdaddy.worker.bedrock.TargetProfile;
import gg.swim.chunkdaddy.worker.bedrock.WorldPackager;
import gg.swim.chunkdaddy.worker.conversion.ArenaTemplate;
import gg.swim.chunkdaddy.worker.conversion.MappingIssue;
import gg.swim.chunkdaddy.worker.conversion.ResolverFactory;
import gg.swim.chunkdaddy.worker.conversion.TemplateImporter;
import gg.swim.chunkdaddy.worker.document.ArenaInstance;
import gg.swim.chunkdaddy.worker.document.ChunkSelection;
import gg.swim.chunkdaddy.worker.document.ClipboardContent;
import gg.swim.chunkdaddy.worker.document.EditResult;
import gg.swim.chunkdaddy.worker.document.SourceInspector;
import gg.swim.chunkdaddy.worker.document.TemplateRegistry;
import gg.swim.chunkdaddy.worker.document.WorldDocument;
import gg.swim.chunkdaddy.worker.document.WorldImporter;
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
            JsonObject result = switch (type) {
                case "capabilities" -> capabilities();
                case "inspect_source" -> inspectSource(request);
                case "new_document" -> newDocument(request);
                case "close_document" -> closeDocument(request);
                case "document_info" -> documentInfo(request);
                case "open_world" -> openWorld(id, request);
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
                case "set_document_name" -> setDocumentName(request);
                case "preview_tiles" -> previewTiles(request);
                case "validate_export" -> validateExport(request);
                case "export_world" -> exportWorld(id, request);
                case "cancel" -> cancel(request);
                case "shutdown" -> new JsonObject();
                default -> throw new WorkerException("protocol.unknown", "Unknown request type '" + type + "'");
            };
            io.writeResult(id, result);
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

        WorldConverter converter = WorldImporter.newConverter();
        runningJobs.put(requestId, new AtomicReference<>(converter));
        progress(requestId, "Reading world chunks...", 0, 0);

        WorldImporter.Result imported = WorldImporter.importWorld(directory, edition, profile, converter);

        WorldDocument document = new WorldDocument(name, profile.id(), templates);
        documents.put(document.id(), document);
        EditResult edit = document.importColumns(imported.columns());
        // An imported source is a starting point, not unsaved work the user did.
        document.markSaved();

        JsonObject result = describeDocument(document);
        result.addProperty("importedColumns", imported.columns().size());
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
        ClipboardContent content = document.capture(selection, cut, null);
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

        EditResult edit = document.paste(content, destChunkX, destChunkZ, replace);

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
                null);
        return describeEdit(document, edit);
    }

    private JsonObject clearSelection(JsonObject request) {
        WorldDocument document = document(request);
        EditResult edit = document.clearSelection(selection(request));
        return describeEdit(document, edit);
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
        new PreviewRenderer(templates).render(
                document.snapshot(), area, sliceY, mode, document.exportRectangle(), output, pixelsPerChunk);

        JsonObject result = new JsonObject();
        result.addProperty("path", output.toString());
        result.addProperty("formatVersion", pixelsPerChunk == 16 ? PreviewRenderer.FORMAT_VERSION : 2);
        result.add("area", Json.of(area));
        result.addProperty("revision", document.revision());
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
                Json.bool(request, "writeCompanionJson", true));

        AtomicReference<WorldConverter> handle = new AtomicReference<>();
        runningJobs.put(requestId, handle);
        long total = rectangle.columnCount();

        BedrockExporter exporter = new BedrockExporter(templates, resolverFactory);
        ExportResult exported = exporter.export(document, exportRequest,
                done -> {
                    if (done == total) {
                        // Enumeration finishes before database flush, compaction and
                        // packaging. Keep the UI active until the final reply arrives.
                        progress(requestId, "Finishing world database and packaging export...", 0, 0);
                    } else {
                        progress(requestId, "Preparing world columns", done, total);
                    }
                }, handle);

        JsonObject result = new JsonObject();
        result.addProperty("path", exported.worldPath());
        result.addProperty("contentColumns", exported.contentColumns());
        result.addProperty("voidColumns", exported.voidColumns());
        result.addProperty("totalColumns", exported.totalColumns());
        result.addProperty("arenaCount", exported.arenaCount());
        result.addProperty("spawnPointCount", exported.spawnPointCount());
        result.addProperty("companionJsonWritten", exported.companionJsonWritten());
        result.add("warnings", Json.ofStrings(exported.warnings()));
        result.addProperty("revision", document.revision());
        return result;
    }

    private JsonObject cancel(JsonObject request) {
        long jobId = request.get("jobId").getAsLong();
        cancelled.computeIfAbsent(jobId, key -> new AtomicBoolean()).set(true);
        AtomicReference<WorldConverter> handle = runningJobs.get(jobId);
        JsonObject result = new JsonObject();
        if (handle != null && handle.get() != null) {
            handle.get().cancel(null);
            result.addProperty("state", "cancelling");
        } else {
            result.addProperty("state", "notRunning");
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
        return result;
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

