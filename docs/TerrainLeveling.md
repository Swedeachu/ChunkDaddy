# Terrain analysis and grouped leveling

The worker supports terrain inspection and collision-checked block displacement through
the CLI. The Python planner recognizes a configurable terrain palette, estimates the
walkable floor, identifies attached geometry, and emits a reviewable plan. It does not
replace a textured map with a generated material palette.

## Workflow on Windows

Install the local analysis dependencies once:

```powershell
./local/uv/uv.exe venv local/terrain-python --python local/python/cpython-3.12.11-windows-x86_64-none/python.exe
./local/uv/uv.exe pip install --python local/terrain-python/Scripts/python.exe -r scripts/terrain-requirements.txt
```

Import and inspect the map without changing its source:

```powershell
./local/terrain-python/Scripts/python.exe scripts/terrain_level.py inspect `
  --source 'map.mcworld' --workspace local/terrain-job
./local/terrain-python/Scripts/python.exe scripts/terrain_analysis.py local/terrain-job/before
```

Author a plan using a map-specific terrain profile. The checked-in Build73 profile
documents the analyzed materials and heights for that map; other maps need their own
reviewed profiles.

```powershell
./local/terrain-python/Scripts/python.exe scripts/terrain_plan.py `
  --volume local/terrain-job/before --config examples/terrain/build73.json `
  --output local/terrain-job/plan
```

Review `plan/report.json`, `plan/displacement-map.png`, and `plan/plan.json`.
Blue cells move down, grey cells are protected support cells, and pale cells stay
at their original height. The plan lists the bounds, materials, roots and final
offset of every recognized group, including groups that must stay fixed.

`--group-offset ID:DY` requests an offset for a particular group. Zero protects it.
Multiple flags can select several groups. These selections are fingerprinted to the
inspected source and remain subject to support, overlapping-selection and collision
checks. The profile can also contain `protectedRegions` with inclusive `minX`, `minZ`,
`maxX` and `maxZ` terrain footprints. Objects whose support footprints touch a protected
region remain fixed.

```powershell
./local/terrain-python/Scripts/python.exe scripts/terrain_level.py apply `
  --source 'map.mcworld' --workspace local/terrain-job/apply `
  --plan local/terrain-job/plan/plan.json `
  --destination exports/map-levelled.mcworld
```

The driver first performs an independent worker preflight. A collision rejects the
entire edit. Successful edits form one undoable document revision. The driver writes
the edited analysis volume and exports a separate `.mcworld`; the input hash is checked
again before completion.

Use `terrain_level.py validate --source ... --workspace ... --plan ...` for a standalone
preflight without changing the document or writing a world.

For saved-file verification, inspect the exported archive into another workspace and
compare both the edited document and reopened world:

```powershell
./local/terrain-python/Scripts/python.exe scripts/terrain_level.py inspect `
  --source exports/map-levelled.mcworld --workspace local/terrain-job/reopened
./local/terrain-python/Scripts/python.exe scripts/terrain_verify.py `
  --before local/terrain-job/before --after local/terrain-job/apply/after `
  --plan local/terrain-job/plan/plan.json --reopened local/terrain-job/reopened/before `
  --output exports/verification.json
```

Verification checks all semantic voxel positions, block-state counts, liquid positions
and block-entity coordinates. Bedrock's explicit default `leaves.update=FALSE` is
normalized against its implicit equivalent; leaf distance, persistence, waterlogging
and every other block property remain exact. Chunk Daddy's existing static-map import
policy applies: the editable Overworld is imported, ordinary entities are excluded,
and unsupported positional identities are not silently copied.

## How the planner separates terrain and builds

- Terrain families, the expected floor band, foundation and target height are explicit
  profile inputs. Low plants are transparent to floor detection; canopies and roofs are not.
- Missing floor samples under structures are inferred from nearby known terrain,
  within a bounded distance. Material classification distinguishes buried substrate
  from exposed structural blocks in the same height band.
- Foreground geometry is segmented into connected components. Low silhouettes and
  support footprints capture roots, rocks and same-palette decorative bases. Overlapping
  supports become a single rigid selection with one consistent vertical offset.
- Large perimeter structures, tall landmarks, liquid columns and uncertain supports
  remain fixed. Terrain approaches protected feet with feathered transitions.
- The planner never lowers a support below its configured minimum. Ambiguous collisions
  freeze the involved selection or support cell; they are reported instead of overwritten.

This is a conservative geometric tool, not a universal semantic recognizer. A low rock
made from exactly the same material as its surrounding terrain can be ambiguous. Use
the group list, protection regions and before/after renders to review each profile.

## Worker operations

`terrain_volume` accepts `documentId`, output `directory`, and optional `minX`, `minY`,
`minZ`, `width`, `height`, `depth`. Defaults are non-air content bounds and the modern
Overworld height range. A volume is limited to 128 million voxels. It writes:

- `volume.json`: bounds, semantic palette, fingerprint and block-entity positions.
- `blocks.u16.gz`: unsigned 16-bit big-endian palette IDs, ordered `z, y, x`.

`terrain_displace` accepts `documentId`, `plan`, and optional `validateOnly`.
The plan specifies matching bounds and fingerprint, a `displacements` filename,
`displacementSha256`, and `maximumShift` (1–64). The gzip field contains one signed
8-bit vertical offset per voxel in the same order. Palette identifiers are never
reconstructed from rendered colors. Air is not pasted as a replacement material.

The worker rejects stale source volumes, mismatched field checksums, clipped targets,
duplicate target voxels, and collisions with stationary blocks before committing.
It detaches changed columns, updates block-entity and supported hanging-frame positions,
and invalidates lighting and height maps. A zero-move plan is an unchanged revision.
Instance-backed documents must be exported and reopened before terrain editing;
existing region-coordinate metadata remains protected by its revision checks.
