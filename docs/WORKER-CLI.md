# Driving ChunkDaddy from the command line

The ChunkDaddy worker is a headless process that speaks **JSON Lines over stdin/stdout**.
It is the whole engine: importing worlds and schematics, placing arenas, editing chunks and
exporting. The Qt application is a client of this protocol and nothing more, so anything
the app can do can be done from a shell or a script with no display, no X server and no GUI
build. Driving the worker does not interfere with a running app — each app session starts
its own worker process, and the one you start is yours alone.

This document is the reference for doing that. It is written to be executed, not skimmed.

---

## 1. Start a worker

```bash
java -cp <classpath> gg.swim.chunkdaddy.worker.WorkerMain --workspace <dir>
```

| argument | meaning |
|---|---|
| `--workspace <dir>` | Scratch root: extracted sources, preview tiles, export staging, logs. Created if absent. A temp directory is used when omitted. |
| `--version` | Print `chunkdaddy-worker <version>` and exit. |

The classpath is the shadow jar produced by the Gradle build:

```bash
java -cp worker/build/dist/chunkdaddy-worker.jar gg.swim.chunkdaddy.worker.WorkerMain --workspace /tmp/ws
```

To run freshly compiled classes against that jar's dependencies, **put the classes first** —
the shadow jar also contains a copy of the worker's own classes, and a stale copy will
silently win:

```bash
java -cp out:worker/build/dist/chunkdaddy-worker.jar gg.swim.chunkdaddy.worker.WorkerMain --workspace /tmp/ws
```

Give it heap. The default JVM heap is often ~1.5 GB, which is not enough for a large world:
`-Xmx6g` is a reasonable starting point.

### The two streams

- **stdout carries protocol frames only.** One JSON object per line, nothing else, ever.
- **stderr carries everything else** — worker logs, Chunker's own logging, stack traces.
  `System.out` is redirected to stderr at startup precisely so a stray library `println`
  cannot corrupt the protocol stream. stderr is worth capturing; it is not noise.

---

## 2. Frames

**Request** — you send, one per line. `id` is yours to choose; correlate replies by it.

```json
{"id": 1, "type": "inspect_source", "path": "/worlds/hub.zip"}
```

**Result** — success.

```json
{"id": 1, "ok": true, "result": { ... }}
```

**Error** — failure. `code` is stable and branchable; `message` is for humans.

```json
{"id": 1, "ok": false, "error": {"code": "document.missing", "message": "No open document ..."}}
```

**Event** — progress, emitted zero or more times *before* the result for the same `id`.

```json
{"id": 6, "event": "progress", "payload": {"stage": "Preparing world columns", "done": 48000, "total": 1263712}}
```

An event has no `ok` field. Test for `"event" in frame` first, then `frame["ok"]`.
`total` is `0` when the work is unbounded (a spinner, not a bar). Schematic import emits
payloads with `stage`, `fileName`, `done`, `total`.

### Concurrency

The worker dispatches each request on a thread pool, so **replies can arrive out of order**
if you have several requests in flight. `shutdown` and `cancel` bypass the pool — `cancel`
deliberately so, since it must not queue behind the job it is cancelling. The simplest
correct client keeps one request outstanding at a time; that is what the driver below does.

### Lifetime — the thing that trips up scripts

**Documents, templates and the clipboard live in the worker process's memory.** They are not
on disk and they do not survive the process. A `documentId` from one worker run is
meaningless to the next. A whole job — import, place, export — must run against **one
worker process**. If you shut the worker down between steps you start from nothing.

Finish with `{"type": "shutdown"}` and close stdin.

---

## 3. The driver: `tools/chunkdaddy_cli.py`

Raw framing is fine for one call and tedious for a job. The driver adds request ids, reply
correlation, progress reporting, timing and — the useful part — binding one step's result
into the next step's request.

