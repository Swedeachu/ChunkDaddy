package gg.swim.chunkdaddy.worker.conversion;

import com.hivemc.chunker.conversion.encoding.java.base.resolver.JavaResolvers;
import com.hivemc.chunker.conversion.intermediate.column.chunk.identifier.ChunkerBlockIdentifier;
import com.hivemc.chunker.nbt.tags.collection.CompoundTag;
import gg.swim.chunkdaddy.worker.schematic.SpongeSchematic;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * A schematic that has been parsed and had its palette audited against the target
 * profile, ready to be instantiated many times.
 *
 * <p>Deliberately stored in Java space: the palette indices and block entity NBT are kept
 * as read, and the resolved Chunker identifiers are computed once into
 * {@link #resolvedPalette}. Instancing is then a translation over shared immutable data,
 * which is what makes 450 copies affordable. The design guide's arithmetic stands: thirty
 * copies of the fifteen production templates is roughly 2.59 billion logical block
 * positions, so nothing here may materialize a copy per instance.
 */
public final class ArenaTemplate {
    private final UUID id = UUID.randomUUID();
    private final String slug;
    private final String sourceFileName;
    private final String sourceSha256;
    private final SpongeSchematic schematic;
    private final ChunkerBlockIdentifier[] resolvedPalette;
    private final List<MappingIssue> issues;
    private final boolean aggregateCandidate;

    /** Local player-feet spawn positions, or null until the user authors them. */
    private @Nullable double[] spawnPoint1;
    private @Nullable double[] spawnPoint2;
    private boolean spawnsConfirmed;
    /** Hash the markers were confirmed against; a new source hash invalidates them. */
    private @Nullable String spawnsConfirmedForSha256;

    ArenaTemplate(String slug,
                  String sourceFileName,
                  String sourceSha256,
                  SpongeSchematic schematic,
                  ChunkerBlockIdentifier[] resolvedPalette,
                  List<MappingIssue> issues,
                  boolean aggregateCandidate) {
        this.slug = slug;
        this.sourceFileName = sourceFileName;
        this.sourceSha256 = sourceSha256;
        this.schematic = schematic;
        this.resolvedPalette = resolvedPalette;
        this.issues = List.copyOf(issues);
        this.aggregateCandidate = aggregateCandidate;
    }

    public UUID id() {
        return id;
    }

    public String slug() {
        return slug;
    }

    public String sourceFileName() {
        return sourceFileName;
    }

    public String sourceSha256() {
        return sourceSha256;
    }

    public SpongeSchematic schematic() {
        return schematic;
    }

    public List<MappingIssue> issues() {
        return issues;
    }

    /**
     * True when this file looks like a collection of several arenas rather than one.
     * The user must include it deliberately; it is never imported as an extra duel map.
     */
    public boolean aggregateCandidate() {
        return aggregateCandidate;
    }

    public int sizeX() {
        return schematic.sizeX();
    }

    public int sizeY() {
        return schematic.sizeY();
    }

    public int sizeZ() {
        return schematic.sizeZ();
    }

    public int footprintChunksX() {
        return schematic.footprintChunksX();
    }

    public int footprintChunksZ() {
        return schematic.footprintChunksZ();
    }

    /** Resolved block for a local position, or air outside the schematic bounds. */
    public ChunkerBlockIdentifier blockAt(int localX, int localY, int localZ) {
        if (localX < 0 || localX >= sizeX() || localY < 0 || localY >= sizeY() || localZ < 0 || localZ >= sizeZ()) {
            return ChunkerBlockIdentifier.AIR;
        }
        return resolvedPalette[schematic.paletteIndices()[schematic.indexOf(localX, localY, localZ)]];
    }

    public ChunkerBlockIdentifier[] resolvedPalette() {
        return resolvedPalette;
    }

    public List<SpongeSchematic.BlockEntityRecord> blockEntities() {
        return schematic.blockEntities();
    }

    /** Java resolvers appropriate for this template's declared DataVersion. */
    public JavaResolvers resolvers(ResolverFactory factory) {
        return factory.forDataVersion(schematic.javaDataVersion());
    }

    public @Nullable double[] spawnPoint1() {
        return spawnPoint1;
    }

    public @Nullable double[] spawnPoint2() {
        return spawnPoint2;
    }

    public boolean spawnsConfirmed() {
        return spawnsConfirmed && sourceSha256.equals(spawnsConfirmedForSha256);
    }

    /**
     * Set the two local spawn markers.
     *
     * <p>Positions are player feet in schematic-local coordinates, not eye height and not
     * the supporting block. Confirmation is bound to the source hash: markers carried over
     * from a file with the same name but different content stay visible as a proposal and
     * lose their confirmed state.
     */
    public void setSpawns(@Nullable double[] one, @Nullable double[] two, boolean confirmed) {
        this.spawnPoint1 = one == null ? null : one.clone();
        this.spawnPoint2 = two == null ? null : two.clone();
        this.spawnsConfirmed = confirmed && one != null && two != null;
        this.spawnsConfirmedForSha256 = this.spawnsConfirmed ? sourceSha256 : null;
    }

    /** Distinct block state identifiers used, for the audit report. */
    public Set<String> usedBlockIdentifiers() {
        Set<String> used = new LinkedHashSet<>();
        for (CompoundTag state : schematic.palette()) {
            used.add(state.getString("Name", "?"));
        }
        return used;
    }

    /** Issues that must be resolved before a production export. */
    public List<MappingIssue> blockingIssues() {
        List<MappingIssue> blocking = new ArrayList<>();
        for (MappingIssue issue : issues) {
            if (issue.severity() == MappingIssue.Severity.BLOCKING) blocking.add(issue);
        }
        return blocking;
    }
}
