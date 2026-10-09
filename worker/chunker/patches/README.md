# ChunkDaddy read performance patches

The Chunker checkout remains at its verified upstream commit. `preparePatchedChunker`
generates four replacement classes from that commit with checked source anchors:

- `BedrockColumnReader.readChunks`: seek the column's subchunk prefix once and read
  its existing records instead of probing all 128 heights, including absent ones.
  Negative Y, dimensions, the supported Y range and version-specific chunk readers
  are retained. Prefix checks prevent reading neighbouring columns or record types.
- `BedrockLevelReader`: use a bounded 128 MiB decompressed database cache instead
  of the library's 8 MiB default for eight concurrent column readers. An optional
  preview callback reads a small spawn area alongside the complete index scan.
  A cooperative stop check ends indexing on close while allowing active tasks to
  finish before their database is released.
- `PreTransformManager`: dispatch vanilla block handlers by enum ordinal inside
  the per-block loop, retaining map dispatch for custom block types. Both handler
  registration overloads update the array, including subsequent registrations.
- `BedrockWorldReader`: when the converter supplies `ProgressiveReadPolicy`, read
  one region at a time in distance order from the current viewport. This avoids
  flooding the scheduler with the entire world's decode tasks before any captured
  columns can reach the viewer. Cross-region pre-transform dependencies still use
  the existing pipeline. Ordinary conversions retain upstream scheduling.

No block connections, block entities, biome decoding or version conversion are
disabled. Tests exercise sparse subchunks and both handler dispatch paths. If the
upstream pin changes, review these replacements before updating their anchors.