```bash
export CHUNKDADDY_CLASSPATH=worker/build/dist/chunkdaddy-worker.jar
export CHUNKDADDY_JAVA=java          # optional, defaults to `java`

python3 tools/chunkdaddy_cli.py --workspace /tmp/ws --jvm=-Xmx6g run plan.jsonl
```

Results go to **stdout as JSON Lines**, one per step; progress, timings and errors go to
**stderr**. Pipe stdout into `jq` or a file and leave stderr on the terminal.

```
--classpath PATH     worker classpath (or $CHUNKDADDY_CLASSPATH)
--java PATH          java binary (or $CHUNKDADDY_JAVA)
--workspace DIR      worker workspace (default ./chunkdaddy-workspace)
--worker-log FILE    capture the worker's stderr here
--jvm=-Xmx6g         extra JVM argument, repeatable. Use = ; a bare -Xmx6g is read as a flag.
--keep-going         carry on after a failed step instead of stopping
```

### Modes

**`run PLAN.jsonl`** — the normal way. One request per line. Blank lines and `#` comments
are skipped. Three meta keys, stripped before sending:

| key | meaning |
|---|---|
| `_save` | Bind this step's `result` under a name for later `${...}` references. |
| `_note` | A label printed to stderr. |
| `_timeout` | Seconds to wait for the reply. Default: wait forever. Set it for exports. |

`${name.path.0.field}` substitutes from a bound result — dotted keys, numeric list indices.
A placeholder that is the *entire* string keeps the referenced value's type, so numbers stay
numbers; a placeholder inside a longer string interpolates as text.

**`call TYPE k=v ...`** — one request, result pretty-printed. Values are parsed as JSON when
they can be, so `minX=1120` is a number and `paths='["/a.schem"]'` is an array.

```bash
python3 tools/chunkdaddy_cli.py call capabilities
python3 tools/chunkdaddy_cli.py call inspect_source path=/worlds/hub.zip
```

**`repl`** — requests on stdin, one result per line on stdout; ids are added for you. For
piping from another program.

```bash
printf '%s\n' '{"type":"capabilities"}' | python3 tools/chunkdaddy_cli.py repl
```

---

## 4. Request reference

`REQ` = required. Every request needs `type`; most document operations need `documentId`.

### Session

| type | fields | returns |
|---|---|---|
| `capabilities` | — | protocol/worker version, Chunker commit, Java version, `maxHeapBytes`, schematic schemas, `targetProfiles[]` |
| `shutdown` | — | `{}`, then the worker exits |
| `cancel` | `jobId` REQ (the `id` of the request to cancel) | `{"state": "cancelling"\|"notRunning"}` |

### Opening worlds

| type | fields | returns |
|---|---|---|
| `inspect_source` | `path` REQ | `scanRoot`, `worlds[]` of `{directory, relativePath, name, edition, versionDescription}`. Extracts archives (`.zip`, `.mcworld`) into the workspace first. Empty `worlds[]` comes with a `note`. |
| `open_world` | `directory` REQ, `edition` REQ (`BEDROCK`/`JAVA`), `profileId`, `name` | document state + `importedColumns`, `notices[]`, `otherDimensions[]`, `sourceExperiments[]`, `importedBounds` |
| `new_document` | `name`, `profileId` | document state |
| `document_info` | `documentId` REQ | document state |
| `close_document` | `documentId` REQ | `{}` |
| `set_document_name` | `documentId` REQ, `name` REQ | document state |

**Document state** (returned by most calls) carries `documentId`, `name`, `profileId`,
`revision`, `dirty`, `canUndo`, `canRedo`, `materializedColumns`, `arenaCount`,
`contentBounds`, `exportRectangle`, `hasExplicitExportRectangle`, `exportBorderChunks`,
`worldSpawn`, `gameType`, `difficulty`, `arenas[]`, `templates[]`.

A **chunk rectangle** is `{minChunkX, minChunkZ, maxChunkX, maxChunkZ}`, inclusive, and
comes back with `widthChunks`, `lengthChunks`, `columnCount` added.

