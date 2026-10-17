# SketchRip Ground-Truth Calibration: Kit PvP Map + Spawn (4 Themes)

The supplied GLB was compared directly against the supplied Bedrock world (`build73vbedrock.mcworld`) using its LevelDB subchunk palettes. The goal of this mode is **objective, repeatable, block-by-block validation**. It never declares a geometric match to be an exact Minecraft state match.

## Verified findings

- Reference world: **729,739 non-air blocks**, **212 palette entries (including air)**, 358 nonempty chunk columns.
- Source GLB: **1,209,410 triangles**, 86 mesh geometries, recovered unit `0.00645162156` (~1/155).
- Exact coordinate registration: schematic local `(0,0,0)` corresponds to reference world `(-167,51,-167)`.
- Without calibration: **290,718** reconstructed positions, **99.997%** of them overlapping reference non-air, **25.71%** correct base material on overlapping positions.
- With supervised tile calibration and geometry inference: **288,691** positions, **99.997%** correct occupancy of produced positions, **95.45%** correct base material on recovered positions. This covers **39.56%** of all non-air ground-truth blocks. The other ~60.4% are not reconstructed by the current visible-surface algorithm.
- Spatially held-out tile calibration: **99.27%** correct base material on 72,269 covered samples, **94.20%** coverage of 76,715 held-out samples. Ambiguous tiles are not forced into the learned map.
- Geometry shape family recall on ground-truth positions exposed to the importer: stairs 923/1,972, slabs 643/2,310, fences 53/957, walls 0/757, panes 1,449/1,985. Stair facing is correct in **321/923 (34.78%)** of recognized stairs; stair top/bottom half is correct in 923/923. **Do not claim exact state reconstruction.**

The learned atlas is a **model-specific calibration** using the supplied ground truth. It does not prove the same mappings generalize to arbitrary GLBs. The ground-truth world is used to supervise tile identification; the resulting reconstruction never copies unseen block positions from that world.

## Install

Unzip the patch into the `srip` branch root. The patch is Python-only; it does not replace ChunkDaddy's Java worker or C++ executable.

## Export the best measured version

From the ChunkDaddy root in PowerShell, set `$glb` to the path of `KIT_PVP_MAP_SPAWN_4_THEMES.glb` extracted from the supplied ZIP. Then run:

```powershell
python scripts/chunkdaddy_cli.py --sketchrip "$glb" --texture-catalog model --texture-matcher fast --material-policy logical --atlas-map "assets/sketchrip/kitpvp-4themes-calibrated-atlas.json" --infer-states --precision-shapes --allow-uncertain --profile bedrock-1.26.0 --output "$HOME\Downloads\KitPVP-Calibrated.mcworld"
```

This calls the normal ChunkDaddy Java worker to create the `.mcworld`. Its actual client-side appearance must still be checked; this sandbox validated the GLB-to-schematic stage and was not equipped with the built Windows/Java worker.

For a fast conversion test, import `fixtures/kitpvp/KITPVP-calibrated-surface.schem` directly into ChunkDaddy instead of reconstructing the GLB.

## Repeat the calibration on your machine

First create a baseline schematic, without the worker:

```powershell
python scripts/chunkdaddy_cli.py --sketchrip "$glb" --texture-catalog model --texture-matcher fast --allow-uncertain --dry-run --output "$HOME\Downloads\KitPVP-Baseline.mcworld"
```

Calibrate using your actual world file:

```powershell
python scripts/sketchrip_truth_calibration.py "$glb" --world "$HOME\Downloads\build73vbedrock.mcworld" --baseline-schematic "$HOME\Downloads\KitPVP-Baseline.schem" --out "$HOME\Downloads\KitPVP-Atlas.json"
```

Finally re-run SketchRip with `--atlas-map "$HOME\Downloads\KitPVP-Atlas.json"` and compare it:

```powershell
python scripts/sketchrip_truth_compare.py --world "$HOME\Downloads\build73vbedrock.mcworld" --schematic "$HOME\Downloads\KitPVP-Calibrated.schem" --report "$HOME\Downloads\KitPVP-Accuracy.json"
```

## Unresolved issues

1. **Hidden-block reconstruction:** 439,000+ reference blocks are not reconstructed from visible surface triangles. Some blocks have no exported faces and therefore have no recoverable identity without assumptions or the original world.
2. **Stair orientation:** correct on 34.78% of recognized stairs. The current neighbor topology solver is not ready for exact reconstruction of all corner shapes.
3. **Partial families:** slabs, walls, fences, buttons, layers, and carpet need per-voxel subgeometry recovery; texture identity alone cannot distinguish them from their parent blocks.
4. **Atlas ambiguity:** some tiles are truly shared by multiple materials, such as `grass_block` and `green_concrete_powder` in this Mineways atlas. They require surrounding geometry/context; the supervised atlas learner abstains when training labels conflict.
5. **DataVersion and Bedrock states:** ensure the Java 26.2 schematic DataVersion and target Bedrock profile are valid for the installed Chunker worker, especially for 1.26.0 corner states.

## Safety regression addressed

Previous versions loaded `ffa-arena-atlas-overrides.json` on every import, regardless of GLB source. This made scene-specific mappings leak across projects. The patched importer applies those implicit overrides only when the complete atlas RGB hash matches the verified FFA atlas. Explicit `--atlas-map` always remains authoritative.
