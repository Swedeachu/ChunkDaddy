package gg.swim.chunkdaddy.worker.bedrock;

import com.hivemc.chunker.conversion.WorldConverter;
import com.hivemc.chunker.conversion.encoding.base.Converter;
import gg.swim.chunkdaddy.worker.conversion.ArenaTemplate;
import gg.swim.chunkdaddy.worker.conversion.MappingIssue;
import gg.swim.chunkdaddy.worker.document.TemplateRegistry;
import gg.swim.chunkdaddy.worker.document.WorldDocument;

import java.util.List;
import java.util.Map;

/**
 * The human-readable conversion report shipped with an export.
 *
 * <p>Its job is to say what was approximated, what was excluded and what has not been
 * verified, in plain terms. A conversion that finished without errors is not the same
 * thing as a world a server will load.
 */
public final class ConversionReport {
    private ConversionReport() {
    }

    public static String render(WorldDocument document,
                                TemplateRegistry templates,
                                WorldConverter converter,
                                List<String> warnings,
                                LevelDataPreserver.Result preserved) {
        StringBuilder out = new StringBuilder();
        out.append("ChunkDaddy conversion report\n");
        out.append("============================\n\n");
        out.append("World: ").append(document.name()).append('\n');
        out.append("Document revision: ").append(document.revision()).append('\n');
        out.append("Target profile: ").append(document.targetProfileId()).append('\n');
        out.append("Chunker revision: ").append(TargetProfile.CHUNKER_COMMIT).append("\n\n");

        out.append("Level settings\n--------------\n");
        out.append("  Game mode: ").append(document.levelSettings().GameType)
                .append(", difficulty ").append(document.levelSettings().Difficulty)
                .append(", commands ")
                .append(document.levelSettings().commandsEnabled ? "on" : "off").append('\n');
        int[] spawn = document.worldSpawn();
        out.append("  World spawn: ").append(spawn[0]).append(", ").append(spawn[1])
                .append(", ").append(spawn[2]).append('\n');
        if (preserved.carried().isEmpty()) {
            out.append("  No level.dat tags were carried over from a source world.\n");
            if (document.canPreserveSourceLevelData()) {
                out.append("  (A source world was opened, but it held nothing this export did not\n")
                        .append("   already write for itself.)\n");
            }
        } else {
            out.append("  Carried from the source world's level.dat: ")
                    .append(String.join(", ", preserved.carried())).append('\n');
            for (String note : preserved.notes()) {
                out.append("      - ").append(note).append('\n');
            }
        }
        out.append('\n');

        if (!warnings.isEmpty()) {
            out.append("Warnings\n--------\n");
            for (String warning : warnings) {
                out.append("  - ").append(warning).append('\n');
            }
            out.append('\n');
        }

        out.append("Templates\n---------\n");
        for (ArenaTemplate template : templates.all()) {
            out.append("  ").append(template.slug())
                    .append("  (").append(template.sourceFileName()).append(")\n");
            out.append("      sponge v").append(template.schematic().spongeVersion())
                    .append(", Java DataVersion ").append(template.schematic().javaDataVersion())
                    .append(", ").append(template.sizeX()).append('x').append(template.sizeY())
                    .append('x').append(template.sizeZ())
                    .append(", ").append(template.footprintChunksX()).append('x')
                    .append(template.footprintChunksZ()).append(" chunks\n");
            out.append("      sha256 ").append(template.sourceSha256()).append('\n');
            out.append("      distinct block identifiers: ").append(template.usedBlockIdentifiers().size())
                    .append(", block entities: ").append(template.blockEntities().size()).append('\n');
            out.append("      spawn markers: ")
                    .append(template.spawnsAutomatic() ? "automatic centre/surface fallback (both entries share a position)"
                            : template.spawnsConfirmed() ? "confirmed" : "NOT CONFIRMED").append('\n');
            if (!template.issues().isEmpty()) {
                for (MappingIssue issue : template.issues()) {
                    out.append("      [").append(issue.severity()).append("] ")
                            .append(issue.kind()).append(' ').append(issue.identifier())
                            .append(" - ").append(issue.detail()).append('\n');
                }
            }
            out.append('\n');
        }

        out.append("Missing mappings reported by Chunker\n");
        out.append("------------------------------------\n");
        Map<Converter.MissingMappingType, java.util.Collection<String>> missing =
                converter.getMissingIdentifiers().asMap();
        if (missing.isEmpty()) {
            out.append("  none\n");
        } else {
            for (Map.Entry<Converter.MissingMappingType, java.util.Collection<String>> entry : missing.entrySet()) {
                out.append("  ").append(entry.getKey()).append(":\n");
                for (String identifier : entry.getValue()) {
                    out.append("      ").append(identifier).append('\n');
                }
            }
        }
        out.append('\n');

        out.append("Excluded by policy\n------------------\n");
        out.append("  - Ordinary entities: a static arena composition does not carry them, and copies\n");
        out.append("    would need fresh identities. Re-enable deliberately if a source world needs them.\n");
        out.append("  - In-game maps and player records: not part of region composition.\n\n");

        out.append("Compatibility\n-------------\n");
        out.append("  This world is stamped with the target profile's version. A client or server\n");
        out.append("  older than that profile refuses it with \"a newer version of the game saved\n");
        out.append("  this world\"; see docs/TargetProfiles.md to pick a different one and re-export.\n");
        return out.toString();
    }
}