### Templates and placement

| type | fields | returns |
|---|---|---|
| `import_schematics` | `paths[]` REQ | `templates[]`, `failures[]`. Per-file failures do not fail the request. |
| `set_template_spawns` | `templateId` REQ, `spawnPoint1`, `spawnPoint2`, `confirmed` | template state |
| `place_grid` | `documentId` REQ, `placements[]` REQ, `replaceExisting` | edit result |

A **placement** is `{templateId, slug, minX, minY, minZ}` plus optional `exportId`,
`ordinal`, `gridRow`, `gridColumn`. `minX`/`minZ` must be chunk aligned (multiples of 16);
the whole Y extent must fit the profile's block range or the request is refused by name.

### Editing

| type | fields |
|---|---|
| `copy_selection` | `documentId` REQ, `add[]` REQ, `subtract[]`, `cut` |
| `paste_clipboard` | `documentId` REQ, `clipboardId` REQ, `destChunkX` REQ, `destChunkZ` REQ, `replaceExisting` |
| `move_selection` | `documentId` REQ, `add[]`/`subtract[]`, `deltaChunkX` REQ, `deltaChunkZ` REQ, `replaceExisting` |
| `clear_selection` | `documentId` REQ, `add[]`/`subtract[]` — **deletes those chunks** |
| `undo` / `redo` | `documentId` REQ |

A **selection** is `add[]` and optional `subtract[]`, each an array of chunk rectangles.
Selections always cover whole vertical columns.

> `copy_selection` currently passes no block-entity resolver, so copying a selection that
> contains a chest, sign or similar fails with *"A block entity resolver is required to copy
> block entities"*. Pure terrain copies fine. See the handoff notes.

### World settings

| type | fields |
|---|---|
| `set_export_rectangle` | `documentId` REQ, `rectangle` (omit or null to revert to content bounds), `borderChunks` |
| `set_world_spawn` | `documentId` REQ, `x` REQ, `y` REQ, `z` REQ |
| `get_level_settings` / `set_level_settings` | `documentId` REQ, `values` object for set |
| `reset_level_settings` | `documentId` REQ — back to the source world's |
| `adopt_level_settings` | `documentId` REQ, `fromDocumentId` REQ |

### Preview

| type | fields | returns |
|---|---|---|
| `preview_tiles` | `documentId` REQ, `area` REQ, `sliceY` (320), `pixelsPerChunk` (1/4/16, default 16), `heightMode` (`HIGHEST_SURFACE`/`SLICE`) | `{path, revision}` — a binary `.cdat` tile file in the workspace |

`area` is capped at 4096 columns per call.

### Export

| type | fields | returns |
|---|---|---|
| `validate_export` | `documentId` REQ, `profileId` | `exportRectangle`, `columnCount`, `estimatedBytes`, `arenaCount`, `spawnPointCount`, `problems[]`, profile info |
| `export_world` | see below | `path`, `contentColumns`, `voidColumns`, `totalColumns`, `arenaCount`, `spawnPointCount`, `companionJsonWritten`, `warnings[]`, `preservedTags[]`, `preservedNotes[]`, `logPath`, `revision` |

`export_world` fields:

| field | default | meaning |
|---|---|---|
| `documentId` | REQ | |
| `destination` | REQ | output path |
| `mode` | `MCWORLD` | `MCWORLD` (zipped) or `FOLDER` |
| `rectangle` | document's | override the export rectangle for this run only |
| `profileId` | document's | re-stamp for another Bedrock version without rebuilding |
| `worldName` | document name | |
| `numberMode` | `EXACT` | arena JSON number formatting |
| `arenaPreset` | `true` | no random ticks, no mob spawning, no fire spread |
| `writeCompanionJson` | `true` | also write `<name>.arenas.json` beside the output |
| `writeVoidColumns` | `true` | write an explicit empty column for every content-free column in the rectangle. **See §6.** |

