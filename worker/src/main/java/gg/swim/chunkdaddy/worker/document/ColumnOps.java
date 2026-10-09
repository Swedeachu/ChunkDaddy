package gg.swim.chunkdaddy.worker.document;

import com.hivemc.chunker.conversion.encoding.base.resolver.blockentity.BlockEntityResolver;
import com.hivemc.chunker.conversion.intermediate.column.ChunkerColumn;
import com.hivemc.chunker.conversion.intermediate.column.biome.ChunkerBiome;
import com.hivemc.chunker.conversion.intermediate.column.biome.layout.ChunkerColumnBasedBiomes;
import com.hivemc.chunker.conversion.intermediate.column.blockentity.BlockEntity;
import com.hivemc.chunker.conversion.intermediate.column.chunk.ChunkCoordPair;
import com.hivemc.chunker.conversion.intermediate.column.chunk.ChunkerChunk;
import com.hivemc.chunker.conversion.intermediate.column.chunk.identifier.ChunkerBlockIdentifier;
import com.hivemc.chunker.conversion.intermediate.column.chunk.palette.Palette;
import com.hivemc.chunker.conversion.intermediate.column.chunk.palette.WriteablePalette;
import com.hivemc.chunker.conversion.intermediate.column.entity.Entity;
import com.hivemc.chunker.nbt.tags.collection.CompoundTag;
import org.jetbrains.annotations.Nullable;

import java.util.Arrays;
import java.util.Optional;

/**
 * Column-level operations that keep block data and the records that reference positions
 * consistent.
 *
 * <p>Moving chunks is never a matter of editing database key bytes. Every absolute
 * coordinate carried by a block entity or entity moves with the blocks, and derived data
 * such as the height map is invalidated rather than carried over stale.
 */
public final class ColumnOps {
    private ColumnOps() {
    }

    /**
     * An explicitly generated, entirely empty column.
     *
     * <p>This is not the same thing as an absent column. The column record is always
     * emitted so the server finds a generated void rather than an ungenerated hole.
     */
    public static ChunkerColumn voidColumn(ChunkCoordPair position, ChunkerBiome biome) {
        ChunkerColumn column = new ChunkerColumn(position);
        // Deliberately no sub-chunks. The writer emits this column's version and Data3D
        // records regardless, and those are what make it a generated empty column; an
        // all-air record for every Y would decode back to exactly this, at the cost of one
        // database entry each. On a large grid that is millions of entries.
        ChunkerBiome[] biomes = new ChunkerBiome[256];
        Arrays.fill(biomes, biome);
        column.setBiomes(new ChunkerColumnBasedBiomes(biomes));
        column.setLightPopulated(false);
        return column;
    }

    /**
     * Produce the column at a new position, translated by a whole number of chunks
     * horizontally and a multiple of sixteen blocks vertically.
     *
     * <p>A vertical shift that is not a multiple of sixteen would repartition sub-chunks,
     * which is a different and more expensive operation than a relocation; it is refused
     * here rather than silently producing misaligned output.
     *
     * @param resolver used to deep copy block entities through their NBT form. Required
     *                 when {@code deepCopy} is set and the column has block entities.
     */
    public static ChunkerColumn relocate(ChunkerColumn source,
                                         int dChunkX,
                                         int dChunkZ,
                                         int dy,
                                         boolean deepCopy,
                                         @Nullable BlockEntityResolver<?, CompoundTag> resolver) {
        return relocate(source, dChunkX, dChunkZ, dy, deepCopy, resolver, deepCopy);
    }

    /** Share read-only palettes while detaching mutable position records. Export clones palettes. */
    public static ChunkerColumn relocateShared(ChunkerColumn source, int dx, int dz,
                                               BlockEntityResolver<?, CompoundTag> resolver) {
        return relocate(source, dx, dz, 0, true, resolver, false);
    }

