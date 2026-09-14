package gg.swim.chunkdaddy.worker.conversion;

/**
 * One conversion outcome that the user has to see.
 *
 * <p>Every encountered block state or block entity type falls into exactly one of these.
 * {@link Severity#BLOCKING} entries stop a production export; the others appear in the
 * report and, for substitutions, require a saved decision.
 */
public record MappingIssue(Severity severity, Kind kind, String identifier, String detail) {
    public enum Severity {
        /** Exactly mapped; recorded only when a report asks for the full audit. */
        INFO,
        /** A declared semantic approximation, or a substitution the user accepted. */
        APPROXIMATION,
        /** Unsupported content. Production export refuses while this is present. */
        BLOCKING
    }

    public enum Kind {
        BLOCK_STATE,
        BLOCK_ENTITY,
        ENTITY,
        /** The input was a real block but the converter produced air: never silent. */
        RESOLVED_TO_AIR,
        OTHER
    }
}
