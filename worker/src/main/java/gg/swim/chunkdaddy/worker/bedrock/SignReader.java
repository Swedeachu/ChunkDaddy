package gg.swim.chunkdaddy.worker.bedrock;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.hivemc.chunker.nbt.tags.Tag;
import com.hivemc.chunker.nbt.tags.collection.CompoundTag;
import com.hivemc.chunker.nbt.tags.primitive.ByteTag;
import com.hivemc.chunker.nbt.tags.primitive.IntTag;
import com.hivemc.chunker.nbt.tags.primitive.StringTag;
import gg.swim.chunkdaddy.worker.util.ChunkRect;
import org.iq80.leveldb.DB;
import org.iq80.leveldb.DBIterator;
import org.iq80.leveldb.impl.Iq80DBFactory;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Lists every sign in a Bedrock world's raw database: position, front/back text and the
 * sign block's own state (so the facing a map designer gave it can be read too).
 *
 * <p>Map designers label a world with signs ("NPC: replay mode", "LB: top kills"), and an
 * agent or script then turns each sign into placed content. Reading the database directly
 * keeps this cheap: no import, no conversion, and nothing is written.</p>
 */
public final class SignReader {
    private static final int BLOCK_ENTITY_TAG = 0x31;
    private static final int SUBCHUNK_TAG = 0x2F;

    private record Sign(int x, int y, int z, String id, String front, String back, boolean waxed) {}

    private SignReader() {
    }

    public static JsonObject read(Path world, ChunkRect area, boolean includeEmpty, boolean includeBlockState) throws IOException {
        List<Sign> signs = new ArrayList<>();
        long blockEntities = 0;
        Path database = world.resolve("db");
        if (!Files.isDirectory(database)) {
            throw new IOException("Missing world database: " + database);
        }

        try (DB db = Iq80DBFactory.factory.open(database.toFile(), VoidCleaner.databaseOptions(false));
             DBIterator it = db.iterator()) {
            for (it.seekToFirst(); it.hasNext();) {
                var entry = it.next();
                var key = VoidCleaner.parse(entry.getKey());
                if (key == null || key.actorDigest() || key.dimension() != 0 || key.tag() != BLOCK_ENTITY_TAG) {
                    continue;
                }

                int chunkX = (int) (key.column() >> 32);
                int chunkZ = (int) key.column();
                if (area != null && !area.contains(chunkX, chunkZ)) {
                    continue;
                }

                ByteBuffer input = ByteBuffer.wrap(entry.getValue()).order(ByteOrder.LITTLE_ENDIAN);
                while (input.hasRemaining()) {
                    CompoundTag tag = Tag.readBedrockNBT(input);
                    if (tag == null) {
                        break;
                    }
                    blockEntities++;
                    String id = tag.getString("id", "");
                    if (!id.equals("Sign") && !id.equals("HangingSign")) {
                        continue;
                    }

                    String front = text(tag, "FrontText");
                    String back = text(tag, "BackText");
                    if (front.isEmpty() && back.isEmpty() && tag.contains("Text")) {
                        front = tag.getString("Text", "");
                    }
                    if (!includeEmpty && front.isBlank() && back.isBlank()) {
                        continue;
                    }

                    signs.add(new Sign(
                            tag.getInt("x", 0),
                            tag.getInt("y", 0),
                            tag.getInt("z", 0),
                            id,
                            front,
                            back,
                            tag.getByte("IsWaxed", (byte) 0) != 0));
                }
            }

            signs.sort(Comparator.comparingInt(Sign::x).thenComparingInt(Sign::z).thenComparingInt(Sign::y));
            JsonArray list = new JsonArray();
            for (Sign sign : signs) {
                JsonObject out = new JsonObject();
                out.addProperty("x", sign.x());
                out.addProperty("y", sign.y());
                out.addProperty("z", sign.z());
                out.addProperty("type", sign.id());
                out.addProperty("front", sign.front());
                out.addProperty("back", sign.back());
                out.addProperty("text", (sign.front() + "\n" + sign.back()).strip());
                out.addProperty("waxed", sign.waxed());
                if (includeBlockState) {
                    CompoundTag block = blockAt(db, sign.x(), sign.y(), sign.z());
                    if (block != null) {
                        out.addProperty("block", block.getString("name", ""));
                        out.add("states", states(block.getCompound("states", new CompoundTag())));
                        Float yaw = facingYaw(block);
                        if (yaw != null) {
                            out.addProperty("facingYaw", yaw);
                        }
                    }
                }
                list.add(out);
            }

            JsonObject result = new JsonObject();
            result.addProperty("blockEntitiesScanned", blockEntities);
            result.addProperty("signCount", signs.size());
            result.add("signs", list);
            return result;
        }
    }

