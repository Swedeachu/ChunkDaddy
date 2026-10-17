# ChunkDaddy SketchRip — precision geometry patch

**This ZIP is a source overlay for the existing ChunkDaddy repository with the latest SketchRip installed.** Extract its contents into the repo root, replacing existing Python script files. It does not contain a standalone application or the full ChunkDaddy source tree.

Run `python -m pytest -q scripts/test_sketchrip_stair_orientation.py scripts/test_sketchrip_signed_shape.py scripts/test_sketchrip_fences.py` to check the new orientation/half-height/fence logic.

Read `docs/SketchRip-Precision-Shape-Refinement.md` for a full explanation and one-line PowerShell commands.

The precomputed `fixtures/FFA-Precision-Shape-Experimental.schem` was generated from a previous FFA reconstruction. It is **not** the original world and must be visually checked against the source Sketchfab GLB.

The `--precision-shapes` switch is intentionally opt-in. Existing commands without it keep the old behavior. No C++ or Java rebuild is necessary.
