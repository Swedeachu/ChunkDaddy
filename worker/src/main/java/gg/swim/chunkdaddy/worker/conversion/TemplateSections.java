package gg.swim.chunkdaddy.worker.conversion;

import com.hivemc.chunker.conversion.intermediate.column.chunk.identifier.ChunkerBlockIdentifier;
import com.hivemc.chunker.conversion.intermediate.column.chunk.palette.ShortBasedPalette;
import gg.swim.chunkdaddy.worker.util.Checked;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A template pre-sliced into chunk-aligned 16x16x16 sections, built once and reused by
 * every instance.
 *
 * <p>This is the difference between an affordable grid and an unusable one. Composing
 * four hundred and fifty arenas block by block would be roughly 2.6 billion writes;
 * composing them by copying prepared section palettes is a few hundred thousand array
 * copies. Sections that are entirely air are not stored at all, which is most of a duel
 * arena's bounding box.
 *
 * <p>The prepared palettes are never handed out directly. Chunker's
 * {@code ShortBasedPalette.compact} mutates in place, and the Bedrock writer compacts
 * every palette it is given, so each instance receives {@link ShortBasedPalette#copy()}.
 */
public final class TemplateSections {
    /** Local section key: local chunk X, local chunk Z, local section Y. */
    private final Map<Long, ShortBasedPalette<ChunkerBlockIdentifier>> sections = new HashMap<>();
    private final int localChunksX;
    private final int localChunksZ;
    private final int localSectionsY;
    private long storedSections;

    private TemplateSections(int localChunksX, int localChunksZ, int localSectionsY) {
        this.localChunksX = localChunksX;
        this.localChunksZ = localChunksZ;
        this.localSectionsY = localSectionsY;
    }

    public static TemplateSections build(ArenaTemplate template) {
        int chunksX = Checked.ceilDiv(template.sizeX(), 16);
        int chunksZ = Checked.ceilDiv(template.sizeZ(), 16);
        int sectionsY = Checked.ceilDiv(template.sizeY(), 16);
        TemplateSections result = new TemplateSections(chunksX, chunksZ, sectionsY);

        ChunkerBlockIdentifier[] palette = template.resolvedPalette();
        int[] indices = template.schematic().paletteIndices();
        int sizeX = template.sizeX();
        int sizeY = template.sizeY();
        int sizeZ = template.sizeZ();

        for (int lcx = 0; lcx < chunksX; lcx++) {
            for (int lcz = 0; lcz < chunksZ; lcz++) {
                for (int lsy = 0; lsy < sectionsY; lsy++) {
                    short[][][] values = null;
                    List<ChunkerBlockIdentifier> keys = null;
                    Map<ChunkerBlockIdentifier, Short> keyIndex = null;

                    for (int x = 0; x < 16; x++) {
                        int localX = (lcx << 4) + x;
                        if (localX >= sizeX) break;
                        for (int y = 0; y < 16; y++) {
                            int localY = (lsy << 4) + y;
                            if (localY >= sizeY) break;
                            for (int z = 0; z < 16; z++) {
                                int localZ = (lcz << 4) + z;
                                if (localZ >= sizeZ) break;

                                int flat = localX + localZ * sizeX + localY * sizeX * sizeZ;
                                ChunkerBlockIdentifier block = palette[indices[flat]];
                                if (block.isAir()) continue;

                                if (values == null) {
                                    values = new short[16][16][16];
                                    keys = new ArrayList<>(8);
                                    keyIndex = new HashMap<>(8);
                                    // Index 0 is always air so that untouched cells of the
                                    // section read back as air rather than as the first
                                    // non-air block encountered.
                                    keys.add(ChunkerBlockIdentifier.AIR);
                                    keyIndex.put(ChunkerBlockIdentifier.AIR, (short) 0);
                                }
                                Short existing = keyIndex.get(block);
                                short index;
                                if (existing == null) {
                                    index = (short) keys.size();
                                    keys.add(block);
                                    keyIndex.put(block, index);
                                } else {
                                    index = existing;
                                }
                                values[x][y][z] = index;
                            }
                        }
                    }

                    if (values != null) {
                        result.sections.put(key(lcx, lcz, lsy), new ShortBasedPalette<>(keys, values));
                        result.storedSections++;
                    }
                }
            }
        }
        return result;
    }

    private static long key(int lcx, int lcz, int lsy) {
        return ((long) (lcx & 0xFFFF) << 32) | ((long) (lcz & 0xFFFF) << 16) | (lsy & 0xFFFF);
    }

    /**
     * A private copy of one prepared section, or null when that section is entirely air
     * and therefore not stored.
     */
    public @Nullable ShortBasedPalette<ChunkerBlockIdentifier> copyOf(int localChunkX, int localChunkZ, int localSectionY) {
        ShortBasedPalette<ChunkerBlockIdentifier> prepared = sections.get(key(localChunkX, localChunkZ, localSectionY));
        return prepared == null ? null : prepared.copy();
    }

    /** True when a section exists at all, without paying for a copy. */
    public boolean hasSection(int localChunkX, int localChunkZ, int localSectionY) {
        return sections.containsKey(key(localChunkX, localChunkZ, localSectionY));
    }

    public int localChunksX() {
        return localChunksX;
    }

    public int localChunksZ() {
        return localChunksZ;
    }

    public int localSectionsY() {
        return localSectionsY;
    }

    /** Number of non-air sections kept in memory, for the performance report. */
    public long storedSections() {
        return storedSections;
    }
}