    private static ChunkerColumn relocate(ChunkerColumn source, int dChunkX, int dChunkZ, int dy,
                                          boolean deepCopy, @Nullable BlockEntityResolver<?, CompoundTag> resolver,
                                          boolean copyPalettes) {
        if ((dy & 15) != 0) {
            throw new IllegalArgumentException(
                    "Vertical column moves must be a multiple of 16 blocks; " + dy + " would repartition sub-chunks.");
        }
        ChunkCoordPair position = new ChunkCoordPair(
                source.getPosition().chunkX() + dChunkX,
                source.getPosition().chunkZ() + dChunkZ);
        ChunkerColumn out = new ChunkerColumn(position);

        int dx = dChunkX << 4;
        int dz = dChunkZ << 4;
        int dChunkY = dy >> 4;

        for (ChunkerChunk sourceChunk : source.getChunks().values()) {
            byte newY = (byte) (sourceChunk.getY() + dChunkY);
            ChunkerChunk chunk = new ChunkerChunk(newY);
            chunk.setPalette(copyPalettes ? sourceChunk.getPalette().copy() : sourceChunk.getPalette());
            // Lighting is position dependent; carrying it over would carry a stale value.
            out.getChunks().put(newY, chunk);
        }

        for (BlockEntity blockEntity : source.getBlockEntities()) {
            BlockEntity moved = deepCopy ? deepCopy(blockEntity, source, resolver) : blockEntity;
            moved.setX(moved.getX() + dx);
            moved.setY(moved.getY() + dy);
            moved.setZ(moved.getZ() + dz);
            // Bedrock-only records such as item frames bypass normal serialization
            // and are written from original NBT. Keep that detached payload in step.
            if (moved.getOriginalNBT() != null) {
                CompoundTag original = moved.getOriginalNBT().clone();
                original.put("x", moved.getX()); original.put("y", moved.getY()); original.put("z", moved.getZ());
                if (original.contains("pairx")) original.put("pairx", Math.addExact(original.getInt("pairx"), dx));
                if (original.contains("pairz")) original.put("pairz", Math.addExact(original.getInt("pairz"), dz));
                moved.setOriginalNBT(original);
            }
            if (moved instanceof com.hivemc.chunker.conversion.encoding.bedrock.base.resolver.blockentity.type.BedrockChestBlockEntity chest) {
                if (chest.getPairX() != null) chest.setPairX(Math.addExact(chest.getPairX(), dx));
                if (chest.getPairZ() != null) chest.setPairZ(Math.addExact(chest.getPairZ(), dz));
            }
            out.getBlockEntities().add(moved);
        }

        for (Entity entity : source.getEntities()) {
            // Bedrock frames enter the intermediate model as entities even when ordinary
            // entity import is disabled. Their hanging anchors must move independently.
            if (entity instanceof com.hivemc.chunker.conversion.intermediate.column.entity.ItemFrameEntity frame) {
                var payload = (com.hivemc.chunker.conversion.encoding.bedrock.base.resolver.blockentity.type.BedrockItemFrameBlockEntity)
                        deepCopy(frame.toBedrockBlockEntity(), source, resolver);
                var copy = frame instanceof com.hivemc.chunker.conversion.intermediate.column.entity.GlowItemFrameEntity
                        ? new com.hivemc.chunker.conversion.intermediate.column.entity.GlowItemFrameEntity()
                        : new com.hivemc.chunker.conversion.intermediate.column.entity.ItemFrameEntity();
                copy.setTileX(Math.addExact(frame.getTileX(), dx));
                copy.setTileY(Math.addExact(frame.getTileY(), dy));
                copy.setTileZ(Math.addExact(frame.getTileZ(), dz));
                copy.setPositionX(frame.getPositionX() + dx); copy.setPositionY(frame.getPositionY() + dy); copy.setPositionZ(frame.getPositionZ() + dz);
                copy.setItem(payload.getItem()); copy.setItemRotation(frame.getItemRotation());
                copy.setDirection(frame.getDirection()); copy.setMap(frame.isMap()); copy.setPhoto(frame.isPhoto());
                out.getEntities().add(copy);
                continue;
            }
            if (deepCopy) throw new IllegalStateException("Copying ordinary entity identities is unsupported: " + entity.getClass().getSimpleName());
            entity.setPositionX(entity.getPositionX() + dx);
            entity.setPositionY(entity.getPositionY() + dy);
            entity.setPositionZ(entity.getPositionZ() + dz);
            out.getEntities().add(entity);
        }

        // The Bedrock writer pads biome lists in place for older worlds. Detach both
        // list and palettes for a writable copy, while read-only composition shares them.
        if (source.getBiomes() != null && copyPalettes) {
            var biomes = new java.util.ArrayList<Palette<ChunkerBiome>>();
            for (var palette : source.getBiomes().asPalette()) biomes.add(palette.copy());
            out.setBiomes(new com.hivemc.chunker.conversion.intermediate.column.biome.layout.ChunkerPaletteBasedBiomes(biomes));
        } else out.setBiomes(source.getBiomes());
        out.setHeightMap(null);
        out.setLightPopulated(false);
        return out;
    }

