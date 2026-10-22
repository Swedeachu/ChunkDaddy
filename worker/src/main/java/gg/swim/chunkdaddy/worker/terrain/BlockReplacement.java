package gg.swim.chunkdaddy.worker.terrain;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.hivemc.chunker.conversion.encoding.base.resolver.blockentity.BlockEntityResolver;
import com.hivemc.chunker.conversion.intermediate.column.ChunkerColumn;
import com.hivemc.chunker.conversion.intermediate.column.chunk.identifier.ChunkerBlockIdentifier;
import com.hivemc.chunker.conversion.intermediate.column.chunk.identifier.type.block.ChunkerVanillaBlockType;
import com.hivemc.chunker.conversion.intermediate.column.chunk.identifier.type.block.states.vanilla.VanillaBlockStates;
import com.hivemc.chunker.conversion.intermediate.column.chunk.identifier.type.block.states.vanilla.types.Bool;
import com.hivemc.chunker.nbt.tags.collection.CompoundTag;
import gg.swim.chunkdaddy.worker.document.ColumnOps;
import gg.swim.chunkdaddy.worker.document.WorldDocument;
import gg.swim.chunkdaddy.worker.protocol.Json;
import gg.swim.chunkdaddy.worker.util.Checked;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Ordered, bounded replacement recipes. Erosion and coherent noise run against a detached volume before one commit. */
public final class BlockReplacement {
    private BlockReplacement() { }

