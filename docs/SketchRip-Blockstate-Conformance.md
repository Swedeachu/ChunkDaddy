# SketchRip: complete blockstate round-trip conformance harness

## Purpose and status

This is a **measurement and calibration harness**, not a claim of solved lossless
GLB -> Minecraft conversion. It enumerates every state present in the bundled
Java 26.2 **blockstates JSON** collection, builds reference block model meshes and
UV atlases from the same pack, creates an authoritative Sponge v3 schematic,
and evaluates inversion against known source IDs and properties. The fixture is
**not** a true Mineways render; it exists to make geometry/state matching
independently testable. A second stage invokes actual Mineways on Windows.

Every state placed is separated from its neighbors by two empty blocks. This
isolates states for geometry comparison but does **not** test adjacency-driven
blockstate recomputation. Connected fences/panes/walls are represented by their
explicit multipart properties in the reference schematic.

## Verified local reference results

| Metric | Observed |
|---|---:|
| Blockstate JSON definition files | 1,198 |
| Enumerated vanilla-model state combinations | 10,400 |
| Rendered into the reference GLB and reference.schem | 10,237 |
| Unrendered, explicitly audited | 163 |
| Unique, correctly inverted geometry + pixel fingerprint | 6,815 |
| State IDs that produce an indistinguishable visible fingerprint | 3,422 |
| Unmatched source-vs-loaded-GLB signatures | **0** |
| Geometry in reference GLB | 265,344 triangles |
| Source atlas textures | 1,227 |

The 3,422 ambiguous states are **not all errors**. Many states have exactly the
same visible geometry, pixels and UVs. Examples include some double slabs vs
full blocks and state properties that do not affect the model. A function of
only the mesh cannot recover a non-visible bit (e.g. powered, waterlogged,
randomTick, persistent, some door variations). Neighbor topology, original NBT,
a known design palette, or user hints are needed to choose those bits.

The reference generator ignores non-JSON special renderers (e.g. moving fluids,
entity-rendered chests/shulker boxes/signs/banners, certain animated or overlay
properties). `unrendered_states.csv` lists all 163 cases, including incorrect
`element.rotation` JSON shapes not handled by this approximation. This is not
an exhaustive list of **all** legal runtime property combinations. Model-defined
variants and multipart conditions do not encode every otherwise valid state.

**Independence:** Reference state identifiers are used to create the fixture and
later to score results, never as hints inside the GLB inference pass. State
resolution hashes oriented triangle geometry, UVs, and RGBA texture pixels.
The scorer preserves ambiguity rather than choosing a random winner. GLB uses
float32 positions/UVs, so expected fingerprints are calibrated at that exact
precision to avoid false misses for thin flowers and petals.

## Windows: use the included fixture immediately

Unzip the drop-in directly into the ChunkDaddy repository root. After one-time
`SKETCHRIP_SETUP.bat` and `python scripts/build.py`:

```powershell
# Inspect reference from supplied known-answer schematic + atlas + GLB:
python scripts/chunkdaddy_cli.py --sketchrip --reference evaluate --out fixtures/reference-all

# Inspect ambiguous / skipped cases in Excel or a text editor:
# fixtures/reference-all/state_conformance.csv
# fixtures/reference-all/unrendered_states.csv
# fixtures/reference-all/CONFORMANCE_SUMMARY.md

# Optional: regenerate all 10,400 model-defined state combinations from vanilla assets:
python scripts/chunkdaddy_cli.py --sketchrip --reference generate --mode all --out fixtures/reference-all

# OR regenerate a smaller fixture for iteration:
python scripts/chunkdaddy_cli.py --sketchrip --reference generate --mode targeted --limit 180 --out fixtures/reference-small
python scripts/chunkdaddy_cli.py --sketchrip --reference evaluate --out fixtures/reference-small
```

## True Mineways export: Windows only

Mineways 13+ supports reading Sponge v3 `.schem` as a Minecraft world and
headless `.mwscript` exports. Install Mineways separately; its EXE is not
bundled. Select an absolute path for `Mineways.exe`:

```powershell
python scripts/chunkdaddy_cli.py --sketchrip --reference mineways --out fixtures/reference-all --mineways-exe "C:/Tools/Mineways/Mineways.exe" --mineways-mode both
```

This **regenerates the script with paths valid on your Windows machine**, runs
real Mineways twice, checks resulting textured OBJ files and writes:

- `fixtures/reference-all/mineways-reference-individual.obj/.glb` — one block at a time,
  easiest to calibrate geometry and UVs
- `fixtures/reference-all/mineways-reference-merged.obj/.glb` — normal grouped geometry,
  tests loss under optimized meshes
