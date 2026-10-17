# SketchRip: physical slab volume and benchmark correction

Basis: `srip` commit `c0f6eb0` and the provided `build73vbedrock.mcworld` / KitPvP GLB.

## Findings

The previous benchmark called 1,088 full-height Bedrock double slabs "missing slabs".
They were **not** missing half slabs. The reference has:

- 1,206 visible single (top/bottom) slabs: 1,206 identified, 1,206 correct halves.
- 1,104 visible double slabs: all 1,104 reconstructed with full-height occupancy;
  16 were incorrectly given bare single-slab identifiers, the other 1,088
  visually indistinguishable from solid cubes.
- 1,972 visible stair blocks: all recognized; 1,930 correct facing and 1,972
  correct top/bottom half in the committed baseline.

## Changes

`sketchrip_shape_evidence.scan`: gather evidence for full-height vertical sides.

`sketchrip_shape_evidence.infer`: when a texture lookup produces a bare `*_slab`
ID without a type, set `type=double` **only** when full-height side geometry is
present and half-height tread geometry is absent. Confirmed on 16/16 such
positions in the real KitPvP GLB; no additional positions were changed by the
isolated evidence pass.

`sketchrip_truth_compare`: score single and double slabs separately, measure
correct slab half states, and distinguish full-height geometry from exact
historical block-ID equality.

## Limitations

- A double slab and a visually identical solid cube cannot always be separated
  from an optimized GLB. This change does not guess the remaining 1,088 IDs.
- 42 stair facings remain different from reference, concentrated in equal-riser
  corner states. Connected roof constraints failed to resolve them without
  overriding hundreds of confidently correct neighboring states, so those
  experimental changes are intentionally **not included**.
- This is a Python-only overlay; it does not change Chunker's Bedrock writer.
- Full `.mcworld` output still needs an end-to-end worker/client check.
