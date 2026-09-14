package gg.swim.chunkdaddy.worker.bedrock;

import com.hivemc.chunker.conversion.encoding.base.Version;
import com.hivemc.chunker.conversion.encoding.bedrock.BedrockDataVersion;

import java.util.ArrayList;
import java.util.List;

/**
 * A tested combination of writer version, Chunker revision and acceptance results.
 *
 * <p>A converter recognizing a version is not the same thing as that output having been
 * loaded by a Bedrock Dedicated Server. {@link #verifiedBds}, {@link #verifiedClient} and
 * {@link #verifiedTungsten} record what has actually been demonstrated, and the
 * application shows that state rather than implying compatibility from a successful
 * conversion. Nothing here may claim forward compatibility with an untested release.
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

    private static final String UNVERIFIED = "not yet verified";

    /**
     * Profiles ChunkDaddy offers.
     *
     * <p>The build height range of Y -64..319 is the common modern Overworld range and is
     * expressed here per profile rather than hardcoded across the codebase, because it is
     * a property of the dimension and the version, not a universal constant.
     */
    public static List<TargetProfile> all() {
        List<TargetProfile> profiles = new ArrayList<>();
        profiles.add(new TargetProfile(
                "bedrock-1.26.50",
                "Bedrock 26.50",
                BedrockDataVersion.V1_26_50.getVersion(),
                -4, 19, true, CHUNKER_COMMIT,
                UNVERIFIED, UNVERIFIED, UNVERIFIED,
                "Newest writer available at the pinned Chunker revision."));
        profiles.add(new TargetProfile(
                "bedrock-1.26.40",
                "Bedrock 26.40",
                BedrockDataVersion.V1_26_40.getVersion(),
                -4, 19, true, CHUNKER_COMMIT,
                UNVERIFIED, UNVERIFIED, UNVERIFIED,
                "Baseline candidate named in the design guide. Run the acceptance procedure "
                        + "in docs/TargetProfiles.md before shipping a world built on it."));
        profiles.add(new TargetProfile(
                "bedrock-1.21.130",
                "Bedrock 1.21.130",
                BedrockDataVersion.V1_21_130.getVersion(),
                -4, 19, true, CHUNKER_COMMIT,
                UNVERIFIED, UNVERIFIED, UNVERIFIED,
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
        return byId("bedrock-1.26.50");
    }

    public int minBlockY() {
        return minChunkY << 4;
    }

    public int maxBlockYInclusive() {
        return (maxChunkY << 4) + 15;
    }

    /** True when every acceptance test for this profile has actually been run. */
    public boolean fullyVerified() {
        return !UNVERIFIED.equals(verifiedBds)
                && !UNVERIFIED.equals(verifiedClient)
                && !UNVERIFIED.equals(verifiedTungsten);
    }

    /** Wording for the UI; never implies more than has been demonstrated. */
    public String verificationSummary() {
        if (fullyVerified()) {
            return "Verified: BDS " + verifiedBds + ", client " + verifiedClient + ", Tungsten " + verifiedTungsten;
        }
        return "Not yet verified against BDS, the vanilla client or Tungsten. "
                + "Conversion succeeding is not evidence that a server will load the result.";
    }
}
