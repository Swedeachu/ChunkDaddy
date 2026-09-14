package gg.swim.chunkdaddy.worker.conversion;

import com.hivemc.chunker.conversion.WorldConverter;
import com.hivemc.chunker.conversion.encoding.base.Version;
import com.hivemc.chunker.conversion.encoding.java.JavaDataVersion;
import com.hivemc.chunker.conversion.encoding.java.JavaEncoders;
import com.hivemc.chunker.conversion.encoding.java.base.reader.JavaLevelReader;
import com.hivemc.chunker.conversion.encoding.java.base.resolver.JavaResolvers;

import java.io.File;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Builds Chunker's Java-side resolvers for a given Java {@code DataVersion}.
 *
 * <p>Schematics are not worlds, so there is no world directory to read; but the Java
 * block state, block entity and biome resolvers live on the Java level reader and are
 * selected by version. We construct the nearest version's reader against a throwaway
 * directory purely to obtain its resolver set.
 *
 * <p>Sponge format version and Java {@code DataVersion} answer different questions: one
 * picks the container schema, the other picks how block states are interpreted. Only the
 * latter is used here.
 */
public final class ResolverFactory {
    private final WorldConverter converter;
    private final File scratchDirectory;
    private final Map<Integer, JavaResolvers> cache = new ConcurrentHashMap<>();

    public ResolverFactory(WorldConverter converter, File scratchDirectory) {
        this.converter = converter;
        this.scratchDirectory = scratchDirectory;
    }

    /**
     * Resolve the Java resolvers for a data version.
     *
     * @param dataVersion the {@code DataVersion} declared by the schematic, or 0/absent
     *                    for Sponge v1 files that predate the field.
     */
    public JavaResolvers forDataVersion(int dataVersion) {
        return cache.computeIfAbsent(dataVersion, this::build);
    }

    /** The Java version Chunker will actually interpret a data version as. */
    public Version resolvedVersion(int dataVersion) {
        return nearest(dataVersion).getVersion();
    }

    private JavaResolvers build(int dataVersion) {
        JavaLevelReader reader = nearest(dataVersion);
        // buildResolvers is a public default method on JavaReaderWriter, so the resolver
        // set can be obtained without running a conversion.
        return reader.buildResolvers(converter).build();
    }

    private JavaLevelReader nearest(int dataVersion) {
        JavaDataVersion version = dataVersion > 0
                ? JavaDataVersion.getNearestVersion(dataVersion)
                : JavaDataVersion.oldest();
        JavaEncoders.JavaEncoder encoder = JavaEncoders.getNearestEncoder(version);
        if (encoder == null) {
            throw new IllegalStateException("No Chunker encoder for Java data version " + dataVersion);
        }
        return encoder.readerConstructor().construct(scratchDirectory, version.getVersion(), converter);
    }
}
