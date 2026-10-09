// Compiles the pinned Chunker `cli` sources as a plain java-library.
//
// Chunker is MIT licensed (see third_party/NOTICES.md). We deliberately do not
// invoke Chunker's own build: it depends on the Gradle plugin portal (shadow,
// git-version, jpackage) and a node toolchain for its Electron app, none of
// which ChunkDaddy needs. Dependency versions below mirror
// third_party/chunker/gradle/libs.versions.toml at the pinned commit; if you
// bump the submodule you must re-check them.

plugins {
    `java-library`
}

val chunkerRoot = rootProject.projectDir.parentFile.resolve("third_party/chunker")
val chunkerCli = chunkerRoot.resolve("cli")

// Keep the vendored checkout at its verified pin. Small, checked replacements live
// here and generate only the optimized classes; upgrades fail if an anchor changed.
val patchedSources = layout.buildDirectory.dir("generated/patched-chunker")
val readerPackage = "com/hivemc/chunker/conversion/encoding/bedrock/base/reader/"
val managerPath = "com/hivemc/chunker/conversion/handlers/pretransform/manager/PreTransformManager.java"
val patchedPaths = listOf(readerPackage + "BedrockColumnReader.java", readerPackage + "BedrockLevelReader.java", managerPath)
val worldReaderPath = readerPackage + "BedrockWorldReader.java"
val allPatchedPaths = patchedPaths + worldReaderPath
val preparePatchedChunker = tasks.register("preparePatchedChunker") {
    inputs.files(allPatchedPaths.map { chunkerCli.resolve("src/main/java/$it") })
    inputs.dir("patches")
    outputs.dir(patchedSources)
    doLast {
        fun replaceOnce(text: String, before: String, after: String): String {
            check(text.indexOf(before) >= 0 && text.indexOf(before) == text.lastIndexOf(before)) {
                "Pinned Chunker patch anchor changed: $before"
            }
            return text.replace(before, after)
        }
        for (path in allPatchedPaths) {
            var text = chunkerCli.resolve("src/main/java/$path").readText().replace("\r\n", "\n")
            if (path.endsWith("BedrockWorldReader.java")) {
                text = replaceOnce(text, "    public void readRegions(Map<RegionCoordPair, Set<ChunkCoordPair>> regions, ColumnConversionHandler columnConversionHandler) {",
                    "    public void readRegions(Map<RegionCoordPair, Set<ChunkCoordPair>> regions, ColumnConversionHandler columnConversionHandler) {\n" +
                    "        if (converter instanceof com.hivemc.chunker.conversion.ProgressiveReadPolicy policy) {\n" +
                    "            policy.indexed(dimension, regions);\n" +
                    "            readNextRegion(new java.util.ArrayList<>(regions.entrySet()), columnConversionHandler, policy);\n" +
                    "            return;\n        }\n")
                text = replaceOnce(text, "    public void readRegion(Map.Entry<RegionCoordPair, Set<ChunkCoordPair>> region, ColumnConversionHandler columnConversionHandler) {",
                    file("patches/readNextRegion.java.txt").readText().replace("\r\n", "\n") + "\n    public void readRegion(Map.Entry<RegionCoordPair, Set<ChunkCoordPair>> region, ColumnConversionHandler columnConversionHandler) {")
            } else if (path.endsWith("BedrockColumnReader.java")) {
                val start = text.indexOf("    protected void readChunks(ChunkerColumn column) {")
                val end = text.indexOf("    /**", start)
                check(start >= 0 && end > start) { "Pinned readChunks method changed" }
                text = text.substring(0, start) + file("patches/readChunks.java.txt").readText().replace("\r\n", "\n") + "\n" + text.substring(end)
            } else if (path.endsWith("BedrockLevelReader.java")) {
                text = replaceOnce(text, "try (DBIterator iterator = database.iterator()) {\n                while (iterator.hasNext()) {",
                    "try (DBIterator iterator = database.iterator()) {\n                while (!(converter instanceof com.hivemc.chunker.conversion.ProgressiveReadPolicy policy) || !policy.stopping()) {\n                    if (!iterator.hasNext()) break;")
                text = replaceOnce(text, "        readCustomDimensions();",
                    "        readCustomDimensions();\n        if (converter instanceof com.hivemc.chunker.conversion.ProgressiveReadPolicy policy) {\n" +
                    "            Task.async(\"Reading initial preview\", TaskWeight.HIGHER, () -> policy.preview(database, createWorldReader(Map.of(), Dimension.OVERWORLD)));\n        }\n")
                text = replaceOnce(text, "options.blockSize(160 * 1024); // 160KB",
                    "options.blockSize(160 * 1024); // 160KB\n        options.cacheSize(128L * 1024 * 1024); // Bounded shared cache for concurrent column reads")
            } else {
                val type = "com.hivemc.chunker.conversion.intermediate.column.chunk.identifier.type.block.ChunkerVanillaBlockType"
                text = replaceOnce(text, "    protected final Map<ChunkerBlockType, BlockPreTransformHandler> blockTypeToHandler",
                    "    private final BlockPreTransformHandler[] vanillaBlockHandlers = new BlockPreTransformHandler[$type.values().length];\n    protected final Map<ChunkerBlockType, BlockPreTransformHandler> blockTypeToHandler")
                // Register through one helper so the fast path stays current even for later registrations.
                text = text.replace("blockTypeToHandler.put(blockType, handler);", "registerBlockHandler(blockType, handler);")
                    .replace("blockTypeToHandler.put(blockType2, handler);", "registerBlockHandler(blockType2, handler);")
                val helper = """
    private void registerBlockHandler(ChunkerBlockType type, BlockPreTransformHandler handler) {
        blockTypeToHandler.put(type, handler);
        if (type instanceof $type vanilla) vanillaBlockHandlers[vanilla.ordinal()] = handler;
    }

"""
                text = replaceOnce(text, "    public void solve(ChunkerColumn column, boolean preTransformAllowed) {", helper + "    public void solve(ChunkerColumn column, boolean preTransformAllowed) {")
                text = replaceOnce(text, "BlockPreTransformHandler handler = blockTypeToHandler.get(blockIdentifier.getType());",
                    "BlockPreTransformHandler handler = blockIdentifier.getType() instanceof $type vanilla\n                                ? vanillaBlockHandlers[vanilla.ordinal()] : blockTypeToHandler.get(blockIdentifier.getType());")
            }
            patchedSources.get().file(path).asFile.apply { parentFile.mkdirs(); writeText(text) }
        }
    }
}

