package gg.swim.chunkdaddy.worker.document;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.hivemc.chunker.conversion.intermediate.column.ChunkerColumn;
import com.hivemc.chunker.conversion.intermediate.column.chunk.identifier.ChunkerBlockIdentifier;
import gg.swim.chunkdaddy.worker.protocol.Json;
import gg.swim.chunkdaddy.worker.util.Checked;
import gg.swim.chunkdaddy.worker.util.ChunkRect;

import java.util.*;

/** Translation-only terrain registration. Palette/storage versions and edits do not affect anchors. */
public final class RegionAlignment {
    private record Tile(long hash, int localX, int y, int localZ) {}
    private record Anchor(int x, int z) {}
    private record Offset(int x, int z) {}
    private RegionAlignment() {}

    /** Rich 8-cubed tiles: flat bedrock, uniform water and void can never vote for an alignment. */
    private static Map<Tile, Anchor> fingerprints(WorldSnapshot world, ChunkRect bounds) {
        Map<Tile, Anchor> unique = new HashMap<>();
        Set<Tile> repeated = new HashSet<>();
        Map<Object, Integer> types = new HashMap<>();
        for (long key : world.materializedKeys()) {
            int cx = Checked.chunkKeyX(key), cz = Checked.chunkKeyZ(key);
            if (bounds != null && !bounds.contains(cx, cz)) continue;
            ChunkerColumn column = world.materializedColumn(cx, cz);
            for (var chunk : column.getChunks().values()) {
                if (!chunk.getPalette().containsValue(b -> !b.isAir())) continue;
                for (int ox = 0; ox < 16; ox += 8) for (int oy = 0; oy < 16; oy += 8) for (int oz = 0; oz < 16; oz += 8) {
                    long hash = 0xcbf29ce484222325L;
                    int solid = 0;
                    Set<Integer> variety = new HashSet<>();
                    for (int x = ox; x < ox + 8; x++) for (int y = oy; y < oy + 8; y++) for (int z = oz; z < oz + 8; z++) {
                        var block = chunk.getPalette().get(x, y, z, ChunkerBlockIdentifier.AIR);
                        // Hash stable type names rather than palette indices or state flags changed by game upgrades.
                        int type = types.computeIfAbsent(block.getType(), t -> t.toString().hashCode());
                        hash = (hash ^ type) * 0x100000001b3L;
                        variety.add(type);
                        if (!block.isAir()) solid++;
                    }
                    if (solid < 32 || variety.size() < 3) continue;
                    Tile tile = new Tile(hash, ox, chunk.getY() * 16 + oy, oz);
                    if (repeated.contains(tile)) continue;
                    if (unique.putIfAbsent(tile, new Anchor(cx, cz)) != null) { unique.remove(tile); repeated.add(tile); }
                }
            }
        }
        return unique;
    }

