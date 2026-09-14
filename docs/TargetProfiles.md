# Target profiles and acceptance

A target profile is a tested combination of writer version, Chunker revision and
acceptance results. The application shows a profile's verification state in the New World
dialog, the Inspector and the export summary, and it never claims more than has been
demonstrated.

## Pinned Chunker revision

```
31c91a92bd2dda746f3e41189b603fcfd1727f04
```

Enforced by `:chunker:verifyChunkerPin`. Chunker is fetched as a git submodule and its
`cli` sources are compiled as a plain library; ChunkDaddy does not fetch a moving branch at
startup or at build time.

## Profiles offered

| Profile ID | Writer | Overworld Y range | BDS | Vanilla client | Tungsten |
| --- | --- | --- | --- | --- | --- |
| `bedrock-1.26.50` | Bedrock 26.50 | -64 to 319 | not yet verified | not yet verified | not yet verified |
| `bedrock-1.26.40` | Bedrock 26.40 | -64 to 319 | not yet verified | not yet verified | not yet verified |
| `bedrock-1.21.130` | Bedrock 1.21.130 | -64 to 319 | not yet verified | not yet verified | not yet verified |

`bedrock-1.26.40` is the baseline candidate named in the design guide. `bedrock-1.26.50`
is the newest writer available at the pinned revision. `bedrock-1.21.130` matches the
version metadata of the supplied `PVP_ZONE_FFA.mcworld`.

**Nothing in this table has been verified yet.** Filling a cell in means the procedure
below was run against a specific artifact and the result recorded, with build numbers.
Update `TargetProfile.all()` in the worker and this table together; they are meant to
disagree loudly rather than drift.

Before shipping, identify the current stable BDS and client builds, pick the matching
profile, and run the procedure. An implementation is not complete if the profile stays
behind current stable without reporting the gap.

## Milestone 0: prove the format path first

Do this before relying on the editor for anything.

- [ ] Build the worker on Windows and on Linux.
- [ ] `capabilities` lists the expected target profiles.
- [ ] Import `8-cyberpunk.schem` (Sponge v3) and `5-tropical-ruins.schem` (Sponge v2).
- [ ] Confirm the conversion report shows no `RESOLVED_TO_AIR` or `BLOCKING` entries.
- [ ] Place one copy of each with a gap between them, and export a small world.
- [ ] Import the `.mcworld` into a vanilla client; start BDS on the extracted folder.
- [ ] Visit both builds and the gap between them.
- [ ] Save, stop, restart, and visit them again.
- [ ] Confirm whether empty-column pruning, biome defaults, finalization or lighting need
      adapter changes.

**Gate:** one real world writes correctly, including an empty gap. Do not build further on
an unverified writer.

## BDS and vanilla acceptance procedure

1. Export once and keep that artifact immutable as the candidate.
2. Extract a copy into a BDS test world folder; set `level-name` in `server.properties` to
   that folder's name.
3. Start the recorded BDS build and join with a matching vanilla client.
4. Visit the FFA region, both spawns of each template's first copy, the far edges of the
   grid, and a middle and a last copy.
5. Inspect negative-Y content, doors, stairs, signs, liquids, lighting and gravity-sensitive
   decoration.
6. Visit a void gap, and a point just outside the export rectangle.
7. Save and stop cleanly, restart, and revisit both content and gaps.
8. Separately import the `.mcworld` through the vanilla client's local-world flow.
9. Record the BDS build, the client build, the world hash, and every observation.

Sampling in the client complements the automated transform and coverage checks; it does not
replace them.

## Tungsten and SwimCore acceptance

Use a **fresh copy of the original candidate export**, not a world BDS has already
rewritten: a BDS rewrite can hide a writer incompatibility.

1. Deploy the world with Tungsten's existing conventions.
2. Verify content, both spawns of sampled arenas, the gaps, and a save/reload cycle.
3. Load `arenas.json` with the actual SwimCore consumer.
4. Reserve all thirty copies of one map, verify a thirty-first request cannot double-book,
   release one, and verify it can be reserved again.

Before finalizing the exporter, read the actual consumer and verify its expected filename,
root shape, numeric types, world selection, uniqueness rules and grouping logic. Do not
assume a class or method name.

If BDS accepts an export but Tungsten does not, inspect Tungsten's world reader and its
version coverage. Fix the incompatibility or offer an explicitly tested common profile;
do not quietly degrade the output to an older format.

## Storage mistakes this project avoids, and why they matter

- Bedrock uses its own LevelDB dialect. The worker uses Chunker's `leveldb-mcpe-java`
  rather than a generic LevelDB.
- Persisted block palettes are not network runtime IDs. Runtime IDs from a live server
  session must never become durable block identities.
- Updating `level.dat` version tags does not convert chunk records or block state schemas.
- A missing database key is not a generated void column. Every column inside the export
  rectangle is emitted; the exporter refuses to publish if the emitted count does not match
  the rectangle.

## World behaviour

The arena preset sets `randomTickSpeed 0`, disables mob spawning, fire spread, weather and
the daylight cycle. This is **not** a freeze. Scheduled ticks, gravity, fluid flow and
player-triggered neighbour updates still happen, and a vanilla client or BDS can still
update unsupported decoration when chunks load or a player interacts.

The release test must inspect the winter, aquatic, foliage-heavy and gravity-sensitive
builds after loading and some interaction. If blocks fall or disappear, choose a concrete
remedy: author-approved substitutions, supported physical support, or an accepted and
documented gameplay difference. Do not claim universal freeze behaviour from one gamerule.
