# Replacing edited maps in a grid

Run locally on Windows. The worker owns all terrain decoding, matching, relocation,
block-entity handling, raster generation and database replacement. Python only coordinates
the job and packages Swim.gg's shared binary header and publication backups.

## Swim.gg workflow

```powershell
python scripts/update_sg_event_maps.py `
  --swim-root C:/Users/camde/source/repos/Swim.gg-2.0 `
  --incoming 'C:/Users/camde/Downloads/temp/aaa/updated chests' `
  --workspace local/map-updates/my-update
```

Use a fresh workspace. Known filename aliases include SG4 → SGFOUR, Turbulance →
turbulence, TheZone85 → zone85 and Phoenix → pheonix. `fixed` and punctuation are
ignored. Unknown or duplicate names fail; `--updates updates.json` instead accepts an
explicit object mapping existing template names to edited archive paths.

The script reads current runtime manifests and confirms they match the installed ZIPs.
Each map is aligned against its first existing copy, using only that old footprint as
the reference. Its full aligned rectangle is placed into all three current slots. New
loaded chunks outside that rectangle are excluded. Empty or missing source columns
erase old columns inside it; old chest entities and subchunks cannot survive a deletion.

Shared map binaries are regenerated from the edited terrain. Their original headers
are retained exactly, so all copies continue using one image with their existing offsets.
Void uses the same shaded water color as the existing renderer. Regions, layout,
instance IDs, spawns and pig/witch positions stay byte-for-byte unchanged. Original source
bounds and source hashes in regions.json describe the retained coordinate frame; the
update report records the new source hashes and recovered translations.

Staging checks all retained/inserted database records, matching copies, block-entity
coordinates, ZIP integrity, the 100,000,000-byte limit per world, PocketMine decoding
and the runtime region pool. It also independently decodes each packaged replacement and
requires 100% block-type agreement, the same translation and matching chest counts against
the edited source. Reports include matched anchors, similarity, ignored extra
columns and chest counts. Compare `update-report.json` before publishing.

Add `--publish` to stage, validate and install in one run. To install an already validated
run, repeat its arguments with `--publish-staged`. Publication checks that inputs and
staged files have not changed. Backups contain old ZIPs, every runtime asset, edited
sources and the report under `saved_worlds_sg_back_ups/map_updates/<run-id>/`.
For a running server, perform installation between restarts; loaded live worlds and
in-memory map-image caches are not changed by replacing saved archives.

## Generic worker operations

Both operations are exposed by `scripts/chunkdaddy_cli.py call ...` or JSONL plans.

```json
{"type":"align_bedrock_region","referencePath":"base.zip","sourcePath":"edited.mcworld","referenceRectangle":{"minChunkX":100,"minChunkZ":200,"maxChunkX":150,"maxChunkZ":250},"profileId":"bedrock-1.21.130","minimumSimilarity":0.98}
```

`sourceToReferenceOffset` translates edited block coordinates to reference coordinates;
`sourceRectangle` is the inverse-translated footprint to pass to `compose_regions`.
Distinctive tiles exclude flat/uniform terrain and duplicate fingerprints. The winning
translation requires at least 16 anchors in eight columns spanning at least four chunks
in X and Z, and four times the runner-up votes. Full-footprint block-type similarity must
then meet the configured threshold (default 98%, minimum 90%). Air/air does not inflate
similarity. Block states are excluded from alignment because client upgrades can change
them; actual replacement retains the edited blocks and their states through Chunker.
Height and orientation must stay unchanged; X/Z translations must be multiples of 16.
Unsupported or ambiguous transformations fail rather than guessing.

Compose the aligned rectangle at the desired existing slots using `compose_regions`,
then export an actor-free overlay with `writeVoidColumns:false` and `voidCleaner:true`.

```json
{"type":"replace_bedrock_regions","basePath":"base.zip","overlayPath":"overlay.zip","rectangles":[{"minChunkX":100,"minChunkZ":200,"maxChunkX":150,"maxChunkZ":250}],"destination":"updated.zip","mode":"ZIP"}
```

Replacement creates a fresh database, omitting all base chunk records inside the supplied
rectangles, and inserts the overlay. It merges both sorted record streams before writing
to avoid overlapping tables and excess storage. Other dimensions, global data, settings, files and
companion metadata are retained. It rejects overlapping footprints, spillover, nonempty
actor references, empty overlay footprints and overlays requiring a newer storage profile.
It reopens output and compares every retained/inserted key and value plus the total record
count before packaging. Inputs are never overwritten by this operation.