Every export writes `<world-name>-export.log` beside the destination (falling back to
`<workspace>/logs`): the request, the composition audit, phase milestones with timings,
result counts, and on failure the full exception chain. `logPath` is in the result, and a
failure message ends with `Full details: <path>`.

### Void cleaner

| type | fields | returns |
|---|---|---|
| `clean_void` | `path` REQ (world folder, `.mcworld` or `.zip`), `destination`, `mode` (`MCWORLD`/`ZIP`/`DIRECTORY`), `dryRun` (false), `airSubChunks` (false) | `path`, `columnsScanned`, `columnsKept`, `columnsRemoved`, `keysRemoved`, `valueBytesRemoved`, `worldRecordsPreserved`, `removedByRecordType{}`, `summary`, `notes[]` |

Operates on a finished world's database. It does **not** open the world as a document,
because importing and re-exporting would drop every record that is not a chunk. See §7.

---

## 5. A complete job

This is the plan used for the measurements in §6, verbatim.

```jsonl
{"_note":"find world roots inside the zip","type":"inspect_source","path":"/in/hub.zip","_save":"src"}
{"_note":"import the hub world","type":"open_world","directory":"${src.worlds.0.directory}","edition":"${src.worlds.0.edition}","name":"hub","profileId":"bedrock-1.26.40","_save":"doc"}
{"_note":"import the schematic","type":"import_schematics","paths":["/in/prisonmine2.schem"],"_save":"tpl"}
{"_note":"place one copy at chunk 70,60","type":"place_grid","documentId":"${doc.documentId}","replaceExisting":false,"placements":[{"templateId":"${tpl.templates.0.templateId}","slug":"${tpl.templates.0.slug}","minX":1120,"minY":0,"minZ":960,"ordinal":1}],"_save":"placed"}
{"_note":"pre-flight checks","type":"validate_export","documentId":"${doc.documentId}","_save":"check"}
{"_note":"export","type":"export_world","documentId":"${doc.documentId}","destination":"/out/hub-with-mine.mcworld","mode":"MCWORLD","worldName":"hub-with-mine","_timeout":3600,"_save":"exported"}
```

```bash
python3 tools/chunkdaddy_cli.py --workspace /tmp/ws --worker-log /tmp/worker.log \
    --jvm=-Xmx6g run plan.jsonl > results.jsonl
jq -r 'select(.ok) | "\(.type) \(.seconds)s"' results.jsonl
```

Without the driver, the same thing by hand:

```bash
{
  echo '{"id":1,"type":"inspect_source","path":"/in/hub.zip"}'
  sleep 20                      # crude: real clients read the reply before sending the next
  echo '{"id":99,"type":"shutdown"}'
} | java -cp worker/build/dist/chunkdaddy-worker.jar \
       gg.swim.chunkdaddy.worker.WorkerMain --workspace /tmp/ws 2>/tmp/worker.log
```

Blind piping like that only works for independent requests. Anything that needs a
`documentId` has to read the previous reply first, which is the driver's whole job.

### Checklist for an agent

1. One worker process per job. Ids do not survive a restart.
2. Read stdout line by line; dispatch on `event` before `ok`.
3. Keep one request outstanding unless you are tracking ids properly.
4. Give the JVM `-Xmx6g` or more for a real world.
5. Set `_timeout` on exports; everything else is fast.
6. On failure, read `error.code` first, then the export log named in the message.
7. Capture stderr. When something goes wrong the stack trace is there, not on stdout.

---

## 6. What this reveals about export cost

Measured on a real hub world (14,292 imported columns) plus one placed arena, exported as
`.mcworld` on a 6 GB heap.

| | columns | content | void | write | total | `.mcworld` | db unpacked |
|---|---|---|---|---|---|---|---|
| default rectangle | 1,263,712 | 14,341 | 1,249,371 | 113.0s | **120.9s** | **52.8 MB** | 61.0 MB |
| rectangle tightened to the main island | 31,228 | 9,294 | 21,934 | 7.4s | 9.3s | 3.1 MB | 3.7 MB |
| default rectangle, `writeVoidColumns:false` | 1,263,712 | 14,341 | 0 written | — | **9.3s** | **4.0 MB** | 4.7 MB |

