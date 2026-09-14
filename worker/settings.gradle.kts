rootProject.name = "chunkdaddy-worker"

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}

// Chunker is vendored as a pinned git submodule at <repo>/third_party/chunker.
// We compile its `cli` source set directly instead of running Chunker's own Gradle
// build, so that ChunkDaddy does not inherit Chunker's plugin chain (shadow,
// git-version, jpackage, node). The pinned commit is recorded in
// docs/TargetProfiles.md and enforced by :chunker:verifyChunkerPin.
include("chunker")
