package gg.swim.chunkdaddy.worker.conversion;

import com.hivemc.chunker.conversion.encoding.java.base.resolver.JavaResolvers;
import com.hivemc.chunker.conversion.intermediate.column.chunk.identifier.ChunkerBlockIdentifier;
import com.hivemc.chunker.nbt.tags.collection.CompoundTag;
import gg.swim.chunkdaddy.worker.schematic.SpongeSchematic;
import gg.swim.chunkdaddy.worker.schematic.SpongeSchematicReader;
import gg.swim.chunkdaddy.worker.util.Slug;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;

/** Turns a {@code .schem} file into an audited {@link ArenaTemplate}. */
public final class TemplateImporter {
    /**
     * Air-like Java identifiers. A palette entry outside this set that converts to air is
     * reported, never accepted quietly: silent air substitution is how builds lose walls.
     */
    private static final Set<String> AIR_IDENTIFIERS =
            Set.of("minecraft:air", "minecraft:cave_air", "minecraft:void_air");

    /**
     * A schematic whose footprint is far larger than a single duel arena is treated as a
     * possible aggregate. The check is on geometry rather than the file name, so arbitrary
     * collections behave the same way as the known {@code all_arenas.schem}.
     */
    private static final int AGGREGATE_CHUNK_FOOTPRINT = 24;

    private final ResolverFactory resolverFactory;

    public TemplateImporter(ResolverFactory resolverFactory) {
        this.resolverFactory = resolverFactory;
    }

    public ArenaTemplate importFile(File file) throws IOException {
        SpongeSchematic schematic = SpongeSchematicReader.read(file);
        String sha256 = sha256(file);
        String slug = Slug.suggestFromFileName(file.getName());

        JavaResolvers resolvers = resolverFactory.forDataVersion(schematic.javaDataVersion());

        List<CompoundTag> palette = schematic.palette();
        ChunkerBlockIdentifier[] resolved = new ChunkerBlockIdentifier[palette.size()];
        List<MappingIssue> issues = new ArrayList<>();

        for (int i = 0; i < palette.size(); i++) {
            CompoundTag state = palette.get(i);
            String name = state.getString("Name", "?");
            ChunkerBlockIdentifier identifier;
            try {
                identifier = resolvers.readBlock(state);
            } catch (Exception e) {
                issues.add(new MappingIssue(MappingIssue.Severity.BLOCKING, MappingIssue.Kind.BLOCK_STATE,
                        name, "Failed to resolve: " + e.getMessage()));
                resolved[i] = ChunkerBlockIdentifier.AIR;
                continue;
            }
            resolved[i] = identifier;

            if (identifier.isAir() && !AIR_IDENTIFIERS.contains(name)) {
                // The guide is explicit: unknown blocks must never silently become air.
                issues.add(new MappingIssue(MappingIssue.Severity.BLOCKING, MappingIssue.Kind.RESOLVED_TO_AIR,
                        name, "Converted to air. Either the state is unsupported by the selected profile or the "
                        + "palette entry is malformed; exporting would delete this block."));
            }
        }

        // Audit block entity types too: an unmapped block entity keeps its block but loses
        // its payload, which for signs and containers is a content change, not a detail.
        for (SpongeSchematic.BlockEntityRecord record : schematic.blockEntities()) {
            if (resolvers.blockEntityResolver().to(record.javaNbt()).isEmpty()) {
                issues.add(new MappingIssue(MappingIssue.Severity.BLOCKING, MappingIssue.Kind.BLOCK_ENTITY,
                        record.id(), "No block entity mapping for the selected profile."));
            }
        }

        boolean aggregate = schematic.footprintChunksX() >= AGGREGATE_CHUNK_FOOTPRINT
                || schematic.footprintChunksZ() >= AGGREGATE_CHUNK_FOOTPRINT;

        return new ArenaTemplate(slug, file.getName(), sha256, schematic, resolved, dedupe(issues), aggregate);
    }

    /** Collapse repeats so a palette of 400 unknown states does not produce 400 lines. */
    private static List<MappingIssue> dedupe(List<MappingIssue> issues) {
        List<MappingIssue> out = new ArrayList<>();
        Set<String> seen = new java.util.HashSet<>();
        for (MappingIssue issue : issues) {
            if (seen.add(issue.kind() + "|" + issue.identifier())) out.add(issue);
        }
        return out;
    }

    public static String sha256(File file) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
        byte[] buffer = new byte[1 << 16];
        try (InputStream in = Files.newInputStream(file.toPath())) {
            int read;
            while ((read = in.read(buffer)) > 0) {
                digest.update(buffer, 0, read);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }
}
