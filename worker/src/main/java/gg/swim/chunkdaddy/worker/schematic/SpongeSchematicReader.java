package gg.swim.chunkdaddy.worker.schematic;

import com.hivemc.chunker.nbt.tags.Tag;
import com.hivemc.chunker.nbt.tags.collection.CompoundTag;
import com.hivemc.chunker.nbt.tags.collection.ListTag;
import com.hivemc.chunker.nbt.tags.primitive.IntTag;
import com.hivemc.chunker.nbt.tags.primitive.ShortTag;
import com.hivemc.chunker.nbt.tags.primitive.StringTag;
import gg.swim.chunkdaddy.worker.util.Checked;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Reads Sponge schematics, dispatching on the actual root layout and {@code Version}
 * tag rather than on the file extension.
 *
 * <p>Version 1 and 2 keep the schematic fields directly in the named root compound and
 * spell the palette {@code Palette} / {@code BlockData}. Version 3 moves everything into
 * a {@code Schematic} child and nests block data under {@code Blocks}. Both encode the
 * block array as one unsigned LEB128 varint per position in
 * {@code x + z*width + y*width*length} order.
 *
 * <p>All size, volume, varint and palette-membership checks happen before any large
 * allocation, so a malformed or hostile file cannot make the worker allocate an
 * arbitrary amount of memory.
 */
public final class SpongeSchematicReader {
    /** Hard ceiling on accepted block volume; well above the largest production arena. */
    public static final long MAX_VOLUME = 512L * 1024 * 1024;
    /** Hard ceiling on a single dimension. Sponge stores these as unsigned 16-bit. */
    public static final int MAX_DIMENSION = 65535;

    private SpongeSchematicReader() {
    }

    public static SpongeSchematic read(File file) {
        CompoundTag root;
        try {
            root = Tag.readPossibleGZipJavaNBT(file);
        } catch (Exception e) {
            throw new SchematicFormatException("Not readable as NBT: " + file.getName() + " (" + e.getMessage() + ")", e);
        }
        if (root == null) {
            throw new SchematicFormatException("Empty NBT root in " + file.getName());
        }
        return read(root, file.getName());
    }

    public static SpongeSchematic read(CompoundTag root, String displayName) {
        // Version 3 nests the schematic in a child compound. A file that has both a
        // child "Schematic" compound and a Version tag is dispatched on the child.
        CompoundTag schematic = root.getOptional("Schematic", CompoundTag.class).orElse(root);

        int version = schematic.getInt("Version", -1);
        if (version == -1) {
            throw new SchematicFormatException(
                    "No Sponge Version tag in " + displayName + ". This is not a Sponge schematic; " +
                            "MCEdit .schematic and Litematica .litematic are different formats.");
        }
        return switch (version) {
            case 1, 2 -> readV1V2(schematic, displayName, version);
            case 3 -> readV3(schematic, displayName);
            default -> throw new SchematicFormatException(
                    "Unsupported Sponge schematic version " + version + " in " + displayName +
                            ". ChunkDaddy reads versions 1, 2 and 3.");
        };
    }

    // ------------------------------------------------------------------
    // Version 1 / 2
    // ------------------------------------------------------------------

    private static SpongeSchematic readV1V2(CompoundTag schematic, String displayName, int version) {
        int sizeX = readUnsignedShortDimension(schematic, "Width", displayName);
        int sizeY = readUnsignedShortDimension(schematic, "Height", displayName);
        int sizeZ = readUnsignedShortDimension(schematic, "Length", displayName);
        long volume = checkVolume(sizeX, sizeY, sizeZ, displayName);

        // Version 1 predates DataVersion; those files are 1.12-era and are read with the
        // oldest flattened resolver Chunker offers, which is the honest answer rather
        // than pretending we know the exact revision.
        int dataVersion = schematic.getInt("DataVersion", 0);

        int[] offset = readIntTriple(schematic, "Offset", displayName, 3);
        if (offset == null) offset = new int[]{0, 0, 0};

        CompoundTag metadata = schematic.getOptional("Metadata", CompoundTag.class).orElse(null);

        CompoundTag paletteTag = schematic.getOptional("Palette", CompoundTag.class)
                .orElseThrow(() -> new SchematicFormatException("Missing Palette in " + displayName));
        List<CompoundTag> palette = decodePalette(paletteTag, schematic.getInt("PaletteMax", -1), displayName);

        byte[] blockData = schematic.getByteArray("BlockData", null);
        if (blockData == null) {
            throw new SchematicFormatException(
                    "Missing BlockData in " + displayName + ". Schematics that only carry the legacy " +
                            "Blocks/Data byte arrays are not supported.");
        }
        int[] indices = decodeVarints(blockData, volume, palette.size(), displayName);

        List<SpongeSchematic.BlockEntityRecord> blockEntities =
                readBlockEntitiesV2(schematic, sizeX, sizeY, sizeZ, displayName);

        // Version 1 and 2 do not carry a WorldEdit origin in a form we are willing to
        // reconstruct from. Some tools write Metadata.WEOffsetX/Y/Z, but that field has
        // meant different things across tools and versions; it is kept as provenance in
        // `metadata` and deliberately not used to invent a source minimum.
        return new SpongeSchematic(version, dataVersion, sizeX, sizeY, sizeZ,
                offset, null, null, palette, indices, blockEntities, metadata);
    }

