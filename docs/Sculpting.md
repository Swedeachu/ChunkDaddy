# Sculpting maps from the command line

`scripts/sculpt.py` is a world-edit style terrain tool: a JSON spec of operations
builds a block volume on disk, `scripts/sculpt_render.py` renders it textured for
review, and the worker's `stamp_volume` request writes it into a world document so
it can be exported and merged into a live world like any other region.

Nothing here opens a live world. Builds happen in a volume folder; worlds are only
touched through the normal export and `merge_bedrock_chunks` path.

## The volume format

A volume folder is the format `terrain_volume` dumps and `stamp_volume` reads:

| File | Contents |
|---|---|
| `volume.json` | `minX/minY/minZ`, `sizeX/sizeY/sizeZ`, `order: "zyx"`, `palette[]` (Chunker block names, optional `states`) |
| `blocks.u16.gz` | gzip of big-endian uint16 palette indices, z then y then x |
| `markers.json` | sculpt's own: named points (`markers[]`: `name`, `pos`, `yaw`, `tags`) and regions (`regions[]`) for the game code |

Palette names are Chunker's enum names (`GRASS_BLOCK`, `OAK_LEAVES` with
`persistent=true`); sculpt accepts lower-case vanilla names and `name[key=value]`.

## Commands

```bash
python scripts/sculpt.py build examples/sculpt/skywars-ffa.json --out <vol>
python scripts/sculpt.py apply <vol> more-ops.json
python scripts/sculpt.py op <vol> box min=[0,0,0] max=[9,3,9] block='"stone"'
python scripts/sculpt.py info <vol>
python scripts/sculpt.py render <vol> --out iso.png --view iso --textures <dir>
```

`render` views: `iso`, `top`, `side`, `sheet` (all four) and `pov` (a player's eye
view: `--stand x,z` or `--eye x,y,z`, `--look x,y,z`, `--figures` adds player-sized
stand-ins, `--fov`). `--textures` points at an unpacked vanilla resource pack's
`textures/blocks`; without it blocks are flat colours. `--cut-below Y` drops what
is under a height so a map does not read as a diorama.

## A spec

```json
{"size": [280, 140, 280], "origin": [0, 0, 0], "ops": [ {"op": "island", ...}, ... ]}
```

Coordinates in ops are local to the volume. Every op takes `skip: true` to switch it
off and `note` for a comment. Block lists are weighted palettes:
`[["stone", 6], ["andesite", 3]]`, picked per block (or by noise where an op has a
`*Noise` scale).

## Ops

| Op | What it does | Fields |
|---|---|---|
| `box` | Cuboid, inclusive | `min`, `max`, `block`/palette, `hollow`, `walls` (sides only), `thickness` |
| `ellipsoid` | Blob | `center`, `radius` (number or `[x,y,z]`), `roughness`, `seed` |
| `cylinder` | Upright cylinder | `center` (x,z), `y`, `height`, `radius` |
| `replace` | Swap blocks in a box | `match[]`, `min`, `max`, palette |
| `surface_paint` | Repaint the top block of columns | `match[]`, `rect`, `depth`, `minSlope`/`maxSlope`, `chance`, `yMin`/`yMax`, `noise`, `seed`, `palette` |
| `heightfield` | Fractal terrain over the whole floor | `base`, `floor`, `amplitude`, `scale`, `octaves`, `ridged`, `detail`, `avoidRect` with `rampStart`/`rampEnd`/`rampPower`/`footWobble` (a flat area with hills rising around it), `edgeFalloff`/`edgeDrop`, `top`, `soil`, `soilDepth`, `stone`, `cliff`, `cliffSlope`, `snow`, `snowLine` |
| `island` | A floating island: a nearly flat top over a tapering, ragged underside | `center`, `radius`, `surfaceY`, `depth`, `spikes` (ridged drips), `rim`, `relief`/`reliefScale`, `plateau`, `wobble`, `lobeScale`, `top`, `soil`, `stone`, `strata`/`strataHeight`, `underside`, `name` (adds a region) |
| `scatter` | Place objects on the surface | `count` or `density`, `rect` or `circle` `[x,z,r]`, `on[]`, `avoid[]` (rects), `avoidCircles[]`, `minSpacing`, `maxSlope`, `yMin`/`yMax`, `objects[]`: `{"tree": "oak"\|"birch"\|"spruce"\|"bush"}`, `{"boulder": r, "palette"}`, `{"block": id}` (on top), `{"surface": id}` (replaces the top block), each with `weight` |
| `hang` | Blocks hanging under others (roots, vines) | `block`, `on[]`, `chance`, `length` |
| `smooth` | Rounds off one-block bumps and pits | `iterations`, `rect` |
| `marker` | A named point | `name`, `pos`, `snap` (drop onto the ground), `yaw`, `tags` |
| `region` | A named box for the game code | `name`, `min`, `max`, any extra fields |
| `clear` | Empty the volume | |
| `ring` | Repeat ops around a circle | `center`, `count`, `radius`, `angleOffset`, `angleJitter`, `radiusJitter`, `jitterSeed`, `ops[]` |

Trees and boulders keep clear of each other: a placement is skipped if a log or
leaves block is near its base, or if it would overlap the footprint of a tree or
boulder an earlier scatter placed (spruce 3 blocks, other trees 2, a boulder its
radius plus one). `"clear": [radius, height]` on an object or op overrides it,
`"clear": 0` turns it off. Scatter boulders before trees so the forest grows
round them.

Inside `ring`, a string starting with `=` is a Python expression over `i`, `n`,
`angle`, `x`, `z` and `yaw` (facing the centre): `"center": ["=x", "=z"]`,
`"name": "=f'spawn{i+1}'"`. Two jittered rings at different radii spread trees
evenly without a grid look (see `examples/sculpt/skywars-ffa.json`).

## Into a world

1. Start a document with `new_document` (empty), or `open_world` on a **copy** of a world.
2. `stamp_volume` with `directory` and an `offsetX/Y/Z` to place it.
3. `export_world` the stamped columns (DIRECTORY mode) for a storage profile no
   newer than the target world.
4. `merge_bedrock_chunks` that export into a **copy** of the target world. It
   refuses to overwrite base columns, so pick an empty rectangle.
5. Check the result with `inspect_bedrock_records`, and replace the target only
   after that.

`stamp_volume` is described in [WORKER-CLI.md](WORKER-CLI.md#editing).

## Examples

- `examples/sculpt/skywars-ffa.json`: a central island of radius 51 and ten spawn
  islands on a ring, trees on two jittered rings, surface redstone ore kept six
  blocks apart, spawn markers facing the centre.
- `examples/sculpt/midfight-ffa.json`: a flat 150 x 120 meadow inside barrier
  walls with a red concrete rim in the floor under them, ridged mountains rising
  around it, boulders and then forest outside.
