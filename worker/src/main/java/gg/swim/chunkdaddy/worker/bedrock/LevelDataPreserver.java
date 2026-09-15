package gg.swim.chunkdaddy.worker.bedrock;

import com.hivemc.chunker.nbt.tags.Tag;
import com.hivemc.chunker.nbt.tags.collection.CompoundTag;
import org.jetbrains.annotations.Nullable;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Carries tags from a source world's {@code level.dat} into the exported one.
 *
 * <p>Chunker models level settings as a flat list of known fields, so anything it has no
 * field for is simply absent from the world it writes. That is not a cosmetic loss. The
 * {@code experiments} compound is the toggled-experiments record, and its {@code gametest}
 * entry is the "Beta APIs" switch; a behaviour pack whose manifest asks for
 * {@code "@minecraft/server": "beta"} has its script module refused outright when that
 * switch is missing. The pack then loads with no scripts, no scenes and no errors, and the
 * server looks perfectly healthy. Writing a fresh default experiments compound would do the
 * same damage, which is why the source's is copied across untouched.
 *
 * <p>The rule is deliberately the opposite way round from a normal allow list: every tag
 * the source had is carried unless it is something this export decides for itself. A tag
 * nobody has thought about yet should survive, because that is exactly the class of bug
 * this exists to fix.
 */
public final class LevelDataPreserver {
    /**
     * Tags that describe <em>this</em> export and must never be taken from the source.
     *
     * <p>The version stamps are the important ones: carrying them over would undo the
     * target profile the user chose and hand them a world their client refuses to open.
     */
    private static final Set<String> NEVER_PRESERVED = Set.of(
            "StorageVersion", "NetworkVersion", "lastOpenedWithVersion",
            "MinimumCompatibleClientVersion", "InventoryVersion", "baseGameVersion",
            "LevelName", "LastPlayed", "SpawnX", "SpawnY", "SpawnZ", "GameType",
            "Generator", "FlatWorldLayers", "RandomSeed", "worldStartCount", "currentTick",
            "DimensionData", "BiomeOverride", "prid", "isFromWorldTemplate",
            "isWorldTemplateOptionLocked", "isRandomSeedAllowed");

    /**
     * Tags copied even when the exported world already has one of its own.
     *
     * <p>Only for records where a default is actively harmful rather than merely different.
     */
    private static final Set<String> ALWAYS_REPLACED = Set.of("experiments");

    /** Tags worth naming in the report rather than listing among the rest. */
    private static final Map<String, String> NOTABLE = Map.of(
            "experiments", "toggled experiments, including gametest (the Beta APIs switch "
                    + "that script behaviour packs need)",
            "world_policies", "world policy overrides",
            "education_features_enabled", "education edition features");

    private LevelDataPreserver() {
    }

    /** What a merge did, so the caller can report it and the user can see it happened. */
    public record Result(List<String> carried, List<String> notes) {
        public boolean isEmpty() {
            return carried.isEmpty();
        }
    }

    /**
     * Merge preserved tags into an exported {@code level.dat}, in place.
     *
     * <p>Never throws: a failure here leaves a world that is still complete and loadable,
     * only missing the carried-over settings, and the caller turns it into a warning. The
     * file is rewritten only when something actually changed.
     */
    public static Result merge(Path levelDat, @Nullable CompoundTag sourceLevelData, List<String> warnings) {
        if (sourceLevelData == null) {
            return new Result(List.of(), List.of());
        }
        try {
            if (!Files.isRegularFile(levelDat)) {
                throw new IOException("no level.dat was produced at " + levelDat);
            }
            int storageVersion = readStorageVersion(levelDat);
            CompoundTag written = Tag.readBedrockNBT(levelDat.toFile());
            if (written == null) {
                throw new IOException("the exported level.dat could not be read back");
            }

            // Work from a copy so the document's own record of the source world is never
            // aliased into the file we are about to write.
            CompoundTag source = sourceLevelData.clone();
            Map<String, Tag<?>> entries = source.getBoxedValue();
            if (entries == null) {
                return new Result(List.of(), List.of());
            }

            List<String> carried = new ArrayList<>();
            Map<String, String> noted = new LinkedHashMap<>();
            for (Map.Entry<String, Tag<?>> entry : entries.entrySet()) {
                String name = entry.getKey();
                if (NEVER_PRESERVED.contains(name)) {
                    continue;
                }
                if (written.contains(name) && !ALWAYS_REPLACED.contains(name)) {
                    continue;
                }
                written.put(name, entry.getValue());
                carried.add(name);
                if (NOTABLE.containsKey(name)) {
                    noted.put(name, NOTABLE.get(name));
                }
            }

            if (carried.isEmpty()) {
                return new Result(List.of(), List.of());
            }
            Tag.writeBedrockNBT(levelDat.toFile(), storageVersion, written);

            List<String> notes = new ArrayList<>();
            for (Map.Entry<String, String> note : noted.entrySet()) {
                notes.add(note.getKey() + " (" + note.getValue() + ")");
            }
            return new Result(List.copyOf(carried), List.copyOf(notes));
        } catch (Throwable e) {
            warnings.add("The world was written, but settings from the source world could not be "
                    + "carried into its level.dat (" + e + "). The world is valid; if the source "
                    + "had experiments enabled, check that the export still does before deploying "
                    + "a script behaviour pack onto it.");
            return new Result(List.of(), List.of());
        }
    }

    /**
     * A Bedrock level.dat begins with a little-endian storage version and length before the
     * NBT payload. Rewriting the file needs the same version back, so it is read from the
     * file itself rather than assumed from the target profile.
     */
    private static int readStorageVersion(Path levelDat) throws IOException {
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(Files.newInputStream(levelDat)))) {
            byte[] header = in.readNBytes(4);
            if (header.length != 4) {
                throw new IOException("level.dat is truncated");
            }
            return ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN).getInt();
        }
    }

    /** True when a source world has experiments toggled on, for the import report. */
    public static boolean hasEnabledExperiments(@Nullable CompoundTag sourceLevelData) {
        return !enabledExperiments(sourceLevelData).isEmpty();
    }

    /**
     * Names of the experiments a source world has switched on.
     *
     * <p>Tag types inside this compound are not guaranteed, so values are inspected
     * rather than cast: a malformed source world must not be able to fail an import.
     */
    public static List<String> enabledExperiments(@Nullable CompoundTag sourceLevelData) {
        List<String> names = new ArrayList<>();
        if (sourceLevelData == null) {
            return names;
        }
        CompoundTag experiments = sourceLevelData.getOptional("experiments", CompoundTag.class).orElse(null);
        if (experiments == null || experiments.getBoxedValue() == null) {
            return names;
        }
        for (Map.Entry<String, Tag<?>> entry : experiments.getBoxedValue().entrySet()) {
            String key = entry.getKey();
            // experiments_ever_used and saved_with_toggled_experiments are bookkeeping,
            // not toggles; any other entry holding a non-zero number is a real one.
            if (key.startsWith("experiments_") || key.startsWith("saved_with_")) {
                continue;
            }
            Object value = entry.getValue() == null ? null : entry.getValue().getBoxedValue();
            if (value instanceof Number number && number.byteValue() != 0) {
                names.add(key);
            }
        }
        names.sort(String::compareTo);
        return names;
    }
}
