package gg.swim.chunkdaddy.worker.protocol;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import gg.swim.chunkdaddy.worker.util.ChunkRect;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Small helpers that fail with protocol error codes rather than null pointers. */
public final class Json {
    private Json() {
    }

    public static JsonObject object(JsonObject parent, String name) {
        JsonElement element = parent.get(name);
        if (element == null || !element.isJsonObject()) {
            throw new WorkerException("protocol.field", "Missing object field '" + name + "'");
        }
        return element.getAsJsonObject();
    }

    public static @Nullable JsonObject optionalObject(JsonObject parent, String name) {
        JsonElement element = parent.get(name);
        return element != null && element.isJsonObject() ? element.getAsJsonObject() : null;
    }

    public static String string(JsonObject parent, String name) {
        JsonElement element = parent.get(name);
        if (element == null || !element.isJsonPrimitive()) {
            throw new WorkerException("protocol.field", "Missing string field '" + name + "'");
        }
        return element.getAsString();
    }

    public static String string(JsonObject parent, String name, String fallback) {
        JsonElement element = parent.get(name);
        return element == null || element.isJsonNull() ? fallback : element.getAsString();
    }

    public static int integer(JsonObject parent, String name) {
        JsonElement element = parent.get(name);
        if (element == null || !element.isJsonPrimitive()) {
            throw new WorkerException("protocol.field", "Missing integer field '" + name + "'");
        }
        return element.getAsInt();
    }

    public static int integer(JsonObject parent, String name, int fallback) {
        JsonElement element = parent.get(name);
        return element == null || element.isJsonNull() ? fallback : element.getAsInt();
    }

    public static double number(JsonObject parent, String name) {
        JsonElement element = parent.get(name);
        if (element == null || !element.isJsonPrimitive()) {
            throw new WorkerException("protocol.field", "Missing number field '" + name + "'");
        }
        return element.getAsDouble();
    }

    public static boolean bool(JsonObject parent, String name, boolean fallback) {
        JsonElement element = parent.get(name);
        return element == null || element.isJsonNull() ? fallback : element.getAsBoolean();
    }

    public static JsonArray array(JsonObject parent, String name) {
        JsonElement element = parent.get(name);
        if (element == null || !element.isJsonArray()) {
            throw new WorkerException("protocol.field", "Missing array field '" + name + "'");
        }
        return element.getAsJsonArray();
    }

    public static List<String> strings(JsonObject parent, String name) {
        List<String> values = new ArrayList<>();
        for (JsonElement element : array(parent, name)) {
            values.add(element.getAsString());
        }
        return values;
    }

    public static UUID uuid(JsonObject parent, String name) {
        try {
            return UUID.fromString(string(parent, name));
        } catch (IllegalArgumentException e) {
            throw new WorkerException("protocol.field", "Field '" + name + "' is not a UUID");
        }
    }

    public static ChunkRect chunkRect(JsonObject parent, String name) {
        JsonObject rect = object(parent, name);
        return new ChunkRect(
                integer(rect, "minChunkX"),
                integer(rect, "minChunkZ"),
                integer(rect, "maxChunkX"),
                integer(rect, "maxChunkZ"));
    }

    public static @Nullable ChunkRect optionalChunkRect(JsonObject parent, String name) {
        JsonObject rect = optionalObject(parent, name);
        if (rect == null) return null;
        return new ChunkRect(
                integer(rect, "minChunkX"),
                integer(rect, "minChunkZ"),
                integer(rect, "maxChunkX"),
                integer(rect, "maxChunkZ"));
    }

    public static JsonObject of(ChunkRect rect) {
        JsonObject object = new JsonObject();
        object.addProperty("minChunkX", rect.minX());
        object.addProperty("minChunkZ", rect.minZ());
        object.addProperty("maxChunkX", rect.maxX());
        object.addProperty("maxChunkZ", rect.maxZ());
        object.addProperty("widthChunks", rect.widthChunks());
        object.addProperty("lengthChunks", rect.lengthChunks());
        object.addProperty("columnCount", rect.columnCount());
        return object;
    }

    public static JsonArray ofStrings(List<String> values) {
        JsonArray array = new JsonArray();
        values.forEach(array::add);
        return array;
    }

    public static double[] triple(JsonObject parent, String name) {
        JsonObject object = object(parent, name);
        return new double[]{number(object, "x"), number(object, "y"), number(object, "z")};
    }

    public static @Nullable double[] optionalTriple(JsonObject parent, String name) {
        JsonObject object = optionalObject(parent, name);
        if (object == null) return null;
        return new double[]{number(object, "x"), number(object, "y"), number(object, "z")};
    }
}
