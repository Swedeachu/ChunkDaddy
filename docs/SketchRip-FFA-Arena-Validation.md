# SketchRip: real FFA Arena 300x300 validation (2026-10-14)

Test input: user-provided `11baf8c705414ea8bf9f833a1b1866e4.glb`, a Sketchfab/Mineways export. This file is **not bundled** with this source overlay.

## Root causes fixed

1. **Quantized scale was not a real mesh interval.** The earlier logarithmic-bin midpoint `0.00701058232423` accumulated one-block errors across the scene. Recovering a true near-modal edge length and testing the integer dimension of the bounding box gives `0.00704228023753` model units per block. Full model spans approximately 307 × 142 × 307 blocks.
2. **Partial UV faces were treated as standalone block textures.** Mineways uses 16 × 16 tiles with 2-pixel gutters. E.g. atlas subrectangle `(293,1007)-(301,1023)` belongs to the full tile `(289,1007)-(305,1023)`. Texturing now matches complete tiles when this atlas arrangement is recognized; general textures retain the previous path.
3. **A second material image changed alpha without changing RGB.** `sfrip` supplied both the original atlas and an artificially faded BLEND copy. Atlas grouping by RGB reuses the highest-fidelity alpha image during *classification only*, while leaving input geometry unchanged.
4. **Fractional surfaces are not all off-grid meshes.** Axis-aligned internal surfaces for partial blocks can lie at Y + 1/2 and similar values. Those faces now contribute parent-voxel votes; diagonal vegetation planes are additionally recognized when their matched block belongs to a known plant class.
5. **Some alpha/tinted textures lack a directly equivalent pixel match in the supplied modern vanilla pack.** A content-fingerprinted override table in `assets/sketchrip/ffa-arena-atlas-overrides.json` repairs 23 recognizable Mineways atlas sprites, but *some block species are educated guesses*, marked for review. It is safe against similarly arranged atlas tiles from unrelated models because every override requires the exact 16×16 RGBA bytes.

## Actual full-mesh dry-run results

| Metric | Measured |
| --- | ---: |
| Source triangle count | 1,537,618 |
| Source mesh objects | 103 |
| Detected grid size | 307 × 142 × 307 |
| Reconstructed occupied voxels | 351,198 |
| Axis-aligned source triangles | 1,529,490 |
| Recovered diagonal plant triangles | 3,796 |
| Unrecovered non-axis-aligned triangles | 4,332 |
| Face coverage (triangle-based, not block accuracy) | 99.7167% |
| Residual low-confidence texture faces | 24 |
| Atlas-override uses (triangle votes) | ~202,000 |
| Number of schematic palette entries (including air) | 72 |
| Compressed schematic output | generated and checked independently |

An independent little-endian-independent NBT parser successfully decoded the gzip-compressed Sponge v3 schematic (`DataVersion=4189`). Its size was 307 × 142 × 307, its `Blocks.Data` size was 13,383,358, its 72 palette indices were valid, and its count of non-air bytes equaled 351,198.

A generated `.schem` does **not** prove Bedrock client compatibility or that every visible shape was recovered exactly. The Java worker → `.mcworld` stage was not runnable in the offline sandbox (its built worker JAR is not provided here); validate that step in the user's local ChunkDaddy build.

## Run locally

```powershell
python scripts/chunkdaddy_cli.py --sketchrip --doctor
python -m pytest -q scripts/test_sketchrip.py

# Use already acquired model, no new request to Sketchfab needed.
python scripts/chunkdaddy_cli.py --sketchrip "$HOME\Downloads\SketchRipAcquired\11baf8c705414ea8bf9f833a1b1866e4.glb" --dry-run --output "$HOME\Downloads\FFA-Arena-Preview.mcworld"

# When satisfied with the schematic, attempt the native Bedrock writer.
python scripts/chunkdaddy_cli.py --sketchrip "$HOME\Downloads\SketchRipAcquired\11baf8c705414ea8bf9f833a1b1866e4.glb" --output "$HOME\Downloads\FFA-Arena-Preview.mcworld" --profile bedrock-1.26.0
```

The default mapping file is applied automatically, but only if exact sprite bytes match. `--atlas-map PATH` selects another mapping file and can be used to refine uncertain materials for other worlds. Every classification override remains visible through `atlas_override_faces`, `requires_review`, and the map's per-sprite notes.

## Known fidelity limits and validation checklist

- The tool recovers textured *surface blocks*. Hidden block layers, original terrain fill, block entities, containers, entities, redstone state, and original game rules are not reconstructible from a surface mesh alone.
- Fractional faces now locate the correct **containing voxel**, but the schematic still uses **full blocks** unless an exact state is available. Stairs, slabs, fences, doors, and iron bars can therefore obstruct movement or look different from the model. Full shape-specific reconstruction remains a separate feature, not a validated fix here.
- Foliage species, rotated log axes, multi-block doors, upper/lower plant halves, waterlogging and decay prevention need visual/client checks. Several overrides are provisional approximations; don't mistake them for exact recovery.
- Compare the generated schematic and world against the supplied GLB in top-down and side views, and test actual spawn areas and PvP pathways before deploying it to a live server.
- The Bedrock 1.26.0 writer profile remains **experimental until tested in the client**.