    private static List<SpongeSchematic.BlockEntityRecord> readBlockEntitiesV2(
            CompoundTag schematic, int sizeX, int sizeY, int sizeZ, String displayName) {
        ListTag<CompoundTag, Map<String, Tag<?>>> list =
                schematic.getList("BlockEntities", CompoundTag.class, null);
        if (list == null) {
            // Version 1 called them TileEntities.
            list = schematic.getList("TileEntities", CompoundTag.class, null);
        }
        if (list == null) return List.of();

        List<SpongeSchematic.BlockEntityRecord> result = new ArrayList<>(list.size());
        for (CompoundTag entry : list) {
            int[] pos = readIntTriple(entry, "Pos", displayName, 3);
            if (pos == null) continue;
            String id = normalizeId(entry.getString("Id", entry.getString("id", null)));
            if (id == null) continue;
            checkBlockEntityPosition(pos, sizeX, sizeY, sizeZ, displayName);

            // In v2 the type-specific fields live directly in the entry. Copy everything
            // except the container-level keys and re-spell it as Java world NBT.
            CompoundTag nbt = entry.clone();
            nbt.remove("Pos");
            nbt.remove("Id");
            nbt.remove("Extra");
            applyJavaHeader(nbt, id, pos);
            result.add(new SpongeSchematic.BlockEntityRecord(pos[0], pos[1], pos[2], id, nbt));
        }
        return result;
    }

    // ------------------------------------------------------------------
    // Version 3
    // ------------------------------------------------------------------

    private static SpongeSchematic readV3(CompoundTag schematic, String displayName) {
        int sizeX = readUnsignedShortDimension(schematic, "Width", displayName);
        int sizeY = readUnsignedShortDimension(schematic, "Height", displayName);
        int sizeZ = readUnsignedShortDimension(schematic, "Length", displayName);
        long volume = checkVolume(sizeX, sizeY, sizeZ, displayName);

        int dataVersion = schematic.getInt("DataVersion", 0);
        if (dataVersion <= 0) {
            throw new SchematicFormatException(
                    "Sponge v3 schematic " + displayName + " has no usable DataVersion. " +
                            "Block states cannot be interpreted without knowing the Java revision.");
        }

        int[] offset = readIntTriple(schematic, "Offset", displayName, 3);
        if (offset == null) offset = new int[]{0, 0, 0};

        CompoundTag metadata = schematic.getOptional("Metadata", CompoundTag.class).orElse(null);
        int[] origin = readWorldEditOriginV3(metadata, displayName);

        CompoundTag blocks = schematic.getOptional("Blocks", CompoundTag.class)
                .orElseThrow(() -> new SchematicFormatException("Missing Blocks compound in v3 schematic " + displayName));

        CompoundTag paletteTag = blocks.getOptional("Palette", CompoundTag.class)
                .orElseThrow(() -> new SchematicFormatException("Missing Blocks.Palette in " + displayName));
        List<CompoundTag> palette = decodePalette(paletteTag, -1, displayName);

        byte[] blockData = blocks.getByteArray("Data", null);
        if (blockData == null) {
            throw new SchematicFormatException("Missing Blocks.Data in " + displayName);
        }
        int[] indices = decodeVarints(blockData, volume, palette.size(), displayName);

        List<SpongeSchematic.BlockEntityRecord> blockEntities =
                readBlockEntitiesV3(blocks, sizeX, sizeY, sizeZ, displayName);

        // S = O + F, only when an origin was actually stored.
        int[] sourceMinimum = null;
        if (origin != null) {
            sourceMinimum = new int[]{origin[0] + offset[0], origin[1] + offset[1], origin[2] + offset[2]};
        }

        return new SpongeSchematic(3, dataVersion, sizeX, sizeY, sizeZ,
                offset, origin, sourceMinimum, palette, indices, blockEntities, metadata);
    }

    private static @Nullable int[] readWorldEditOriginV3(@Nullable CompoundTag metadata, String displayName) {
        if (metadata == null) return null;
        CompoundTag worldEdit = metadata.getOptional("WorldEdit", CompoundTag.class).orElse(null);
        if (worldEdit == null) return null;
        return readIntTriple(worldEdit, "Origin", displayName, 3);
    }

