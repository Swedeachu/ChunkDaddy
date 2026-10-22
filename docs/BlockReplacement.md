# Block replacement and erosion

Chunk Daddy's `replace_blocks` worker operation applies an ordered JSON recipe to a detached semantic volume and commits all changes as one undoable revision. It supports air removal, seeded material splotches, iterative air-side erosion, and clearing waterlogging. This uses the normal world importer/exporter and preserves unrelated blocks, structures, and positional records.

## CLI

Use Python with `scripts/terrain-requirements.txt` installed:

```powershell
local/terrain-python/Scripts/python.exe scripts/replace_world_blocks.py `
  --source exports/build73-levelled/build73-levelled.mcworld `
  --recipe examples/terrain/build73-polish.json `
  --destination exports/build73-polished/build73-polished.mcworld `
  --workspace local/terrain-build73/polish
```

The driver inspects the source, binds the recipe to its fingerprint, previews the entire edit, applies it, exports, and reopens the actual archive. It audits every voxel against the pre-export edit, rejects changes outside the match set, verifies block entity positions/types, and checks that the source archive hash stayed unchanged. Evidence goes into the workspace (`recipe.json`, `edit.json`, semantic volumes, `verification.json`). The destination must differ from the source.

For a bounded recipe, the fingerprint is taken from the selected volume while the audit still checks the entire world. Matching materials outside the selection must remain unchanged. `examples/terrain/build73-nether-dirt.json` demonstrates a chunk-aligned selection that includes an irregular biome seam.

The audit treats implicit and explicit dry `waterlogged: false` states as equivalent, and does the same for default leaf `update: false`, straight stair shapes, and false connections on fences, bars, and stained glass panes. Other properties are compared exactly. Use `--profile` to match the source's Bedrock version, especially for 26.50 worlds which store explicit stair shapes and connections. Optional `expectAbsent` lists block types that the CLI must confirm are completely absent from the reopened export.

## Recipe

```json
{
  "schemaVersion": 1,
  "clearWaterlogged": true,
  "operations": [
    {"match": ["COBWEB"], "palette": [{"type": "AIR"}]},
    {"mode": "erode", "match": ["WATER"], "faces": "horizontal"},
    {
      "match": ["WATER"], "scale": 5.5, "seed": 73022,
      "palette": [{"type": "BLUE_STAINED_GLASS"}, {"type": "CYAN_STAINED_GLASS"}]
    }
  ]
}
```

- Types use Chunker's vanilla enum names. Invalid names, empty match sets, air matches, invalid scales/weights, and edits that would change a block entity's owning block type are rejected before committing.
- `replace` is the default mode. Palette choices have optional positive `weight` (default 1). Choices create default-state blocks. A one-entry palette is a uniform replacement; choosing `AIR` removes blocks.
- Multiple choices use smooth, seeded 2D value noise in **world** x/z coordinates. `scale` is the lattice spacing in blocks (default 6). Vertical layers share the same pattern; selecting a smaller region does not shift it. Weights are noise thresholds, not a guarantee of exact material percentages.
- `erode` removes matched blocks touching air, then repeats inward until stable. `faces: horizontal` checks four lateral neighbors, deliberately preserving open-top basins. `all` checks all six faces. Missing voxels outside the selection count as air. Erosion never removes unrelated materials. A queue visits each eroded voxel once; it does not rescan the whole world for each iteration.
- Operations run in order; removing cobwebs first can expose more water to erosion. Replacement runs after erosion so shaved water becomes air, while enclosed water becomes glass.
- `clearWaterlogged` dries waterlogged structures after the operations without removing their other states. It does not implicitly replace water cauldrons; match `WATER_CAULDRON` explicitly if desired.
- Optional `minX`, `minY`, `minZ`, `width`, `height`, `depth` restrict the volume. Defaults cover content chunks over Bedrock Y -64 through 319. Optional `fingerprint` rejects an unexpected source. The volume limit is 128 million voxels.

## Worker protocol

```json
{"type":"replace_blocks","documentId":"world-1","recipe":"C:/path/recipe.json","validateOnly":true}
```

Omit `validateOnly` or set it to false to commit. Replies include per-operation changed-block counts, unique final changed blocks, dried blocks, source fingerprint, and revision. Apply replies include changed columns. A dry run or a no-op leaves the revision unchanged. Positional block records remain protected; this operation intentionally does not provide implicit container deletion.

## Build 73 polish

The supplied example removes cobwebs and unsupported lily pads, shaves raised water horizontally, replaces contained water with blue/cyan glass, replaces soul soil/sand with dirt/coarse-dirt patches, empties water cauldrons, and clears waterlogging. It applies to the already levelled export, keeping its terrain and moved structures in place.
