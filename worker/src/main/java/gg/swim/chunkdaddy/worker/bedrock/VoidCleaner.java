package gg.swim.chunkdaddy.worker.bedrock;

import org.iq80.leveldb.CompressionType;
import org.iq80.leveldb.DB;
import org.iq80.leveldb.DBIterator;
import org.iq80.leveldb.Options;
import org.iq80.leveldb.WriteBatch;
import org.iq80.leveldb.impl.Iq80DBFactory;
import org.iq80.leveldb.table.BloomFilterPolicy;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.LongConsumer;
import java.util.stream.Stream;

/**
 * Remove columns that hold nothing from a Bedrock world's database.
 *
 * <p>An exported world records every column inside the export rectangle, including the
 * ones with no blocks in them, so that a server finds generated void rather than an
 * ungenerated hole. When the written {@code level.dat} already generates void - a flat
 * world whose only layer is air, which is what this exporter writes - those records
 * reproduce for a fee exactly what the generator gives for free. On a composition whose
 * bounding box spans far apart islands they are almost the whole world: a measured hub
 * carried 1,263,712 columns to hold 14,341 real ones.
 *
 * <p>This is deliberately not an import and re-export. A world's database holds far more
 * than chunks - scoreboards, villages, maps, player data, ticking areas, structure
 * templates, portals - and a round trip through the composer would silently drop every one
 * of them. So the cleaner works on the database directly and copies forward, byte for byte,
 * every record it does not positively identify as belonging to an empty column.
 *
 * <h2>What makes a column removable</h2>
 *
 * Every one of these must hold:
 * <ul>
 *   <li>no sub-chunk records at all, or - only when {@code dropAirOnlySubChunks} is set -
 *       sub-chunks whose block palette is a single entry of {@code minecraft:air};</li>
 *   <li>no legacy terrain;</li>
 *   <li>no block entities, entities or actor digest;</li>
 *   <li>no pending ticks, random ticks, hardcoded spawners, border blocks or legacy block
 *       extra data.</li>
 * </ul>
 *
 * Biomes and heightmaps do not count as content: they are what the generator would produce
 * anyway, and keeping a column alive for them is the entire waste being removed here.
 *
 * <p>Anything that cannot be confidently parsed as a chunk key is treated as a world level
 * record and preserved. The failure this guards against is deleting something real, so
 * every ambiguity resolves towards keeping.
 */
public final class VoidCleaner {
    /** Chunk record tags that mean the column holds something a generator would not produce. */
    private static final Set<Integer> CONTENT_TAGS = Set.of(
            0x2F, // SubChunkPrefix
            0x30, // LegacyTerrain
            0x31, // BlockEntity
            0x32, // Entity
            0x33, // PendingTicks
            0x34, // LegacyBlockExtraData
            0x38, // BorderBlocks
            0x39, // HardcodedSpawners
            0x3A  // RandomTicks
    );

    /** Every tag that belongs to a column, content or not. Used to recognise chunk keys. */
    private static final Set<Integer> CHUNK_TAGS = Set.of(
            0x2B, 0x2C, 0x2D, 0x2E, 0x2F, 0x30, 0x31, 0x32, 0x33, 0x34, 0x35, 0x36, 0x37,
            0x38, 0x39, 0x3A, 0x3B, 0x3C, 0x3D, 0x3E, 0x3F, 0x40, 0x41, 0x76);