- `fixtures/reference-all/mineways-export-*.log` — exact Mineways stdout/stderr

Run the production importer on each export and compare it to the source:

```powershell
python scripts/chunkdaddy_cli.py --sketchrip --reference evaluate-mineways --out fixtures/reference-all --mesh fixtures/reference-all/mineways-reference-individual.glb
python scripts/chunkdaddy_cli.py --sketchrip --reference evaluate-mineways --out fixtures/reference-all --mesh fixtures/reference-all/mineways-reference-merged.glb
```

Results go into `mineways-evaluation.json` (the second command overwrites the
first; save a copy to compare both). The evaluator reports correct **block
names**, exact **states/properties**, unmatched cells and top confusion pairs.
Its coordinate registration is heuristic; if a true Mineways export offsets
its origin/scales or changes rotation, supply `--scale` and inspect alignment.

Mineways's renderer may omit faces, tint textures, and use block geometry that
differs from Minecraft's JSON models. This is precisely what the external
round trip measures. The Windows command has **not** been executed in the
Linux validation environment, and Mineways failures remain possible. See
https://erich666.github.io/Mineways/scripting.html for the supported script commands.

## Production SketchRip changes

1. Cached 16x16 RGBA pixel descriptors are checked **before** approximations.
   An exact terracotta tile is not assigned to bamboo because an 8x8 thumbnail
   happens to resemble it. KNN candidates are refined against full-resolution
   pixels and cross-block near-ties are flagged rather than silently trusted.
2. The reference JSON geometry is compiled into a cached 60-feature-per-state
   partial-block model database (plane/normal/quadrant histograms). This avoids
   reparsing the entire texture pack for every world.
3. `--infer-states` (OFF by default) makes *conservative* per-voxel proposals
   for slabs, stairs, ladders, walls, fences, panes and related models when
   geometry+material can distinguish a state. It writes per-voxel evidence to
   `geometry_state_inference` in `.sketchrip.json` and forces review. On one
   isolated 1,200-state reference fixture it proposed 93 refinements, 91
   correct and 2 wrong-to-wrong glass-pane vs iron-bars distinctions; it
   changed no previously correct cells incorrectly. That sample is not a
   guarantee for production. On a smaller 180-state fixture 13/13 proposed
   changes were correct.
4. Existing manual atlas overrides still take priority when explicitly supplied.
   The bundled FFA-specific overrides remain source-model-specific.

Run a real arena with the **experimental** state inference on:

```powershell
python scripts/chunkdaddy_cli.py --sketchrip "C:/Models/arena.glb" --dry-run --allow-uncertain --infer-states --output "$HOME/Downloads/arena-study.mcworld"
```

Inspect the `.sketchrip.json` proposals and the `.schem` **before** allowing a
`.mcworld` export. Do not expect stairs or ladder rotations to all be correct
on an unrelated Mineways mesh. The feature matcher currently needs the correct
base material to have already been inferred; it is **not** a complete joint
block-ID-and-state solver. Geometry is insufficient for full cubes that share
identical surfaces (e.g. some stone variants under heavy texture tint).

## Tungsten and Bedrock compatibility

The user-supplied Tungsten source has native runtime registries in
`src/Engine/Gameplay/World/BlockRegistry.cpp` and property handling in
`BlockStateProperties.cpp`. Those are useful for an eventual Java -> Bedrock
state crosswalk and supported-block audit, but are **not** interchangeable with
Java's visual block-model JSON. SketchRip creates Java-style Sponge v3 palettes;
ChunkDaddy/Chunker must validate Java -> Bedrock mapping and reject unknown
states, including newer blocks unrecognized by pinned Java DataVersion 4189.
Do not silently substitute those with air/full cubes. Client-side validation
of Bedrock 1.26.0 is still required.

## Conformance acceptance criteria before shipping by default

- True Mineways individual and merged exports both run on the user's machine.
- All renderable states are classified uniquely or **explicitly ambiguous**, with
  per-family confusion reports; false confidently identified states are zero.
- Real export fixtures include adjacent connection variants, waterlogged/hidden
  state audits, transparent textures, directional signs/doors/rails, biome
  tinting and partial-geometry culling.
- Corrected `--infer-states` output improves exact stair/door/ladder/pane/wall
  properties, not only broad block types; measure without reading source truth
  during conversion.
- Round-trip `.mcworld` passes Chunker's mapping audit, reimports without
  unintended substitutions, and is tested inside a Bedrock client.

The current implementation is a **reproducible foundation for those gates**,
not their completion.
