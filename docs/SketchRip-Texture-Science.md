# SketchRip texture science — randomized pixel/atlas/GLB conformance

## What changed

`--texture-lab` is a deterministic fuzz harness for **texture identity**, *not* a claim that a textured face identifies its original Minecraft block state.

The vanilla Java pack supplies 1,269 files in `textures/block/`. Fifteen are diagnostic/crack/redstone overlay sprites without a safe direct block ID. The new experimental model catalog indexes **1,254** usable sprites (old filename-based index: **861**). The authoring graph follows `blockstates/*.json → model variants/multipart → model parents → #texture aliases → textures/block/*.png`, preserving *all candidate blocks* instead of flattening planks/stairs/slabs/fences to one guess. Exact 16×16 RGBA hashes are checked before anything approximate. Rotated exact aliases, opaque JPEG degradation, and per-channel lighting/tint perturbations have separate tests. A PCA-assisted 28D candidate index permits refinement against full-resolution RGB+alpha with a bounded 96-neighbor search.

Cinnabar's included source was checked via `crates/assets/data/vanilla-pack-vocabulary-1.26.50.tsv` (Bedrock v1.26.50, protocol 2193): 1,463 block IDs and 1,367 terrain texture tokens. It is a **validation vocabulary, not a Java-to-Bedrock block-state mapper**, and a matching string is not proof of compatible properties. The compact Cinnabar vocabulary and model-reference catalog have been derived into `assets/sketchrip/` for inspection. Java schematics still go through ChunkDaddy/Chunker to target Bedrock.

## Verified randomized tests (seed 20261015)

Two thousand independently selected **distinguishable sprites** per transformation, 14,000 samples total, using the uploaded vanilla pack as ground truth:

| Transformation | Correct texture identities | Raw accuracy | Accepted precision | Accepted coverage |
| --- | ---: | ---: | ---: | ---: |
| Exact | 2000/2000 | 100.00% | 100.00% | 100.00% |
| Atlas/gutter crop | 2000/2000 | 100.00% | 100.00% | 100.00% |
| 90°/180°/270° rotation | 2000/2000 | 100.00% | 100.00% | 99.95% |
| Opaque JPEG quality 90 | 1995/2000 | 99.75% | 99.75% | 99.45% |
| Opaque JPEG quality 70 | 1974/2000 | 98.70% | 99.64% | 97.45% |
| Brightness 0.72–1.28× | 1957/2000 | 97.85% | 98.18% | 98.85% |
| Independent RGB tint 0.8–1.2× | 1985/2000 | 99.25% | 99.35% | 99.45% |

Visually identical vanilla sprite groups are excluded from strict **unique identity** scoring and included in `visually_duplicate_texture_groups` in the report. A rotated sprite that becomes identical to another texture is also ambiguous; some predicted names can only be disambiguated by knowing original face UV orientation and neighboring block context. JPEG is restricted to fully opaque source sprites because JPEG strips alpha. Strong tint/brightness changes are synthetic; actual Mineways packing has different error distributions.

A separate generated atlas-packed GLB fixture with **2,500 full cubes**, randomized source block names, and known coordinates passed production `reconstruct()` with **2,500/2,500 correct IDs**, no missing cells, no extra cells. A **5,000-block** randomized source Sponge schematic with 555 unique plain-block names is included for future Mineways export. These are synthetic cubes, *not* a real Mineways GLB export of the 5,000-block world. The known-answer source schematic is **not** smuggled into the inference function.

## Important finding: correct texture != correct block

The real FFA arena exposed a regression when the new 1,254-sprite model catalog was used without geometry context: many faces mapped to derivative texture names such as `bed_down` and `spruce_shelf`, producing incorrect **bed and shelf block IDs**. The full GLB was reconstructed with the experimental fast path and the report explicitly recorded 28,234 uncertain faces. That run is a **failed block-identity acceptance test**, despite high face coverage and successful schematic creation.

For this reason, the normal `--sketchrip` command deliberately defaults to **`--texture-catalog legacy`**. The new catalog and learned matcher are opt-in:

```powershell
# Stable behavior for existing real Sketchfab imports
python scripts/chunkdaddy_cli.py --sketchrip "C:/Models/FFA_Arena.glb" --dry-run

# Investigate experimental mappings (DO NOT publish its world as validated)
python scripts/chunkdaddy_cli.py --sketchrip "C:/Models/FFA_Arena.glb" --texture-catalog model --texture-matcher fast --allow-uncertain --dry-run
```

`--texture-matcher auto` (default) uses fast candidate refinement on meshes of at least 250,000 triangles; the calibrated rotated/lighting matcher is used for smaller assets. You can override it with `--texture-matcher fast` or `--texture-matcher calibrated`. Large-scale calibrated reconstruction remains a performance concern and is not required for normal imports.

## Reproducible randomized science workflow

Run from the ChunkDaddy root:

```powershell
python scripts/chunkdaddy_cli.py --sketchrip --texture-lab --samples 2000 --world-blocks 5000 --mesh-blocks 2500 --out fixtures/texture-lab
python -m pytest -q scripts/test_sketchrip.py scripts/test_sketchrip_reference.py scripts/test_sketchrip_texture_science.py
```

Add `--cinnabar "C:/path/to/cinnabar.zip"` to cross-check the full provided Bedrock vocabulary again, without requiring you to keep the full Cinnabar archive inside ChunkDaddy. `--seed` makes runs reproducible. `--modes` selects individual corruption modes. Large fuzz runs use `--samples 10000` or more.

Generated files:

- `texture-lab-results.json`: aggregate accuracy, accepted precision, coverage, exact ambiguity groups, Cinnabar cross-check
- `texture-lab-confusions.csv`: **every** observed wrong identity, including wrong-but-high-confidence cases
- `randomized-source.schem` and `randomized-source.json`: known-answer, spaced block grid, for a real Mineways export on Windows
- `randomized-textured-cubes.glb` and `.schem`: synthetic atlas-packed GLB with known-answer block positions
- `randomized-mesh-evaluation.json`: actual GLB → production importer results, not a descriptor-only lookup score
- `assets/sketchrip/texture-to-block-candidates-v5.json`: deterministic texture → candidate block-ID list from vanilla model JSONs, not a single guessed state

## Remaining required gates before switching production to the model catalog

1. For each face, recover its **source texture identity** and all candidate block families; do not immediately emit a block ID.
2. Join all visible faces per voxel; test full-block, slab, stair, fence, wall, iron bar, ladder, pane, leaf and door geometry against authored variants.
3. Prioritize **zero wrong-but-confident block IDs** over maximizing 'mapped faces'. Report ambiguity separately. Do not use a small low-confidence count as correctness evidence.
4. Run `randomized-source.schem` through actual Mineways individual and merged exports on Windows; score both against source block names and orientations. This cannot be completed using only the self-rendered reference GLB.
5. Validate Java block state → Bedrock v1.26.0 mapping via Chunker and real Bedrock client loading. Runtime/source differences remain possible.

The 99% texture **identity** target is met for clean, atlas-cropped, JPEG90 and randomized tint samples under this specific held-out-per-query stress harness; the 99% **block-ID** accuracy target on real Mineways exports is **not achieved**.
