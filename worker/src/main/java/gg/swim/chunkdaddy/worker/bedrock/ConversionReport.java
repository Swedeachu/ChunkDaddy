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
                                List<String> warnings) {
        StringBuilder out = new StringBuilder();
        out.append("ChunkDaddy conversion report\n");
        out.append("============================\n\n");
        out.append("World: ").append(document.name()).append('\n');
        out.append("Document revision: ").append(document.revision()).append('\n');
        out.append("Target profile: ").append(document.targetProfileId()).append('\n');
        out.append("Chunker revision: ").append(TargetProfile.CHUNKER_COMMIT).append("\n\n");

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
                    .append(template.spawnsConfirmed() ? "confirmed" : "NOT CONFIRMED").append('\n');
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

        out.append("What this report does not establish\n");
        out.append("-----------------------------------\n");
        out.append("  A successful conversion is not evidence of BDS, vanilla client or Tungsten\n");
        out.append("  compatibility. Run the acceptance procedure in docs/TargetProfiles.md against\n");
        out.append("  this exact artifact and record the results before deploying it.\n");
        return out.toString();
    }
}
