# ChunkDaddy SketchRip — stair orientation precision patch

Extract the ZIP **contents** into your existing `ChunkDaddy` repository root, replacing `scripts/sketchrip.py`. It adds `scripts/sketchrip_stair_orientation.py`, a regression test, a reference fixture and documentation. It does **not** include or replace ChunkDaddy's C++ and Java sources, and it requires your already-installed SketchRip files from Directional Materials Iteration 2.

**No rebuild required.** This is a Python-only change.

To quickly test the patch with your existing GLB and schematic, see `docs/SketchRip-Stair-Orientation.md`.

The real-model pass proposed 2,070 stair state corrections while leaving 4,013 low-evidence stair positions unchanged. This is not a claim that all stair orientations are now correct in-game.
