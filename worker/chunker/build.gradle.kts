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

sourceSets {
    named("main") {
        java.setSrcDirs(listOf(chunkerCli.resolve("src/main/java")))
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

tasks.named("compileJava") { dependsOn(verifyChunkerPin) }
