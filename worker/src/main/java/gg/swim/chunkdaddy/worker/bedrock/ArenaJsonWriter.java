package gg.swim.chunkdaddy.worker.bedrock;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import gg.swim.chunkdaddy.worker.conversion.ArenaTemplate;
import gg.swim.chunkdaddy.worker.document.ArenaInstance;
import gg.swim.chunkdaddy.worker.document.TemplateRegistry;
import gg.swim.chunkdaddy.worker.document.WorldSnapshot;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Writes the server-facing {@code arenas.json}.
 *
 * <p>The file is exactly the requested top-level mapping from arena name to its two duel
 * spawn positions: no wrapper object, no comments, no trailing comma. Each position is
 * computed with the same {@code P = B + L} transform used to place the arena's blocks, in
 * the same committed revision, so the coordinates cannot drift from the world.
 */
public final class ArenaJsonWriter {
    /** How coordinates are serialized. */
    public enum NumberMode {
        /**
         * Whole values are written without a decimal point, fractional values are kept
         * exactly. This matches the requested example while not silently discarding a
         * half-block offset the user deliberately entered.
         */
        EXACT,
        /**
         * Round to integers. Only for a consumer that has been shown to reject decimals;
         * selecting it is a deliberate, recorded choice.
         */
        INTEGER
    }

    private ArenaJsonWriter() {
    }

    /** Arena instances that cannot be exported yet, with the reason. */
    public static List<String> unresolved(WorldSnapshot snapshot, TemplateRegistry templates) {
        List<String> problems = new ArrayList<>();
        Set<String> names = new HashSet<>();
        for (ArenaInstance instance : sorted(snapshot)) {
            ArenaTemplate template = templates.get(instance.templateId());
            if (template == null) {
                problems.add(instance.exportId() + ": its template is no longer loaded in this session.");
                continue;
            }
            if (!names.add(instance.exportId())) {
                problems.add(instance.exportId() + ": duplicate arena name.");
            }
            if (instance.needsRevalidation()) {
                problems.add(instance.exportId() + ": a partial edit changed this arena; revalidate or remove it.");
            }
            if (!template.spawnsConfirmed()) {
                problems.add(instance.exportId() + ": template '" + template.slug()
                        + "' has no confirmed spawn markers for its current source hash.");
            }
        }
        return problems;
    }

    /**
     * Build the mapping. Callers must have checked {@link #unresolved} first: an arena
     * with missing spawns is never quietly omitted from the output.
     */
    public static JsonObject build(WorldSnapshot snapshot, TemplateRegistry templates, NumberMode mode) {
        List<String> problems = unresolved(snapshot, templates);
        if (!problems.isEmpty()) {
            throw new IllegalStateException("Cannot write arenas.json:\n  " + String.join("\n  ", problems));
        }

        JsonObject root = new JsonObject();
        for (ArenaInstance instance : sorted(snapshot)) {
            ArenaTemplate template = templates.require(instance.templateId());
            JsonObject arena = new JsonObject();
            arena.add("spawnPoint1", position(instance.toWorld(template.spawnPoint1()), mode));
            arena.add("spawnPoint2", position(instance.toWorld(template.spawnPoint2()), mode));
            root.add(instance.exportId(), arena);
        }
        return root;
    }

    public static String serialize(JsonObject object) {
        // Pretty printing keeps the file reviewable in a pull request; Gson never emits a
        // trailing comma, which the example in the original request did contain.
        return new GsonBuilder().setPrettyPrinting().create().toJson(object) + "\n";
    }

    private static JsonObject position(double[] p, NumberMode mode) {
        JsonObject object = new JsonObject();
        object.add("x", number(p[0], mode));
        object.add("y", number(p[1], mode));
        object.add("z", number(p[2], mode));
        return object;
    }

    private static JsonPrimitive number(double value, NumberMode mode) {
        if (mode == NumberMode.INTEGER) {
            return new JsonPrimitive(Math.round(value));
        }
        if (value == Math.rint(value) && !Double.isInfinite(value)) {
            return new JsonPrimitive((long) value);
        }
        // BigDecimal keeps the exact decimal the user typed rather than a float artefact.
        return new JsonPrimitive(new BigDecimal(Double.toString(value)));
    }

    /** Stable ordering: template slug, then ordinal. Never filesystem enumeration order. */
    private static List<ArenaInstance> sorted(WorldSnapshot snapshot) {
        List<ArenaInstance> instances = new ArrayList<>(snapshot.instances());
        instances.sort(Comparator.comparing(ArenaInstance::templateSlug).thenComparingInt(ArenaInstance::ordinal));
        return instances;
    }
}