    /**
     * Deep copy a block entity by round-tripping it through NBT.
     *
     * <p>Chunker's block entity objects are mutable and some carry payloads that must not
     * be shared between two arenas; a shallow share would let an edit to one appear in the
     * other, and would make a later coordinate rewrite corrupt both.
     */
    public static BlockEntity deepCopy(BlockEntity source, ChunkerColumn column,
                                       @Nullable BlockEntityResolver<?, CompoundTag> resolver) {
        if (resolver == null) {
            throw new IllegalStateException("A block entity resolver is required to copy block entities");
        }
        // Trapped chests resolve through Chest's handler, which requires its Bedrock
        // wrapper. Prepare that new wrapper before serialization instead of invoking
        // the handler with an intermediate subclass.
        BlockEntity serializable = source;
        if (source instanceof com.hivemc.chunker.conversion.intermediate.column.blockentity.container.randomizable.ChestBlockEntity
                && !(source instanceof com.hivemc.chunker.conversion.encoding.bedrock.base.resolver.blockentity.type.BedrockChestBlockEntity)) {
            serializable = resolver.updateBeforeWrite(column, source.getX(), source.getY(), source.getZ(), source);
        }
        Optional<CompoundTag> nbt = resolver.from(serializable);
        if (nbt.isEmpty()) {
            // Imported columns contain intermediate types. Bedrock encodes some of
            // them (chests, brushable blocks, shulkers) through a format-specific
            // wrapper which also needs the block's state. These handlers return new
            // wrappers; do not run all write hooks on the shared source, since hooks
            // for directly serializable types such as banners mutate their input.
            BlockEntity writable = resolver.updateBeforeWrite(column,
                    source.getX(), source.getY(), source.getZ(), source);
            nbt = resolver.from(writable);
        }
        if (nbt.isEmpty()) {
            throw new IllegalStateException(
                    "Cannot copy block entity at " + source.getX() + "," + source.getY() + "," + source.getZ()
                            + ": the selected profile cannot serialize " + source.getClass().getSimpleName()
                            + ". Copying it would silently drop its data.");
        }
        Optional<BlockEntity> copy = resolver.to(nbt.get().clone());
        if (copy.isEmpty()) {
            throw new IllegalStateException(
                    "Block entity at " + source.getX() + "," + source.getY() + "," + source.getZ()
                            + " did not survive an NBT round trip; refusing to copy it.");
        }
        BlockEntity result = copy.get();
        result.setX(source.getX());
        result.setY(source.getY());
        result.setZ(source.getZ());
        return result;
    }

    /** Get, creating if needed, a writable palette for a sub-chunk of a column. */
    public static WriteablePalette<ChunkerBlockIdentifier> writeableChunk(ChunkerColumn column, byte chunkY) {
        ChunkerChunk chunk = column.getChunks().get(chunkY);
        if (chunk == null) {
            chunk = new ChunkerChunk(chunkY);
            column.getChunks().put(chunkY, chunk);
        }
        Palette<ChunkerBlockIdentifier> palette = chunk.getPalette();
        if (palette instanceof WriteablePalette<ChunkerBlockIdentifier> writeable) {
            return writeable;
        }
        WriteablePalette<ChunkerBlockIdentifier> writeable = palette.asWriteable();
        chunk.setPalette(writeable);
        return writeable;
    }
}
