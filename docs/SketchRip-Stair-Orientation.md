# SketchRip stair-facing refinement (experimental)

This **Python-only overlay** is installed by extracting its contents into the root of your existing ChunkDaddy checkout. It assumes the prior "Directional Materials Iteration 2" overlay is present. No C++ rebuild, Java worker rebuild, or pip reinstall is needed.

## The bug

The earlier 60-value normal/plane histogram pooled stairs into large quadrants and often could not distinguish opposite riser directions. Its fallback could settle on a stable but visually incorrect facing. Mineways may also strip exterior faces, making whole-block cosine scoring a poor facing test.

## Implementation

`scripts/sketchrip_stair_orientation.py` adds a **separate post-pass** operating only on voxels already classified as stairs. It accumulates area-weighted evidence from interior quarter-block/half-block planes, preserving the location of risers and treads in a 4x4 surface grid. It compares that signature with every vanilla state for the **same block family**, so it never changes the stair's material or turns it into an unrelated block.

- Processes triangles in vectorized batches (no Python loop per triangle).
- Only accepts strongly supported visible shapes, and declines weak/ambiguous matches.
- A pair of corner-state keys can yield identical visible geometry; a geometrically equivalent representative is chosen when the existing shape is wrong, and semantic ambiguity remains documented.
- Does not fabricate missing geometry or infer stairs from full cubes. This is not a proof of correct in-game semantics.

## Fast retry against an existing reconstructed schematic

Run from the ChunkDaddy root in PowerShell. Use an existing `.schem` produced by SketchRip with stair IDs already in it:

```powershell
$glb = (Get-ChildItem "$HOME\Downloads\SketchRipAcquired" -Filter "*.glb" -Recurse | Select-Object -First 1).FullName
$schem = "$HOME\Downloads\FFA-Directional.schem"

python scripts/chunkdaddy_cli.py --sketchrip "$glb" --refine-schematic "$schem" --stair-orientation-only --dry-run --output "$HOME\Downloads\FFA-StairOrientation.mcworld"
```

This creates `FFA-StairOrientation.schem` and `FFA-StairOrientation.sketchrip.json` without analyzing textures or running the original state matcher again. To export the corrected schematic to a `.mcworld` through ChunkDaddy's Java worker:

```powershell
python scripts/chunkdaddy_cli.py --sketchrip "$HOME\Downloads\FFA-StairOrientation.schem" --profile bedrock-1.26.0 --output "$HOME\Downloads\FFA-StairOrientation.mcworld"
```

## Fresh full import

```powershell
python scripts/chunkdaddy_cli.py --sketchrip "$glb" --texture-catalog model --texture-matcher fast --material-policy logical --infer-states --allow-uncertain --dry-run --output "$HOME\Downloads\FFA-Reconstructed.mcworld"
```

## Validation

- 56 focused/existing Python regression tests passed in the development sandbox.
- A separate synthetic all-40-state fixture for four stair families verified that the reconstructed **internal geometry** matches the known source state, including Z-up and missing exterior faces.
- Re-ran the stair-only pass against the actual 1,537,618-triangle FFA GLB and the prior Iteration 2 schematic: 8,231 stair positions inspected, 2,070 orientation corrections proposed, 351,198 total block positions unchanged, **no changes to material identifiers or non-stair blocks**.
- On this arena 4,013 stair positions had insufficient interior evidence for a reliable reorientation. Those were intentionally left alone; an in-game visual inspection of the newly corrected schematic remains necessary.
- End-to-end Bedrock `.mcworld` export was not executed in this sandbox; the provided `.schem` is for review or subsequent worker export.

## Included fixture

`fixtures/FFA-Arena-Stair-Orientation.schem` is the actual arena from the Iteration 2 schematic with only high-confidence stair orientation proposals applied. It and its accompanying `.sketchrip.json` can be used for quick comparisons without recomputing the GLB. These are derived from the user's earlier uploaded GLB and reconstructed schematic.
