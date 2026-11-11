package gg.swim.chunkdaddy.worker.preview;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.hivemc.chunker.conversion.intermediate.column.ChunkerColumn;
import com.hivemc.chunker.conversion.intermediate.column.chunk.ChunkerChunk;
import com.hivemc.chunker.conversion.intermediate.column.chunk.identifier.ChunkerBlockIdentifier;
import gg.swim.chunkdaddy.worker.document.ArenaInstance;
import gg.swim.chunkdaddy.worker.document.WorldSnapshot;
import gg.swim.chunkdaddy.worker.protocol.Json;
import gg.swim.chunkdaddy.worker.protocol.WorkerException;
import gg.swim.chunkdaddy.worker.util.Checked;
import gg.swim.chunkdaddy.worker.util.ChunkRect;

import javax.imageio.ImageIO;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.font.FontRenderContext;
import java.awt.font.TextLayout;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;
import java.util.stream.IntStream;

/**
 * Renders a whole world top down into one labelled PNG: every chunk column with content,
 * coloured by its highest block and hill-shaded, with named rectangles drawn over it.
 *
 * <p>Labels come from two sources. {@code regions} are explicit chunk rectangles. {@code
 * markers} are block positions (an arena's spawn, say): the cluster of content chunks
 * around a marker gets the marker's label. Clusters are found by joining content chunks
 * no more than {@code clusterGapChunks} empty chunks apart, then merging clusters whose
 * bounding boxes overlap. Clusters no label reaches are drawn grey and listed in the
 * result, so stray chunks show up instead of hiding in the picture.
 */
public final class MapRenderer {
    static {
        System.setProperty("java.awt.headless", "true");
    }

    private static final int BACKGROUND = 0x101215;
    private static final int GRID_MINOR = 0x1B1E23;
    private static final int GRID_MAJOR = 0x2A2F37;
    private static final int UNKNOWN_RGB = 0x9A9A9A;
    private static final String UNLABELED = "unlabeled";
    private static final long MAX_PIXELS = 150_000_000L;
    /** Blocks nobody sees from above: an arena's barrier roof would hide the arena. */
    private static final List<String> DEFAULT_IGNORED = List.of("barrier", "structure void", "light block", "invisible");
    /** Smallest label drawn inside its box; smaller boxes get theirs underneath. */
    private static final int MIN_INSIDE_FONT = 11;
    /** Distinct, readable on dark terrain; used for groups the request gives no colour. */
    private static final int[] PALETTE = {
            0xFF5A5A, 0x4FA3FF, 0x5ED16A, 0xFFC23D, 0xC77DFF, 0x3DDAD7, 0xFF8A3D, 0xFF6FB5,
            0xB5E61D, 0x8C9EFF, 0xFFE07A, 0x7AFFC2
    };

    /** What the terrain pass needs besides the snapshot: the colour per block and a surface tally. */
    private record Surface(List<String> ignored, Map<ChunkerBlockIdentifier, Integer> colors,
                           Map<String, LongAdder> tally) {
        int color(ChunkerBlockIdentifier block) {
            Integer cached = colors.get(block);
            if (cached != null) return cached;
            String name = block.getType().toString().toLowerCase(Locale.ROOT).replace('_', ' ');
            int rgb = ignored.stream().anyMatch(name::contains) ? -1
                    : block.hasRGBColor() ? block.getRGBColor() & 0xFFFFFF : UNKNOWN_RGB;
            colors.put(block, rgb);
            return rgb;
        }
    }

    private record Cluster(ChunkRect rect, int columns) { }

    /** One labelled rectangle on the map. */
    private record Area(String label, String group, ChunkRect rect, boolean explicit) { }

    private MapRenderer() { }

