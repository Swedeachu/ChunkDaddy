# SketchRip Stair Roof Footprint Fix (2026-10-15)

**Drop-in patch for the existing ChunkDaddy SketchRip Precision Shapes release.** Extract into the ChunkDaddy repository root and overwrite `scripts/sketchrip.py`. No C++ or Java rebuild is needed.

## Root cause

The prior stair rotation matchers assigned top-plane geometry at integer Y to the next voxel or excluded it as non-fractional, then tried to infer orientations from interior riser histograms. This was especially destructive in the repeated 4x4 recessed rings on the FFA Arena: the GLB's inner corners have **three occupied roof quadrants**, but SketchRip sometimes exported an **outer corner with only one**. Straight side stairs were also exported as outer corners.

## Fix

New `scripts/sketchrip_stair_roof.py` reads the actual visible horizontal surfaces at **Y+1 for bottom stairs** and **Y for top stair undersides**. It matches 2x2 footprint masks against the vanilla 26.2 block-model JSON and preserves a state if its rendered footprint already matches. It disregards triangle winding, checks that the entire source triangle lies in the voxel, skips occluded faces when a block is above/below, and abstains on incomplete, overlapping, merged, or missing roofs. It does not change material families or any non-stair blocks.

The roof pass runs **last** when `--precision-shapes` is enabled, so old histogram-based stair inference cannot overwrite its stronger observations. It also runs in `--stair-orientation-only` mode if `--precision-shapes` is supplied.

## Verified real FFA model

- Source GLB: 1,537,618 triangles.
- Source schematic used: `FFA-Precision-Shape-Experimental.schem` (the previous precision-shapes baseline, included in previous release).
- 8,231 stairs inspected; 2,062 orientation/shape changes from the roof pass.
- 2,061 already had matching exposed roof footprints.
- 2,075 underside candidates had a block directly below; 2,033 top-roof candidates had a block above. They were left unchanged.
- Preserved 351,198 occupied blocks and the 307 x 142 x 307 bounds.
- All 12 rim stair positions in the analyzed 4x4 recess at (70..73, Y=10, Z=96..99) have the correct vanilla-model high/low roof arrangement after refinement.
- These are mesh-supported visible geometry improvements, not proof of lossless recovery. In-game verification remains necessary.

## Commands (PowerShell, from repository root)

Fast stair-only retry against an existing schematic:

```powershell
python scripts/chunkdaddy_cli.py --sketchrip "$glb" --refine-schematic "$HOME\Downloads\FFA-Arena-Precision.schem" --stair-orientation-only --precision-shapes --profile bedrock-1.26.0 --output "$HOME\Downloads\FFA-Stairs-RoofFixed.mcworld"
```

Full GLB reconstruction:

```powershell
python scripts/chunkdaddy_cli.py --sketchrip "$glb" --texture-catalog model --texture-matcher fast --material-policy logical --infer-states --precision-shapes --allow-uncertain --profile bedrock-1.26.0 --output "$HOME\Downloads\FFA-Stairs-RoofFixed.mcworld"
```

The `--refine-schematic` command preserves the materials in the existing schematic. The full reconstruction reruns texture matching and the other shape detectors. Use `--dry-run` to write only the schematic and diagnostic JSON instead of an `.mcworld`.

## Tests

The patch was validated on the 1.54-million-triangle GLB and on known-answer fixtures: all 40 vanilla stair states, both halves, all four facings, straight/inner/outer corner variants, reversed winding, Z-up coordinates, occlusion, merged roofs, and three repeated 4x4 recesses with deliberately incorrect baseline orientations. 58 focused regression tests pass.

## Known limitations

Invisible or occluded upper/underside surfaces cannot prove orientation. Such stairs remain as before; the old partial-riser estimator may still be wrong there. Some different block state strings yield *identical full vanilla model geometry*: an exact semantic original state cannot be uniquely recovered in those cases. This patch does not attempt to fix slabs, fences, buttons, or texture identity.
