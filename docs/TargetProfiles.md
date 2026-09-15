# Target profiles

A target profile decides the version ChunkDaddy stamps into the exported world, and which
Chunker writer produces the chunk records. It is the single setting that determines whether
a given client or server will open the result.

## The one rule that matters

Bedrock writes two version stamps into `level.dat`:

| Tag | Meaning |
| --- | --- |
| `lastOpenedWithVersion` | the build that last wrote the world |
| `MinimumCompatibleClientVersion` | the oldest build allowed to open it |

A client older than `MinimumCompatibleClientVersion` refuses the world with
**"a newer version of the game saved this world and we can't open it"**. The world is not
damaged; the client is simply older than the profile it was written for.

So: **pick the profile that matches the oldest build you need to load the world with**, not
the newest profile in the list. Exporting on `bedrock-1.26.50` and then opening on a
1.26.45 client produces exactly that error, because 26.50 is a newer release family than
26.4x.

## Profiles

| Profile ID | Writer | Stamps | Opens on | Overworld Y |
| --- | --- | --- | --- | --- |
| `bedrock-1.26.50` | Bedrock 26.50 | `[1,26,50,0,0]`, storage 10, network 2192 | 1.26.50 and newer | -64 to 319 |
| `bedrock-1.26.40` | Bedrock 26.40 | `[1,26,40,0,0]`, storage 10, network 2168 | 1.26.40 and newer | -64 to 319 |
| `bedrock-1.21.130` | Bedrock 1.21.130 | `[1,21,130,0,0]`, storage 10, network 898 | 1.21.130 and newer | -64 to 319 |

`bedrock-1.26.40` is the default: it matches the 26.40 protocol family the Enchanted stack
runs, and every 1.26.4x client opens it. Move up to `bedrock-1.26.50` only once both the
server build and the clients you care about are on 26.50.

If you already have an export stamped too new, you do not have to rebuild the composition
from scratch — re-export the same document with a lower profile. Only the level data and
chunk encoding are rewritten; the arena JSON and the manifest come out identical apart from
the recorded profile.

## Pinned Chunker revision

```
31c91a92bd2dda746f3e41189b603fcfd1727f04
```

Enforced by `:chunker:verifyChunkerPin`. Chunker's sources are checked out under
`third_party/chunker` and compiled as a plain library; ChunkDaddy never fetches a moving
branch at build time. If you bump the pin, update `TargetProfile.all()` in the worker and
this table together — they are meant to disagree loudly rather than drift.

## What to check after an export

Not a gate, just the list of things that have actually bitten this pipeline:

- The world opens on the oldest client you intend to support.
- Both spawn positions of a first, a middle and a last copy of each template are where
  `arenas.json` says they are.
- A void gap between two arenas is empty, and stays empty after a save and reload.
- Negative-Y content, doors, stairs, signs, liquids and gravity-sensitive decoration
  survived the round trip.
- For Tungsten, use a fresh copy of the export rather than one BDS has already rewritten —
  a BDS rewrite can hide a writer incompatibility.

## Storage rules this project follows

- Bedrock uses its own LevelDB dialect. The worker uses Chunker's `leveldb-mcpe-java`
  rather than a generic LevelDB.
- Persisted block palettes are not network runtime IDs. Runtime IDs from a live server
  session never become durable block identities.
- Updating `level.dat` version tags does not convert chunk records or block state schemas;
  changing profile re-runs the writer.
- A missing database key is not a generated void column. Every column inside the export
  rectangle gets a version record, and the exporter refuses to publish if the emitted count
  does not match the rectangle.
- A void world is expressed the way vanilla expresses one: generator 2 with a single
  air layer in `FlatWorldLayers`.

## World behaviour

The arena preset sets `randomTickSpeed 0` and disables mob spawning, fire spread, weather
and the daylight cycle. This is **not** a freeze. Scheduled ticks, gravity, fluid flow and
player-triggered neighbour updates still happen, and a client or BDS can still update
unsupported decoration when chunks load or a player interacts. If blocks fall or disappear
in the winter, aquatic or foliage-heavy builds, the remedy is author-approved substitutions
or real physical support, not another gamerule.
