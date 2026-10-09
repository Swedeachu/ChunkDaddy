package gg.swim.chunkdaddy.worker.document;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.hivemc.chunker.conversion.encoding.base.resolver.blockentity.BlockEntityResolver;
import com.hivemc.chunker.conversion.intermediate.column.ChunkerColumn;
import com.hivemc.chunker.nbt.tags.collection.CompoundTag;
import gg.swim.chunkdaddy.worker.protocol.Json;
import gg.swim.chunkdaddy.worker.util.Checked;
import gg.swim.chunkdaddy.worker.util.ChunkRect;

import java.util.HashSet;
import java.util.Set;

/** Reusable composition transaction: one local metadata template and many translated regions. */
public final class RegionComposer {
    private final WorldSnapshot.Builder builder = WorldSnapshot.empty().toBuilder();
    private final JsonObject templates = new JsonObject();
    private final JsonArray instances = new JsonArray();
    private final Set<String> instanceIds = new HashSet<>();
    private final java.util.List<ChunkRect> footprints = new java.util.ArrayList<>();

    public void add(String name, WorldSnapshot source, ChunkRect bounds, JsonObject metadata,
                     JsonArray coordinatePaths, JsonArray placements,
                     BlockEntityResolver<?, CompoundTag> resolver) {
        if (templates.has(name)) throw new IllegalArgumentException("Duplicate region template " + name);
        JsonObject template = new JsonObject();
        template.add("sourceBounds", Json.of(bounds));
        template.add("metadata", RegionMetadata.translate(metadata, coordinatePaths,
                Math.negateExact(Math.multiplyExact(bounds.minX(), 16)), 0,
                Math.negateExact(Math.multiplyExact(bounds.minZ(), 16))));
        template.add("coordinatePaths", coordinatePaths.deepCopy());
        int columns = 0;
        for (long key : source.materializedKeys()) {
            if (bounds.contains(Checked.chunkKeyX(key), Checked.chunkKeyZ(key))) columns++;
        }
        template.addProperty("sourceColumns", columns);
        if (columns == 0) throw new IllegalArgumentException("Region " + name + " contains no columns");
        templates.add(name, template);

        for (JsonElement element : placements) {
            JsonObject placement = element.getAsJsonObject();
            String id = Json.string(placement, "id");
            if (!instanceIds.add(id)) throw new IllegalArgumentException("Duplicate region instance " + id);
            int destX = Json.integer(placement, "destChunkX");
            int destZ = Json.integer(placement, "destChunkZ");
            int dx = Math.subtractExact(destX, bounds.minX());
            int dz = Math.subtractExact(destZ, bounds.minZ());
            ChunkRect target = bounds.translated(dx, dz);
            for (ChunkRect footprint : footprints) if (footprint.intersects(target))
                throw new IllegalArgumentException("Overlapping region " + id);
            footprints.add(target);
            for (long key : source.materializedKeys()) {
                int cx = Checked.chunkKeyX(key), cz = Checked.chunkKeyZ(key);
                if (!bounds.contains(cx, cz)) continue;
                ChunkerColumn column = source.materializedColumn(cx, cz);
                builder.putColumn(Math.addExact(cx, dx), Math.addExact(cz, dz),
                        ColumnOps.relocateShared(column, dx, dz, resolver));
            }
            JsonObject instance = new JsonObject();
            instance.addProperty("id", id);
            instance.addProperty("template", name);
            instance.add("bounds", Json.of(target));
            JsonObject offset = new JsonObject();
            offset.addProperty("x", Math.multiplyExact(destX, 16));
            offset.addProperty("y", 0);
            offset.addProperty("z", Math.multiplyExact(destZ, 16));
            instance.add("offset", offset);
            instances.add(instance);
        }
    }

    public WorldSnapshot snapshot() { return builder.build(); }
    public JsonObject manifest(String world) {
        JsonObject result = new JsonObject();
        result.addProperty("schemaVersion", 1);
        result.addProperty("world", world);
        result.add("templates", templates);
        result.add("instances", instances);
        return result;
    }
}
