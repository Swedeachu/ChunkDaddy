package gg.swim.chunkdaddy.worker.bedrock;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import gg.swim.chunkdaddy.worker.conversion.ArenaTemplate;
import gg.swim.chunkdaddy.worker.conversion.MappingIssue;
import gg.swim.chunkdaddy.worker.document.ArenaInstance;
import gg.swim.chunkdaddy.worker.document.TemplateRegistry;
import gg.swim.chunkdaddy.worker.document.WorldDocument;
import gg.swim.chunkdaddy.worker.util.ChunkRect;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * The rich manifest that sits alongside the minimal {@code arenas.json}.
 *
 * <p>Server tooling that needs more than two positions reads this; reopening an exported
 * world uses it to recover template and grid identity. It records the exact document
 * revision and target profile so a consumer can tell whether it still matches the world it
 * sits next to. A manifest is not evidence about a world that has since been edited
 * elsewhere.
 */
public final class ManifestWriter {
    public static final int SCHEMA_VERSION = 1;

    private ManifestWriter() {
    }

    public static JsonObject build(WorldDocument document,
                                   ExportRequest request,
                                   TemplateRegistry templates,
                                   ChunkRect rectangle,
                                   long contentColumns,
                                   long voidColumns) {
        JsonObject root = new JsonObject();
        root.addProperty("schemaVersion", SCHEMA_VERSION);
        root.addProperty("exportId", UUID.randomUUID().toString());
        root.addProperty("exportedAt", Instant.now().toString());
        root.addProperty("documentId", document.id().toString());
        root.addProperty("documentRevision", document.revision());
        root.addProperty("worldName", request.worldName());

        JsonObject profile = new JsonObject();
        profile.addProperty("id", request.profile().id());
        profile.addProperty("displayName", request.profile().displayName());
        profile.addProperty("version", request.profile().version().toString());
        profile.addProperty("chunkerCommit", request.profile().chunkerCommit());
        profile.addProperty("stable", request.profile().stable());
        profile.addProperty("verifiedBds", request.profile().verifiedBds());
        profile.addProperty("verifiedClient", request.profile().verifiedClient());
        profile.addProperty("verifiedTungsten", request.profile().verifiedTungsten());
        profile.addProperty("verificationSummary", request.profile().verificationSummary());
        root.add("targetProfile", profile);

        root.addProperty("dimension", "minecraft:overworld");
        JsonObject bounds = new JsonObject();
        bounds.addProperty("minChunkX", rectangle.minX());
        bounds.addProperty("minChunkZ", rectangle.minZ());
        bounds.addProperty("maxChunkX", rectangle.maxX());
        bounds.addProperty("maxChunkZ", rectangle.maxZ());
        bounds.addProperty("widthChunks", rectangle.widthChunks());
        bounds.addProperty("lengthChunks", rectangle.lengthChunks());
        bounds.addProperty("totalColumns", rectangle.columnCount());
        bounds.addProperty("contentColumns", contentColumns);
        bounds.addProperty("generatedVoidColumns", voidColumns);
        root.add("exportChunkBounds", bounds);

        JsonObject spawn = new JsonObject();
        int[] worldSpawn = document.worldSpawn();
        spawn.addProperty("x", worldSpawn[0]);
        spawn.addProperty("y", worldSpawn[1]);
        spawn.addProperty("z", worldSpawn[2]);
        root.add("worldSpawn", spawn);

        JsonArray templateArray = new JsonArray();
        for (ArenaTemplate template : templates.all()) {
            JsonObject entry = new JsonObject();
            entry.addProperty("uuid", template.id().toString());
            entry.addProperty("slug", template.slug());
            entry.addProperty("sourceFile", template.sourceFileName());
            entry.addProperty("sourceSha256", template.sourceSha256());
            entry.addProperty("spongeVersion", template.schematic().spongeVersion());
            entry.addProperty("javaDataVersion", template.schematic().javaDataVersion());
            JsonObject size = new JsonObject();
            size.addProperty("x", template.sizeX());
            size.addProperty("y", template.sizeY());
            size.addProperty("z", template.sizeZ());
            entry.add("size", size);
            entry.addProperty("spawnsConfirmed", template.spawnsConfirmed());
            entry.addProperty("spawnsAutomatic", template.spawnsAutomatic());
            if (template.spawnPoint1() != null) entry.add("localSpawnPoint1", triple(template.spawnPoint1()));
            if (template.spawnPoint2() != null) entry.add("localSpawnPoint2", triple(template.spawnPoint2()));
            if (template.schematic().worldEditOrigin() != null) {
                entry.add("worldEditOrigin", triple(template.schematic().worldEditOrigin()));
            }
            entry.add("schematicOffset", triple(template.schematic().offset()));
            if (template.schematic().sourceMinimum() != null) {
                entry.add("reconstructedSourceMinimum", triple(template.schematic().sourceMinimum()));
            }
            templateArray.add(entry);
        }
        root.add("templates", templateArray);

        JsonArray instanceArray = new JsonArray();
        List<ArenaInstance> instances = new ArrayList<>(document.snapshot().instances());
        instances.sort(Comparator.comparing(ArenaInstance::templateSlug).thenComparingInt(ArenaInstance::ordinal));
        for (ArenaInstance instance : instances) {
            JsonObject entry = new JsonObject();
            entry.addProperty("uuid", instance.id().toString());
            entry.addProperty("exportId", instance.exportId());
            entry.addProperty("templateUuid", instance.templateId().toString());
            entry.addProperty("templateSlug", instance.templateSlug());
            entry.addProperty("ordinal", instance.ordinal());
            JsonObject minimum = new JsonObject();
            minimum.addProperty("x", instance.minX());
            minimum.addProperty("y", instance.minY());
            minimum.addProperty("z", instance.minZ());
            entry.add("minimumCorner", minimum);
            ChunkRect footprint = instance.chunkBounds();
            JsonObject chunkBounds = new JsonObject();
            chunkBounds.addProperty("minChunkX", footprint.minX());
            chunkBounds.addProperty("minChunkZ", footprint.minZ());
            chunkBounds.addProperty("maxChunkX", footprint.maxX());
            chunkBounds.addProperty("maxChunkZ", footprint.maxZ());
            entry.add("chunkBounds", chunkBounds);
            entry.addProperty("gridRow", instance.gridRow());
            entry.addProperty("gridColumn", instance.gridColumn());
            entry.addProperty("needsRevalidation", instance.needsRevalidation());
            instanceArray.add(entry);
        }
        root.add("instances", instanceArray);

        JsonObject conversion = new JsonObject();
        JsonArray issues = new JsonArray();
        for (ArenaTemplate template : templates.all()) {
            for (MappingIssue issue : template.issues()) {
                JsonObject entry = new JsonObject();
                entry.addProperty("template", template.slug());
                entry.addProperty("severity", issue.severity().name());
                entry.addProperty("kind", issue.kind().name());
                entry.addProperty("identifier", issue.identifier());
                entry.addProperty("detail", issue.detail());
                issues.add(entry);
            }
        }
        conversion.add("issues", issues);
        conversion.addProperty("ordinaryEntitiesIncluded", false);
        conversion.addProperty("inGameMapsIncluded", false);
        conversion.addProperty("note",
                "Ordinary entities and in-game maps are excluded from static arena composition by policy. "
                        + "Chunker documents limitations around general entity conversion and structure data.");
        root.add("conversionSummary", conversion);

        return root;
    }

    public static String serialize(JsonObject object) {
        return new GsonBuilder().setPrettyPrinting().create().toJson(object) + "\n";
    }

    private static JsonObject triple(double[] values) {
        JsonObject object = new JsonObject();
        object.addProperty("x", values[0]);
        object.addProperty("y", values[1]);
        object.addProperty("z", values[2]);
        return object;
    }

    private static JsonObject triple(int[] values) {
        JsonObject object = new JsonObject();
        object.addProperty("x", values[0]);
        object.addProperty("y", values[1]);
        object.addProperty("z", values[2]);
        return object;
    }
}
