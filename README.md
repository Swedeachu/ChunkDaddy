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

## What is and is not established

The schematic decoding, layout arithmetic, instance identity rules and the generated-void
contract are implemented and covered by tests. **No exported world has been loaded by a
Bedrock Dedicated Server, a vanilla client or Tungsten yet.** A conversion that finishes
without errors is not evidence of compatibility. Every target profile records what has
actually been demonstrated, the application shows that state rather than implying more,
and [`docs/TargetProfiles.md`](docs/TargetProfiles.md) holds the acceptance procedure that
has to be run before a world built with this tool is deployed.

Project saving (`.chunkdaddy` files) is specified in the design guide but not implemented
in this build. Exporting writes a manifest that records template hashes, instance
identities and the grid layout, which is enough to recover that information from an
exported world.
