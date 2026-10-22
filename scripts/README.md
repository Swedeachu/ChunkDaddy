# Setup and development builds

Use these same launchers for a fresh checkout and every subsequent source change.
They install missing build dependencies, fetch the pinned Chunker source, build and
test the worker and desktop app, and bundle the required runtime files. Existing
downloads and build outputs are reused for incremental builds.

Run from this folder:

| Platform | Setup, build and test | Also launch the app |
| --- | --- | --- |
| Windows x64 | Double-click `build-windows.bat` | `build-windows.bat -Run` |
| Linux | `bash build-linux.sh` | `bash build-linux.sh --run` |

## Release archives

When you are ready to publish, these build and then write exactly one archive per
platform. That file is the entire GitHub release asset; nothing else needs uploading.

| Platform | Command | Produces |
| --- | --- | --- |
| Windows x64 | Double-click `release-windows.bat` | `../dist/ChunkDaddy-<version>-windows-x64.zip` |
| Linux | `bash release-linux.sh` | `../dist/ChunkDaddy-<version>-linux-<arch>.zip` |

The archive holds the app, the conversion worker, a bundled Java runtime, the Qt runtime
and the licences, and nothing from the build tree. The version comes from the `project()`
declaration in `CMakeLists.txt`; pass `--version 1.2.0` to override it for a one-off.
Linux bundles Qt when it can and falls back to requiring system Qt if that bundle does
not verify, which the archive's `README.txt` then says. `--system-qt` forces the fallback.

The launchers find the repository relative to their own location, so the current
working directory does not matter. Windows needs no preinstalled Python or developer
terminal. Missing Visual Studio tools can require administrator approval; Linux
system dependencies can require sudo. First setup requires internet access.

Windows produces `../build/windows-release/chunkdaddy.exe`; Linux produces
`../build/linux-release/chunkdaddy`. Close a running Windows build before rebuilding it,
or pass `-BuildDir build/windows-dev` to the batch launcher to keep that session open.
Build directories passed to these launchers are relative to the repository root.

`setup-windows.ps1` handles Windows dependencies and compiler setup. `build-linux.sh`
handles Linux dependencies. Both invoke the shared `build.py` orchestration.
If Python is already installed, `python build.py --run` works too (`python3` on Linux)
and automatically invokes the appropriate setup first.

See [the build guide](../docs/BuildAndRun.md) for supported systems and troubleshooting.

## Terrain leveling

For terrain analysis, rigid group selection and reviewed leveling, see
[Terrain leveling](../docs/TerrainLeveling.md). `terrain_level.py` imports/exports through
the worker, `terrain_plan.py` authors grouped displacement fields, and `terrain_verify.py`
checks the edited document against its reopened export.

## Map zoo exports

`build_map_zoo.py --source Maps.zip --workspace <folder> --destination <savedWorlds>/MAP_ZOO.zip --publish`
uses the CLI worker to survey and combine each nested `.mcworld` once, in a six-column
chunk-aligned grid with at least ten empty chunks between footprints. `--columns` and
`--gap-chunks` configure the layout. It preserves map heights, translates centers into a
parseable `MAP_ZOO.json`, verifies every exported region, and includes the same JSON inside
the ZIP. Terrain bounds ignore empty generated columns; bundled packs and ordinary entities
are excluded. Use a fresh workspace, or pass `--inventory` from a previous survey of the
identical source archive to reuse its bounds. The standalone MapZooViewer sibling project
can browse these destinations without changes to any gameplay plugin.

For a directory of `.mcworld` files, use `--source <directory> --world PEVENT`
and `--destination <savedWorlds>/PEVENT.zip`. `--category` assigns a category to
all inputs, and `--title-case` cleans up lowercase display names. For example:

```powershell
python scripts/build_map_zoo.py `
  --source 'C:/Users/camde/Downloads/temp/hive java/bedrock' `
  --world PEVENT --category 'Hive Maps' --title-case `
  --columns 5 --gap-chunks 10 --workspace local/pevent `
  --destination ../SwimCorePvP/TungstenServerRelease/savedWorlds/PEVENT.zip --publish
```

Every output ZIP contains Chunk Daddy's canonical `regions.json`. Publishing also
writes `<world>.regions.json` beside the ZIP, byte-for-byte identical to that manifest,
and `<world>.json` with the flattened, translated teleport coordinates. The viewer's
world picker reads the canonical template metadata and adds each instance offset.

## Block replacement

`replace_world_blocks.py` applies a JSON recipe through the worker, exports a separate world, reopens it, and audits every voxel and block entity position. It requires `terrain-requirements.txt`. See [Block replacement and erosion](../docs/BlockReplacement.md) and `examples/terrain/build73-polish.json` for coherent material splotches and iterative water shaving.