    private static List<SpongeSchematic.BlockEntityRecord> readBlockEntitiesV3(
            CompoundTag blocks, int sizeX, int sizeY, int sizeZ, String displayName) {
        ListTag<CompoundTag, Map<String, Tag<?>>> list =
                blocks.getList("BlockEntities", CompoundTag.class, null);
        if (list == null) return List.of();

        List<SpongeSchematic.BlockEntityRecord> result = new ArrayList<>(list.size());
        for (CompoundTag entry : list) {
            int[] pos = readIntTriple(entry, "Pos", displayName, 3);
            if (pos == null) continue;
            String id = normalizeId(entry.getString("Id", null));
            if (id == null) continue;
            checkBlockEntityPosition(pos, sizeX, sizeY, sizeZ, displayName);

            // v3 separates the payload into a Data compound.
            CompoundTag data = entry.getOptional("Data", CompoundTag.class).orElse(null);
            CompoundTag nbt = data == null ? new CompoundTag() : data.clone();
            applyJavaHeader(nbt, id, pos);
            result.add(new SpongeSchematic.BlockEntityRecord(pos[0], pos[1], pos[2], id, nbt));
        }
        return result;
    }

    // ------------------------------------------------------------------
    // Shared decoding
    // ------------------------------------------------------------------

    /**
     * Sponge stores dimensions in a short tag but defines them as unsigned 16-bit, so a
     * value above 32767 arrives as a negative short and must be widened, not rejected.
     */
    private static int readUnsignedShortDimension(CompoundTag compound, String name, String displayName) {
        Tag<?> tag = compound.get(name);
        int value;
        if (tag instanceof ShortTag shortTag) {
            value = shortTag.getValue() & 0xFFFF;
        } else if (tag instanceof IntTag intTag) {
            // Some writers use an int tag; Minecraft-adjacent tooling tolerates it.
            value = intTag.getValue();
        } else {
            throw new SchematicFormatException("Missing or invalid " + name + " in " + displayName);
        }
        if (value <= 0 || value > MAX_DIMENSION) {
            throw new SchematicFormatException(
                    "Dimension " + name + "=" + value + " in " + displayName + " is out of range 1.." + MAX_DIMENSION);
        }
        return value;
    }

    private static long checkVolume(int sizeX, int sizeY, int sizeZ, String displayName) {
        long volume = Checked.volume(sizeX, sizeY, sizeZ);
        if (volume > MAX_VOLUME) {
            throw new SchematicFormatException(
                    "Refusing " + displayName + ": " + sizeX + "x" + sizeY + "x" + sizeZ + " is " + volume +
                            " block positions, above the " + MAX_VOLUME + " limit.");
        }
        if (volume > Integer.MAX_VALUE) {
            throw new SchematicFormatException(
                    "Refusing " + displayName + ": volume " + volume + " exceeds a single indexable array.");
        }
        return volume;
    }

    private static @Nullable int[] readIntTriple(CompoundTag compound, String name, String displayName, int expected) {
        int[] value = compound.getIntArray(name, null);
        if (value == null) return null;
        if (value.length != expected) {
            throw new SchematicFormatException(
                    name + " in " + displayName + " has " + value.length + " entries, expected " + expected);
        }
        return value;
    }