    public static JsonObject align(WorldSnapshot reference, ChunkRect bounds, WorldSnapshot source, double minimumSimilarity) {
        if (minimumSimilarity < 0.9 || minimumSimilarity > 1) throw new IllegalArgumentException("minimumSimilarity must be between 0.9 and 1");
        var referenceTiles = fingerprints(reference, bounds);
        var sourceTiles = fingerprints(source, null);
        Map<Offset, List<Anchor>> votes = new HashMap<>();
        for (var entry : referenceTiles.entrySet()) {
            Anchor found = sourceTiles.get(entry.getKey());
            if (found == null) continue;
            Anchor ref = entry.getValue();
            votes.computeIfAbsent(new Offset(Math.subtractExact(ref.x, found.x), Math.subtractExact(ref.z, found.z)), k -> new ArrayList<>()).add(ref);
        }
        var ranked = votes.entrySet().stream().sorted(Comparator.<Map.Entry<Offset, List<Anchor>>>comparingInt(e -> e.getValue().size()).reversed()).toList();
        if (ranked.isEmpty()) throw new IllegalArgumentException("No distinctive unchanged terrain anchors; cannot align safely");
        var best = ranked.getFirst();
        int runnerUp = ranked.size() > 1 ? ranked.get(1).getValue().size() : 0;
        Set<Anchor> columns = new HashSet<>(best.getValue());
        int minX = columns.stream().mapToInt(Anchor::x).min().orElseThrow(), maxX = columns.stream().mapToInt(Anchor::x).max().orElseThrow();
        int minZ = columns.stream().mapToInt(Anchor::z).min().orElseThrow(), maxZ = columns.stream().mapToInt(Anchor::z).max().orElseThrow();
        if (best.getValue().size() < 16 || columns.size() < 8 || maxX - minX < 4 || maxZ - minZ < 4 || best.getValue().size() < runnerUp * 4)
            throw new IllegalArgumentException("Ambiguous or poorly distributed terrain anchors; refusing automatic alignment");
        Offset offset = best.getKey();
        ChunkRect sourceBounds = bounds.translated(Math.negateExact(offset.x), Math.negateExact(offset.z));
        long compared = 0, changed = 0, oldChests = 0, newChests = 0, removedChests = 0, addedChests = 0;
        Set<Long> keys = new HashSet<>();
        for (long key : reference.materializedKeys()) if (bounds.contains(Checked.chunkKeyX(key), Checked.chunkKeyZ(key))) keys.add(key);
        int ignoredColumns = 0;
        for (long key : source.materializedKeys()) {
            int x = Math.addExact(Checked.chunkKeyX(key), offset.x), z = Math.addExact(Checked.chunkKeyZ(key), offset.z);
            if (bounds.contains(x, z)) keys.add(Checked.chunkKey(x, z)); else ignoredColumns++;
        }
        for (long key : keys) {
            int x = Checked.chunkKeyX(key), z = Checked.chunkKeyZ(key);
            var before = reference.materializedColumn(x, z);
            var after = source.materializedColumn(x - offset.x, z - offset.z);
            Set<Byte> ys = new HashSet<>();
            if (before != null) ys.addAll(before.getChunks().keySet());
            if (after != null) ys.addAll(after.getChunks().keySet());
            for (byte cy : ys) for (int bx = 0; bx < 16; bx++) for (int by = 0; by < 16; by++) for (int bz = 0; bz < 16; bz++) {
                var old = block(before, cy, bx, by, bz);
                var replacement = block(after, cy, bx, by, bz);
                if (old.isAir() && replacement.isAir()) continue;
                compared++;
                if (!old.getType().equals(replacement.getType())) changed++;
                boolean a = isChest(old), b = isChest(replacement);
                if (a) oldChests++;
                if (b) newChests++;
                if (a && !b) removedChests++;
                if (!a && b) addedChests++;
            }
        }
        double similarity = compared == 0 ? 0 : 1.0 - (double) changed / compared;
        if (similarity < minimumSimilarity) throw new IllegalArgumentException("Best translation failed full terrain validation: similarity " + similarity + " < " + minimumSimilarity);
        JsonObject result = new JsonObject();
        JsonObject translation = new JsonObject();
        translation.addProperty("x", Math.multiplyExact(offset.x, 16)); translation.addProperty("y", 0); translation.addProperty("z", Math.multiplyExact(offset.z, 16));
        result.add("sourceToReferenceOffset", translation);
        result.add("sourceRectangle", Json.of(sourceBounds));
        result.addProperty("matchingAnchors", best.getValue().size()); result.addProperty("matchingColumns", columns.size());
        result.addProperty("runnerUpAnchors", runnerUp); result.addProperty("similarity", similarity);
        result.addProperty("comparedNonAirUnionBlocks", compared); result.addProperty("changedBlockTypes", changed);
        result.addProperty("oldChestBlocks", oldChests); result.addProperty("newChestBlocks", newChests);
        result.addProperty("removedChestBlocks", removedChests); result.addProperty("addedChestBlocks", addedChests);
        result.addProperty("ignoredSourceColumnsOutsideFootprint", ignoredColumns);
        JsonArray landmarks = new JsonArray();
        columns.stream().sorted(Comparator.comparingInt(Anchor::x).thenComparingInt(Anchor::z)).limit(64).forEach(a -> {
            JsonObject point = new JsonObject(); point.addProperty("chunkX", a.x); point.addProperty("chunkZ", a.z); landmarks.add(point);
        });
        result.add("landmarkColumns", landmarks);
        return result;
    }

    private static ChunkerBlockIdentifier block(ChunkerColumn column, byte cy, int x, int y, int z) {
        var chunk = column == null ? null : column.getChunks().get(cy);
        return chunk == null ? ChunkerBlockIdentifier.AIR : chunk.getPalette().get(x, y, z, ChunkerBlockIdentifier.AIR);
    }
    private static boolean isChest(ChunkerBlockIdentifier b) {
        String name = b.getType().toString();
        return name.equals("CHEST") || name.equals("TRAPPED_CHEST");
    }
}
