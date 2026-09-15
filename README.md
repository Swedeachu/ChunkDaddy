# ChunkDaddy

Bedrock world composer and duel arena grid editor.

ChunkDaddy combines selected chunk regions from Minecraft worlds into a new Bedrock world,
places configurable numbers of `.schem` arenas into a chunk-spaced grid, and exports both
the playable world and a JSON mapping from arena name to its two duel spawn positions.

The full specification is [`docs/ChunkDaddyDesignGuide.md`](docs/ChunkDaddyDesignGuide.md).
This README is the short version of how the code is arranged and how to build it.

## Shape of the project

| Part | Language | Owns |
| --- | --- | --- |
| Desktop application | C++20 + Qt 6 Widgets | Window, tabs, viewport, selection, layout arithmetic, document commands |
| Format worker | Java 21, built on a pinned Chunker | Decoded block data, Java to Bedrock conversion, block entities, LevelDB writing |

The split is deliberate. Reimplementing current Java-to-Bedrock block mapping in C++ would
be the largest maintenance burden in the project, so Chunker owns it; the native side owns
everything that has to feel immediate. They talk over JSON Lines on stdio, and large
payloads (preview tiles, worlds) travel as files in a job workspace rather than through the
protocol.

**There is exactly one owner of block data: the worker.** The C++ side submits commands
such as "translate this selection by these chunks" and receives a new revision plus the
bounds that changed. It never builds a second world representation.

## Repository layout

```
CMakeLists.txt, CMakePresets.json   native build
src/app/                            main window, menus, tabs, dialogs, settings
src/view/                           tiled top-down viewport and tile cache
src/document/                       documents, selection, clipboard, workspace, history
src/layout/                         checked geometry, grid planner, instance naming
src/worker/                         worker process client and protocol
worker/                             Gradle module: the Java format worker
worker/chunker/                     builds the pinned Chunker sources as a library
tests/                              C++ unit tests (ctest)
third_party/chunker/                downloaded checkout, pinned revision
docs/                               design guide, build notes, target profile matrix
packaging/                          Windows and Linux distribution definitions
```

## Building

**Windows:** double-click `scripts\build-windows.bat` (or run `scripts\build-windows.bat -Run` to launch afterwards).

**Linux:** run `bash scripts/build-linux.sh` (add `--run` to launch afterwards).

Use the same command after source changes. With Python installed, `python scripts/build.py
--run` also performs setup, an incremental build, tests and launch (use `python3` on Linux).
Direct invocation initializes the Windows compiler and SDK environment automatically.
Use `--build-dir build/windows-dev` for a separate build while another app session is open.

Setup fetches the pinned Chunker sources, installs the build dependencies, builds and tests
both halves, and stages the worker, Java runtime and Windows Qt runtime next to the app.
Linux uses system Qt packages and may ask for sudo; Windows may request administrator
approval if Visual Studio C++ Build Tools are missing. Git for Windows is required.

Launch `build/windows-release/chunkdaddy.exe` or `build/linux-release/chunkdaddy`.
See [`docs/BuildAndRun.md`](docs/BuildAndRun.md) for prerequisites, manual builds and troubleshooting.

## Releasing

Double-click `scripts\release-windows.bat`, or run `bash scripts/release-linux.sh`. Each
builds and then writes exactly one archive into `dist/`, and that file is the whole GitHub
release asset for its platform: the app, the conversion worker, a bundled Java runtime, the
Qt runtime and the licences, with nothing from the build tree.

## Compatibility

Exports load in the vanilla Bedrock client and in Bedrock Dedicated Server. The one thing
to get right is the **target profile**, which decides the version stamped into `level.dat`.

Bedrock writes `MinimumCompatibleClientVersion` into every world. A client older than that
value refuses to open it with *"a newer version of the game saved this world"* - the world
is fine, the client is simply older than the profile it was written for. So pick a profile
at or below the oldest client and server build you intend to load it with, not the newest
profile available. The export dialog lets you change it and re-export without rebuilding
the composition. [`docs/TargetProfiles.md`](docs/TargetProfiles.md) lists what each profile
stamps.

Project saving (`.chunkdaddy` files) is specified in the design guide but not implemented
in this build. Exporting writes a manifest that records template hashes, instance
identities and the grid layout, which is enough to recover that information from an
exported world.

## Licence

ChunkDaddy is MIT licensed; see [`LICENSE`](LICENSE). It builds Chunker (MIT) into its
conversion worker and links Qt 6 (LGPLv3) dynamically. What has to ship with a binary
build is spelled out in [`third_party/NOTICES.md`](third_party/NOTICES.md), and the release
scripts put all of it in the archive's `LICENSES/` folder.