    public static JsonObject render(WorldSnapshot snapshot, String documentName, JsonObject request) throws IOException {
        int blocksPerPixel = Json.integer(request, "blocksPerPixel", 2);
        if (blocksPerPixel != 1 && blocksPerPixel != 2 && blocksPerPixel != 4 && blocksPerPixel != 8 && blocksPerPixel != 16)
            throw new WorkerException("map.scale", "blocksPerPixel must be 1, 2, 4, 8 or 16");
        int pixelsPerChunk = 16 / blocksPerPixel;
        Path destination = Path.of(Json.string(request, "destination"));

        Set<Long> content = contentKeys(snapshot);
        ChunkRect area = Json.optionalChunkRect(request, "area");
        if (area == null) {
            ChunkRect bounds = snapshot.contentChunkBounds();
            if (bounds == null) throw new WorkerException("map.empty", "The document has no content to render");
            int margin = Math.max(0, Json.integer(request, "marginChunks", 4));
            area = new ChunkRect(bounds.minX() - margin, bounds.minZ() - margin, bounds.maxX() + margin, bounds.maxZ() + margin);
        }
        long mapWidth = (long) area.widthChunks() * pixelsPerChunk, mapHeight = (long) area.lengthChunks() * pixelsPerChunk;
        if (mapWidth * mapHeight > MAX_PIXELS)
            throw new WorkerException("map.tooLarge", "A " + mapWidth + "x" + mapHeight
                    + " map is too large; raise blocksPerPixel or pass a smaller area");

        // ---- terrain ----
        int width = (int) mapWidth, height = (int) mapHeight;
        int[] rgb = new int[width * height];
        int[] heights = new int[width * height];
        java.util.Arrays.fill(heights, Integer.MIN_VALUE);
        ChunkRect bounds = area;
        List<Long> visible = content.stream()
                .filter(key -> bounds.contains(Checked.chunkKeyX(key), Checked.chunkKeyZ(key))).toList();
        List<String> ignored = request.has("ignoreBlocks")
                ? Json.strings(request, "ignoreBlocks").stream().map(t -> t.toLowerCase(Locale.ROOT).replace('_', ' ')).toList()
                : DEFAULT_IGNORED;
        Surface surface = new Surface(ignored, new ConcurrentHashMap<>(), new ConcurrentHashMap<>());
        visible.parallelStream().forEach(key -> paintChunk(snapshot, key, bounds, blocksPerPixel, width, rgb, heights, surface));

        int left = 64, top = 96, legendWidth = Json.integer(request, "legendWidth", 460);
        BufferedImage image = new BufferedImage(left + width + legendWidth, top + height, BufferedImage.TYPE_INT_RGB);
        boolean grid = Json.bool(request, "chunkGrid", pixelsPerChunk >= 8);
        int majorEvery = Math.max(1, Json.integer(request, "gridMajorChunks", 32));
        IntStream.range(0, height).parallel().forEach(py -> {
            for (int px = 0; px < width; px++) {
                int index = py * width + px, h = heights[index];
                int color;
                if (h == Integer.MIN_VALUE) {
                    color = BACKGROUND;
                    int cx = bounds.minX() + px / pixelsPerChunk, cz = bounds.minZ() + py / pixelsPerChunk;
                    boolean xLine = px % pixelsPerChunk == 0, zLine = py % pixelsPerChunk == 0;
                    if ((xLine && Math.floorMod(cx, majorEvery) == 0) || (zLine && Math.floorMod(cz, majorEvery) == 0)) color = GRID_MAJOR;
                    else if (grid && (xLine || zLine)) color = GRID_MINOR;
                } else {
                    double factor = 0.72 + 0.28 * clamp((h + 64) / 384.0);
                    int west = px > 0 ? heights[index - 1] : Integer.MIN_VALUE;
                    int north = py > 0 ? heights[index - width] : Integer.MIN_VALUE;
                    int slope = 0;
                    if (west != Integer.MIN_VALUE) slope += h - west;
                    if (north != Integer.MIN_VALUE) slope += h - north;
                    factor *= 1.0 + Math.max(-0.22, Math.min(0.22, slope * 0.06));
                    color = scale(rgb[index], factor);
                    if (grid && (px % pixelsPerChunk == 0 || py % pixelsPerChunk == 0)) color = scale(color, 0.86);
                }
                image.setRGB(left + px, top + py, color);
            }
        });

        // ---- labels ----
        int gap = Math.max(0, Json.integer(request, "clusterGapChunks", 1));
        List<Cluster> clusters = clusters(visible, gap);
        List<Area> areas = new ArrayList<>();
        List<ChunkRect> explicitRects = new ArrayList<>();
        if (request.has("regions")) for (JsonElement element : Json.array(request, "regions")) {
            JsonObject region = element.getAsJsonObject();
            ChunkRect rect = Json.chunkRect(region, "rectangle");
            String label = Json.string(region, "label");
            areas.add(new Area(label, Json.string(region, "group", label), rect, true));
            explicitRects.add(rect);
        }
        // A cluster entirely inside an explicit region belongs to it.
        List<Cluster> open = clusters.stream()
                .filter(cluster -> explicitRects.stream().noneMatch(rect -> covers(rect, cluster.rect()))).toList();
        Map<Cluster, List<String[]>> claimed = new LinkedHashMap<>();
        int reach = Math.max(0, Json.integer(request, "markerReachChunks", 4));
        List<JsonObject> missed = new ArrayList<>();
        if (request.has("markers")) for (JsonElement element : Json.array(request, "markers")) {
            JsonObject marker = element.getAsJsonObject();
            int cx = Math.floorDiv((int) Math.floor(Json.number(marker, "x")), 16);
            int cz = Math.floorDiv((int) Math.floor(Json.number(marker, "z")), 16);
            String label = Json.string(marker, "label");
            if (explicitRects.stream().anyMatch(rect -> rect.contains(cx, cz))) continue;
            Cluster best = null;
            int bestDistance = Integer.MAX_VALUE;
            for (Cluster cluster : open) {
                int distance = distance(cluster.rect(), cx, cz);
                if (distance <= reach && distance < bestDistance) { best = cluster; bestDistance = distance; }
            }
            if (best == null) {
                JsonObject miss = new JsonObject();
                miss.addProperty("label", label);
                miss.addProperty("chunkX", cx);
                miss.addProperty("chunkZ", cz);
                missed.add(miss);
                continue;
            }
            claimed.computeIfAbsent(best, k -> new ArrayList<>()).add(new String[]{label, Json.string(marker, "group", label)});
        }
        JsonArray unlabeled = new JsonArray();
        for (Cluster cluster : open) {
            List<String[]> hits = claimed.get(cluster);
            if (hits == null) {
                areas.add(new Area("?", UNLABELED, cluster.rect(), false));
                JsonObject entry = Json.of(cluster.rect());
                entry.addProperty("columns", cluster.columns());
                unlabeled.add(entry);
                continue;
            }
            String label = hits.get(0)[0];
            if (hits.size() > 1) label += " +" + (hits.size() - 1);
            areas.add(new Area(label, hits.get(0)[1], cluster.rect(), false));
        }

        Map<String, Integer> colors = groupColors(request, areas);
        Graphics2D g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setClip(left, top, width, height);
        for (Area a : areas) drawArea(g, a, colors.get(a.group()), bounds, pixelsPerChunk, left, top);
        g.setClip(null);
        drawAxes(g, bounds, pixelsPerChunk, majorEvery, left, top, width, height);
        if (Json.bool(request, "groupCaptions", true)) drawCaptions(g, areas, colors, bounds, pixelsPerChunk, left, top);
        Map<String, int[]> groupStats = groupStats(areas);
        drawLegend(g, Json.string(request, "title", documentName), blocksPerPixel, bounds, visible.size(),
                groupStats, colors, left + width, top, legendWidth, height);
        g.dispose();

        Path parent = destination.toAbsolutePath().getParent();
        if (parent != null) Files.createDirectories(parent);
        ImageIO.write(image, "png", destination.toFile());

        JsonObject result = new JsonObject();
        result.addProperty("path", destination.toString());
        result.addProperty("width", image.getWidth());
        result.addProperty("height", image.getHeight());
        result.addProperty("blocksPerPixel", blocksPerPixel);
        result.add("area", Json.of(bounds));
        result.addProperty("contentColumns", visible.size());
        result.addProperty("clusters", clusters.size());
        JsonArray groups = new JsonArray();
        groupStats.forEach((name, stats) -> {
            JsonObject entry = new JsonObject();
            entry.addProperty("group", name);
            entry.addProperty("areas", stats[0]);
            entry.addProperty("chunkColumns", stats[1]);
            entry.addProperty("rgb", String.format("#%06X", colors.get(name)));
            groups.add(entry);
        });
        result.add("groups", groups);
        result.add("unlabeled", unlabeled);
        JsonArray topBlocks = new JsonArray();
        surface.tally().entrySet().stream()
                .sorted((a, b) -> Long.compare(b.getValue().sum(), a.getValue().sum())).limit(20)
                .forEach(entry -> {
                    JsonObject row = new JsonObject();
                    row.addProperty("block", entry.getKey());
                    row.addProperty("pixels", entry.getValue().sum());
                    topBlocks.add(row);
                });
        result.add("surfaceBlocks", topBlocks);
        JsonArray missedMarkers = new JsonArray();
        missed.forEach(missedMarkers::add);
        result.add("missedMarkers", missedMarkers);
        JsonArray shared = new JsonArray();
        claimed.forEach((cluster, hits) -> {
            if (hits.size() < 2) return;
            JsonObject entry = Json.of(cluster.rect());
            JsonArray labels = new JsonArray();
            for (String[] hit : hits) labels.add(hit[0]);
            entry.add("labels", labels);
            shared.add(entry);
        });
        result.add("sharedClusters", shared);
        return result;
    }