Solving the two-variable system across the first two rows gives the marginal cost of a
column:

- **content column: ~599 µs, ~295 bytes**
- **void column: ~84 µs, ~45 bytes**

So on the default rectangle, **void columns are 92% of the write time and 93% of the
bytes**. Two independent causes, with different fixes:

### 6a. The export rectangle is one box around disjoint islands

The hub's content is **9 separate islands**. Two of them — 180 columns at chunk `-1,-1257`
and 212 columns at `695,-21` — sit 1,100 and 630 chunks from everything else. The bounding
box of all content is therefore 782 × 1,616 = 1,263,712 columns to hold 14,292 real ones:
**98.9% void**.

The per-island boxes total 33,948 columns. Making the export region a union of content
clusters (each padded by `exportBorderChunks`) instead of one global box is **37× less work
on this world**, with no change to what is written where content exists. This is the safe
fix, and it needs no decision about semantics.

### 6b. Explicit void is redundant for a flat-air world

The exporter stamps the written `level.dat` with `Generator = 2` (flat) and
`FlatWorldLayers` = a single layer of `minecraft:air`. **Any column the game generates on
its own is already void.** The 1,249,371 explicitly written empty columns reproduce exactly
what that generator produces for free.

Verified by re-importing both exports and comparing block content on a stride hash:

```
only in A (full): 1,249,371  (1,249,371 of them empty, 0 with blocks)
only in B (novoid): 0
shared columns: 14,341      differing: 0
RESULT: every column carrying blocks is identical in both worlds.
```

`writeVoidColumns: false` is therefore an opt-in flag, **defaulting to `true`** so the
original contract is unchanged. It is sound exactly when the written `level.dat` already
makes ungenerated ground void, which is the case for every profile this exporter currently
writes. It has **not been confirmed in-game** — that is the one check that cannot be done
headlessly, and it should be done before the default changes.

### 6c. Secondary findings

- **`validate_export` estimated 2,588,082,176 bytes; the export was 52,784,660.** The
  formula is a flat `columns * 2048`, which is ~7× the measured cost of a content column
  and ~45× that of a void one. It is meant to be conservative, but at 49× high on a real
  world it tells the user nothing useful about scale.
- **Re-importing an exported world inherits its void.** Re-opening `hub-with-mine.mcworld`
  gives a document with 1,263,712 materialized columns instead of 14,341, so the cost
  compounds on every round trip through ChunkDaddy.
- Even with both fixes, a void column still costs ~84 µs — a `ChunkerBiome[256]` allocated
  and filled per column in `ColumnOps.voidColumn`, plus a full walk through Chunker's
  pre-transform handler including a `processedColumns` set that grows to one entry per
  column. Sharing one immutable biome array and short-circuiting empty columns past the
  pre-transform stage are the next things to look at, but after 6a and 6b there is far less
  left to win.


---

## 7. The void cleaner

```bash
java -cp worker/build/dist/chunkdaddy-worker.jar \
     gg.swim.chunkdaddy.worker.VoidCleanerMain <world-or-archive> [output] [options]
```

| option | meaning |
|---|---|
| `--dry-run` | Report what would go. Writes nothing, counts everything. |
| `--air-sub-chunks` | Also drop columns whose only sub-chunks are a single air palette entry. |
| `--zip` | Write a `.zip` instead of a `.mcworld`. Same archive, different name. |
| `--folder` | Write a world folder instead of an archive. |
| `--quiet` | Summary line only. |

### Cleaning a folder of worlds

```powershell
# look first
scripts\void-clean-all.ps1 -Folder C:\path\to\savedWorlds -DryRun
# then do it
scripts\void-clean-all.ps1 -Folder C:\path\to\savedWorlds
```

