# Region composition from the Windows CLI

`scripts/chunkdaddy_cli.py` drives the same Java worker as the desktop application.
`compose_regions` creates a new document atomically from whole chunk rectangles.
Each source supplies `name`, `path`, `rectangle`, `metadata`, `coordinatePaths`,
and `placements` (`id`, `destChunkX`, `destChunkZ`). Sources may be archives or
world directories. Placements must have unique IDs and non-overlapping footprints.

Metadata is stored once per template in `regions.json`. Instance offsets are block
coordinates of destination rectangle corners. Template coordinates are relative
to the source rectangle corner. Select coordinate objects with JSON pointers,
such as `/spawns/*`; `*` selects all array elements or object values. Axis field
names default to `x`, `y`, `z`; an empty axis field disables that axis. Other
properties, including rotation, IDs and numeric gameplay settings, are preserved.

Terrain palettes are shared during composition; mutable block entities are cloned,
including translated paired-chest coordinates and preserved item-frame NBT. Export
detaches block palettes and biome lists before conversion. Composition normalizes
legacy biome layers into the modern negative-Y frame. Ordinary entities are excluded
by the world importer. The exporter
embeds the manifest in ZIP, MCWORLD and DIRECTORY output. Subsequent geometry edits
invalidate that manifest and export refuses it; recompose to update placements.

`surface_raster` creates an RGBA surface raster from a document's materialized
terrain. Supply `documentId`, `destination`, `minX`, `minZ`, optional dimensions
(default 128), stride (default 4), and ordered `colorRules`. Rules contain
`contains` terms and either an RGB integer or `ignore: true`. The same raster
request can be attached to a composition source as `raster`.

`verify_regions` takes `path` to an exported world archive. It independently reads
the database, compares subchunks and biome/height records for every translated
copy, verifies block entities belong to their physical chunks, and compares their
NBT after normalizing XYZ and chest pair coordinates. Missing or extra records fail.

`realign_block_entities` repairs legacy absolute tile coordinates in a disposable
archive extraction. Supply `path`, `destination`, and explicit `blockEntityIds`.
It preserves each tile's position within its owning chunk and its Y coordinate.
Unselected misplaced tiles fail before any database writes. The complete region
comparison must pass before the repaired ZIP replaces the destination.

Region verification and tile repair use the same 160 KiB compressed database
blocks as export and void cleaning. A maintenance reader can trigger LevelDB
compaction; using its default 4 KiB blocks can inflate an otherwise unchanged
world. `clean_void` rebuilds live records into a fresh compressed database even
when it finds no empty columns. Its report includes retained bytes by record type.

## Swim.gg adapter

Run locally with Python and the rebuilt worker:

```powershell
python scripts/build_sg_event_worlds.py --swim-root 'C:\Users\camde\source\repos\Swim.gg-2.0' --incoming 'C:\Users\camde\Downloads\temp\aaa\new sg maps' --publish
```

The adapter stages two worlds, textures and manifests in `scripts/sg_events_workspace/split3`.
Its default output profile is Bedrock 1.21.130, compatible with Swim.gg's PocketMine
storage reader. Use `--profile` only after checking the server's world-format limit;
its advertised client protocol is a separate limit.
It keeps an 800-block radius around each map center (extended for distant gameplay
positions), clipped to actual source bounds, excluding remote builder remnants.
Three columns contain the three copies; each row contains one template. Uniform
strides guarantee at least 208 blocks of empty space between all footprints.
Provided spawn/POI coordinates retain their precision; malformed missing commas
in supplied spawn lists are repaired without changing coordinates.

`--world-count`, `--copies`, `--exclude` and `--max-world-bytes` control grouping.
Defaults exclude construct, ExtractionZero and mockingjay, keeping 20 templates
with three copies each across SG_EVENTS and SG_EVENTS2. Whole templates are
balanced using previous database record counts. Each ZIP must be below 100,000,000
bytes; oversized output fails before publication. Textures are regenerated with
void using normal shaded water (RGB 55,55,220).

The root SG JSON indexes folder-specific manifests. Every template appears in
exactly one world. `--publish-staged` verifies and installs staged output;
`--publish` also installs after fresh composition. Both paths check physical
copies, PocketMine loading and the PHP pool. Previous worlds/assets are verified
into `saved_worlds_sg_back_ups/previous_compositions`. Legacy SG.zip and unrelated
worlds remain. Original backups support regeneration. `build_sg_events.py` remains
the single-world adapter and supplies shared preparation/validation helpers.
