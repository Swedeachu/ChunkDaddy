# SketchRip: rotation-aware textures and voxel-local shape inference

This revision addresses two failures visible in the 300x300 FFA arena:

1. Mineways rotates 16x16 source sprites within its texture atlas. The former fixed-orientation 8x8/16x16 nearest-neighbor matcher often mistook rotated patterned surfaces for visually similar uniform wool/concrete and logs.
2. Surface-to-block reconstruction collapses slabs, stairs and connected geometry to plain block IDs unless a separate geometry pass examines half-height planes and stair risers. The old geometry pass iterated over every triangle in Python and timed out on the full arena.

## Changes

- `scripts/sketchrip.py`: fast matcher tests all four 90-degree texture rotations. It still uses cached 8x8 nearest-neighbor candidates, then checks every candidate at 16x16 RGBA resolution. Pixel-exact tiles take priority. The logical material prior and explicit atlas overrides remain supported.
- `scripts/sketchrip_state_inference.py`: batched NumPy local-plane extraction (no Python loop per triangle), sparse occupied-voxel lookups, interior-plane matching against the vanilla block-model fingerprint catalog. Stair-like geometry requires a horizontal half-height face **and** a vertical riser; slab-like geometry requires a broad half-height face with no riser. Full-cube geometry does not trigger either case. Stair facing/half/shape come from matching rotated reference geometry; visually equivalent states are reported.
- `scripts/test_sketchrip_material_policy.py` + `scripts/test_sketchrip_shape_precision.py`: orientation-sensitive material/geometry regression tests.
- `fixtures/rotated-atlas/`: five 16x16 specimens from the supplied real GLB atlas for repeatable regression testing.
- `fixtures/FFA-Arena-ShapeAware.schem`: an experimental real-GLB schematic, accompanied by an explicit review JSON. It is not a lossless reconstruction.

## Real-GLB validation

| Measure | Before | Current experimental run |
|---|---:|---:|
| Source triangles | 1,537,618 | 1,537,618 |
| Occupied voxels | 351,198 | 351,198 |
| Geometric face coverage | 99.72% | 99.72% |
| Polished andesite voxels | 0 | 111,420 |
| Stone bricks voxels | not in the main reported palette | 4,248 |
| Brick blocks | not in the main reported palette | 9,530 |
| Shape changes | approximately 150 in the earlier conservative pass | 17,935 proposals |
| Stairs among shape proposals | limited in earlier run | 8,231 |
| Slabs among shape proposals | limited in earlier run | 9,693 |

The rotated matcher maps the source atlas's 379,899 tile to polished andesite, 541,953 to spruce planks, 127,1007 to bricks, and 973,1007 to stone bricks. These are **texture interpretations**, not a verified export of the original Minecraft save.

A 181-state isolated known-answer fixture (three sets of 40 oriented stairs, plus slabs/fences/bars/full blocks) recovered the correct family for all 120 stair states; it reported geometry-equivalent orientations rather than fabricating certainty. Four reference full cubes were left unchanged. This test exercises vanilla JSON model-derived GLB, not the user's Mineways source export. The real arena proposals still require in-game review.

## Commands (Windows PowerShell from ChunkDaddy repo)

Set `$glb` to the locally downloaded model:

```powershell
$glb = "$HOME\Downloads\SketchRipAcquired\11baf8c705414ea8bf9f833a1b1866e4.glb"
```

Produce a diagnostic schematic first:

```powershell
python scripts/chunkdaddy_cli.py --sketchrip "$glb" --texture-catalog model --texture-matcher fast --material-policy logical --infer-states --allow-uncertain --dry-run --output "$HOME\Downloads\FFA-ShapeAware.mcworld"
```

After inspecting `FFA-ShapeAware.schem` and `FFA-ShapeAware.sketchrip.json`, remove `--dry-run` for a Bedrock export:

```powershell
python scripts/chunkdaddy_cli.py --sketchrip "$glb" --texture-catalog model --texture-matcher fast --material-policy logical --infer-states --allow-uncertain --profile bedrock-1.26.0 --output "$HOME\Downloads\FFA-ShapeAware.mcworld"
```

To export the included precomputed real-model schematic without rereading geometry:

```powershell
python scripts/chunkdaddy_cli.py --sketchrip "fixtures\FFA-Arena-ShapeAware.schem" --profile bedrock-1.26.0 --output "$HOME\Downloads\FFA-ShapeAware-Test.mcworld"
```

## Limits and review policy

- Visible mesh geometry **cannot** recover unrendered/occluded blocks or invisible state properties.
- Some stair orientations produce identical reference meshes; the model reports this ambiguity and chooses a stable visible equivalent.
- The updated matcher recognizes additional atlas textures, but material names inferred from modified legacy sprites remain hypotheses (especially foliage, slabs, decorative blocks).
- `--infer-states` stays opt-in. This is a large experimental change: review the result against the source model before publishing it.
- The **entire** Windows Bedrock 1.26.0 export-and-load flow has not been tested inside this Linux sandbox.
- Model atlas texture ambiguities remain visible in the diagnostics; `--allow-uncertain` accepts them instead of silently claiming they are exact.
