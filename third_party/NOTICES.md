# Third-party notices

## Chunker

<https://github.com/HiveGamesOSS/Chunker>, MIT licence.

Fetched by setup as a Git checkout at `third_party/chunker`, pinned to
`31c91a92bd2dda746f3e41189b603fcfd1727f04`. ChunkDaddy compiles the sources under
`cli/src/main` as a library and does not run Chunker's own build. Its licence text ships
with the checkout; include it in any distributed package.

Chunker in turn depends on, among others, picocli, JetBrains annotations, fastutil,
Caffeine, Guava, Gson, lz4-java and `com.hivemc.leveldb` (a Bedrock-compatible LevelDB).
Those dependency versions are mirrored in `worker/chunker/build.gradle.kts` and must be
re-checked whenever the pinned revision is bumped. Review each dependency's licence before
distributing binaries.

## Qt 6

<https://www.qt.io>, LGPLv3 or a commercial licence.

ChunkDaddy links Qt 6 Widgets, Gui, Core and Test dynamically. Distributing a package that
bundles Qt under the LGPL carries obligations, including providing the means to relink
against a modified Qt. Check the terms that apply to your distribution before shipping.

## Bundled Java runtime

A packaged ChunkDaddy includes a Java runtime so that users do not have to install one.
Whichever build you bundle (for example Eclipse Temurin, GPLv2 with Classpath Exception),
ship its licence and notice files inside the package.

## Specifications read, not embedded

The Sponge Schematic Specification and WorldEdit's Sponge v3 reader and writer were read
to implement ChunkDaddy's own schematic adapter. WorldEdit is GPL-licensed; no WorldEdit
code is included in this repository. Reading a specification does not require embedding an
implementation of it, and none was copied.

## Supplied world assets

`PVP_ZONE_FFA.mcworld`, `PVP_ZONE_15_ARENAS` and the schematics in it are production
fixtures belonging to their authors. They are not part of this repository and are excluded
by `.gitignore`. Do not commit them.
