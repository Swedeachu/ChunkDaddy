# SketchRip: signed stair / slab and conservative fence refinement

This is an **opt-in** experiment. It does not establish a lossless Mineways-to-Bedrock conversion and does not repair texture family misidentification or invisible block properties.

## Why this patch exists

* The previous FFA reconstruction contained **12,448 slabs labeled `type=top` and zero labeled `type=bottom`**, even though the source GLB exposes both kinds of mid-height planes. The old cosine-based shape inference ignored face winding and could not distinguish the side of the half-height surface reliably.
* Previous stair orientation matching discarded triangle-winding sign, used an unsigned 4×4 plane footprint, and allowed hidden adjacent geometry to make facing states look alike. New matching uses signed 8×8 interior surface rasters with per-mesh winding calibration and a minimum improvement over the existing stair before changing it.
* Some fences were classified as solid wood because their faces share plank textures. A conservative candidate detector now requires narrow axis-aligned fractional planes in **both** horizontal axes and matches the geometry against vanilla fence connection models.

## Commands

Reconstruct from an existing GLB with the previous classifier plus the opt-in geometry changes:

```powershell
python scripts/chunkdaddy_cli.py --sketchrip "$glb" --texture-catalog model --texture-matcher fast --material-policy logical --infer-states --precision-shapes --allow-uncertain --profile bedrock-1.26.0 --output "$HOME\Downloads\FFA-Precision.mcworld"
```

Faster repeat of the geometry pass using **your most recent** previously reconstructed schematic. This reuses the existing block-ID guesses and does **not** redo the texture classification:

```powershell
python scripts/chunkdaddy_cli.py --sketchrip "$glb" --refine-schematic "$HOME\Downloads\FFA-Arena-Fixed.schem" --precision-shapes --allow-uncertain --profile bedrock-1.26.0 --output "$HOME\Downloads\FFA-Precision-Refined.mcworld"
```

You can test without exporting a world using `--dry-run`; it produces `.schem` and `.sketchrip.json` only. For diagnosis, use `--precision-no-slabs` or `--precision-no-fences` alongside `--precision-shapes`. `--stair-orientation-only` deliberately does not change slabs or fences.

Omit `--precision-shapes` to retain the previous stair inference and avoid these optional slab/fence corrections. No C++ / Java rebuild is needed; it changes Python scripts.

## Measurements from real FFA arena (original 1,537,618-triangle GLB)

Source input for geometry-only pass: `SketchRip-StairOrientation-Final.schem` from the previous iteration, not the original Minecraft save. It contained 351,198 occupied positions.

| Metric | Result |
| --- | ---: |
| Output dimensions | 307 × 142 × 307 |
| Occupied block positions | 351,198 (unchanged) |
| Old slab states | 12,448 top / 0 bottom |
| New slab states | 5,264 top / 7,192 bottom; additional slab states without `type` may remain |
| Slab half changes | 7,192 |
| Precision stair proposals | 36 |
| Conservative plank-to-fence proposals | 64 |
| General shape pass proposals (pre-existing) | 2,070 |

The stair and fence numbers are **proposals**, not externally verified true positives. Many staircase orientations still abstain due to insufficient or conflicting visible geometry. The material inference stage is unchanged and still has known errors. Do not conflate a good block shape classification with correct original block identity.

### Why the slab change is credible but still needs review

A bottom slab's exposed middle horizontal plane faces upward; a top slab's exposed middle plane faces downward. The existing output assigned virtually all recognized slabs to one half regardless of these surfaces. The patch calibrates source triangle winding from the stair model matching before voting on the middle plane. It applies a half correction only when area > 0.10 block² and the face direction dominates the opposite by 2.75:1. If the winding cannot be calibrated, it makes **no slab changes**. Synthetic top/bottom and reversed-triangle-winding tests pass; the entire arena cannot be certified without the original world save.

### Why fences remain conservative

Plank textures alone cannot establish fence identity. Reclassifying every thin wooden surface would destroy parts of doors, stairs, decorative trims, and wall cladding. The detector requires narrow intersecting plane evidence and a leading vanilla fence model before proposing a connection state. Real-data matches with low geometry scores abstain. For real fences missed by this stage, additional neighborhood-aware geometry fitting is required.

### Buttons, levers, doors, torches

Previously recognized members of those families still use `sketchrip_small_shapes.py`; this patch does **not** solve the harder problem of discovering an unrecognized button or wall torch from a generic stone or plank material. Automatic creation of those families from texture alone is intentionally not enabled.

## Verification

* 62 automated tests against the existing SketchRip suite and new focused winding/fence/slab tests.
* Fresh GLB→schematic smoke test passed with `--precision-shapes`.
* Real 1,537,618-triangle GLB geometry-only run wrote a Sponge v3 schematic and audit JSON.
* **Not performed:** Bedrock client visual validation or end-to-end `.mcworld` load test in this sandbox.