    private static String text(CompoundTag tag, String side) {
        if (!tag.contains(side)) {
            return "";
        }
        return tag.getCompound(side).getString("Text", "");
    }

    private static JsonObject states(CompoundTag states) {
        JsonObject out = new JsonObject();
        for (Map.Entry<String, Tag<?>> entry : states) {
            Tag<?> value = entry.getValue();
            if (value instanceof IntTag i) {
                out.addProperty(entry.getKey(), i.getValue());
            } else if (value instanceof ByteTag b) {
                out.addProperty(entry.getKey(), b.getValue());
            } else if (value instanceof StringTag s) {
                out.addProperty(entry.getKey(), s.getValue());
            } else {
                out.addProperty(entry.getKey(), value.toSNBT());
            }
        }
        return out;
    }

    /**
     * The yaw (Minecraft convention: 0 = south/+Z, 90 = west/-X) the sign's text faces, so
     * whatever replaces the sign can face the reader the same way.
     */
    private static Float facingYaw(CompoundTag block) {
        CompoundTag states = block.getCompound("states", new CompoundTag());
        if (states.contains("ground_sign_direction")) {
            return states.getInt("ground_sign_direction", 0) * 22.5f;
        }
        if (states.contains("facing_direction")) {
            return switch (states.getInt("facing_direction", 2)) {
                case 2 -> 180f;
                case 3 -> 0f;
                case 4 -> 90f;
                case 5 -> 270f;
                default -> null;
            };
        }
        return null;
    }

    /** Decodes one block from a persistent-palette sub-chunk (versions 8 and 9). */
    private static CompoundTag blockAt(DB db, int x, int y, int z) throws IOException {
        int chunkX = Math.floorDiv(x, 16);
        int chunkZ = Math.floorDiv(z, 16);
        int section = Math.floorDiv(y, 16);
        byte[] key = new byte[10];
        writeLe32(key, 0, chunkX);
        writeLe32(key, 4, chunkZ);
        key[8] = (byte) SUBCHUNK_TAG;
        key[9] = (byte) section;
        byte[] value = db.get(key);
        if (value == null || value.length < 3) {
            return null;
        }

        ByteBuffer input = ByteBuffer.wrap(value).order(ByteOrder.LITTLE_ENDIAN);
        int version = input.get() & 0xFF;
        if (version != 8 && version != 9) {
            return null;
        }
        int storages = input.get() & 0xFF;
        if (version == 9) {
            input.get();
        }
        if (storages == 0) {
            return null;
        }

        int header = input.get() & 0xFF;
        int bits = header >> 1;
        int index = (Math.floorMod(x, 16) << 8) | (Math.floorMod(z, 16) << 4) | Math.floorMod(y, 16);
        int paletteIndex = 0;
        if (bits != 0) {
            int perWord = 32 / bits;
            int words = (4096 + perWord - 1) / perWord;
            int start = input.position();
            int word = input.getInt(start + (index / perWord) * 4);
            paletteIndex = (word >>> ((index % perWord) * bits)) & ((1 << bits) - 1);
            input.position(start + words * 4);
        }
        int paletteSize = input.getInt();
        CompoundTag found = null;
        for (int i = 0; i < paletteSize; i++) {
            CompoundTag entry = Tag.readBedrockNBT(input);
            if (i == paletteIndex) {
                found = entry;
                break;
            }
        }
        return found;
    }

    private static void writeLe32(byte[] out, int offset, int value) {
        out[offset] = (byte) value;
        out[offset + 1] = (byte) (value >> 8);
        out[offset + 2] = (byte) (value >> 16);
        out[offset + 3] = (byte) (value >> 24);
    }
}
