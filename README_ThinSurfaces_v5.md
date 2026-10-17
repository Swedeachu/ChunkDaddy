# SketchRip v5 — measured snow-layer and carpet geometry

Drop-in for `Swedeachu/ChunkDaddy` on branch `srip`, based on its committed GeometryEvidence version plus the SlabVolume v4 patch. Extract at the repository root, overwriting matching files. Python-only; no C++/Java rebuild needed.

## New behavior

With `--geometry-evidence`, `sketchrip_shape_evidence.refine_thin_layers()` reads horizontal GLB triangle planes at 1/16-block precision to distinguish:

- Java `minecraft:snow[layers=1..7]` from `minecraft:snow_block`, using roofs at 2/16 through 14/16.
- `minecraft:<color>_carpet` from `minecraft:<color>_wool`, using a roof at 1/16.

Requires at least 0.7 block^2 of source fractional-roof area; full-cube roofs do not trigger a conversion. The pass runs after stair/slab/fence refinement and records `thin_block_geometry` in `.sketchrip.json`. A source GLB cannot always reconstruct hidden blocks or otherwise visually identical block states.

## Ground-truth validation

Input: supplied KIT_PVP_MAP_SPAWN_4_THEMES.glb, supplied `build73vbedrock.mcworld`, previous KitPVP-GeometryEvidence.schem. Position registration `(-167,51,-167)` in reference coordinates.

* 2,341 snow blocks converted to snow layers: all 2,341 correspond to snow layers in the reference, **all layer heights match**.
* 223 colored wool blocks converted to carpet: all 223 match carpet in the reference.
* Zero false-positive conversions among 2,564 changes.
* Surface material accuracy 95.7178% → **96.6060%** (278,885 / 288,683 overlapping positions).
* Position coverage unchanged: 288,683 / 729,739 reference non-air blocks (39.5598%).
* Stair facing still 1,930/1,972; stair halves still 1,972/1,972.
* Single slab halves 1,206/1,206; 1,088 visually full-size double slabs remain ordinary cubes with wrong semantic identities.
* Legacy stair roof validation still reports 527 conflicts over 1,281 observed stair roofs; not fixed here.

**Important limitation:** one Mineways atlas UV tile in this model is shared by at least 3,425 grass blocks and 3,401 green-concrete-powder blocks. Same UV coordinates and same pixel texture cannot disambiguate the original ID without other evidence. Do not artificially claim perfect material classification.

## Full conversion

```powershell
python scripts/chunkdaddy_cli.py --sketchrip "$glb" --texture-catalog model --texture-matcher fast --material-policy logical --atlas-map "assets/sketchrip/kitpvp-4themes-calibrated-atlas.json" --infer-states --precision-shapes --geometry-evidence --allow-uncertain --profile bedrock-1.26.0 --output "$HOME\Downloads\KitPVP-v5.mcworld"
```

## Fast geometry-only retry

```powershell
python scripts/chunkdaddy_cli.py --sketchrip "$glb" --refine-schematic "$HOME\Downloads\KitPVP-GeometryEvidence.schem" --precision-shapes --geometry-evidence --allow-uncertain --profile bedrock-1.26.0 --output "$HOME\Downloads\KitPVP-v5-Refined.mcworld"
```

## Testing

```powershell
python -m unittest discover -s scripts -p "test_*.py"
```

Focused tests, entire available unit-test suite, and full GLB-to-schematic geometry-only pipeline pass in the provided validation environment. **The Java worker `.mcworld` conversion and Minecraft client render have not been tested in this environment.**

## Files

- `scripts/sketchrip.py`: new opt-in final thin-block pass
- `scripts/sketchrip_shape_evidence.py`: source geometry scanner and classifier (includes SlabVolume v4 improvements)
- `scripts/sketchrip_truth_calibration.py`, `scripts/sketchrip_truth_compare.py`: correct scorer aliases for Bedrock carpet and Java snow layers
- `scripts/test_sketchrip_thin_layers.py`, `scripts/test_sketchrip_thin_truth.py`: synthetic regression tests
- `fixtures/kitpvp/KitPVP-v5-FullIntegration.schem`: benchmark output
- `fixtures/kitpvp/KitPVP-v5-FullIntegration.sketchrip.json`: geometry pass report
- `fixtures/kitpvp/KitPVP-v5-FullIntegration.truth.json`: real-world comparison report
