# SketchRip: target-version-aware stair reconstruction

This is an **overwrite patch** to the existing ChunkDaddy SketchRip checkout.
It modifies Python scripts only; rebuild neither Qt/C++ nor the Java worker.

## What was wrong

The old process matched Java stair meshes independently and assigned `shape=inner_left`,
`shape=outer_right`, etc. That cannot guarantee correct Bedrock output:

* ChunkDaddy's pinned Chunker `BedrockStateGroups.STAIRS` exports `weirdo_direction`
  and `upside_down_bit` for 1.26.0/1.26.40, but adds `minecraft:corner` only in **1.26.50**.
* Tungsten's `Detail/StairShape.h` computes inner/outer corners from horizontal
  neighbor facings and matching half; the clockwise indices are `0=east`,
  `1=west`, `2=south`, `3=north`. The pinned Chunker `BedrockStateTypes` confirms
  those four integers map to the same cardinal directions.
* Therefore a face-by-face Java `shape` assignment is **not enough** in older Bedrock.

## The fix

1. Measure quarter-cell roof/underside footprints from the original GLB.
2. Group same-height, same-half adjacent stairs into connected components.
3. Construct constraints for **each stair's entire four-neighbor configuration**.
4. Solve facing assignments using finite-domain constraint propagation and bounded
   exact backtracking; forward-check the resulting shapes against the original
   GLB measurements using the Tungsten neighbor topology.
5. Change states only when the whole component is consistent. Do not change
   a component when constraints conflict or the search limit is exceeded.
6. Record unsupported/contradictory components and individual coordinates in
   `bedrock_stair_topology.failures` in the JSON report.

Where multiple solutions render identically, the solver tries the existing facing
first. This is only a deterministic tie-break **among fully matching geometry**.
It does not claim the original Java state was uniquely determined.

## Install

Extract this ZIP **into your ChunkDaddy repo root**, replace existing files.
This assumes the earlier full SketchRip drop-in is already installed.

## Commands (PowerShell from repository root)

`$glb` should be the path of your downloaded FFA GLB.

### Recommended when the destination supports Bedrock 1.26.50

In **1.26.50** Chunker can carry the corner shape explicitly. This is the best
path for sculpted Java/GLB stair corners that don't satisfy legacy topology.

```powershell
python scripts/chunkdaddy_cli.py --sketchrip "$glb" --texture-catalog model --texture-matcher fast --material-policy logical --infer-states --precision-shapes --allow-uncertain --profile bedrock-1.26.50 --output "$HOME\Downloads\FFA-Bedrock-2650.mcworld"
```

### Bedrock 1.26.0, exact connected-stair solver

```powershell
python scripts/chunkdaddy_cli.py --sketchrip "$glb" --texture-catalog model --texture-matcher fast --material-policy logical --infer-states --precision-shapes --allow-uncertain --profile bedrock-1.26.0 --output "$HOME\Downloads\FFA-Bedrock-2600.mcworld"
```

This improves facings only where they can be made consistent with the mesh.
**It cannot force unavailable old-Bedrock corner states.**

### Strict mode (do not export inaccurate legacy corners)

Append `--require-exact-stairs` to the Bedrock 1.26.0 command. It still writes
`.schem` and `.sketchrip.json` but returns an error instead of a `.mcworld` if
any fully observed component is impossible under the target topology.

### Fast geometry-only rerun

```powershell
python scripts/chunkdaddy_cli.py --sketchrip "$glb" --refine-schematic "$HOME\Downloads\FFA-Arena-Fixed.schem" --stair-orientation-only --precision-shapes --dry-run --profile bedrock-1.26.0 --output "$HOME\Downloads\FFA-Topology-Check.mcworld"
```

## Verified real FFA results

The uploaded FFA GLB has 1,537,618 triangles, and the known existing schematic
has 351,198 occupied block coordinates and 8,231 recognized stairs.

A real GLB geometry-only run with this solver:

* 4,123 stair roofs yielded usable measured masks.
* 3,672 roof constraints across 3,531 connected components had **exactly
  consistent** Bedrock neighbor-facing solutions.
* 1,876 facing/shape assignments changed as part of those solutions.
* **40 components (451 observations) were inconsistent** with these older
  Bedrock topology constraints; they were preserved unchanged and logged.
  These include repeated twelve-stair recess rings such as those highlighted
  in the screenshots.
* 351,198 occupied block locations are retained.

This does **not** prove 1.26.0 in-game correctness. Tungsten's Bedrock corner
rules are the forward-model reference, not a live Minecraft client renderer.
Actual 1.26.50 client visual comparison remains essential too.

## Focused tests

```powershell
python -m pytest -q scripts/test_sketchrip_bedrock_stairs.py scripts/test_sketchrip_stair_roof.py
```

The change does not modify material mapping, slabs, texture lookup, or C++/Java.