One JVM per world, so a world that fails does not take the batch with it. Cleaned archives
land in a `cleaned` subfolder as `<name>-cleaned.zip`; sources are never modified and
anything already named `*-cleaned` is skipped, so re-running is safe.

Takes a world folder, a `.mcworld` or a `.zip`. The source is never modified. Also
available as the `clean_void` protocol request, and as the **Void cleaner** checkbox on the
export dialog, which is on by default.

### What it removes

A column goes only when **all** of these hold:

- no sub-chunk records at all — or, with `--air-sub-chunks`, only sub-chunks whose block
  palette is a single entry of `minecraft:air`;
- no legacy terrain;
- no block entities, entities or actor digest;
- no pending ticks, random ticks, hardcoded spawners, border blocks or legacy block extra
  data.

Biomes and heightmaps do not count as content. They are what the generator produces anyway,
and keeping a column alive for them is the whole waste being removed.

### What it never touches

Everything else, byte for byte: scoreboards, villages, maps, player data, ticking areas,
structure templates, portals, the level chunk metadata dictionary, `actorprefix` records,
`level.dat`, `levelname.txt`, `world_icon.jpeg` and any packs beside the database.

This is why the cleaner works on the database directly instead of importing and
re-exporting. A round trip through the composer understands chunks and nothing else, and
would silently drop every record in that list.

Recognising a chunk key by length alone would be a bug: `BiomeData`, `Overworld` and
`mVillages` are each nine ASCII bytes, exactly the length of an overworld chunk key. The
cleaner requires the tag byte to be one Bedrock actually uses, and anything it cannot parse
confidently is treated as a world record and kept. `VoidCleanerTest` builds a world
containing one of everything — those three collisions included — and asserts every record
survives.

### Measured

A hub exported with the full rectangle, cleaned:

| | before | after |
|---|---|---|
| `.mcworld` | 62.0 MB | **3.6 MB** (17.1× smaller) |
| columns | 1,263,712 | 2,782 |
| database keys | 8,860,186 | 33,676 |
| `level.dat` | — | byte identical |

Block verification, by re-importing both and hashing every column's sub-chunks:

```
only in A: 1,260,930  (1,260,930 of them empty, 0 with blocks)
only in B: 0
shared columns: 2,782      differing: 0
RESULT: every column carrying blocks is identical in both worlds.
```

### Two kinds of waste, and why the checkbox catches both

`writeVoidColumns: false` stops the exporter writing the export rectangle's empty columns.
That is the 1.25 million, and skipping them is where the time is saved.

It cannot see the second kind. The composer counts a column as content if the document has
it materialized, whether or not it holds any blocks — and a world imported from a live
server is full of columns the server saved the moment a player flew over them. On the
measured hub, **11,889 of 14,341 "content" columns contained no blocks at all**. Only a
pass over the written database finds those.

So the `voidCleaner` export flag does both: it turns off explicit void writing, then runs
the cleaner over the staged world before packaging.

| hub + 1 arena | time | `.mcworld` |
|---|---|---|
| default rectangle, no cleaning | 120.9s | 52.8 MB |
| `writeVoidColumns: false` only | 9.3s | 4.0 MB |
| `voidCleaner: true` (the checkbox) | 18.6s | **3.3 MB** |

The cleaner pass costs about ten seconds and takes another 0.7 MB off. It also folds the
write-ahead log, which is worth more than it sounds: an unfolded log is uncompressed, and
the first version of this tool produced a 59 MB directory that collapsed to 3.7 MB the
moment anything opened it.

### Caveat

Removing an explicit empty column is sound exactly when the written `level.dat` already
makes ungenerated ground void. Every profile this exporter writes stamps `Generator = 2`
with a single `minecraft:air` layer, so that holds. It has **not been confirmed in-game** —
load a cleaned world and walk the gaps before trusting it on something you cannot rebuild.
