package gg.swim.chunkdaddy.worker.document;

import com.hivemc.chunker.conversion.encoding.base.resolver.blockentity.BlockEntityResolver;
import com.hivemc.chunker.conversion.encoding.java.base.resolver.JavaResolvers;
import com.hivemc.chunker.conversion.intermediate.column.ChunkerColumn;
import com.hivemc.chunker.conversion.intermediate.column.biome.ChunkerBiome;
import com.hivemc.chunker.conversion.intermediate.column.blockentity.BlockEntity;
import com.hivemc.chunker.conversion.intermediate.column.chunk.ChunkCoordPair;
import com.hivemc.chunker.conversion.intermediate.column.chunk.ChunkerChunk;
import com.hivemc.chunker.conversion.intermediate.column.chunk.identifier.ChunkerBlockIdentifier;
import com.hivemc.chunker.conversion.intermediate.column.chunk.palette.ShortBasedPalette;
import com.hivemc.chunker.conversion.intermediate.column.chunk.palette.WriteablePalette;
import com.hivemc.chunker.nbt.tags.collection.CompoundTag;
import gg.swim.chunkdaddy.worker.conversion.ArenaTemplate;
import gg.swim.chunkdaddy.worker.conversion.ResolverFactory;
import gg.swim.chunkdaddy.worker.conversion.TemplateSections;
import gg.swim.chunkdaddy.worker.schematic.SpongeSchematic;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Optional;

/**
 * Builds the final column for one chunk position by combining materialized content with
 * the arena instances that overlap it.
 *
 * <p>Instance placement uses exact region replacement: everything inside a template's box,
 * including its air, is written over the destination. That is what makes every copy of a
 * template identical and clears stale content from the box. Generated void is only ever
 * written into columns that have no content at all; it is never a final blanket pass over
 * placed arenas.
 */
public final class ColumnComposer {
    private final TemplateRegistry templates;
    private final ResolverFactory resolverFactory;
    private final int minChunkY;
    private final int maxChunkY;
    private final ChunkerBiome fallbackBiome;
    private final @Nullable BlockEntityResolver<?, CompoundTag> materializedBlockEntityResolver;

    public ColumnComposer(TemplateRegistry templates,
                          ResolverFactory resolverFactory,
                          int minChunkY,
                          int maxChunkY,
                          ChunkerBiome fallbackBiome,
                          @Nullable BlockEntityResolver<?, CompoundTag> materializedBlockEntityResolver) {
        this.templates = templates;
        this.resolverFactory = resolverFactory;
        this.minChunkY = minChunkY;
        this.maxChunkY = maxChunkY;
        this.fallbackBiome = fallbackBiome;
        this.materializedBlockEntityResolver = materializedBlockEntityResolver;
    }

    /**
     * Whether composing this column would produce content, or whether the column is
     * absent and should be void filled instead.
     */
    public boolean hasContent(WorldSnapshot snapshot, int chunkX, int chunkZ) {
        return snapshot.hasContent(chunkX, chunkZ);
    }

    /** True when the column's content comes from a template instance. */
    public boolean containsTemplateContent(WorldSnapshot snapshot, int chunkX, int chunkZ) {
        return !snapshot.instancesAt(chunkX, chunkZ).isEmpty();
    }

    /**
     * Compose the column. The returned column is freshly built and owned by the caller:
     * the Bedrock writer compacts palettes and rewrites block entity lists in place, so a
     * shared column would be corrupted by the first write.
     */
    public ChunkerColumn compose(WorldSnapshot snapshot, int chunkX, int chunkZ) {
        ChunkCoordPair position = new ChunkCoordPair(chunkX, chunkZ);
        ChunkerColumn base = snapshot.materializedColumn(chunkX, chunkZ);
        ChunkerColumn column = base == null
                ? new ChunkerColumn(position)
                : ColumnOps.relocate(base, 0, 0, 0, true, materializedBlockEntityResolver);

        for (ArenaInstance instance : snapshot.instancesAt(chunkX, chunkZ)) {
            applyInstance(column, instance, chunkX, chunkZ);
        }

        if (column.getBiomes() == null) {
            column.setBiomes(uniformBiomes(fallbackBiome));
        }
        // Sub-chunks that hold nothing are left out rather than padded in: the writer
        // would drop them again, and allocating them for every column of a large export
        // is millions of throwaway objects.
        column.setHeightMap(null);
        return column;
    }

