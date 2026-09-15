# Third-party notices

ChunkDaddy itself is MIT licensed; see `../LICENSE`. This file covers the
components it builds on, and what has to travel with a binary distribution.

## Chunker — MIT

<https://github.com/HiveGamesOSS/Chunker>, Copyright (c) 2024 Hive Games.

ChunkDaddy compiles Chunker's `cli/src/main` sources into its conversion worker jar, so
every distributed build contains Chunker code. MIT requires the copyright notice and
permission text to travel with it: the verbatim licence is in
`third_party/chunker-LICENSE.txt`, and the packaging scripts copy it into `LICENSES/` next
to the executable. Do not ship a build without it.

The sources are pinned to `31c91a92bd2dda746f3e41189b603fcfd1727f04` and checked out under
`third_party/chunker`. ChunkDaddy does not run Chunker's own Gradle build; it compiles the
sources as a plain library, which keeps Chunker's plugin chain (shadow, git-version,
jpackage, its Electron app) out of the picture.

Chunker's own dependencies are pulled from Maven Central and end up in the shaded worker
jar: picocli (Apache-2.0), JetBrains annotations (Apache-2.0), fastutil (Apache-2.0),
Caffeine (Apache-2.0), Guava (Apache-2.0), Gson (Apache-2.0), lz4-java (Apache-2.0) and
`com.hivemc.leveldb` (a Bedrock-compatible LevelDB fork; check its POM for the effective
terms). Their versions are mirrored in `worker/chunker/build.gradle.kts` and must be
re-checked whenever the pin moves. Apache-2.0 requires its own notice file to be preserved
where the upstream jar ships one.

## Qt 6 — LGPLv3 or commercial

<https://www.qt.io>

ChunkDaddy links Qt 6 Core, Gui, Widgets and Test **dynamically**, which is what keeps an
MIT application compatible with the LGPL. Two obligations follow for a distributed build:
ship the LGPLv3 text, and keep the user able to replace the Qt libraries with their own
build. The dynamic link plus shipping Qt as separate DLLs/shared objects satisfies the
second. Do not statically link Qt into ChunkDaddy without either a commercial Qt licence or
relicensing ChunkDaddy accordingly.

## Bundled Java runtime

A packaged ChunkDaddy includes a `jlink` runtime so users do not install Java. Eclipse
Temurin and other OpenJDK builds are GPLv2 with the Classpath Exception, which is what lets
a proprietary or MIT application ship on top of them. Copy the runtime's own `legal/`
directory into the package; `jlink` produces it for you.

## Specifications read, not embedded

The Sponge Schematic Specification and WorldEdit's Sponge v3 reader and writer were read to
implement ChunkDaddy's own schematic adapter. WorldEdit is GPL licensed and **no WorldEdit
code is included here**. Reading a specification does not require embedding an
implementation of it, and none was copied.

## Supplied world assets

`PVP_ZONE_FFA.mcworld`, `PVP_ZONE_15_ARENAS` and its schematics belong to their authors.
They are production fixtures, are not part of this repository, and are excluded by
`.gitignore`. Do not commit them.
