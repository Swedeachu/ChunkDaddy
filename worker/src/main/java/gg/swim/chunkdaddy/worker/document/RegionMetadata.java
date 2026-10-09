package gg.swim.chunkdaddy.worker.document;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import gg.swim.chunkdaddy.worker.protocol.Json;

/** Explicit JSON pointer selectors prevent unrelated numeric fields from being translated. */
public final class RegionMetadata {
    private RegionMetadata() { }

    public static JsonObject translate(JsonObject metadata, com.google.gson.JsonArray selectors,
                                        int dx, int dy, int dz) {
        JsonObject result = metadata.deepCopy();
        for (JsonElement element : selectors) {
            JsonObject selector = element.getAsJsonObject();
            String path = Json.string(selector, "path");
            if (!path.startsWith("/")) throw new IllegalArgumentException("Coordinate path must start with /: " + path);
            visit(result, path.substring(1).split("/", -1), 0, selector, dx, dy, dz);
        }
        return result;
    }

    private static void visit(JsonElement node, String[] path, int depth, JsonObject selector,
                               int dx, int dy, int dz) {
        if (depth == path.length) {
            if (!node.isJsonObject()) throw new IllegalArgumentException("Coordinate selector must resolve to objects");
            JsonObject point = node.getAsJsonObject();
            shift(point, Json.string(selector, "x", "x"), dx);
            shift(point, Json.string(selector, "y", "y"), dy);
            shift(point, Json.string(selector, "z", "z"), dz);
            return;
        }
        String key = path[depth].replace("~1", "/").replace("~0", "~");
        if (key.equals("*")) {
            if (node.isJsonArray()) {
                for (JsonElement child : node.getAsJsonArray()) visit(child, path, depth + 1, selector, dx, dy, dz);
            } else if (node.isJsonObject()) {
                for (var entry : node.getAsJsonObject().entrySet()) visit(entry.getValue(), path, depth + 1, selector, dx, dy, dz);
            } else throw new IllegalArgumentException("Wildcard must resolve to array or object");
        } else {
            JsonElement child = node.isJsonArray() ? node.getAsJsonArray().get(Integer.parseInt(key))
                    : node.getAsJsonObject().get(key);
            if (child == null) throw new IllegalArgumentException("Missing coordinate path segment " + key);
            visit(child, path, depth + 1, selector, dx, dy, dz);
        }
    }

    private static void shift(JsonObject point, String field, int delta) {
        if (field.isEmpty()) return; // Empty axis name explicitly opts out (e.g. texture has no Y).
        JsonElement value = point.get(field);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber())
            throw new IllegalArgumentException("Missing numeric coordinate " + field);
        double translated = value.getAsDouble() + delta;
        if (!Double.isFinite(translated)) throw new IllegalArgumentException("Non-finite coordinate " + field);
        point.addProperty(field, translated);
    }
}
