# ChunkDaddy + SketchRip + sfrip — single drop-in overlay

**Extract this archive's contents into the root of an existing `Swedeachu/ChunkDaddy` source checkout.**
It adds a Sketchfab-to-GLB acquisition stage (`sfrip`), block-texture matching, voxel reconstruction, Sponge `.schem` writing, and calls to ChunkDaddy's Java Chunker worker for `.mcworld` export.

## Install once (Windows)

1. Commit or back up any edits to `scripts/chunkdaddy_cli.py` and `worker/.../TargetProfile.java` first: the drop-in replaces these two files.
2. Extract this ZIP into `ChunkDaddy/` so its `scripts/`, `worker/`, and `assets/` directories merge with your existing ones. **There should not be a `SketchRip/` parent folder.**
3. Install Python 3.10+ and Node.js 18+ if needed. You need Java 21 and ChunkDaddy's normal build prerequisites as well.
4. Double-click **`SKETCHRIP_SETUP.bat`**. It installs the Python libraries and locally installs `sfrip` into `.sketchrip-tools/` (no global npm writes). This setup requires Internet access. The third-party `sfrip` code is not included in this archive.
5. Build ChunkDaddy and its Java worker: `python scripts/build.py`. The native build does not install Python/Node dependencies; setup above is a separate one-time step.
6. Double-click **`SKETCHRIP_RUN.bat`**. Enter a Sketchfab model URL (or local `.glb`/`.gltf`/`.obj`). Output defaults to Downloads.

## Fixing missing dependencies / checking a Windows installation

The Python interpreter used for `python scripts/chunkdaddy_cli.py --sketchrip` must also have the dependencies installed. A native Qt/Java build succeeding does **not** install Python libraries. From the ChunkDaddy root:

```powershell
python scripts/chunkdaddy_cli.py --sketchrip --setup
python scripts/chunkdaddy_cli.py --sketchrip --doctor
python -m pytest -q scripts/test_sketchrip.py
```

`--setup` runs `scripts/sketchrip_setup.py` to install the Python dependencies with the current `sys.executable` and installs `sfrip` locally using npm. `--doctor` works with just the Python standard library, even if `numpy`/`trimesh` are missing; it checks local prerequisites but does not download a model. You can also double-click `SKETCHRIP_DOCTOR.bat`.

Run the command below for an initial **acquisition + reconstruction** smoke test that preserves the GLB and avoids claiming the world has exported:

```powershell
python scripts/chunkdaddy_cli.py --sketchrip "https://sketchfab.com/3d-models/ffa-arena-300x300-11baf8c705414ea8bf9f833a1b1866e4" --acquire sfrip --dry-run --keep-acquired "$HOME/Downloads/SketchRipAcquired"
```

If that completes, check `Downloads/SketchRipAcquired` for the acquired `.glb` and accompanying info file; inspect the `.sketchrip.json` report for voxel counts, uncertainty, block palette and face coverage. A generated schematic is **not** evidence that the imported world looks right; inspect it in-game before publishing.

## Command-line

```powershell
# One-command Sketchfab -> sfrip -> mesh analysis -> schematic -> ChunkDaddy -> mcworld
python scripts/chunkdaddy_cli.py --sketchrip "https://sketchfab.com/3d-models/ffa-arena-300x300-11baf8c705414ea8bf9f833a1b1866e4" --acquire sfrip --profile bedrock-1.26.0

# Other example (subject to the model's licence)
python scripts/chunkdaddy_cli.py --sketchrip "https://sketchfab.com/3d-models/4-biomes-kitpvp-map-039fa5b6b07c4a1f90219bb6f7b0b128" --acquire sfrip --output "$HOME/Downloads/4-biomes.mcworld"

# Local assets work even without Node/sfrip; test reconstruction without the Java worker
python scripts/chunkdaddy_cli.py --sketchrip fixtures/four-blocks.glb --dry-run

# Keep the original acquired GLB for independent inspection
python scripts/chunkdaddy_cli.py --sketchrip "SKETCHFAB_URL" --keep-acquired "C:/Models/Sketchfab"
```

### Useful options

- `--acquire auto|sfrip|official` — `auto` prefers installed `sfrip`, then official Sketchfab API when configured.
- `--sfrip-command PATH` — point to a custom `sfrip` CLI binary or `sfrip.mjs` script.
- `--sfrip-merge off|objects|groups|materials|all` — keep mesh segmentation, default `objects`.
- `--sfrip-max-edge 0|2048|4096` — texture resolution; `0` leaves original sizes.
- `--scale N` — explicit model units per Minecraft block if the inferred block grid is wrong.
- `--up-axis y|z` — orientation of the source asset (glTF typically Y-up).
- `--similarity 0.83` and `--allow-uncertain` — matching confidence controls; never auto-force uncertain samples.
- `--world-y 64` — chunk-aligned world placement; `--output ...mcworld` specifies destination.
- `--dry-run` — produce `.schem` and `.sketchrip.json` without Java/LevelDB export.
- `--textures /path/to/pack.zip` — override the bundled texture pack.
- `--keep-acquired PATH` — preserve GLB and acquisition manifest instead of deleting the temporary staging directory.

