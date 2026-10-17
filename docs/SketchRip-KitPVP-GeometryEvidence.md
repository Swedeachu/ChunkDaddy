# SketchRip: KitPvP geometry-evidence iteration

This is an incremental source overlay for ChunkDaddy's `srip` branch. It requires
all previous texture-calibration and geometry modules already committed to that branch.
No C++/Java rebuild is required for the Python-only files in this overlay.

## Changes

- `--geometry-evidence` enables a vectorized, per-voxel surface pass. It recognizes
  half-height treads, inset wall surfaces, narrow fence posts/rails, stained-glass
  panes, and (only with an explicit atlas hint) stone buttons.
- Stairs are first identified as a material-compatible *family*, then their
  horizontal facing is determined by the signed internal riser. Equal-area corner
  risers are reported as ambiguous, never broken by alphabetical state names.
- `--geometry-axis-profile mineways|vanilla` selects the mesh normal convention.
  Mineways is the default. Vanilla JSON reference meshes use a different convention.
- KitPvP-only geometry aliases in the calibrated atlas permit the detected
  `light_gray_concrete` tile to become `cobblestone_wall` and some `tuff` tiles
  to become `stone_button` **only where the geometry strongly supports it**.
  These aliases must not be applied to unrelated models.
- The small-shape matcher no longer infers `powered=true` on an initially
  unpowered/unspecified button from shape alone.
- The final Bedrock 1.26.0 stair-topology check is rerun **after** signed
  facing corrections, so `--require-exact-stairs` does not use stale results.

## Reproduce the real-model benchmark

Inputs: the user's ripped `KIT_PVP_MAP_SPAWN_4_THEMES.glb`, original
`build73vbedrock.mcworld`, and the previously generated calibrated schematic.

From the ChunkDaddy repository root:

```powershell
$glb = (Get-ChildItem "$HOME\Downloads\kitpvp" -Filter "KIT_PVP_MAP_SPAWN_4_THEMES.glb" -Recurse | Select-Object -First 1).FullName
python scripts/chunkdaddy_cli.py --sketchrip "$glb" --texture-catalog model --texture-matcher fast --material-policy logical --atlas-map "assets/sketchrip/kitpvp-4themes-calibrated-atlas.json" --infer-states --precision-shapes --geometry-evidence --allow-uncertain --profile bedrock-1.26.0 --output "$HOME\Downloads\KitPVP-GeometryEvidence.mcworld"
```

For a faster geometry-only comparison with a previously generated schematic:

```powershell
python scripts/chunkdaddy_cli.py --sketchrip "$glb" --refine-schematic "$HOME\Downloads\KitPVP-Calibrated.schem" --precision-shapes --geometry-evidence --atlas-map "assets/sketchrip/kitpvp-4themes-calibrated-atlas.json" --allow-uncertain --dry-run --output "$HOME\Downloads\KitPVP-GeometryEvidence.mcworld"
```

The second command writes a `.schem` and `.sketchrip.json`; `--dry-run` skips
Bedrock export. It does not repeat texture matching.

## Measured ground-truth comparison

Comparisons are on the **288,683 predicted positions overlapping real non-air
blocks**, after applying the independently measured `(-167, +51, -167)`
translation. They do not count missing hidden blocks as correct.

| Visible family | Previous correct | This iteration correct | Final precision |
| --- | ---: | ---: | ---: |
| Stairs | 923 / 1,972 | **1,972 / 1,972** | 100% |
| Stairs facing | 321 / 923 | **1,930 / 1,972** | 97.87% of detected stairs |
| Stairs top/bottom | 923 / 923 | **1,972 / 1,972** | 100% |
| Slabs | 643 / 2,310 | **1,222 / 2,310** | 100% family precision |
| Fences | 53 / 957 | **957 / 957** | 100% family precision |
| Cobblestone walls | 0 / 757 | **757 / 757** | 100% family precision |
| Stained-glass panes | 1,449 / 1,985 | **1,911 / 1,985** | 1,911 / 1,915 predicted |
| Buttons | 0 / 19 | **19 / 19** | 100% family precision |

The updated file has 288,691 non-air positions, compared to 729,739 in the
original world. About 60% of original blocks, including many hidden/interior
ones, remain absent from the GLB-based reconstruction.

## Outstanding accuracy blockers

1. **Stair corners:** 527 of 1,281 visible roof constraints disagree with the
   final pre-1.26.50 neighbor-topology forward model. This may include occluded
   source faces and imperfect legacy-corner semantics. It is not valid to call
   those corners losslessly reconstructed. `--require-exact-stairs` now rejects
   the output when these post-edit conflicts exist.
2. **Slabs:** 1,088 visible reference slab blocks remain classified as full
   blocks. Many have no usable local half-height surface in the model because
   neighboring geometry occludes it or the exporter merged it. Top/bottom is
   correct on recognized stairs but full slab exact-state correctness is
   measured separately from slab family recall.
3. **Connections and buttons:** Correct family does not prove fence connections,
   wall low/tall arms, button attachment/facing or invisible powered states.
4. **Glass panes:** 74 reference panes are not identified, and four predictions
   remain false positives.
5. **Other block states/materials:** Base-material accuracy on recovered
   positions is ~95.72%. This is not exact block-state accuracy.
6. **Missing hidden blocks:** A rendered surface cannot uniquely specify the
   full interior voxel volume. An exact 729,739-block copy is impossible from
   this GLB alone without consulting an original save or making assumptions.

## Validation performed

- Full 1,209,410-triangle GLB-to-schematic geometry retry completed.
- Schematic reread and ground-truth `.mcworld` comparison completed.
- 35 geometry/reference tests plus seven parameterized subtests passed.
- The actual Java worker `.mcworld` export and in-client rendering were **not**
  executed in this sandbox; they remain local validation gates.

The fixture files in this patch are diagnostic outputs, not complete
playable worlds. Use the first command above to build a `.mcworld` locally.