sourceSets {
    named("main") {
        java.setSrcDirs(listOf(chunkerCli.resolve("src/main/java")))
        val originals = allPatchedPaths.map { chunkerCli.resolve("src/main/java/$it") }.toSet()
        java.exclude { it.file in originals }
        java.srcDir(patchedSources)
        java.srcDir("src/main/java")
        resources.setSrcDirs(listOf(chunkerCli.resolve("src/main/resources")))
    }
    named("test") {
        java.setSrcDirs(emptyList<File>())
        resources.setSrcDirs(emptyList<File>())
    }
}

dependencies {
    api("info.picocli:picocli:4.7.7")
    api("org.jetbrains:annotations:26.1.0")
    api("it.unimi.dsi:fastutil:8.5.19")
    api("com.github.ben-manes.caffeine:caffeine:3.2.4")
    api("com.google.guava:guava:33.7.1-jre")
    api("com.google.code.gson:gson:2.14.0")
    api("net.jpountz.lz4:lz4:1.3.0")
    api("com.hivemc.leveldb:leveldb-api:1.1.0")
    api("com.hivemc.leveldb:leveldb:1.1.0")
}

java {
    toolchain { languageVersion.set(JavaLanguageVersion.of(21)) }
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
    options.isFork = true
    options.forkOptions.jvmArgs = (options.forkOptions.jvmArgs ?: mutableListOf()) + "-Xss4m"
    // Chunker's own build enables -Xlint:unchecked; we silence third-party noise
    // so that ChunkDaddy's warnings stay visible.
    options.compilerArgs.add("-nowarn")
}

val expectedPin = "31c91a92bd2dda746f3e41189b603fcfd1727f04"

val verifyChunkerPin = tasks.register("verifyChunkerPin") {
    group = "verification"
    description = "Fails if the vendored Chunker submodule is not at the pinned, tested commit."
    doLast {
        if (!chunkerCli.resolve("src/main/java").isDirectory) {
            throw GradleException(
                "third_party/chunker is empty. Run setup.cmd (Windows) or bash setup.sh (Linux)."
            )
        }
        val head = providers.exec {
            workingDir = chunkerRoot
            commandLine("git", "rev-parse", "HEAD")
        }.standardOutput.asText.get().trim()
        if (head != expectedPin) {
            throw GradleException(
                "Chunker submodule is at $head but ChunkDaddy is pinned to $expectedPin.\n" +
                    "Conversion output is only validated against the pinned commit. " +
                    "If the bump is intentional, update expectedPin here, update " +
                    "docs/TargetProfiles.md, and re-run the acceptance tests."
            )
        }
    }
}

tasks.named("compileJava") { dependsOn(verifyChunkerPin, preparePatchedChunker) }