Use `python scripts/chunkdaddy_cli.py --sketchrip --help` for the full help text. The `--sketchrip` flag belongs to the **Python CLI**, not the native Qt `chunkdaddy.exe` executable.

## Architecture

```
Sketchfab URL
   -> sfrip (Node CLI; installed on first setup) -> GLB + info.json
   -> trimesh import (scene transforms applied)
   -> grid detection and voxel faces / merged-face rasterization
   -> image atlas tile samples -> cached vanilla texture descriptors
   -> exact-hash lookup or nearest-neighbor palette match
   -> {x,y,z -> Java block identifier} + confidence audit
   -> Sponge v3 .schem
   -> ChunkDaddy worker: new_document / import_schematics / place_grid / validate_export / export_world
   -> .mcworld + .sketchrip.json
```

Model rights: use this only for models you own, have licensed or have permission to extract and convert. Sketchfab store items and Creative Commons models retain their original license restrictions and attribution requirements. The `sfrip` project is MIT-licensed and is installed separately from https://github.com/Corpocore/sfrip (not vendored here).

## Validation & limitations

```powershell
python -m pytest -q scripts/test_sketchrip.py
python scripts/chunkdaddy_cli.py --sketchrip fixtures/four-blocks.glb --dry-run --output "C:/Temp/SketchRip-Smoke.mcworld"
```

**Locally tested:** 13 regression tests passed, including a 32×32 GLB with actual vanilla atlas textures, exact NBT validation, merged-face grid rasterization, subprocess acquisition via a controlled stand-in executable, and mocked worker orchestration. A separate 100×100 GLB (20,000 triangles) recovered 10,000 expected block types with zero mismatches in approximately 0.16 seconds of reconstruction on the test host. The output of that smoke command is `.schem` + `.sketchrip.json`, not `.mcworld`.

**Not yet tested:** Actual `sfrip` network extraction of the two Sketchfab examples, a complete Java/Chunker `.mcworld` export, or importing a resulting map into the Bedrock 1.26.0 game client. `sfrip` may fail after site changes, network restrictions, or source-model quirks. Attempts to fetch the actual FFA model in this test environment encountered HTTP 403 / inaccessible DNS, so no real Sketchfab GLB was obtained here. No claims are made about the two real maps. Its npm package must be available to the machine when setup runs.

**Reconstruction fidelity is limited:** This is a prototype and can misidentify stairs/slabs/fences, block rotations, plants, transparent/animated materials, merged UVs, or geometry without visible surfaces. Minecraft's hidden blocks and original tile entities cannot be reconstructed from the rendered mesh. Full cube surfaces and selected planar merged faces are supported; results require inspection before production use. `--scale` should be used when auto-scale picks the wrong lattice. A model with no usable textures is rejected, not silently remapped to stone. Exact Bedrock 1.26.0 compatibility is provisional until the worker and client are tested.

Do not mistake successful schematic generation for a verified, playable world.

## Files installed or replaced

```
ChunkDaddy/
├── README_SKETCHRIP_DROPIN.md                     [NEW]
├── SKETCHRIP_SETUP.bat                           [NEW]
├── SKETCHRIP_DOCTOR.bat                          [NEW]
├── SKETCHRIP_RUN.bat                             [NEW]
├── requirements-sketchrip.txt                    [NEW]
├── assets/sketchrip/vanilla-26.2.zip             [NEW, supplied reference pack]
├── scripts/chunkdaddy_cli.py                     [REPLACES]
├── scripts/sketchrip.py                          [NEW]
├── scripts/_sketchrip_doctor.py                  [NEW]
├── scripts/sketchrip_setup.py                    [NEW]
├── scripts/test_sketchrip.py                     [NEW]
├── worker/src/main/java/gg/swim/chunkdaddy/worker/bedrock/TargetProfile.java [REPLACES]
├── docs/SketchRip.md                             [NEW]
└── fixtures/four-blocks.glb + .schem + .sketchrip.json [NEW, small smoke fixtures]
```

No automatic Git push, PR, or edits to your remote repository are performed by this ZIP. `sfrip` and npm dependencies are fetched only when `SKETCHRIP_SETUP.bat` is explicitly run.
