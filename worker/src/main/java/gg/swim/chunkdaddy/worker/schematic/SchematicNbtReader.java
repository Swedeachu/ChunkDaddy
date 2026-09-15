package gg.swim.chunkdaddy.worker.schematic;

import com.hivemc.chunker.nbt.TagType;
import com.hivemc.chunker.nbt.io.Reader;
import com.hivemc.chunker.nbt.tags.Tag;
import com.hivemc.chunker.nbt.tags.array.ByteArrayTag;
import com.hivemc.chunker.nbt.tags.collection.CompoundTag;
import com.hivemc.chunker.nbt.tags.collection.ListTag;

import java.io.*;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.zip.GZIPInputStream;

/** Schematic-sized byte arrays, using Chunker's tag model and primitive decoding.
 * Chunker's world NBT convenience reader limits byte arrays to 64 KiB, whereas
 * Sponge stores the whole schematic's block data in one multi-megabyte array.
 * These limits apply only here, without changing the pinned world reader.
 */
final class SchematicNbtReader {
    static final int MAX_BYTE_ARRAY = 256 * 1024 * 1024;
    private static final int MAX_ENTRIES = 1_000_000;

    static CompoundTag read(File file) throws IOException {
        try (BufferedInputStream source = new BufferedInputStream(Files.newInputStream(file.toPath()))) {
            source.mark(2);
            boolean gzip = source.read() == 0x1f && source.read() == 0x8b;
            source.reset();
            try (DataInputStream input = new DataInputStream(new BudgetInputStream(
                    gzip ? new GZIPInputStream(source) : source))) {
                if (input.readUnsignedByte() != 10) throw new IOException("Schematic root must be an NBT compound");
                input.readUTF(); // The named root may be "Schematic" or empty.
                return (CompoundTag) readTag(input, 10, 0);
            }
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static Tag<?> readTag(DataInputStream input, int type, int depth) throws IOException {
        if (depth > 128) throw new IOException("Schematic NBT is nested too deeply");
        if (type == 10) {
            CompoundTag compound = new CompoundTag();
            for (int count = 0; ; count++) {
                int child = input.readUnsignedByte();
                if (child == 0) return compound;
                if (count >= MAX_ENTRIES) throw new IOException("Too many schematic NBT entries");
                String name = input.readUTF();
                compound.put(name, readTag(input, child, depth + 1));
            }
        }
        if (type == 9) {
            int child = input.readUnsignedByte();
            int count = input.readInt();
            if (count < 0 || count > MAX_ENTRIES || (child == 0 && count != 0))
                throw new IOException("Invalid schematic NBT list length: " + count);
            TagType elementType = TagType.getById(child);
            ArrayList values = new ArrayList(Math.min(count, 1024));
            for (int i = 0; i < count; i++) values.add(readTag(input, child, depth + 1));
            return new ListTag(elementType, values);
        }
        if (type == 7) {
            int size = input.readInt();
            if (size < 0 || size > MAX_BYTE_ARRAY)
                throw new IOException("Schematic byte array exceeds the 256 MiB limit: " + size);
            byte[] bytes = new byte[size];
            input.readFully(bytes);
            return new ByteArrayTag(bytes);
        }
        if (type == 0) throw new IOException("Unexpected NBT end tag");
        Tag<?> tag = TagType.getById(type).getConstructor().get();
        tag.decodeValue(Reader.toJavaReader(input));
        return tag;
    }

    private static final class BudgetInputStream extends FilterInputStream {
        private long remaining = 512L * 1024 * 1024;
        BudgetInputStream(InputStream input) { super(input); }
        private void consumed(int count) throws IOException {
            if (count > 0 && (remaining -= count) < 0)
                throw new IOException("Schematic expanded NBT exceeds the 512 MiB limit");
        }
        @Override public int read() throws IOException {
            int value = in.read();
            consumed(value < 0 ? 0 : 1);
            return value;
        }
        @Override public int read(byte[] bytes, int offset, int length) throws IOException {
            int count = in.read(bytes, offset, (int) Math.min(length, remaining + 1));
            consumed(count);
            return count;
        }
    }
}
