package gg.swim.chunkdaddy.worker.preview;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.hivemc.chunker.conversion.intermediate.column.ChunkerColumn;
import com.hivemc.chunker.conversion.intermediate.column.chunk.identifier.ChunkerBlockIdentifier;
import gg.swim.chunkdaddy.worker.document.WorldSnapshot;
import gg.swim.chunkdaddy.worker.protocol.Json;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/** Generic RGBA terrain raster with configurable transparent overlays and named color rules. */
public final class SurfaceRaster {
    private record Surface(int rgb, int height) { }
    private SurfaceRaster() { }

    public static JsonObject write(WorldSnapshot snapshot, JsonObject request) throws Exception {
        int width = Json.integer(request, "width", 128), height = Json.integer(request, "height", 128);
        int stride = Json.integer(request, "blocksPerPixel", 4);
        if (width < 1 || height < 1 || width > 4096 || height > 4096 || stride < 1 || stride > 16)
            throw new IllegalArgumentException("Raster dimensions or sample stride outside supported range");
        int minX = Json.integer(request, "minX"), minZ = Json.integer(request, "minZ");
        JsonArray rules = request.has("colorRules") ? Json.array(request, "colorRules") : new JsonArray();
        Map<ChunkerBlockIdentifier, Integer> colors = new HashMap<>();
        byte[] rgba = new byte[Math.multiplyExact(Math.multiplyExact(width, height), 4)];
        Double[] previous = new Double[width];
        int nonempty = 0;
        for (int py = 0; py < height; py++) {
            Double[] current = new Double[width];
            for (int px = 0; px < width; px++) {
                Map<Integer, Integer> counts = new LinkedHashMap<>();
                int samples = 0, heights = 0;
                int sampleStride = Math.max(1, stride / 4);
                for (int sz = 0; sz < stride; sz += sampleStride) {
                    for (int sx = 0; sx < stride; sx += sampleStride) {
                        int x = minX + px * stride + sx, z = minZ + py * stride + sz;
                        Surface surface = sample(snapshot.materializedColumn(x >> 4, z >> 4), x & 15, z & 15, rules, colors);
                        if (surface == null) continue;
                        counts.merge(surface.rgb(), 1, Integer::sum);
                        heights += surface.height();
                        samples++;
                    }
                }
                int rgb = Json.integer(request, "emptyRgb", 0x4040ff);
                int shade = Json.integer(request, "emptyShade", 220);
                if (samples > 0) {
                    nonempty++;
                    int largest = -1;
                    for (var entry : counts.entrySet()) if (entry.getValue() > largest) {
                        rgb = entry.getKey(); largest = entry.getValue();
                    }
                    current[px] = (double) heights / samples;
                    shade = 220;
                    if (previous[px] != null) {
                        double slope = (current[px] - previous[px]) * 4.0 / (stride + 4.0)
                                + (((px + py) & 1) - 0.5) * 0.4;
                        shade = slope > 0.6 ? 255 : slope < -0.6 ? 180 : 220;
                    }
                }
                int offset = (py * width + px) * 4;
                rgba[offset] = (byte) (((rgb >> 16) & 255) * shade / 255);
                rgba[offset + 1] = (byte) (((rgb >> 8) & 255) * shade / 255);
                rgba[offset + 2] = (byte) ((rgb & 255) * shade / 255);
                rgba[offset + 3] = (byte) 255;
            }
            previous = current;
        }
        Path destination = Path.of(Json.string(request, "destination"));
        Files.createDirectories(destination.toAbsolutePath().getParent());
        Files.write(destination, rgba);
        JsonObject result = new JsonObject();
        result.addProperty("path", destination.toString());
        result.addProperty("width", width); result.addProperty("height", height);
        result.addProperty("nonemptyPixels", nonempty);
        return result;
    }

    private static Surface sample(ChunkerColumn column, int x, int z, JsonArray rules,
                                   Map<ChunkerBlockIdentifier, Integer> cache) {
        if (column == null || column.getChunks().isEmpty()) return null;
        for (int cy = column.getChunks().lastByteKey(); cy >= column.getChunks().firstByteKey(); cy--) {
            var chunk = column.getChunks().get((byte) cy);
            if (chunk == null) continue;
            for (int y = 15; y >= 0; y--) {
                var block = chunk.getPalette().get(x, y, z, ChunkerBlockIdentifier.AIR);
                if (block == null || block.isAir()) continue;
                int rgb = cache.computeIfAbsent(block, key -> color(key, rules));
                if (rgb >= 0) return new Surface(rgb, cy * 16 + y);
            }
        }
        return null;
    }

    private static int color(ChunkerBlockIdentifier block, JsonArray rules) {
        String name = block.getType().toString().toLowerCase(Locale.ROOT).replace('_', ' ');
        for (JsonElement element : rules) {
            JsonObject rule = element.getAsJsonObject();
            for (JsonElement term : Json.array(rule, "contains")) if (name.contains(term.getAsString()))
                return Json.bool(rule, "ignore", false) ? -1 : Json.integer(rule, "rgb");
        }
        return block.hasRGBColor() ? block.getRGBColor() : 0x808080;
    }
}