    // ------------------------------------------------------------------ terrain

    private static Set<Long> contentKeys(WorldSnapshot snapshot) {
        Set<Long> keys = new HashSet<>(snapshot.materializedKeys());
        for (ArenaInstance instance : snapshot.instances()) {
            ChunkRect rect = instance.chunkBounds();
            for (int x = rect.minX(); x <= rect.maxX(); x++)
                for (int z = rect.minZ(); z <= rect.maxZ(); z++) keys.add(Checked.chunkKey(x, z));
        }
        return keys;
    }

    private static void paintChunk(WorldSnapshot snapshot, long key, ChunkRect area, int blocksPerPixel,
                                   int width, int[] rgb, int[] heights, Surface surface) {
        int chunkX = Checked.chunkKeyX(key), chunkZ = Checked.chunkKeyZ(key);
        ChunkerColumn column = snapshot.materializedColumn(chunkX, chunkZ);
        if (column == null || column.getChunks().isEmpty()) return;
        int pixelsPerChunk = 16 / blocksPerPixel;
        int originX = (chunkX - area.minX()) * pixelsPerChunk, originZ = (chunkZ - area.minZ()) * pixelsPerChunk;
        var chunks = column.getChunks();
        for (int sz = 0; sz < pixelsPerChunk; sz++) {
            for (int sx = 0; sx < pixelsPerChunk; sx++) {
                int localX = sx * blocksPerPixel + blocksPerPixel / 2, localZ = sz * blocksPerPixel + blocksPerPixel / 2;
                search:
                for (int cy = chunks.lastByteKey(); cy >= chunks.firstByteKey(); cy--) {
                    ChunkerChunk chunk = chunks.get((byte) cy);
                    if (chunk == null) continue;
                    for (int y = 15; y >= 0; y--) {
                        ChunkerBlockIdentifier block = chunk.getPalette().get(localX, y, localZ, ChunkerBlockIdentifier.AIR);
                        if (block == null || block.isAir()) continue;
                        int color = surface.color(block);
                        if (color < 0) continue;
                        surface.tally().computeIfAbsent(block.getType().toString(), k -> new LongAdder()).increment();
                        int index = (originZ + sz) * width + originX + sx;
                        rgb[index] = color;
                        heights[index] = cy * 16 + y;
                        break search;
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------ clusters

    private static List<Cluster> clusters(List<Long> keys, int gap) {
        Map<Long, Integer> index = new HashMap<>();
        for (int i = 0; i < keys.size(); i++) index.put(keys.get(i), i);
        int[] parent = IntStream.range(0, keys.size()).toArray();
        int reach = gap + 1;
        for (int i = 0; i < keys.size(); i++) {
            int x = Checked.chunkKeyX(keys.get(i)), z = Checked.chunkKeyZ(keys.get(i));
            for (int dx = -reach; dx <= reach; dx++)
                for (int dz = -reach; dz <= reach; dz++) {
                    Integer j = index.get(Checked.chunkKey(x + dx, z + dz));
                    if (j != null) union(parent, i, j);
                }
        }
        Map<Integer, int[]> boxes = new LinkedHashMap<>();
        for (int i = 0; i < keys.size(); i++) {
            int x = Checked.chunkKeyX(keys.get(i)), z = Checked.chunkKeyZ(keys.get(i));
            int[] box = boxes.computeIfAbsent(find(parent, i), k -> new int[]{x, z, x, z, 0});
            box[0] = Math.min(box[0], x); box[1] = Math.min(box[1], z);
            box[2] = Math.max(box[2], x); box[3] = Math.max(box[3], z);
            box[4]++;
        }
        // An arena whose open middle was void-cleaned can split; boxes that overlap are one place.
        List<int[]> merged = new ArrayList<>(boxes.values());
        boolean changed = true;
        while (changed) {
            changed = false;
            outer:
            for (int i = 0; i < merged.size(); i++)
                for (int j = i + 1; j < merged.size(); j++) {
                    int[] a = merged.get(i), b = merged.get(j);
                    if (a[0] <= b[2] && b[0] <= a[2] && a[1] <= b[3] && b[1] <= a[3]) {
                        merged.set(i, new int[]{Math.min(a[0], b[0]), Math.min(a[1], b[1]),
                                Math.max(a[2], b[2]), Math.max(a[3], b[3]), a[4] + b[4]});
                        merged.remove(j);
                        changed = true;
                        break outer;
                    }
                }
        }
        return merged.stream().map(b -> new Cluster(new ChunkRect(b[0], b[1], b[2], b[3]), b[4])).toList();
    }

    private static int find(int[] parent, int i) {
        while (parent[i] != i) { parent[i] = parent[parent[i]]; i = parent[i]; }
        return i;
    }

    private static void union(int[] parent, int a, int b) {
        int ra = find(parent, a), rb = find(parent, b);
        if (ra != rb) parent[Math.max(ra, rb)] = Math.min(ra, rb);
    }

    private static boolean covers(ChunkRect outer, ChunkRect inner) {
        return inner.minX() >= outer.minX() && inner.maxX() <= outer.maxX()
                && inner.minZ() >= outer.minZ() && inner.maxZ() <= outer.maxZ();
    }

    /** Chebyshev distance in chunks from a chunk to a rectangle; 0 inside. */
    private static int distance(ChunkRect rect, int x, int z) {
        int dx = Math.max(0, Math.max(rect.minX() - x, x - rect.maxX()));
        int dz = Math.max(0, Math.max(rect.minZ() - z, z - rect.maxZ()));
        return Math.max(dx, dz);
    }

    // ------------------------------------------------------------------ drawing

    private static Map<String, Integer> groupColors(JsonObject request, List<Area> areas) {
        Map<String, Integer> colors = new LinkedHashMap<>();
        if (request.has("groups")) for (JsonElement element : Json.array(request, "groups")) {
            JsonObject group = element.getAsJsonObject();
            colors.put(Json.string(group, "name"), parseRgb(group.get("rgb")));
        }
        colors.put(UNLABELED, 0x8A8F98);
        int next = 0;
        for (Area a : areas) if (!colors.containsKey(a.group())) colors.put(a.group(), PALETTE[next++ % PALETTE.length]);
        return colors;
    }

    private static int parseRgb(JsonElement value) {
        if (value.getAsJsonPrimitive().isNumber()) return value.getAsInt() & 0xFFFFFF;
        String text = value.getAsString().trim();
        if (text.startsWith("#")) text = text.substring(1);
        return Integer.parseInt(text, 16) & 0xFFFFFF;
    }

    private static Map<String, int[]> groupStats(List<Area> areas) {
        Map<String, int[]> stats = new LinkedHashMap<>();
        for (Area a : areas) {
            int[] s = stats.computeIfAbsent(a.group(), k -> new int[2]);
            s[0]++;
            s[1] += a.rect().widthChunks() * a.rect().lengthChunks();
        }
        // Unlabeled last, so the legend reads as the named places first.
        int[] unlabeled = stats.remove(UNLABELED);
        if (unlabeled != null) stats.put(UNLABELED, unlabeled);
        return stats;
    }

    private static void drawArea(Graphics2D g, Area a, int rgb, ChunkRect area, int ppc, int left, int top) {
        int x = left + (a.rect().minX() - area.minX()) * ppc, y = top + (a.rect().minZ() - area.minZ()) * ppc;
        int w = a.rect().widthChunks() * ppc, h = a.rect().lengthChunks() * ppc;
        g.setColor(new Color((rgb >> 16) & 255, (rgb >> 8) & 255, rgb & 255, a.explicit() ? 36 : 46));
        g.fillRect(x, y, w, h);
        g.setColor(new Color(rgb));
        float stroke = a.explicit() ? 3f : Math.max(1.5f, Math.min(3f, ppc / 3f));
        g.setStroke(a.group().equals(UNLABELED)
                ? new BasicStroke(stroke, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10f, new float[]{6f, 4f}, 0f)
                : new BasicStroke(stroke));
        g.drawRect(x, y, w - 1, h - 1);

        int maxSize = a.explicit() ? 40 : 22;
        Font font = fit(g, a.label(), w - 6, MIN_INSIDE_FONT - 1, maxSize);
        if (a.explicit()) drawText(g, a.label(), font, x + 8, y + 8, false, Color.WHITE);
        else if (font.getSize() >= MIN_INSIDE_FONT) drawText(g, a.label(), font, x + w / 2, y + h / 2, true, Color.WHITE);
        else {
            Font below = new Font(Font.SANS_SERIF, Font.BOLD, MIN_INSIDE_FONT + 1);
            int textWidth = g.getFontMetrics(below).stringWidth(a.label());
            drawText(g, a.label(), below, x + w / 2 - textWidth / 2, y + h + 3, false, Color.WHITE);
        }
    }

    /** Above each group's overall extent: its name and how many areas it holds. */
    private static void drawCaptions(Graphics2D g, List<Area> areas, Map<String, Integer> colors,
                                     ChunkRect area, int ppc, int left, int top) {
        Map<String, int[]> extents = new LinkedHashMap<>();
        Map<String, Integer> counts = new HashMap<>();
        for (Area a : areas) {
            if (a.explicit() || a.group().equals(UNLABELED)) continue;
            int[] e = extents.computeIfAbsent(a.group(), k -> new int[]{a.rect().minX(), a.rect().minZ(), a.rect().maxX(), a.rect().maxZ()});
            e[0] = Math.min(e[0], a.rect().minX()); e[1] = Math.min(e[1], a.rect().minZ());
            e[2] = Math.max(e[2], a.rect().maxX()); e[3] = Math.max(e[3], a.rect().maxZ());
            counts.merge(a.group(), 1, Integer::sum);
        }
        Font font = new Font(Font.SANS_SERIF, Font.BOLD, 30);
        extents.forEach((group, e) -> {
            if (counts.get(group) < 2) return;
            int rgb = colors.get(group);
            String text = group + "  ·  " + counts.get(group);
            int x = left + (e[0] - area.minX()) * ppc, y = top + (e[1] - area.minZ()) * ppc;
            drawText(g, text, font, x, Math.max(30, y - 46), false, new Color(rgb));
        });
    }

    private static void drawAxes(Graphics2D g, ChunkRect area, int ppc, int every, int left, int top, int width, int height) {
        g.setColor(new Color(BACKGROUND));
        g.fillRect(0, 0, left + width, top);
        g.fillRect(0, 0, left, top + height);
        Font font = new Font(Font.SANS_SERIF, Font.PLAIN, 12);
        g.setFont(font);
        Color ink = new Color(0xB8BEC8);
        for (int cx = area.minX(); cx <= area.maxX(); cx++) {
            if (Math.floorMod(cx, every) != 0) continue;
            int x = left + (cx - area.minX()) * ppc;
            g.setColor(ink);
            g.drawLine(x, 4, x, 22);
            g.drawString("c" + cx + " / x" + (cx * 16), x + 3, 18);
        }
        for (int cz = area.minZ(); cz <= area.maxZ(); cz++) {
            if (Math.floorMod(cz, every) != 0) continue;
            int y = top + (cz - area.minZ()) * ppc;
            g.setColor(ink);
            g.drawLine(left - 6, y, left - 1, y);
            g.drawString("c" + cz, 4, y + 4);
            g.drawString("z" + (cz * 16), 4, y + 17);
        }
    }

    private static void drawLegend(Graphics2D g, String title, int bpp, ChunkRect area, int columns,
                                   Map<String, int[]> stats, Map<String, Integer> colors,
                                   int x, int top, int width, int height) {
        g.setColor(new Color(0x16191E));
        g.fillRect(x, 0, width, top + height);
        g.setColor(new Color(0x2A2F37));
        g.fillRect(x, 0, 2, top + height);
        int cx = x + 24, y = top + 30;
        drawText(g, title, fit(g, title, width - 48, 14, 34), cx, y, false, Color.WHITE);
        y += 58;
        Font body = new Font(Font.SANS_SERIF, Font.PLAIN, 16);
        Color muted = new Color(0xB8BEC8);
        for (String line : List.of(
                "1 pixel = " + bpp + " block" + (bpp == 1 ? "" : "s") + ", 1 chunk = " + (16 / bpp) + " px",
                "Chunks x " + area.minX() + ".." + area.maxX() + ", z " + area.minZ() + ".." + area.maxZ(),
                String.format(Locale.ROOT, "%,d chunk columns with content", columns))) {
            drawText(g, line, body, cx, y, false, muted);
            y += 26;
        }
        y += 20;
        Font name = new Font(Font.SANS_SERIF, Font.BOLD, 19);
        for (var entry : stats.entrySet()) {
            int rgb = colors.get(entry.getKey());
            g.setColor(new Color(rgb));
            g.fillRect(cx, y + 2, 22, 22);
            g.setColor(Color.BLACK);
            g.drawRect(cx, y + 2, 21, 21);
            drawText(g, entry.getKey(), fit(g, entry.getKey(), width - 90, 12, 19).deriveFont(Font.BOLD), cx + 34, y, false, Color.WHITE);
            int[] s = entry.getValue();
            String detail = s[0] + (s[0] == 1 ? " area" : " areas") + String.format(Locale.ROOT, ", %,d chunks", s[1]);
            drawText(g, detail, body, cx + 34, y + 26, false, muted);
            y += 62;
            if (y > top + height - 40) break;
        }
    }

    /** The largest font from {@code min} to {@code max} whose text fits {@code width} pixels. */
    private static Font fit(Graphics2D g, String text, int width, int min, int max) {
        for (int size = max; size > min; size--) {
            Font font = new Font(Font.SANS_SERIF, Font.BOLD, size);
            if (g.getFontMetrics(font).stringWidth(text) <= width) return font;
        }
        return new Font(Font.SANS_SERIF, Font.BOLD, min);
    }

    /** White (or coloured) text with a dark outline, readable on any terrain. */
    private static void drawText(Graphics2D g, String text, Font font, int x, int y, boolean centered, Color fill) {
        if (text.isEmpty()) return;
        FontRenderContext frc = g.getFontRenderContext();
        TextLayout layout = new TextLayout(text, font, frc);
        double tx = centered ? x - layout.getBounds().getWidth() / 2 - layout.getBounds().getX() : x;
        double ty = centered ? y + layout.getAscent() / 2 - layout.getDescent() / 2 : y + layout.getAscent();
        Shape outline = layout.getOutline(AffineTransform.getTranslateInstance(tx, ty));
        g.setStroke(new BasicStroke(Math.max(2.5f, font.getSize() / 6f), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.setColor(new Color(0, 0, 0, 220));
        g.draw(outline);
        g.setColor(fill);
        g.fill(outline);
    }

    private static int scale(int rgb, double factor) {
        int r = (int) Math.min(255, ((rgb >> 16) & 255) * factor);
        int gr = (int) Math.min(255, ((rgb >> 8) & 255) * factor);
        int b = (int) Math.min(255, (rgb & 255) * factor);
        return (r << 16) | (gr << 8) | b;
    }

    private static double clamp(double value) {
        return value < 0 ? 0 : Math.min(value, 1);
    }
}
