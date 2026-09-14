package gg.swim.chunkdaddy.worker.schematic;

import com.hivemc.chunker.nbt.tags.collection.CompoundTag;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * A parsed Sponge schematic, normalized into one coordinate frame.
 *
 * <p>Vocabulary follows the design guide:
 * <ul>
 *   <li>{@code L} - position local to the schematic minimum corner, the frame used by
 *       {@link #paletteIndices()} and by {@link BlockEntityRecord#localX()} and friends.</li>
 *   <li>{@code O} - the stored WorldEdit copy origin, when the file carries one.</li>
 *   <li>{@code F} - the schematic offset: minimum corner relative to the paste anchor.</li>
 *   <li>{@code S} - the reconstructed original minimum corner, {@code O + F}, only
 *       available when an origin was actually stored under a convention we recognize.</li>
 * </ul>
 *
 * <p>{@code sourceMinimum} is deliberately nullable. The Axiom-produced v2 files in the
 * production set carry no WorldEdit origin, so their original world position cannot be
 * reconstructed and must not be guessed.
 */
public record SpongeSchematic(
        int spongeVersion,
        int javaDataVersion,
        int sizeX,
        int sizeY,
        int sizeZ,
        /** F: minimum corner relative to the paste anchor. Absent in the file means (0,0,0). */
        int[] offset,
        /** O: stored WorldEdit origin, or null when the file does not carry one. */
        @Nullable int[] worldEditOrigin,
        /** S = O + F, or null when no origin was stored. */
        @Nullable int[] sourceMinimum,
        /** Palette entries as Java block state compounds: {Name:"minecraft:x", Properties:{..}}. */
        List<CompoundTag> palette,
        /** One palette index per block position, indexed by {@code x + z*sizeX + y*sizeX*sizeZ}. */
        int[] paletteIndices,
        List<BlockEntityRecord> blockEntities,
        /** Original metadata retained verbatim as provenance. */
        @Nullable CompoundTag metadata
) {
    /**
     * A block entity in template-local coordinates, already flattened into the
     * Java-world NBT shape that Chunker's Java resolvers expect ({@code id}, {@code x},
     * {@code y}, {@code z} plus type-specific fields).
     */
    public record BlockEntityRecord(int localX, int localY, int localZ, String id, CompoundTag javaNbt) {
    }

    /** Index into {@link #paletteIndices()} for a local position. */
    public int indexOf(int localX, int localY, int localZ) {
        return localX + localZ * sizeX + localY * sizeX * sizeZ;
    }

    /** Footprint in chunk columns, assuming the minimum corner is chunk aligned. */
    public int footprintChunksX() {
        return (sizeX + 15) / 16;
    }

    public int footprintChunksZ() {
        return (sizeZ + 15) / 16;
    }
}
