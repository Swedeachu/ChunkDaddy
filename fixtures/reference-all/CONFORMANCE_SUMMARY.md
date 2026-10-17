# SketchRip vanilla model conformance

- Enumerated states: **10,400**
- Renderable with static vanilla JSON models: **10,237**
- Unrendered/special-renderer states: **163**
- Uniquely recovered from GLB geometry + atlas pixels: **6,815**
- Visually indistinguishable from another state: **3,422**
- Unmatched reconstruction fingerprints: **0**

These results are for the **pack-JSON internal renderer**; they do NOT measure Mineways/Sketchfab fidelity or production SketchRip's exact-state accuracy.
The ambiguity count is not a reconstruction defect that can be fixed by another texture metric: geometrically identical state renders are underdetermined.