    /** A column that exists but holds nothing: explicit generated void. */
    public ChunkerColumn composeVoid(int chunkX, int chunkZ) {
        return ColumnOps.voidColumn(new ChunkCoordPair(chunkX, chunkZ), fallbackBiome);
    }

    // ------------------------------------------------------------------

    private void applyInstance(ChunkerColumn column, ArenaInstance instance, int chunkX, int chunkZ) {
        ArenaTemplate template = templates.require(instance.templateId());
        TemplateSections sections = templates.sections(instance.templateId());

        int columnMinX = chunkX << 4;
        int columnMinZ = chunkZ << 4;

        // Intersection of the instance box with this column, in world coordinates.
        int x0 = Math.max(instance.minX(), columnMinX);
        int x1 = Math.min(instance.maxXExclusive(), columnMinX + 16);
        int z0 = Math.max(instance.minZ(), columnMinZ);
        int z1 = Math.min(instance.maxZExclusive(), columnMinZ + 16);
        if (x0 >= x1 || z0 >= z1) return;

        boolean yAligned = (instance.minY() & 15) == 0;
        // Chunk-aligned placement is enforced when the grid is planned; a manual paste
        // could in principle be off-grid, in which case we take the general path.
        boolean xzAligned = (instance.minX() & 15) == 0 && (instance.minZ() & 15) == 0;

        int worldMinChunkY = instance.minY() >> 4;
        int worldMaxChunkY = (instance.maxYExclusive() - 1) >> 4;

        for (int worldChunkY = worldMinChunkY; worldChunkY <= worldMaxChunkY; worldChunkY++) {
            if (worldChunkY < minChunkY || worldChunkY > maxChunkY) {
                throw new IllegalStateException(
                        "Arena " + instance.exportId() + " extends to sub-chunk Y " + worldChunkY
                                + ", outside the target profile's range " + minChunkY + ".." + maxChunkY
                                + ". Placement must not be silently clipped.");
            }
            byte sectionY = (byte) worldChunkY;

            int sectionMinY = worldChunkY << 4;
            int y0 = Math.max(instance.minY(), sectionMinY);
            int y1 = Math.min(instance.maxYExclusive(), sectionMinY + 16);

            boolean fullyCovered = xzAligned && yAligned
                    && x0 == columnMinX && x1 == columnMinX + 16
                    && z0 == columnMinZ && z1 == columnMinZ + 16
                    && y0 == sectionMinY && y1 == sectionMinY + 16;

            if (fullyCovered) {
                int localChunkX = (columnMinX - instance.minX()) >> 4;
                int localChunkZ = (columnMinZ - instance.minZ()) >> 4;
                int localSectionY = (sectionMinY - instance.minY()) >> 4;
                ShortBasedPalette<ChunkerBlockIdentifier> prepared =
                        sections.copyOf(localChunkX, localChunkZ, localSectionY);

                ChunkerChunk chunk = new ChunkerChunk(sectionY);
                if (prepared != null) {
                    chunk.setPalette(prepared);
                }
                // A null prepared section means the template is entirely air here. Because
                // placement is exact replacement, that still overwrites the destination:
                // the freshly constructed all-air chunk replaces whatever was there.
                column.getChunks().put(sectionY, chunk);
            } else {
                WriteablePalette<ChunkerBlockIdentifier> palette = ColumnOps.writeableChunk(column, sectionY);
                for (int worldX = x0; worldX < x1; worldX++) {
                    for (int worldY = y0; worldY < y1; worldY++) {
                        for (int worldZ = z0; worldZ < z1; worldZ++) {
                            ChunkerBlockIdentifier block = template.blockAt(
                                    worldX - instance.minX(),
                                    worldY - instance.minY(),
                                    worldZ - instance.minZ());
                            palette.set(worldX & 15, worldY & 15, worldZ & 15, block);
                        }
                    }
                }
            }
        }

        // Destination block entities inside the replaced box are stale by definition.
        column.getBlockEntities().removeIf(existing -> instance.containsBlock(existing.getX(), existing.getY(), existing.getZ()));

        addInstanceBlockEntities(column, instance, template, x0, x1, z0, z1);
    }