    private static final byte[] DIGP = "digp".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] ACTOR_PREFIX = "actorprefix".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] AIR = "minecraft:air".getBytes(StandardCharsets.US_ASCII);

    public record Settings(boolean dropAirOnlySubChunks, boolean dryRun) {
        public static Settings safe() {
            return new Settings(false, false);
        }
    }

    public record Report(long keysScanned,
                         long columnsScanned,
                         long columnsKept,
                         long columnsRemoved,
                         long keysRemoved,
                         long valueBytesRemoved,
                         long airOnlyColumnsRemoved,
                         long worldRecordsPreserved,
                         Map<String, Long> removedByTag,
                         List<String> notes) {
        public String summary() {
            return String.format(
                    "%,d of %,d columns held nothing and were removed (%,d keys, %,d bytes of values). "
                            + "%,d columns kept, %,d world level records preserved.",
                    columnsRemoved, columnsScanned, keysRemoved, valueBytesRemoved,
                    columnsKept, worldRecordsPreserved);
        }
    }

    private VoidCleaner() {
    }

    /**
     * Clean {@code sourceWorld} into {@code destinationWorld}.
     *
     * <p>The source is opened read only and never written. The destination is built fresh,
     * so the output database is also compacted; that alone is worth a good deal on a world
     * that has been edited.
     *
     * @param progress called with the number of keys processed, periodically. May be null.
     */
    public static Report clean(Path sourceWorld, Path destinationWorld, Settings settings,
                               LongConsumer progress) throws IOException {
        Path sourceDb = sourceWorld.resolve("db");
        if (!Files.isDirectory(sourceDb)) {
            throw new IOException("Not a Bedrock world: no db directory in " + sourceWorld);
        }

        List<String> notes = new ArrayList<>();
        Map<Integer, Set<Long>> contentColumns = new HashMap<>();
        Map<Integer, Set<Long>> allColumns = new HashMap<>();
        Set<Long> airOnlyCandidates = new HashSet<>();
        long keysScanned = 0;

        // ---- pass one: decide which columns hold something ----
        try (DB db = open(sourceDb, false); DBIterator it = db.iterator()) {
            for (it.seekToFirst(); it.hasNext(); it.next()) {
                Map.Entry<byte[], byte[]> entry = it.peekNext();
                byte[] key = entry.getKey();
                keysScanned++;
                if (progress != null && (keysScanned & 0xFFFF) == 0) progress.accept(keysScanned);

                ChunkKey parsed = parse(key);
                if (parsed == null) continue;
                allColumns.computeIfAbsent(parsed.dimension, d -> new HashSet<>()).add(parsed.column);

                if (parsed.actorDigest) {
                    // A non-empty digest lists entities living in this column.
                    if (entry.getValue().length > 0) mark(contentColumns, parsed);
                    continue;
                }
                if (!CONTENT_TAGS.contains(parsed.tag)) continue;

                if (parsed.tag == 0x2F) {
                    if (!settings.dropAirOnlySubChunks() || !isAirOnlySubChunk(entry.getValue())) {
                        mark(contentColumns, parsed);
                    } else {
                        airOnlyCandidates.add(parsed.column);
                    }
                } else if (entry.getValue().length > 0) {
                    // An empty record carries nothing; the exporter writes these for every
                    // column whether or not anything is in them.
                    mark(contentColumns, parsed);
                }
            }
        }

        long columnsScanned = allColumns.values().stream().mapToLong(Set::size).sum();
        long columnsKept = contentColumns.values().stream().mapToLong(Set::size).sum();
        long columnsRemoved = columnsScanned - columnsKept;
        long airOnlyRemoved = airOnlyCandidates.stream()
                .filter(c -> !contentColumns.getOrDefault(0, Set.of()).contains(c)).count();

        // ---- pass two: copy everything that survives into a fresh database ----
        // A dry run makes the same pass and counts the same records, it just writes
        // nothing. A report that cannot say how much it would remove is not a report.
        Path destinationDb = null;
        if (!settings.dryRun()) {
            Files.createDirectories(destinationWorld);
            copyWorldFilesExceptDatabase(sourceWorld, destinationWorld);
            destinationDb = destinationWorld.resolve("db");
            if (Files.exists(destinationDb)) deleteRecursively(destinationDb);
            Files.createDirectories(destinationDb);
        }

        Map<String, Long> removedByTag = new TreeMap<>();
        long keysRemoved = 0, bytesRemoved = 0, worldRecords = 0, written = 0;

        try (DB source = open(sourceDb, false);
             DB destination = settings.dryRun() ? null : open(destinationDb, true);
             DBIterator it = source.iterator()) {
            WriteBatch batch = destination == null ? null : destination.createWriteBatch();
            int batched = 0;
            try {
                for (it.seekToFirst(); it.hasNext(); it.next()) {
                    Map.Entry<byte[], byte[]> entry = it.peekNext();
                    byte[] key = entry.getKey();
                    byte[] value = entry.getValue();

                    ChunkKey parsed = parse(key);
                    boolean keep;
                    if (parsed == null) {
                        // Not a chunk key: a scoreboard, a village, a map, a player, a
                        // ticking area, a structure template. Never ours to judge.
                        keep = true;
                        worldRecords++;
                    } else {
                        keep = contentColumns.getOrDefault(parsed.dimension, Set.of())
                                .contains(parsed.column);
                        if (!keep) {
                            keysRemoved++;
                            bytesRemoved += value.length;
                            removedByTag.merge(parsed.actorDigest ? "digp" : tagName(parsed.tag), 1L, Long::sum);
                        }
                    }
                    if (!keep) continue;

                    written++;
                    if (batch == null) continue;
                    batch.put(key, value);
                    if (++batched >= 4096) {
                        destination.write(batch);
                        batch.close();
                        batch = destination.createWriteBatch();
                        batched = 0;
                        if (progress != null) progress.accept(written);
                    }
                }
                if (batch != null) destination.write(batch);
            } finally {
                if (batch != null) batch.close();
            }
        }

        if (!settings.dryRun()) {
            // Everything written so far sits in the write-ahead log, which is uncompressed
            // and as large as the data that went through it. Reopening runs LevelDB's
            // ordinary recovery, which folds the log into tables with the world's own
            // compression and deletes it. Minecraft would do this on first load anyway;
            // doing it here means the world we hand over is a tenth of the size and the
            // first load is not the slow one.
            try (DB reopened = open(destinationDb, false)) {
                assert reopened != null;
            } catch (Throwable e) {
                notes.add("The cleaned world is correct but its write-ahead log could not be "
                        + "folded (" + e + "); it will be larger than necessary until first load.");
            }
        }

        if (columnsRemoved > 0 && columnsKept == 0) {
            notes.add("Every column in this world was empty. Check that you meant to clean it.");
        }
        return new Report(keysScanned, columnsScanned, columnsKept, columnsRemoved,
                keysRemoved, bytesRemoved, airOnlyRemoved, worldRecords, removedByTag, notes);
    }

    // ------------------------------------------------------------------

    private static void mark(Map<Integer, Set<Long>> content, ChunkKey key) {
        content.computeIfAbsent(key.dimension, d -> new HashSet<>()).add(key.column);
    }

    private static DB open(Path directory, boolean create) throws IOException {
        Options options = new Options();
        // Must match what Bedrock writes, or the output is not readable as a world.
        options.compressionType(CompressionType.ZLIB_RAW);
        options.blockSize(160 * 1024);
        options.filterPolicy(new BloomFilterPolicy(10));
        options.writeBufferSize(64 * 1024 * 1024);
        options.createIfMissing(create);
        options.errorIfExists(false);
        return new Iq80DBFactory().open(directory.toFile(), options);
    }

    /** level.dat, levelname.txt, world_icon.jpeg, behaviour and resource packs, and so on. */
    private static void copyWorldFilesExceptDatabase(Path source, Path destination) throws IOException {
        try (Stream<Path> entries = Files.list(source)) {
            for (Path path : entries.toList()) {
                if (path.getFileName().toString().equals("db")) continue;
                Path target = destination.resolve(path.getFileName().toString());
                if (Files.isDirectory(path)) {
                    copyTree(path, target);
                } else {
                    Files.copy(path, target, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    private static void copyTree(Path source, Path destination) throws IOException {
        try (Stream<Path> walk = Files.walk(source)) {
            for (Path path : walk.toList()) {
                Path target = destination.resolve(source.relativize(path).toString());
                if (Files.isDirectory(path)) Files.createDirectories(target);
                else {
                    Files.createDirectories(target.getParent());
                    Files.copy(path, target, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    private static void deleteRecursively(Path path) throws IOException {
        if (!Files.exists(path)) return;
        try (Stream<Path> walk = Files.walk(path)) {
            for (Path entry : walk.sorted((a, b) -> b.getNameCount() - a.getNameCount()).toList()) {
                Files.deleteIfExists(entry);
            }
        }
    }

    /** A parsed chunk key: which dimension, which column, which record. */
    private record ChunkKey(int dimension, long column, int tag, boolean actorDigest) {
    }

    /**
     * Recognise a chunk key, or return null for anything else.
     *
     * <p>Length alone is not enough: {@code BiomeData}, {@code Overworld} and
     * {@code mVillages} are all nine ASCII bytes, exactly the length of an overworld chunk
     * key. The tag byte is what separates them, so a key only counts as a chunk key when
     * that byte is a tag Bedrock actually uses.
     */
    static ChunkKey parse(byte[] key) {
        int n = key.length;
        if (n == 9 || n == 10) {
            int tag = key[8] & 0xFF;
            if (!CHUNK_TAGS.contains(tag)) return null;
            if (n == 10 && tag != 0x2F) return null;   // only sub-chunks carry an index byte
            if (n == 9 && tag == 0x2F) return null;    // ...and they always carry one
            return new ChunkKey(0, column(key, 0), tag, false);
        }
        if (n == 13 || n == 14) {
            int tag = key[12] & 0xFF;
            if (!CHUNK_TAGS.contains(tag)) return null;
            if (n == 14 && tag != 0x2F) return null;
            if (n == 13 && tag == 0x2F) return null;
            int dimension = le32(key, 8);
            if (dimension != 1 && dimension != 2) return null; // nether and end only
            return new ChunkKey(dimension, column(key, 0), tag, false);
        }
        if (n == 12 && startsWith(key, DIGP)) return new ChunkKey(0, column(key, 4), -1, true);
        if (n == 16 && startsWith(key, DIGP)) {
            int dimension = le32(key, 12);
            if (dimension != 1 && dimension != 2) return null;
            return new ChunkKey(dimension, column(key, 4), -1, true);
        }
        // actorprefix keys are addressed by entity id, not by column. They are only
        // reachable through a non-empty digp, which keeps its column, so they are never
        // orphaned by this cleaner. Preserved as world records.
        if (startsWith(key, ACTOR_PREFIX)) return null;
        return null;
    }

    private static long column(byte[] key, int offset) {
        return ((long) le32(key, offset) << 32) | (le32(key, offset + 4) & 0xFFFFFFFFL);
    }

    private static int le32(byte[] b, int off) {
        return (b[off] & 0xFF) | ((b[off + 1] & 0xFF) << 8)
                | ((b[off + 2] & 0xFF) << 16) | ((b[off + 3] & 0xFF) << 24);
    }

    private static boolean startsWith(byte[] key, byte[] prefix) {
        if (key.length < prefix.length) return false;
        for (int i = 0; i < prefix.length; i++) if (key[i] != prefix[i]) return false;
        return true;
    }

    /**
     * Is this sub-chunk nothing but air?
     *
     * <p>Deliberately narrow. A sub-chunk counts as empty only in the one shape that is
     * unambiguous: a single block storage, zero bits per block - so every position indexes
     * the same palette entry - a palette of exactly one entry, and that entry naming
     * {@code minecraft:air}. Any other shape, any unexpected version, any parse that runs
     * short, and the answer is no. Being wrong here deletes someone's build.
     */
    static boolean isAirOnlySubChunk(byte[] value) {
        if (value.length < 3) return false;
        int offset = 0;
        int version = value[offset++] & 0xFF;
        int storages;
        if (version == 1) {
            storages = 1;
        } else if (version == 8) {
            storages = value[offset++] & 0xFF;
        } else if (version == 9) {
            storages = value[offset++] & 0xFF;
            offset++; // sub-chunk Y index
        } else {
            return false;
        }
        if (storages != 1) return false;      // a water layer means it is not empty
        if (offset >= value.length) return false;

        int header = value[offset++] & 0xFF;
        int bitsPerBlock = header >> 1;
        if (bitsPerBlock != 0) return false;  // more than one distinct block present
        // bitsPerBlock 0 stores no indices at all; the palette count follows immediately.
        if (offset + 4 > value.length) return false;
        int paletteCount = le32(value, offset);
        offset += 4;
        if (paletteCount != 1) return false;

        // The remainder is one little endian NBT compound. Rather than parse it, require
        // that it names air and nothing else of block length - a palette entry for any
        // other block cannot contain this string.
        byte[] tail = new byte[value.length - offset];
        System.arraycopy(value, offset, tail, 0, tail.length);
        return indexOf(tail, AIR) >= 0;
    }

    private static int indexOf(byte[] haystack, byte[] needle) {
        outer:
        for (int i = 0; i + needle.length <= haystack.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) continue outer;
            }
            return i;
        }
        return -1;
    }

    static String tagName(int tag) {
        return switch (tag) {
            case 0x2B -> "Data3D";
            case 0x2C -> "Version";
            case 0x2D -> "Data2D";
            case 0x2E -> "Data2DLegacy";
            case 0x2F -> "SubChunk";
            case 0x30 -> "LegacyTerrain";
            case 0x31 -> "BlockEntity";
            case 0x32 -> "Entity";
            case 0x33 -> "PendingTicks";
            case 0x34 -> "LegacyBlockExtraData";
            case 0x35 -> "BiomeState";
            case 0x36 -> "FinalizedState";
            case 0x37 -> "ConversionData";
            case 0x38 -> "BorderBlocks";
            case 0x39 -> "HardcodedSpawners";
            case 0x3A -> "RandomTicks";
            case 0x3B -> "CheckSums";
            case 0x3C -> "GenerationSeed";
            case 0x3D -> "PreCavesCliffsBlending";
            case 0x3E -> "BlendingBiomeHeight";
            case 0x3F -> "MetaDataHash";
            case 0x40 -> "BlendingData";
            case 0x41 -> "ActorDigestVersion";
            case 0x76 -> "LegacyVersion";
            default -> String.format("0x%02X", tag);
        };
    }
}