    /**
     * Decode the {@code name -> index} palette into a dense list of Java block state
     * compounds. Gaps in the index space are a format error rather than something to
     * paper over with air, because an unnoticed gap would silently delete blocks.
     */
    private static List<CompoundTag> decodePalette(CompoundTag paletteTag, int paletteMax, String displayName) {
        int size = paletteTag.size();
        if (size == 0) {
            throw new SchematicFormatException("Empty palette in " + displayName);
        }
        if (paletteMax >= 0 && paletteMax != size) {
            // PaletteMax is advisory in v2 and disagreeing with the real size has been
            // seen in the wild; trust the actual entries but say so.
            size = Math.max(size, paletteMax);
        }
        CompoundTag[] dense = new CompoundTag[size];
        for (Map.Entry<String, Tag<?>> entry : paletteTag.getBoxedValue().entrySet()) {
            if (!(entry.getValue() instanceof IntTag indexTag)) {
                throw new SchematicFormatException(
                        "Palette entry " + entry.getKey() + " in " + displayName + " is not an int index");
            }
            int index = indexTag.getValue();
            if (index < 0 || index >= size) {
                throw new SchematicFormatException(
                        "Palette index " + index + " for " + entry.getKey() + " in " + displayName +
                                " is outside 0.." + (size - 1));
            }
            if (dense[index] != null) {
                throw new SchematicFormatException(
                        "Duplicate palette index " + index + " in " + displayName);
            }
            dense[index] = parseBlockState(entry.getKey(), displayName);
        }
        List<CompoundTag> result = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            if (dense[i] == null) {
                throw new SchematicFormatException(
                        "Palette in " + displayName + " has no entry for index " + i +
                                "; refusing rather than substituting air.");
            }
            result.add(dense[i]);
        }
        return result;
    }

    /**
     * Parse a Sponge palette key such as
     * {@code minecraft:oak_stairs[facing=east,half=bottom,shape=straight,waterlogged=false]}
     * into the {@code {Name, Properties}} compound that Chunker's Java resolvers read.
     */
    static CompoundTag parseBlockState(String key, String displayName) {
        String name = key;
        CompoundTag properties = null;
        int bracket = key.indexOf('[');
        if (bracket >= 0) {
            if (!key.endsWith("]")) {
                throw new SchematicFormatException("Malformed palette key '" + key + "' in " + displayName);
            }
            name = key.substring(0, bracket);
            String body = key.substring(bracket + 1, key.length() - 1);
            properties = new CompoundTag();
            if (!body.isEmpty()) {
                for (String pair : body.split(",")) {
                    int eq = pair.indexOf('=');
                    if (eq <= 0) {
                        throw new SchematicFormatException(
                                "Malformed block state property '" + pair + "' in key '" + key + "' of " + displayName);
                    }
                    properties.put(pair.substring(0, eq).trim(), new StringTag(pair.substring(eq + 1).trim()));
                }
            }
        }
        name = name.trim();
        if (name.isEmpty()) {
            throw new SchematicFormatException("Empty block name in palette key '" + key + "' of " + displayName);
        }
        if (!name.contains(":")) name = "minecraft:" + name;

        CompoundTag state = new CompoundTag(2);
        state.put("Name", name.toLowerCase(Locale.ROOT));
        if (properties != null && properties.size() > 0) {
            state.put("Properties", properties);
        }
        return state;
    }

    /**
     * Decode the unsigned LEB128 block array.
     *
     * <p>The decoded entry count must match the declared volume exactly and every index
     * must be present in the palette. A truncated final varint is a format error: the
     * alternative, stopping early, would quietly drop the last blocks of the build.
     */
    static int[] decodeVarints(byte[] data, long volume, int paletteSize, String displayName) {
        int expected = (int) volume;
        int[] out = new int[expected];
        int pos = 0;
        int count = 0;
        while (pos < data.length) {
            if (count == expected) {
                throw new SchematicFormatException(
                        "Block data in " + displayName + " decodes to more than the declared " + expected + " positions");
            }
            int value = 0;
            int shift = 0;
            while (true) {
                if (pos >= data.length) {
                    throw new SchematicFormatException(
                            "Truncated varint at position " + count + " in " + displayName);
                }
                byte b = data[pos++];
                value |= (b & 0x7F) << shift;
                if ((b & 0x80) == 0) break;
                shift += 7;
                if (shift > 28) {
                    throw new SchematicFormatException(
                            "Varint at position " + count + " in " + displayName + " is longer than 5 bytes");
                }
            }
            if (value < 0 || value >= paletteSize) {
                throw new SchematicFormatException(
                        "Block data in " + displayName + " references palette index " + value +
                                " but the palette has " + paletteSize + " entries");
            }
            out[count++] = value;
        }
        if (count != expected) {
            throw new SchematicFormatException(
                    "Block data in " + displayName + " decoded " + count + " positions but the declared size is " + expected);
        }
        return out;
    }

    private static void checkBlockEntityPosition(int[] pos, int sizeX, int sizeY, int sizeZ, String displayName) {
        if (pos[0] < 0 || pos[0] >= sizeX || pos[1] < 0 || pos[1] >= sizeY || pos[2] < 0 || pos[2] >= sizeZ) {
            throw new SchematicFormatException(
                    "Block entity at " + pos[0] + "," + pos[1] + "," + pos[2] + " in " + displayName +
                            " is outside the schematic bounds " + sizeX + "x" + sizeY + "x" + sizeZ);
        }
    }

    private static @Nullable String normalizeId(@Nullable String id) {
        if (id == null || id.isBlank()) return null;
        String lower = id.trim().toLowerCase(Locale.ROOT);
        return lower.contains(":") ? lower : "minecraft:" + lower;
    }

    /** Re-spell a block entity payload as Java world NBT: lowercase id plus x/y/z. */
    private static void applyJavaHeader(CompoundTag nbt, String id, int[] localPos) {
        nbt.put("id", id);
        nbt.put("x", localPos[0]);
        nbt.put("y", localPos[1]);
        nbt.put("z", localPos[2]);
        nbt.put("keepPacked", (byte) 0);
    }
}
