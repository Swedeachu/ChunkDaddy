# SketchRip: Logical Minecraft material prior

## Why this change exists

The vanilla 26.2 texture pack contains newer decorative sprites whose colors and patterns are close to far more common construction materials. On the FFA Mineways atlas, texture-only nearest neighbors repeatedly assigned `spruce_shelf` to stripped spruce wood, `blue_bed` to a tan wood tile, and colored `shulker_box` IDs to fabric-colored tiles. A texture similarity score is **not** a block identity proof.

This patch adds a bounded candidate-selection prior for **approximate** texture matches (normal and fast matchers). The search remains over the fixed-size nearest-neighbor candidate list; it does not do one blockstate comparison per triangle. The prior makes shelf, bed, shulker, door and other decorative materials more expensive than conventional building blocks when the pixels alone cannot distinguish them. Exact sprites and explicit user atlas mappings remain authoritative. The output report records unique changed tiles under `logical_material_prior`.

The heuristic can still be wrong. A tan plank tile may resolve to a different wood species; it has no access to hidden source block data. Its aim is to prevent unsupported decorative material guesses, not invent certainty.

## Reproducible real GLB dry-run

```powershell
$glb = "$HOME\Downloads\SketchRipAcquired\11baf8c705414ea8bf9f833a1b1866e4.glb"
python scripts/chunkdaddy_cli.py --sketchrip "$glb" --texture-catalog model --texture-matcher fast --material-policy logical --allow-uncertain --dry-run --output "$HOME\Downloads\FFA-Logical.mcworld"
```

Without shape inference, this produces a schematic and report, not a `.mcworld`. To request world export, omit `--dry-run`. The experimental shape inference is **separate** and remains opt-in with `--infer-states`; do **not** enable it on a multi-million-triangle scene expecting fast execution yet.

## Validation on provided FFA GLB

- **1,537,618 triangles**, **351,198 reconstructed block positions**, **99.72% geometric face coverage**.
- No IDs ending in `_shelf`, `_bed` or `_shulker_box` remained in this run's output palette (the earlier output contained thousands). This is **not** an independent proof of correctness of the replacement block types.
- **27,610 texture faces** remained below the confidence threshold. Run with `--allow-uncertain` while reviewing the resulting schematic.
- The run did **not** exercise `.mcworld` export inside the sandbox; the Java worker and Bedrock loading are separate checks.

## Shape inference

The opt-in model-reference pass now requires observed fractional/interior face geometry before proposing a non-cube model. This avoids calling an exposed face of a solid plank block a fence or slab simply because the texture is the same. It groups visually equivalent candidates by block family: `oak_slab` vs `petrified_oak_slab` prefers ordinary oak, and equivalent stair orientations are recorded under `visually_equivalent_states`, not represented as exactly recovered orientations.

Unit tests verify selected vanilla-model straight/corner stair, slab, and fence geometries. This is **not a proof of exact mapping** of arbitrary connected structures. The full 1.54-million-triangle shape-inference pass did not finish within the sandbox time limit; run it only when you're prepared for a long CPU job. Some corner/connection states cannot be uniquely inferred from exported visible geometry.

## Comparing to nearest-only for diagnostics

```powershell
python scripts/chunkdaddy_cli.py --sketchrip "$glb" --texture-catalog model --texture-matcher fast --material-policy nearest --allow-uncertain --dry-run --output "$HOME\Downloads\FFA-Nearest.mcworld"
```

Examine the two `.sketchrip.json` reports and inspect `.schem` outputs side-by-side. `--material-policy nearest` is a diagnostic setting, not recommended for real-world imports.

## Remaining work

1. Make model-state inference **voxel-grouped and vectorized** for full arenas. The current Python triangle scan is too slow for the entire FFA scene.
2. Recover per-voxel multi-face textures, shape, and neighboring connections together, rather than choosing IDs from individual face votes.
3. Build an actual Mineways round trip with a known original world and compare IDs and blockstates (not just synthetic vanilla-model meshes).
4. Audit especially wool vs concrete, log species, stone vs stone bricks, and every bed/shelf/shulker substitution against known source blocks.
