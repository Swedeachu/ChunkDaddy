package gg.swim.chunkdaddy.worker.schematic;

import com.hivemc.chunker.nbt.TagType;
import com.hivemc.chunker.nbt.tags.Tag;
import com.hivemc.chunker.nbt.tags.collection.CompoundTag;
import com.hivemc.chunker.nbt.tags.collection.ListTag;
import com.hivemc.chunker.nbt.tags.primitive.IntTag;
import com.hivemc.chunker.nbt.tags.primitive.StringTag;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Small owned fixtures for the decoding rules that are easy to get wrong: axis order,
 * root layout, varint termination and offset arithmetic. Deliberately tiny and
 * asymmetric, so a transposed axis cannot pass by accident.
 */
class SpongeSchematicReaderTest {

    /** Unsigned LEB128, matching the Sponge specification's block array encoding. */
    private static byte[] varints(int... values) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (int value : values) {
            int remaining = value;
            while ((remaining & ~0x7F) != 0) {
                out.write((remaining & 0x7F) | 0x80);
                remaining >>>= 7;
            }
            out.write(remaining);
        }
        return out.toByteArray();
    }

    /** 3 wide, 2 high, 4 long: every axis a different length. */
    private static int[] asymmetricIndices() {
        int sizeX = 3;
        int sizeY = 2;
        int sizeZ = 4;
        int[] indices = new int[sizeX * sizeY * sizeZ];
        // Stone at the minimum corner, dirt at the maximum corner, air everywhere else.
        indices[0 + 0 * sizeX + 0 * sizeX * sizeZ] = 1;
        indices[2 + 3 * sizeX + 1 * sizeX * sizeZ] = 2;
        return indices;
    }

    private static CompoundTag palette() {
        CompoundTag palette = new CompoundTag();
        palette.put("minecraft:air", new IntTag(0));
        palette.put("minecraft:stone", new IntTag(1));
        palette.put("minecraft:dirt[snowy=false]", new IntTag(2));
        return palette;
    }

    private static CompoundTag v2Root() {
        CompoundTag root = new CompoundTag();
        root.put("Version", 2);
        root.put("DataVersion", 4325);
        root.put("Width", (short) 3);
        root.put("Height", (short) 2);
        root.put("Length", (short) 4);
        root.put("Offset", new int[]{-3, -1, -4});
        root.put("Palette", palette());
        root.put("PaletteMax", 3);
        root.put("BlockData", varints(asymmetricIndices()));
        return root;
    }

    private static CompoundTag v3Root(boolean withOrigin) {
        CompoundTag blocks = new CompoundTag();
        blocks.put("Palette", palette());
        blocks.put("Data", varints(asymmetricIndices()));

        CompoundTag schematic = new CompoundTag();
        schematic.put("Version", 3);
        schematic.put("DataVersion", 4189);
        schematic.put("Width", (short) 3);
        schematic.put("Height", (short) 2);
        schematic.put("Length", (short) 4);
        schematic.put("Offset", new int[]{-198, -1, -248});
        schematic.put("Blocks", blocks);

        if (withOrigin) {
            CompoundTag worldEdit = new CompoundTag();
            worldEdit.put("Origin", new int[]{-27, 68, 1485});
            CompoundTag metadata = new CompoundTag();
            metadata.put("WorldEdit", worldEdit);
            schematic.put("Metadata", metadata);
        }

        CompoundTag root = new CompoundTag();
        root.put("Schematic", schematic);
        return root;
    }

    @Test
    void readsVersionTwoFromTheRootCompound() {
        SpongeSchematic schematic = SpongeSchematicReader.read(v2Root(), "fixture-v2");
        assertEquals(2, schematic.spongeVersion());
        assertEquals(4325, schematic.javaDataVersion());
        assertEquals(3, schematic.sizeX());
        assertEquals(2, schematic.sizeY());
        assertEquals(4, schematic.sizeZ());
        // A v2 Axiom-style file carries no origin, so no source minimum can be claimed.
        assertNull(schematic.worldEditOrigin());
        assertNull(schematic.sourceMinimum());
    }

    @Test
    void readsVersionThreeFromTheNestedCompound() {
        SpongeSchematic schematic = SpongeSchematicReader.read(v3Root(false), "fixture-v3");
        assertEquals(3, schematic.spongeVersion());
        assertEquals(4189, schematic.javaDataVersion());
        assertEquals(3, schematic.sizeX());
    }

    @Test
    void indexOrderIsXThenZThenY() {
        SpongeSchematic schematic = SpongeSchematicReader.read(v2Root(), "fixture-v2");
        // index = x + z*width + y*width*length
        assertEquals(0, schematic.indexOf(0, 0, 0));
        assertEquals(1, schematic.indexOf(1, 0, 0));
        assertEquals(3, schematic.indexOf(0, 0, 1));
        assertEquals(12, schematic.indexOf(0, 1, 0));

        assertEquals("minecraft:stone",
                schematic.palette().get(schematic.paletteIndices()[schematic.indexOf(0, 0, 0)])
                        .getString("Name", "?"));
        assertEquals("minecraft:dirt",
                schematic.palette().get(schematic.paletteIndices()[schematic.indexOf(2, 1, 3)])
                        .getString("Name", "?"));
    }

    @Test
    void blockStatePropertiesAreParsed() {
        SpongeSchematic schematic = SpongeSchematicReader.read(v2Root(), "fixture-v2");
        CompoundTag dirt = schematic.palette().get(2);
        assertEquals("minecraft:dirt", dirt.getString("Name", "?"));
        CompoundTag properties = dirt.get("Properties", CompoundTag.class);
        assertNotNull(properties);
        assertEquals("false", properties.getString("snowy", "?"));
    }

    @Test
    void cyberpunkOriginArithmetic() {
        // The real 8-cyberpunk values: S = O + F.
        SpongeSchematic schematic = SpongeSchematicReader.read(v3Root(true), "fixture-cyberpunk");
        assertNotNull(schematic.worldEditOrigin());
        assertNotNull(schematic.sourceMinimum());
        assertEquals(-225, schematic.sourceMinimum()[0]);
        assertEquals(67, schematic.sourceMinimum()[1]);
        assertEquals(1237, schematic.sourceMinimum()[2]);
        // The offset must not be applied a second time.
        assertEquals(-198, schematic.offset()[0]);
    }

    @Test
    void aTruncatedVarintIsRefused() {
        CompoundTag root = v2Root();
        byte[] data = root.getByteArray("BlockData", null);
        byte[] truncated = new byte[data.length];
        System.arraycopy(data, 0, truncated, 0, data.length);
        // Make the final byte a continuation, so the stream ends mid-varint.
        truncated[truncated.length - 1] = (byte) 0x80;
        root.put("BlockData", truncated);

        SchematicFormatException error =
                assertThrows(SchematicFormatException.class, () -> SpongeSchematicReader.read(root, "truncated"));
        assertTrue(error.getMessage().contains("Truncated"));
    }

    @Test
    void aShortBlockArrayIsRefusedRatherThanPaddedWithAir() {
        CompoundTag root = v2Root();
        root.put("BlockData", varints(0, 0, 0));
        assertThrows(SchematicFormatException.class, () -> SpongeSchematicReader.read(root, "short"));
    }

    @Test
    void anOutOfRangePaletteIndexIsRefused() {
        CompoundTag root = v2Root();
        int[] indices = asymmetricIndices();
        indices[0] = 99;
        root.put("BlockData", varints(indices));
        assertThrows(SchematicFormatException.class, () -> SpongeSchematicReader.read(root, "bad-index"));
    }

    @Test
    void aPaletteWithAGapIsRefused() {
        CompoundTag palette = new CompoundTag();
        palette.put("minecraft:air", new IntTag(0));
        palette.put("minecraft:stone", new IntTag(2)); // index 1 missing
        CompoundTag root = v2Root();
        root.put("Palette", palette);
        assertThrows(SchematicFormatException.class, () -> SpongeSchematicReader.read(root, "gap"));
    }

    @Test
    void anImplausibleVolumeIsRefusedBeforeAllocating() {
        CompoundTag root = v2Root();
        root.put("Width", (short) 0xFFFF);
        root.put("Height", (short) 0xFFFF);
        root.put("Length", (short) 0xFFFF);
        SchematicFormatException error =
                assertThrows(SchematicFormatException.class, () -> SpongeSchematicReader.read(root, "huge"));
        assertTrue(error.getMessage().contains("Refusing"));
    }

    @Test
    void dimensionsAboveThirtyTwoThousandAreReadAsUnsigned() {
        CompoundTag root = v2Root();
        root.put("Width", (short) 40000);
        root.put("Height", (short) 1);
        root.put("Length", (short) 1);
        root.put("BlockData", new byte[40000]);
        // 40000 blocks is below the volume limit. Supply matching data and verify
        // the decoded dimension, rather than expecting an unrelated truncation error.
        SpongeSchematic schematic = SpongeSchematicReader.read(root, "wide");
        assertEquals(40000, schematic.sizeX());
    }

    @Test
    void aBlockEntityOutsideTheBoundsIsRefused() {
        CompoundTag entry = new CompoundTag();
        entry.put("Id", "minecraft:chest");
        entry.put("Pos", new int[]{99, 0, 0});

        List<CompoundTag> entries = new ArrayList<>();
        entries.add(entry);

        CompoundTag root = v2Root();
        root.put("BlockEntities", new ListTag<>(TagType.COMPOUND, entries));
        assertThrows(SchematicFormatException.class, () -> SpongeSchematicReader.read(root, "stray-be"));
    }

    @Test
    void aFileWithNoVersionTagIsRejectedWithAUsefulMessage() {
        CompoundTag root = new CompoundTag();
        root.put("Width", (short) 1);
        SchematicFormatException error =
                assertThrows(SchematicFormatException.class, () -> SpongeSchematicReader.read(root, "not-sponge"));
        assertTrue(error.getMessage().contains("not a Sponge schematic"));
    }

    @Test
    void gzipRoundTripThroughChunkerNbt() throws Exception {
        byte[] encoded = Tag.writeGZipJavaNBT(v3Root(true));
        CompoundTag decoded = Tag.readGZipJavaNBT(encoded);
        SpongeSchematic schematic = SpongeSchematicReader.read(decoded, "round-trip");
        assertEquals(3, schematic.spongeVersion());
        assertEquals(-225, schematic.sourceMinimum()[0]);
    }

    @Test
    void anUnqualifiedPaletteKeyGetsTheMinecraftNamespace() {
        CompoundTag state = SpongeSchematicReader.parseBlockState("stone", "x");
        assertEquals("minecraft:stone", state.getString("Name", "?"));
        assertEquals("minecraft:stone", ((StringTag) state.get("Name")).getValue());
    }

    @Test
    void aMalformedPaletteKeyIsRefused() {
        assertThrows(SchematicFormatException.class,
                () -> SpongeSchematicReader.parseBlockState("minecraft:stone[facing", "x"));
        assertThrows(SchematicFormatException.class,
                () -> SpongeSchematicReader.parseBlockState("minecraft:stone[facing]", "x"));
    }
}