    public static JsonObject apply(WorldDocument document, JsonObject request,
                                   BlockEntityResolver<?, CompoundTag> resolver) throws Exception {
        JsonObject recipe = JsonParser.parseString(Files.readString(Path.of(Json.string(request, "recipe")))).getAsJsonObject();
        TerrainVolume volume = TerrainVolume.read(document, recipe);
        if (recipe.has("fingerprint") && !volume.fingerprint.equals(Json.string(recipe, "fingerprint")))
            throw new IllegalArgumentException("Replacement recipe does not match this source volume");
        short[] edited = volume.blocks.clone();
        List<ChunkerBlockIdentifier> palette = new ArrayList<>(volume.palette);
        JsonArray reports = new JsonArray();
        for (var entry : recipe.getAsJsonArray("operations")) {
            JsonObject operation = entry.getAsJsonObject();
            Set<ChunkerVanillaBlockType> types = new HashSet<>();
            for (var type : operation.getAsJsonArray("match")) types.add(ChunkerVanillaBlockType.valueOf(type.getAsString()));
            if (types.isEmpty() || types.contains(ChunkerVanillaBlockType.AIR))
                throw new IllegalArgumentException("Replacement match must contain non-air block types");
            boolean[] matches = new boolean[palette.size()];
            for (int i = 0; i < palette.size(); i++) matches[i] = types.contains(palette.get(i).getType());
            String mode = Json.string(operation, "mode", "replace");
            int count;
            if (mode.equals("erode")) {
                String faces = Json.string(operation, "faces", "horizontal");
                if (!faces.equals("horizontal") && !faces.equals("all")) throw new IllegalArgumentException("faces must be horizontal or all");
                count = erode(volume, edited, matches, faces.equals("all"));
            } else if (mode.equals("replace")) {
                JsonArray choices = operation.getAsJsonArray("palette");
                if (choices == null || choices.isEmpty()) throw new IllegalArgumentException("Replacement palette is empty");
                int[] ids = new int[choices.size()];
                double[] weights = new double[choices.size()];
                double total = 0;
                for (int j = 0; j < choices.size(); j++) {
                    JsonObject choice = choices.get(j).getAsJsonObject();
                    ids[j] = id(palette, new ChunkerBlockIdentifier(ChunkerVanillaBlockType.valueOf(Json.string(choice, "type"))));
                    double weight = choice.has("weight") ? choice.get("weight").getAsDouble() : 1;
                    if (!Double.isFinite(weight) || weight <= 0) throw new IllegalArgumentException("Palette weights must be finite and positive");
                    total += weight; weights[j] = total;
                }
                if (!Double.isFinite(total)) throw new IllegalArgumentException("Palette weights overflow");
                double scale = operation.has("scale") ? operation.get("scale").getAsDouble() : 6;
                if (!Double.isFinite(scale) || scale <= 0) throw new IllegalArgumentException("Noise scale must be positive");
                long seed = operation.has("seed") ? operation.get("seed").getAsLong() : 0;
                count = 0;
                // Cache one noise value per x/z column. Vertical surfaces retain a consistent material pattern.
                double[] noise = new double[volume.width * volume.depth];
                for (int z = 0; z < volume.depth; z++) for (int x = 0; x < volume.width; x++)
                    noise[z * volume.width + x] = noise(volume.minX + x, volume.minZ + z, scale, seed) * total;
                for (int i = 0; i < edited.length; i++) {
                    int old = edited[i] & 65535;
                    if (old >= matches.length || !matches[old]) continue;
                    int choice = 0;
                    double value = noise[i / (volume.width * volume.height) * volume.width + i % volume.width];
                    while (choice < weights.length - 1 && value >= weights[choice]) choice++;
                    if (old != ids[choice]) { edited[i] = (short) ids[choice]; count++; }
                }
            } else throw new IllegalArgumentException("Unknown replacement mode: " + mode);
            JsonObject report = new JsonObject();
            report.addProperty("mode", mode); report.add("match", operation.get("match")); report.addProperty("changedBlocks", count);
            reports.add(report);
        }
        int dried = 0;
        if (Json.bool(recipe, "clearWaterlogged", false)) {
            int[] dryIds = new int[palette.size()];
            Arrays.fill(dryIds, -1);
            for (int p = 0; p < dryIds.length; p++) {
                var block = palette.get(p);
                if (block.getState(VanillaBlockStates.WATERLOGGED) == Bool.TRUE)
                    dryIds[p] = id(palette, block.copyWith(VanillaBlockStates.WATERLOGGED, Bool.FALSE));
            }
            for (int i = 0; i < edited.length; i++) {
                int p = edited[i] & 65535;
                if (p < dryIds.length && dryIds[p] >= 0) { edited[i] = (short) dryIds[p]; dried++; }
            }
        }
        int changedCount = 0;
        for (int i = 0; i < edited.length; i++) if (edited[i] != volume.blocks[i]) changedCount++;
        var source = document.snapshot();
        // A generic material recipe must not silently invalidate a container or other positional block record.
        for (long key : source.materializedKeys()) for (var entity : source.materializedColumn(Checked.chunkKeyX(key), Checked.chunkKeyZ(key)).getBlockEntities()) {
            int i = volume.indexWorld(entity.getX(), entity.getY(), entity.getZ());
            if (i >= 0 && edited[i] != volume.blocks[i]
                    && palette.get(edited[i] & 65535).getType() != volume.palette.get(volume.blocks[i] & 65535).getType())
                throw new IllegalArgumentException("Replacement would invalidate block entity at " + entity.getX() + "," + entity.getY() + "," + entity.getZ());
        }
        JsonObject result = new JsonObject();
        result.addProperty("fingerprint", volume.fingerprint); result.add("operations", reports);
        result.addProperty("driedBlocks", dried); result.addProperty("changedBlocks", changedCount);
        boolean validateOnly = Json.bool(request, "validateOnly", false);
        result.addProperty("validateOnly", validateOnly);
        if (validateOnly || changedCount == 0) { result.addProperty("revision", document.revision()); return result; }
        var builder = source.toBuilder();
        Map<Long, ChunkerColumn> changed = new LinkedHashMap<>();
        for (int i = 0; i < edited.length; i++) {
            if (edited[i] == volume.blocks[i]) continue;
            int x = volume.minX + i % volume.width;
            int y = volume.minY + i / volume.width % volume.height;
            int z = volume.minZ + i / (volume.width * volume.height);
            long key = Checked.chunkKey(x >> 4, z >> 4);
            ChunkerColumn column = changed.get(key);
            if (column == null) {
                column = ColumnOps.relocate(source.materializedColumn(x >> 4, z >> 4), 0, 0, 0, true, resolver);
                changed.put(key, column);
            }
            ColumnOps.writeableChunk(column, (byte) (y >> 4)).set(x & 15, y & 15, z & 15, palette.get(edited[i] & 65535));
        }
        for (var column : changed.values()) {
            column.setHeightMap(null); column.setLightPopulated(false);
            builder.putColumn(column.getPosition().chunkX(), column.getPosition().chunkZ(), column);
        }
        result.addProperty("revision", document.commitSnapshot(builder.build()));
        result.addProperty("changedColumns", changed.size());
        return result;
    }

