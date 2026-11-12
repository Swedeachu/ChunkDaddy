package gg.swim.chunkdaddy.worker.bedrock;

import com.hivemc.chunker.nbt.tags.Tag;
import com.hivemc.chunker.nbt.tags.collection.CompoundTag;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Moves one chunk column's blocks up or down by any number of blocks, not only whole
 * sub-chunks. Every version 8/9 sub-chunk of the column is decoded into block states per
 * storage layer, each block is moved by {@code deltaY}, and the column is re-encoded into
 * fresh sub-chunks with compact palettes. Sub-chunks that end up all air are not written.
 *
 * <p>Block states travel as their stored NBT, so blocks newer than any conversion profile
 * survive unchanged. Biomes are not moved (see {@link #shiftHeightMap}).</p>
 */
public final class VerticalShifter {
    /** Bedrock's Overworld build range, in sub-chunk indices: y -64 .. 319. */
    public static final int MIN_SECTION = -4;
    public static final int MAX_SECTION = 19;
    private static final int[] BIT_SIZES = {1, 2, 3, 4, 5, 6, 8, 16};

    private VerticalShifter() {
    }

    /** One decoded storage layer of one sub-chunk: 4096 palette indices (x, z, y order) and the palette. */
    private record Layer(int[] indices, List<CompoundTag> palette, String[] keys) {}

    /** A decoded sub-chunk: its version and its storage layers. */
    private record Section(int version, List<Layer> layers) {}

    /**
     * @param sections the column's sub-chunk values by signed sub-chunk index
     * @param deltaY   blocks to move up (negative moves down)
     * @return the shifted column's sub-chunk values by their new index
     * @throws IOException when a sub-chunk can't be decoded or a non-air block would leave the build range
     */
    public static Map<Integer, byte[]> shiftColumn(Map<Integer, byte[]> sections, int deltaY) throws IOException {
        if (deltaY == 0) {
            return new TreeMap<>(sections);
        }
        Map<Integer, Section> decoded = new HashMap<>();
        int version = 9;
        int maxLayers = 1;
        CompoundTag airTemplate = null;
        for (var entry : sections.entrySet()) {
            Section section = decode(entry.getValue());
            decoded.put(entry.getKey(), section);
            version = section.version();
            maxLayers = Math.max(maxLayers, section.layers().size());
            if (airTemplate == null) {
                for (Layer layer : section.layers()) {
                    if (!layer.palette().isEmpty()) {
                        airTemplate = air(layer.palette().get(0).getInt("version", 18168865));
                        break;
                    }
                }
            }
        }
        CompoundTag airTag = airTemplate != null ? airTemplate : air(18168865);
        String airKey = "";

        // Which target sections can receive blocks.
        int lowest = Integer.MAX_VALUE;
        int highest = Integer.MIN_VALUE;
        for (int index : decoded.keySet()) {
            lowest = Math.min(lowest, Math.floorDiv(index * 16 + deltaY, 16));
            highest = Math.max(highest, Math.floorDiv(index * 16 + 15 + deltaY, 16));
        }

        Map<Integer, byte[]> out = new TreeMap<>();
        for (int target = lowest; target <= highest; target++) {
            List<int[]> layerIndices = new ArrayList<>();
            List<List<CompoundTag>> layerPalettes = new ArrayList<>();
            boolean anyBlock = false;
            for (int layer = 0; layer < maxLayers; layer++) {
                Map<String, Integer> paletteIds = new HashMap<>();
                List<CompoundTag> palette = new ArrayList<>();
                paletteIds.put(airKey, 0);
                palette.add(airTag);
                int[] indices = new int[4096];
                boolean layerHasBlock = false;
                for (int y = 0; y < 16; y++) {
                    int sourceY = target * 16 + y - deltaY;
                    Section source = decoded.get(Math.floorDiv(sourceY, 16));
                    if (source == null || layer >= source.layers().size()) continue;
                    Layer from = source.layers().get(layer);
                    int sy = Math.floorMod(sourceY, 16);
                    for (int x = 0; x < 16; x++) {
                        for (int z = 0; z < 16; z++) {
                            int at = (x << 8) | (z << 4);
                            int sourceIndex = from.indices()[at | sy];
                            String key = from.keys()[sourceIndex];
                            if (key == null) continue; // air
                            CompoundTag state = from.palette().get(sourceIndex);
                            Integer id = paletteIds.get(key);
                            if (id == null) {
                                id = palette.size();
                                paletteIds.put(key, id);
                                palette.add(state);
                            }
                            indices[at | y] = id;
                            layerHasBlock = true;
                        }
                    }
                }
                if (layer > 0 && !layerHasBlock) break; // a trailing all-air layer is dropped
                anyBlock |= layerHasBlock;
                layerIndices.add(indices);
                layerPalettes.add(palette);
            }
            if (!anyBlock) continue;
            if (target < MIN_SECTION || target > MAX_SECTION) {
                throw new IOException("Shifting by " + deltaY + " would move blocks to sub-chunk " + target
                        + ", outside the Overworld build range (y -64 .. 319)");
            }
            out.put(target, encode(version, target, layerIndices, layerPalettes));
        }
        return out;
    }

    /**
     * Data3D (tag 0x2B) and Data2D (tag 0x2D) start with a 16 x 16 heightmap of little-endian
     * shorts. Each nonzero entry moves by {@code deltaY}; the biome storage after it is kept as
     * it is, so the shifted blocks keep their column's biomes at their old heights. For worlds
     * whose columns have one biome top to bottom (void and flat worlds) that is exact.
     */
    public static byte[] shiftHeightMap(byte[] value, int deltaY) {
        if (deltaY == 0 || value.length < 512) {
            return value;
        }
        byte[] out = value.clone();
        ByteBuffer buffer = ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < 256; i++) {
            short height = buffer.getShort(i * 2);
            if (height != 0) {
                buffer.putShort(i * 2, (short) Math.max(0, height + deltaY));
            }
        }
        return out;
    }

    private static CompoundTag air(int version) {
        CompoundTag tag = new CompoundTag();
        tag.put("name", "minecraft:air");
        tag.put("states", new CompoundTag());
        tag.put("version", version);
        return tag;
    }

    private static boolean isAir(CompoundTag state) {
        return "minecraft:air".equals(state.getString("name", ""));
    }

    private static String identity(CompoundTag state) throws IOException {
        return Arrays.toString(Tag.writeBedrockNBT(state));
    }

    private static Section decode(byte[] value) throws IOException {
        ByteBuffer input = ByteBuffer.wrap(value).order(ByteOrder.LITTLE_ENDIAN);
        int version = input.get() & 0xFF;
        if (version != 8 && version != 9) {
            throw new IOException("Unsupported sub-chunk version " + version + " for a vertical shift");
        }
        int layers = input.get() & 0xFF;
        if (version == 9) {
            input.get(); // stored index; rewritten on encode
        }
        List<Layer> out = new ArrayList<>();
        for (int layer = 0; layer < layers; layer++) {
            int header = input.get() & 0xFF;
            if ((header & 1) != 0) {
                throw new IOException("Runtime-ID sub-chunk palettes are not stored in worlds");
            }
            int bits = header >> 1;
            int[] indices = new int[4096];
            if (bits != 0) {
                int perWord = 32 / bits;
                int words = (4096 + perWord - 1) / perWord;
                int mask = (1 << bits) - 1;
                for (int w = 0; w < words; w++) {
                    int word = input.getInt();
                    for (int i = 0; i < perWord; i++) {
                        int index = w * perWord + i;
                        if (index < 4096) indices[index] = (word >>> (i * bits)) & mask;
                    }
                }
            }
            // A zero-bit layer has an implicit single-entry palette with no size prefix in some
            // writers; the stored format always carries the count, so read it.
            int paletteSize = input.getInt();
            List<CompoundTag> palette = new ArrayList<>(paletteSize);
            for (int i = 0; i < paletteSize; i++) {
                palette.add(Tag.readBedrockNBT(input));
            }
            for (int index : indices) {
                if (index >= palette.size()) {
                    throw new IOException("Sub-chunk palette index " + index + " out of range (" + palette.size() + ")");
                }
            }
            String[] keys = new String[palette.size()];
            for (int i = 0; i < keys.length; i++) {
                keys[i] = isAir(palette.get(i)) ? null : identity(palette.get(i));
            }
            out.add(new Layer(indices, palette, keys));
        }
        return new Section(version, out);
    }

    private static byte[] encode(int version, int index, List<int[]> layerIndices, List<List<CompoundTag>> palettes) throws IOException {
        List<byte[]> paletteBytes = new ArrayList<>();
        int size = 3;
        for (int layer = 0; layer < layerIndices.size(); layer++) {
            for (CompoundTag tag : palettes.get(layer)) {
                byte[] nbt = Tag.writeBedrockNBT(tag);
                paletteBytes.add(nbt);
                size += nbt.length;
            }
            size += 1 + 4 + 4096 * 2 + 4;
        }
        ByteBuffer out = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN);
        out.put((byte) version);
        out.put((byte) layerIndices.size());
        if (version == 9) {
            out.put((byte) index);
        }
        int paletteCursor = 0;
        for (int layer = 0; layer < layerIndices.size(); layer++) {
            List<CompoundTag> palette = palettes.get(layer);
            int[] indices = layerIndices.get(layer);
            int needed = 1;
            while ((1 << needed) < palette.size()) needed++;
            int bits = 16;
            for (int candidate : BIT_SIZES) {
                if (candidate >= needed) {
                    bits = candidate;
                    break;
                }
            }
            out.put((byte) (bits << 1));
            int perWord = 32 / bits;
            int words = (4096 + perWord - 1) / perWord;
            for (int w = 0; w < words; w++) {
                int word = 0;
                for (int i = 0; i < perWord; i++) {
                    int at = w * perWord + i;
                    if (at < 4096) word |= indices[at] << (i * bits);
                }
                out.putInt(word);
            }
            out.putInt(palette.size());
            for (int i = 0; i < palette.size(); i++) {
                out.put(paletteBytes.get(paletteCursor++));
            }
        }
        byte[] result = new byte[out.position()];
        out.flip();
        out.get(result);
        return result;
    }
}
