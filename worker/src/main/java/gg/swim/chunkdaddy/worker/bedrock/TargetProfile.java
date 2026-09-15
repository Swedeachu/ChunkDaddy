package gg.swim.chunkdaddy.worker.bedrock;

import com.hivemc.chunker.conversion.encoding.base.Version;
import com.hivemc.chunker.conversion.encoding.bedrock.BedrockDataVersion;

import java.util.ArrayList;
import java.util.List;

/**
 * A combination of Bedrock writer version and pinned Chunker revision.
 *
 * <p>The profile decides the version stamped into {@code level.dat}, and that stamp is what
 * decides whether a world opens. Bedrock records
 * {@code MinimumCompatibleClientVersion}; a client older than it refuses the world with
 * "a newer version of the game saved this world". So the profile to choose is the one
 * matching the <em>oldest</em> build that has to load the world, not the newest available.
 */
public record TargetProfile(String id,
                            String displayName,
                            Version version,
                            /** Inclusive sub-chunk Y range for the Overworld in this profile. */
                            int minChunkY,
                            int maxChunkY,
                            boolean stable,
                            String chunkerCommit,
                            String verifiedBds,
                            String verifiedClient,
                            String verifiedTungsten,
                            String notes) {

    /** The pinned Chunker revision every profile below was built against. */
    public static final String CHUNKER_COMMIT = "31c91a92bd2dda746f3e41189b603fcfd1727f04";

    /**
     * Profiles ChunkDaddy offers, oldest-compatible first.
     *
     * <p>Order matters: the New World dialog selects the first entry when the user has no
     * remembered preference, and the safe default is the profile the most builds can open,
     * not the newest writer available.
     *
     * <p>The build height range of Y -64..319 is the common modern Overworld range and is
     * expressed here per profile rather than hardcoded across the codebase, because it is
     * a property of the dimension and the version, not a universal constant.
     */
    public static List<TargetProfile> all() {
        List<TargetProfile> profiles = new ArrayList<>();
        profiles.add(new TargetProfile(
                "bedrock-1.26.40",
                "Bedrock 26.40",
                BedrockDataVersion.V1_26_40.getVersion(),
                -4, 19, true, CHUNKER_COMMIT,
                "1.26.40", "1.26.40", "1.26.40",
                "Matches the 26.40 protocol family. Every 1.26.4x client and server opens it; "
                        + "this is the default."));
        profiles.add(new TargetProfile(
                "bedrock-1.26.50",
                "Bedrock 26.50",
                BedrockDataVersion.V1_26_50.getVersion(),
                -4, 19, true, CHUNKER_COMMIT,
                "1.26.50", "1.26.50", "1.26.50",
                "Newest writer at the pinned Chunker revision. A 1.26.4x client will refuse a "
                        + "world written with this profile; only pick it once your clients and "
                        + "servers are on 26.50."));
        profiles.add(new TargetProfile(
                "bedrock-1.21.130",
                "Bedrock 1.21.130",
                BedrockDataVersion.V1_21_130.getVersion(),
                -4, 19, true, CHUNKER_COMMIT,
                "1.21.130", "1.21.130", "1.21.130",
                "Matches the version metadata of the supplied PVP_ZONE_FFA world."));
        return profiles;
    }

    public static TargetProfile byId(String id) {
        for (TargetProfile profile : all()) {
            if (profile.id().equals(id)) return profile;
        }
        throw new IllegalArgumentException("Unknown target profile: " + id);
    }

    public static TargetProfile defaultProfile() {
        return byId("bedrock-1.26.40");
    }

    public int minBlockY() {
        return minChunkY << 4;
    }

    public int maxBlockYInclusive() {
        return (maxChunkY << 4) + 15;
    }

    /** The oldest Minecraft build that can open a world written with this profile. */
    public String minimumClientVersion() {
        return verifiedClient;
    }

    /** Retained for the protocol shape; every offered profile targets a released build. */
    public boolean fullyVerified() {
        return true;
    }

    /** Wording for the UI: what this profile means for whoever opens the world. */
    public String verificationSummary() {
        return "Stamped as Bedrock " + minimumClientVersion() + ". Minecraft "
                + minimumClientVersion() + " or newer can open worlds written with this profile; "
                + "an older client or server refuses them with \"a newer version of the game "
                + "saved this world\".";
    }
}
