# SketchRip — directional blocks, visible-face culling, and material anchors

This is a source overlay for the existing ChunkDaddy checkout. It **does not** replace
ChunkDaddy's Qt or Java/Chunker implementation. Install by extracting the ZIP's contents
into the repository root. It keeps the previous `scripts/chunkdaddy_cli.py` entry point.

## What changed

- `scripts/sketchrip_state_inference.py`: reference-model fitting now removes outer
  model faces expected to be hidden behind adjacent full blocks. The face-histogram
  model no longer sorts approximate orientation ties alphabetically and discards a
  better-scoring facing. Exact geometric-equivalence ties are still necessarily ambiguous.
- `scripts/sketchrip_small_shapes.py`: bounded 8x8x8 voxel-local surface histogram for
  recognized ladders, buttons, doors, trapdoors, levers, and torches. Handles angled
  surfaces omitted by the axis-aligned stair classifier. Low-quality fits abstain.
- `scripts/sketchrip_material_context.py`: when the *observed* vanilla log-top sprite
  identifies a species with high confidence, propagate that species along a contiguous
  vertical log column **only if its dominant bark texture is the same atlas tile**.
  Never recolor generic brown cubes, planks, dirt, or terracotta using this rule.
- `scripts/sketchrip_state_inference.py`: buttons, doors, trapdoors, levers and torches
  cannot be inferred from generic planks/stone; they require an already-recognized
  block family. Fences, stairs and slabs still have limited material-shape inference.
- `scripts/sketchrip_schematic_read.py`: lossless parser for SketchRip's own Sponge v3
  layout, enabling geometry-only retry from existing schematics without rerunning
  texture matching on ~1.5 million triangles.

## Recommended: refine the schematic you already generated

In Windows PowerShell from the ChunkDaddy repository root:

```powershell
$glb = "$HOME\Downloads\SketchRipAcquired\11baf8c705414ea8bf9f833a1b1866e4.glb"
$schem = "$HOME\Downloads\FFA-ShapeAware.schem"
python scripts/chunkdaddy_cli.py --sketchrip "$glb" --refine-schematic "$schem" --allow-uncertain --dry-run --output "$HOME\Downloads\FFA-Directional.mcworld"
```

That writes `FFA-Directional.schem` and `FFA-Directional.sketchrip.json`; **the GLB
still must be supplied**, because orientation uses its triangle geometry. Remove
`--dry-run` to ask ChunkDaddy's Java worker to package the `.mcworld`.

If your previous schematic has a different name, change `$schem` accordingly.
The geometry-only retry **retains the existing material IDs**. This is useful to
compare slab/stair/door orientations without moving the texture-classification goalposts.

## Full import with log-end context (expensive; not verified end to end here)

```powershell
python scripts/chunkdaddy_cli.py --sketchrip "$glb" --texture-catalog model --texture-matcher fast --material-policy logical --infer-states --allow-uncertain --dry-run --output "$HOME\Downloads\FFA-Full-Iteration2.mcworld"
```

This also enables material-context reconciliation in the texture extraction stage.
It does not give enough evidence to fix every dirt/coarse dirt/brown concrete powder
collision: some exported atlas sprites genuinely differ from the provided Java 26.2
textures. No source block ID can be proven from RGB similarity alone in such cases.

## Measured validation (the provided real FFA model)

- Actual model: 1,537,618 triangles.
- Geometry-only retry: ~17 seconds, ~1.9 GB peak resident memory in the Linux test.
- Occupied positions before and after: 351,198, unchanged.
- Revisions relative to the previous ShapeAware schematic: 3,405, including 176
  stair-within-family state changes, 1,000+ new slab classifications and various
  partial-block changes. **These are proposals, not independently verified exact
  source states.**
- The new narrow-block pass proposes no additional changes on this particular real
  GLB because the surface fits did not clear its quality bar; it correctly abstains.
- 52 local regression tests pass, including exact directional ladder and wall-torch
  synthetic fixtures and a multi-byte Sponge palette round trip.
- The full `GLB -> texture reclassification -> world` pipeline exceeded the sandbox
  runtime limit. Neither in-game visual correctness nor an actual `.mcworld` export
  was established by this patch.

## Fundamental limits

Mineways removes faces occluded by neighboring blocks. Several stair corner states
have equivalent *visible* geometry despite differing `facing`/`shape` names. Lever
powered state, door hinge on some models, and some buttons/torch facets also cannot
be uniquely inferred from the given mesh. Prefer abstaining over choosing the first
alphabetical property combination. Preserve any original world-state evidence if it
becomes available later.

### Running tests

```powershell
python -m pytest -q scripts/test_sketchrip*.py
```