    private static int id(List<ChunkerBlockIdentifier> palette, ChunkerBlockIdentifier block) {
        int id = palette.indexOf(block);
        if (id >= 0) return id;
        if (palette.size() >= 65536) throw new IllegalArgumentException("Too many block states");
        palette.add(block); return palette.size() - 1;
    }

    private static boolean matches(short[] blocks, boolean[] matches, int i) {
        int id = blocks[i] & 65535;
        return id < matches.length && matches[id];
    }

    private static int erode(TerrainVolume v, short[] blocks, boolean[] matches, boolean allFaces) {
        ArrayDeque<Integer> queue = new ArrayDeque<>();
        BitSet queued = new BitSet(blocks.length);
        int[] offsets = allFaces ? new int[]{-1,1,-v.width*v.height,v.width*v.height,-v.width,v.width}
                                : new int[]{-1,1,-v.width*v.height,v.width*v.height};
        for (int i = 0; i < blocks.length; i++) if (matches(blocks, matches, i)) {
            for (int face = 0; face < offsets.length; face++) {
                int neighbor = neighbor(v, i, face, offsets[face]);
                if (neighbor < 0 || blocks[neighbor] == 0) { queue.add(i); queued.set(i); break; }
            }
        }
        int count = 0;
        while (!queue.isEmpty()) {
            int i = queue.removeFirst(); blocks[i] = 0; count++;
            for (int face = 0; face < offsets.length; face++) {
                int neighbor = neighbor(v, i, face, offsets[face]);
                if (neighbor >= 0 && !queued.get(neighbor) && matches(blocks, matches, neighbor)) {
                    queued.set(neighbor); queue.add(neighbor);
                }
            }
        }
        return count;
    }

    private static int neighbor(TerrainVolume v, int i, int face, int offset) {
        int x = i % v.width, y = i / v.width % v.height, z = i / (v.width * v.height);
        if (face == 0 && x == 0 || face == 1 && x == v.width - 1
                || face == 2 && z == 0 || face == 3 && z == v.depth - 1
                || face == 4 && y == 0 || face == 5 && y == v.height - 1) return -1;
        return i + offset;
    }

    static double noise(int x, int z, double scale, long seed) {
        double px = x / scale, pz = z / scale;
        long ix = (long) Math.floor(px), iz = (long) Math.floor(pz);
        double fx = px - ix, fz = pz - iz;
        fx = fx * fx * (3 - 2 * fx); fz = fz * fz * (3 - 2 * fz);
        double a = random(ix, iz, seed), b = random(ix + 1, iz, seed);
        double c = random(ix, iz + 1, seed), d = random(ix + 1, iz + 1, seed);
        return (a + (b - a) * fx) * (1 - fz) + (c + (d - c) * fx) * fz;
    }

    private static double random(long x, long z, long seed) {
        long h = seed ^ x * 0x9E3779B97F4A7C15L ^ z * 0xC2B2AE3D27D4EB4FL;
        h = (h ^ h >>> 30) * 0xBF58476D1CE4E5B9L;
        h = (h ^ h >>> 27) * 0x94D049BB133111EBL;
        h ^= h >>> 31;
        return (h >>> 11) * 0x1.0p-53;
    }
}