    /**
     * Re-resolve the template's block entities for this instance.
     *
     * <p>Each copy gets its own object graph, resolved from the template's original NBT
     * with world coordinates applied. Sharing resolved block entities between copies would
     * mean an edit or a later coordinate rewrite in one arena silently affecting another.
     */
    private void addInstanceBlockEntities(ChunkerColumn column,
                                          ArenaInstance instance,
                                          ArenaTemplate template,
                                          int x0, int x1, int z0, int z1) {
        if (template.blockEntities().isEmpty()) return;

        // Look up only the records that land in this column. Scanning the whole template
        // once per column is quadratic in disguise: the mine arena alone has 1,693 block
        // entities and covers 208 columns, so thirty copies of the production set came to
        // tens of millions of bounds checks that decided nothing.
        final List<SpongeSchematic.BlockEntityRecord> records;
        if ((instance.minX() & 15) == 0 && (instance.minZ() & 15) == 0) {
            records = template.blockEntitiesInLocalChunk((x0 - instance.minX()) >> 4,
                                                        (z0 - instance.minZ()) >> 4);
        } else {
            // An off-grid paste does not line up with the template's local chunk grid, so
            // fall back to the full scan rather than index into the wrong bucket.
            records = template.blockEntities();
        }
        if (records.isEmpty()) return;

        JavaResolvers resolvers = template.resolvers(resolverFactory);
        for (SpongeSchematic.BlockEntityRecord record : records) {
            int worldX = instance.minX() + record.localX();
            int worldZ = instance.minZ() + record.localZ();
            if (worldX < x0 || worldX >= x1 || worldZ < z0 || worldZ >= z1) continue;
            int worldY = instance.minY() + record.localY();

            CompoundTag nbt = record.javaNbt().clone();
            nbt.put("x", worldX);
            nbt.put("y", worldY);
            nbt.put("z", worldZ);

            Optional<BlockEntity> resolved = resolvers.blockEntityResolver().to(nbt);
            if (resolved.isEmpty()) {
                // Import auditing already refuses templates whose block entities do not
                // map, so reaching here means the profile changed under us.
                throw new IllegalStateException(
                        "Block entity " + record.id() + " in template " + template.slug()
                                + " no longer resolves for the selected profile.");
            }
            BlockEntity blockEntity = resolved.get();
            blockEntity.setX(worldX);
            blockEntity.setY(worldY);
            blockEntity.setZ(worldZ);
            column.getBlockEntities().add(blockEntity);
        }
    }

    private static com.hivemc.chunker.conversion.intermediate.column.biome.layout.ChunkerColumnBasedBiomes
    uniformBiomes(ChunkerBiome biome) {
        ChunkerBiome[] biomes = new ChunkerBiome[256];
        java.util.Arrays.fill(biomes, biome);
        return new com.hivemc.chunker.conversion.intermediate.column.biome.layout.ChunkerColumnBasedBiomes(biomes);
    }

    public int minChunkY() {
        return minChunkY;
    }

    public int maxChunkY() {
        return maxChunkY;
    }
}

