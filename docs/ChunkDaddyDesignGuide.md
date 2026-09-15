# ChunkDaddy: Bedrock World Composer and Duel Arena Grid Editor

**Status:** implementation design and acceptance checklist; application not yet implemented.  
**Prepared:** 14 September 2026.  
**Repository destination:** `ChunkDaddy/docs/ChunkDaddyDesignGuide.md`.  
**Primary targets:** Windows and Linux desktop; vanilla Bedrock world storage; Bedrock Dedicated Server (BDS), vanilla Bedrock client, and Tungsten loading.

## 1. Product goal and recommended approach

Build a small, offline desktop application that combines selected regions from Minecraft worlds into a new Bedrock world and places configurable numbers of schematic arenas into a chunk-spaced grid. The output must include both the playable world and a JSON mapping from stable arena instance names to their two duel spawn positions.

The initial production composition is:

- One selected region containing the FFA arena from `PVP_ZONE_FFA.mcworld`.
- Fifteen duel arena templates, normally instantiated thirty times each: **450 duel arenas**.
- A hub region added later through the same world-tab and chunk-clipboard workflow.
- Explicitly generated void chunk columns covering the rectangular export area, including gaps and unused grid cells.

**Recommended stack:** C++20 and Qt 6 Widgets for the desktop application, with a bundled local Java worker that adapts a pinned revision of Chunker's conversion code. Use Chunker for Java/Bedrock decoding, mappings, Bedrock serialization, and its compatible LevelDB implementation. Write a small Sponge schematic adapter and the actual composition/editor logic in ChunkDaddy.

This is a deliberate development-time choice. Reimplementing current Java-to-Bedrock conversion in C++ would create the largest maintenance burden in this project. C++ still owns the native UI, layout planning, preview composition, selection, and document orchestration. Java can stream and parallelize the format work; native kernels can be introduced later if measurements identify a useful bottleneck.

Ship one desktop application with its runtime included. Users should not install Java, run a server, open a browser, or type conversion commands.

### Essential corrections to the original premise

1. **WorldEdit origin and schematic offset are placement metadata, not two duel spawns.** Missing markers use the automatic centre/surface fallback described in the spawn workflow below; distinct duel positions can be authored per template.
2. **The supplied schematics mix Sponge v2 and v3.** Supporting only one format cannot import all fifteen maps.
3. **A missing chunk is not a generated void chunk.** Export must enumerate the entire selected rectangle and serialize valid empty columns.
4. **World archives are `.mcworld`, not `.mcpack`.** Offer `.mcworld`, `.zip`, and a world directory. Resource/behavior packs are a separate asset category.
5. **A converter recognizing a version does not establish BDS compatibility.** The exact exported format must pass a vanilla client/BDS round trip and a separate Tungsten test.

## 2. Evidence and boundaries of this guide

The supplied `pvp zone worlds.zip` was inspected directly. Its nested `.mcworld` archives and `level.dat` metadata were read, and the NBT metadata and block-entity lists of its schematics were parsed. This establishes the inventory below; it does **not** establish that any source or proposed export has been opened successfully in BDS.

The current Chunker source, its format registry and selected writers, WorldEdit's Sponge v3 reader/writer, and mc-world-forge's documentation were reviewed through GitHub. The inspected Chunker tree was:

`31c91a92bd2dda746f3e41189b603fcfd1727f04`

Pin an actual tested revision in the implementation. Do not fetch a moving branch during ordinary application startup.

The ChunkDaddy repository was not discoverable through the connected GitHub account. The supplied Tungsten branch was not accessible through that connection either. No Tungsten or SwimCore implementation details are asserted here. This guide specifies the integration contract and identifies the limited checks to make against those repositories when available. The Windows source paths in the prompt are developer-machine paths; they are not available in this Linux session.

### Supplied world assets

| Asset inside the upload | Observed information | Intended use |
| --- | --- | --- |
| `PVP_ZONE_FFA.mcworld` | Bedrock archive; `lastOpenedWithVersion` is `[1,21,130,0,0]`; stored spawn `(0,65,0)`; `Generator=1` | Source tab for selecting the FFA region |
| `PVP_ZONE_15_ARENAS/15_Duel_PvP_Arenas_-_Chunkity.mcworld` | Bedrock archive; version metadata `[1,21,100,0,0]`; stored spawn `(0,-60,0)`; `Generator=2` | Alternative source/reference tab |
| `PVP_ZONE_15_ARENAS/15 Duel PvP Arenas Java World/` | Java world directory with Anvil region files | Alternative source/reference tab |
| `PVP_ZONE_15_ARENAS/Schematics/` | Fifteen named arena schematics plus `all_arenas.schem` | Primary source of repeatable templates |

Both Bedrock inputs report `StorageVersion=9` and have a `level.dat` header version of 9, despite their later game-version metadata. Treat the inputs as converter-authored files needing real validation. Do not copy their metadata blindly into a new world or infer the chunk encoding solely from one tag.

### Individual schematic inventory

Dimensions are **X × Y × Z**, in blocks. Chunk footprints assume the minimum X/Z corner is chunk-aligned and no rotation is applied.

| File | Sponge version | Java DataVersion | Dimensions | Footprint in chunks | Block entities | WorldEdit origin present |
| --- | ---: | ---: | --- | --- | ---: | --- |
| `1-desert.schem` | 3 | 4189 | 198 × 123 × 249 | 13 × 16 | 516 | Yes |
| `2-oriental.schem` | 3 | 4189 | 198 × 132 × 249 | 13 × 16 | 1,152 | Yes |
| `3-mushroom.schem` | 2 | 4325 | 173 × 98 × 171 | 11 × 11 | 0 | No |
| `4-winter.schem` | 3 | 4189 | 198 × 118 × 249 | 13 × 16 | 0 | Yes |
| `5-tropical-ruins.schem` | 2 | 4325 | 208 × 121 × 264 | 13 × 17 | 0 | No |
| `6-aquatic.schem` | 2 | 4325 | 200 × 120 × 247 | 13 × 16 | 0 | No |
| `7-mythic.schem` | 2 | 4325 | 175 × 104 × 243 | 11 × 16 | 0 | No |
| `8-cyberpunk.schem` | 3 | 4189 | 198 × 161 × 249 | 13 × 16 | 16 | Yes |
| `9-mine.schem` | 3 | 4189 | 198 × 139 × 249 | 13 × 16 | 1,693 | Yes |
| `10-greek.schem` | 2 | 4325 | 149 × 84 × 219 | 10 × 14 | 0 | No |
| `11-arabic.schem` | 3 | 4189 | 198 × 102 × 249 | 13 × 16 | 1,687 | Yes |
| `12-modern.schem` | 3 | 4189 | 198 × 132 × 248 | 13 × 16 | 0 | Yes |
| `13-pirate.schem` | 3 | 4189 | 198 × 144 × 249 | 13 × 16 | 358 | Yes |
| `14-magic.schem` | 3 | 4189 | 198 × 141 × 248 | 13 × 16 | 0 | Yes |
| `15-medieval.schem` | 2 | 4325 | 197 × 123 × 209 | 13 × 14 | 0 | No |

There are nine v3 files and six v2 files. Together they describe **86,413,299 block positions**, including air, and **5,422 block entities**. Their entity lists are absent/empty; ordinary entities are separate from block entities.

`all_arenas.schem` is v2, DataVersion 4325, dimensions **663 × 132 × 1194**, with 4,915 block entities. Its dimensions and block-entity count differ from the individual collection. Some individual templates were produced by WorldEdit/FAWE and others by Axiom. Do not assume that the aggregate, individual files, and world copies are identical revisions or share interchangeable coordinate frames.

Default the import selection to the fifteen individual files when this known collection is detected. Show `all_arenas.schem` as an aggregate candidate requiring explicit inclusion; do not silently import it as a sixteenth duel map. For arbitrary collections, let users classify aggregate files rather than relying only on filename heuristics.

## 3. Scope and completion criteria

### Required first release

- New void Bedrock world with an explicit supported target format.
- Open multiple Bedrock world folders, `.mcworld` archives, or `.zip` containers in tabs.
- Recognize multiple worlds inside a container and let the user choose which to open.
- Import Java world folders/archives through the conversion worker, using the same editing model.
- Top-down block-color preview with chunk boundaries, pan, zoom, selection, and coordinate readout.
- Rectangular full-column selection by click/drag; numeric bounds editing.
- Copy, cut, paste, move, undo, and redo, including between tabs.
- Application-owned chunk clipboard, independent of the OS clipboard.
- Multi-file `.schem` import, Sponge v2/v3, per-template counts, configurable chunk gaps, and grid preview.
- Two authored, explicitly imported, or automatic centre/surface spawn markers per duel template.
- Stable arena instance names, transformed spawn coordinates, and export of the requested JSON shape.
- Explicit void generation across the rectangular output area.
- Export selected tab to `.mcworld`, `.zip`, or a world directory, defaulting to Downloads.
- Reopenable project state with source/template identity, layout, markers, edits, and undo/recovery data as appropriate.
- Windows and Linux packages containing the conversion worker and Java runtime.

### Defer unless a supplied asset makes them necessary

Free rotation, arbitrary scaling, terrain generation, a full 3D renderer, schematic export, block painting, scripting, multiplayer editing, a web frontend, and live server connection are outside the initial scope. First release movements are translations; chunk clipboard movements use multiples of 16 blocks horizontally.

Do not defer an importer or block-entity feature actually required by the supplied maps under the label of “MVP.” Those are production fixtures.

## 4. Technology choices and responsibilities

| Area | Choice | Reason and boundary |
| --- | --- | --- |
| Desktop UI | C++20, Qt 6 Widgets | Native menus, tabs, dialogs, drag/drop, docking, painting, settings, process management |
| Viewport | Custom `QWidget` with tiled `QImage` painting | A top-down editor does not need a new graphics engine; batch tile rendering and overlays |
| Layout/editor orchestration | C++ library in ChunkDaddy | Deterministic grids, bounds, selection, instance IDs, transactions, document state |
| Format worker | Java, adapting pinned Chunker source | Central owner of semantic chunks, conversion, block entities, and Bedrock export |
| NBT | Reuse Chunker's NBT implementation inside worker | Avoid a second Java/Bedrock parser in the UI process |
| Bedrock database | Chunker's `leveldb-mcpe-java` dependency | Use a Bedrock-compatible implementation rather than stock LevelDB assumptions |
| Worker communication | `QProcess`, structured control messages, local binary files | No HTTP service or JNI requirement; clear process/crash boundary |
| JSON | Qt JSON in C++; existing worker JSON facilities | No extra library just to serialize small manifests |
| Archive handling | libzip in native layer, or one already proven ZIP library | ZIP64, streaming import/export, consistent handling on both platforms |
| Build | CMake presets + Gradle wrapper | Reproducible native and worker builds; one root build entry point |
| Packaging | Qt deployment tooling + bundled Java runtime | User launches a single app without separately installing dependencies |

Qt supplies the desktop widgets and file dialog facilities needed here. Resolve Downloads through `QStandardPaths::DownloadLocation`, and launch the worker using `QProcess` with an argument list. [Qt Widgets](https://doc.qt.io/qt-6/qtwidgets-index.html), [QFileDialog](https://doc.qt.io/qt-6/qfiledialog.html), [QStandardPaths](https://doc.qt.io/qt-6/qstandardpaths.html), [QProcess](https://doc.qt.io/qt-6/qprocess.html).

Chunker is Java-based, provides a CLI, and uses its own Java LevelDB fork. Its README identifies general entity conversion and structure data as limitations. Reusing it does not mean a ready-made schematic editor or a stable embedding API already exists. [Chunker README](https://github.com/HiveGamesOSS/Chunker/blob/31c91a92bd2dda746f3e41189b603fcfd1727f04/README.md).

Use libzip if no existing repository dependency already solves archive handling. It supports reading and writing ZIP archives; ZIP64 matters because replicated worlds can greatly exceed compressed source sizes. [libzip](https://libzip.org/).

### Keep a single semantic owner

The **worker owns decoded block data and semantic transformations**. C++ owns document commands, reference graphs, numeric layout and rendering, not a competing Bedrock world representation.

For example, C++ submits `TranslateSelection(dx,dz)`; the worker translates chunk contents and block-entity coordinates, then returns a new immutable snapshot reference and changed-tile IDs. Do not send millions of block records through JSON, and do not independently implement NBT coordinate rewriting in both languages.

Use Chunker's intermediate objects inside the worker behind a ChunkDaddy adapter. Their presence in the repository is useful, but their compatibility is not a public ABI promise. A versioned worker cache codec is ChunkDaddy work; do not serialize Java objects and treat that as a durable interchange standard. [Chunker intermediate column](https://github.com/HiveGamesOSS/Chunker/blob/31c91a92bd2dda746f3e41189b603fcfd1727f04/cli/src/main/java/com/hivemc/chunker/conversion/intermediate/column/ChunkerColumn.java).

### What to reuse from mc-world-forge

Reuse the interaction idea: configurable arena batches, explicit spacing, deterministic placement, and spawn JSON generated from placement data. Its documented Bedrock path targets an older 1.18/PMMP-oriented format, so it is not the current BDS writer for ChunkDaddy. [mc-world-forge README](https://github.com/Swedeachu/mc-world-forge/blob/main/README.md).

## 5. Desktop workflow and UI specification

### Main window

Use a conventional layout:

| Area | Contents |
| --- | --- |
| Menu bar | File, Edit, Import, View, World, Help |
| Tab bar | One document per open world; dirty indicator and close button |
| Center | Top-down world view, chunk grid, selection and paste preview |
| Left dock | Templates and arena instances; searchable map names |
| Right dock | Selection bounds, destination coordinates, spawn markers, target-format information |
| Bottom | Cursor block/chunk coordinates, selection size, job progress, cancel control |

The viewport is dominant. Advanced conversion diagnostics belong in a report panel, not the main user workflow.

### File → New Void Bedrock World

Request world name and target profile. The initial document has a void generator policy, no terrain, and an editable world-spawn marker. A suggested export rectangle may be empty until content is added.

A new document can be saved before spawns are configured. Production export requires a safe world spawn or an explicitly selected spectator-only inspection preset; never add a hidden platform that changes the user's composition.

### Drag/drop a world

Dropping a world opens it in a new tab; it does not automatically merge its contents into the active world. The source opens from an immutable working snapshot. The user selects the FFA region, copies it, switches to the output tab, and pastes.

On a container with multiple world roots, show a list with detected edition, name, and path. The supplied outer ZIP needs this behavior. File extensions are hints; identify actual roots from their structure and metadata.

### Import → Schematics…

The OS multi-file dialog accepts N files. Dragging schematic files onto a world invokes the same import dialog. Display:

- File, editable template slug, dimensions, format, palette status, marker status.
- Copy count, default **30**, editable independently per template.
- Horizontal chunk gap, optionally separate X and Z values.
- Columns, with an automatic suggestion and manual override.
- Chunk-aligned grid minimum X/Z and per-template minimum Y.
- “Minimum corner” placement mode by default; an advanced “WorldEdit anchor” mode.
- Exact-paste or non-air overlay policy.
- Proposed grid extent, total instances, output columns, and disk estimate.
- Any collision with existing FFA/hub/arena regions.

Use **Preview Grid** and **Place Grid** actions. Placement is one undoable command. Markers may be authored before or after placement, but production arena JSON cannot contain unresolved spawns.

### Export Current World…

Show current tab name and target profile. The native Save dialog chooses `.mcworld` or `.zip`; directory export uses a directory chooser. Default to Downloads, remembering the last chosen directory afterward. Include an option to return to the system default.

Before writing, show the export rectangle, arena count, unresolved errors, and companion filenames. Do not ask the user about low-level chunk versions or palette encodings.

## 6. Top-down viewer and selection behavior

### Rendering

Generate 16 × 16 block-color tiles, grouped into larger texture/image pages for rendering. Cache multiple zoom levels. At distant zoom, use reduced tiles; at close zoom, show block cells and outlines. Never allocate a full-resolution image covering the entire arena grid.

The worker derives preview samples from semantic chunks. C++ caches and paints the images. A color table with elevation shading is sufficient initially; a textured map can follow without changing editing behavior.

Offer a height-slice control and a “highest visible surface” mode. Roofs, tree canopies, and bridges otherwise make spawn authoring ambiguous. Barrier and invisible marker blocks should have an optional overlay rather than obscure the art.

Distinguish **content**, **generated void**, and **absent/unimported** columns visibly. A black background alone cannot tell the user whether a gap will be serialized.

### Navigation and selection

- Mouse wheel zooms around the cursor; middle-drag or Space-drag pans.
- Left-drag selects an inclusive rectangle of chunk columns.
- Shift adds a rectangle; an explicit subtract mode removes a rectangle.
- Show selected chunk count and editable numeric bounds.
- A Move tool drags the selected content on the chunk lattice.
- Paste previews follow the cursor and can be positioned numerically before committing.
- Escape cancels the preview without changing the document.
- The initial supported editing dimension is Overworld. Other dimensions may be inspected/imported through clearly separate views, but no dimension is silently merged into Overworld.

Use 64-bit checked arithmetic internally for coordinate products and totals. Validate against the selected world format and practical editor coordinate limits before committing. For negative coordinates, chunk division must use mathematical floor: block X `-1` is chunk `-1`, local X `15`.

## 7. Document model, clipboard, and transactions

Suggested conceptual model:

| Object | Required data |
| --- | --- |
| `WorldDocument` | UUID, name, target profile, settings, snapshot reference, export rectangle, world spawn |
| `SourceAsset` | UUID, relative project path, content hash, detected format/version, import report |
| `ArenaTemplate` | UUID, slug, source hash, dimensions, offset/origin metadata, semantic snapshot, two local spawns |
| `ArenaInstance` | UUID, stable export ID, template UUID, placement minimum, bounds, grid membership |
| `GridPlan` | UUID, ordered template/count list, cell dimensions, gaps, columns, origin, instance assignments |
| `ChunkSelection` | Dimension, chunk mask/rectangles, reference anchor |
| `ChunkClipboard` | Immutable snapshot reference, selection mask, relative bounds, included instance/marker metadata |
| `EditTransaction` | Before/after references, affected documents, bounds, instance changes, inverse operation |

All content-changing operations update **blocks and placement metadata together**. The exported spawn positions must be derived from the same committed document revision as the world bytes.

### Application-owned clipboard

Ctrl+C/X/V are handled by the chunk viewport when it has focus. They must not replace the OS clipboard. Normal text fields still use their ordinary text clipboard behavior.

Copy captures an immutable snapshot; later source edits do not modify the copied selection. Large selections are backed by project/cache files, not held entirely in RAM. Closing the source tab does not invalidate a copied selection.

Recommended cut behavior: Ctrl+X captures a pending cut and highlights it; the source is cleared only when paste commits successfully. Canceling the pending cut leaves the source unchanged. Explain this in the UI. A committed cut across two tabs is one workspace transaction and is undone consistently in both tabs.

### Moving and replacing chunks

1. Read from a pre-operation snapshot, so overlapping moves cannot overwrite their own source.
2. Validate dimensions, target height limits, destination bounds, and collision policy.
3. Materialize transformed destination records in temporary storage.
4. Rewrite every supported absolute coordinate/reference.
5. Update affected arena placements and markers.
6. Clear the vacated area to generated void; preserve overlap belonging to the moved destination.
7. Commit atomically and invalidate affected preview tiles.

A chunk selection includes the full vertical column. Copying partial block heights is a separate future feature.

### Arena metadata follows chunk edits

- Moving an entire registered arena moves its spawns, bounds, and grid placement record.
- Copying an arena creates a new instance identity and unique export name.
- Cutting an arena within one project preserves identity; a cross-project import resolves ID/name conflicts explicitly.
- Selecting part of a registered arena defaults to expanding the selection to that arena's bounds, with a deliberate “edit partial arena” alternative.
- A partial destructive edit marks that arena as needing revalidation. Export cannot pretend it remains a complete valid template instance.
- Replacing chunks that intersect another arena cannot leave its old spawn record active.

Use an edit command abstraction with disk-backed before/after data. Qt undo UI can expose the history, but asynchronous worker completion must finish before the command becomes committed. Do not let separate per-tab undo stacks contradict a cross-tab transaction.

## 8. Schematic parsing and conversion

### Required parser paths

Dispatch using the actual `Version` tag and root layout, not filename extension alone.

| Concern | Sponge v2 | Sponge v3 |
| --- | --- | --- |
| Main compound | Schematic fields directly in named root compound | Fields in `Schematic` child of root |
| Palette | `Palette` | `Blocks.Palette` |
| Block data | `BlockData` | `Blocks.Data` |
| Block entities | `BlockEntities` | `Blocks.BlockEntities` |
| Extra block-entity fields | Version-specific flattened representation | `Data` compound with separate `Id` and `Pos` |
| Placement | Normalize that version's offset conventions | Normalize offset and optional WorldEdit origin |

Both formats use compressed NBT and palette-index data. Implement schema-specific readers and normalize into one template coordinate frame. [Sponge v2 specification](https://github.com/SpongePowered/Schematic-Specification/blob/master/versions/schematic-2.md), [Sponge v3 specification](https://github.com/SpongePowered/Schematic-Specification/blob/master/versions/schematic-3.md).

For v3, the palette-data index is:

`index = x + z * width + y * width * length`

Validate dimensions, checked volume, varint termination, decoded entry count, palette membership, NBT lengths/depth, and block-entity positions before accepting the template. Dimensions are unsigned 16-bit values even though NBT uses a short tag. Bound decompression and allocations to avoid an accidental or malicious oversized file exhausting memory.

Sponge format version and Java `DataVersion` answer different questions: one selects the container schema, the other selects how Java block states and data should be interpreted. Neither is a Bedrock protocol or storage version.

### Conversion pipeline

1. Parse the schematic and retain original metadata as provenance.
2. Normalize blocks, block entities, and markers to local coordinates starting at the schematic minimum corner.
3. Resolve Java states using the declared DataVersion and the selected Chunker adapter.
4. Convert to the worker's canonical semantic representation, including fluids and block entities.
5. Audit every used palette state and every encountered block-entity type.
6. Cache the converted template once for the target profile and converter revision.
7. Instantiate the cached template using placement transforms; regenerate location-dependent records per copy.
8. Serialize final Bedrock columns through the selected writer.

Do not assume Chunker's existing CLI reads `.schem`; no schematic reader was identified in the inspected source tree. The Sponge adapter and intermediate-data bridge are explicit implementation tasks. A temporary Java Anvil world is a fallback if direct integration proves impractical, but it adds another write/read cycle and must preserve DataVersion and block entities.

### Conversion outcomes

Every encountered state/type has one of four outcomes: exact mapping, declared semantic approximation, user-selected substitution, or unsupported. Production export blocks unsupported required content. Approximations and substitutions appear in the report and require a saved user decision for that template/profile.

**Never silently replace unknown blocks with air.** Do not assume matching `minecraft:` names imply identical edition schemas. Inspect orientation, waterlogging, doors, stairs, slabs, fences, walls, vines, plants, signs, and paired containers in converted fixtures.

The supplied assets require specific coverage for brushable blocks, signs, decorated pots, structure blocks, barrels, and campfires. Empty sign text does not make a sign's block entity disposable: orientation, wax/glow state, and renderer behavior still matter.

### Paste policy

Default for an arena grid: **exact region replacement**, including schematic air. This guarantees repeatable copies and clears stale destination content within the template bounds.

Offer non-air overlay for deliberate composition. In that mode, a source air cell does not remove a destination block, but replacing a block must still clear an incompatible destination block entity or fluid layer. Generated void is only written into uncovered output columns; it must never overwrite pasted content as a final blanket pass.

No block rotations in the first release. Translation avoids having to rotate every directional block state, entity yaw, paired-container relationship, and structure reference.

## 9. Coordinate conventions and WorldEdit metadata

Use one explicit vocabulary:

- `L`: position local to the schematic minimum corner.
- `O`: stored WorldEdit copy origin, if available.
- `F`: schematic offset, minimum corner relative to the paste anchor.
- `S`: original minimum corner, where source-origin reconstruction is supported.
- `A`: requested destination paste anchor.
- `B`: destination minimum corner.
- `P`: final world position.

For the inspected WorldEdit v3 convention:

`S = O + F`  
`B = A + F`  
`P = B + L`

If the UI specifies `B`, compute `A = B - F` only when an anchor display is needed. Do not apply the offset a second time. WorldEdit's v3 reader reconstructs the minimum from origin plus offset; its writer stores offset as minimum minus origin. [WorldEdit v3 reader](https://github.com/EngineHub/WorldEdit/blob/master/worldedit-core/src/main/java/com/sk89q/worldedit/extent/clipboard/io/sponge/SpongeSchematicV3Reader.java), [WorldEdit v3 writer](https://github.com/EngineHub/WorldEdit/blob/master/worldedit-core/src/main/java/com/sk89q/worldedit/extent/clipboard/io/sponge/SpongeSchematicV3Writer.java).

For older metadata conventions, normalize in the version adapter. Do not apply the v3 WorldEdit-origin rule to every field called `WEOrigin`, `WEOffset`, or `Offset` in historical formats.

### Actual cyberpunk example

The uploaded `8-cyberpunk.schem` contains:

```text
size   = (198, 161, 249)
O      = (-27, 68, 1485)
F      = (-198, -1, -248)
S      = (-225, 67, 1237)
```

Its inclusive reconstructed source bounds are:

```text
X: -225 through -28
Y:   67 through 227
Z: 1237 through 1485
```

The copy anchor in local coordinates is `-F = (198,1,248)`. Local block X ranges from 0 through 197, so the anchor is outside the stored X extent. It is not a validated player spawn.

Suppose the desired destination minimum is `B=(4096,-32,4096)`. A source-world reference point `Q` from the same source revision becomes:

`L = Q - S`  
`P = B + L`

Only accept `Q` as source-world coordinates when the source coordinate frame is known. The Axiom v2 files with zero offsets and no WorldEdit origin do not supply enough information to reconstruct their original world positions.

Internally use half-open bounds `[min,maxExclusive)` for volumes and iteration. Display inclusive chunk bounds in the UI, labeling them clearly. Derive maxima from `min + size`; avoid scattered `+1` corrections.

## 10. Spawn authoring and validation

### What the supplied files establish

No explicit `spawnPoint1`/`spawnPoint2` metadata was found in the individual schematics. A scan for common named spawn fields/strings found no matching markers. Cyberpunk's sixteen sign block entities contain empty text. Arabic has two structure blocks, but their names/metadata are empty; these are not proof of duel spawns.

This does not exclude an intentional visual marker made of ordinary blocks. It means the application cannot reliably infer two gameplay positions from the metadata provided.

### Automatic defaults and optional authoring workflow

When a template has no markers, find its geometric centre X/Z and scan that column down
from the top for the highest non-air block. Set player-feet Y to that block's Y + 1.
If the centre column is empty, search outward for a nearby occupied column and use its
block-centred X/Z. Both required spawn entries initially share this location. An entirely
empty schematic has no fallback and still needs explicit markers.

These automatic defaults permit export without manual confirmation. Label them as
automatic in the UI and manifest, bind them to the current source hash, and preserve
existing authored markers. Automatic defaults identify a surface, not two distinct duel
positions or a verified playable floor.

To replace the defaults, author **two local player-feet positions once per template**:

1. Open the template preview.
2. Set the intended gameplay height slice, so roofs do not capture the clicks.
3. Place Spawn 1 and Spawn 2 with the marker tool.
4. Adjust X/Y/Z numerically and optionally set yaw/pitch.
5. Validate floor support, body clearance, hazards, and bounds.
6. Save the markers with the template's content hash.

If a new source hash appears, keep old markers as a visible proposal but require revalidation. Do not attach markers to a different revision simply because its filename matches.

Supported marker sources, in precedence order:

1. Explicit user-authored markers saved in the project/template sidecar.
2. A documented custom schematic metadata namespace recognized by ChunkDaddy.
3. Explicitly configured sign/structure-marker conventions.
4. Automatic centre/surface defaults, labeled automatic and accepted for export.
5. Other heuristic suggestions, labeled unconfirmed until the user confirms them.

A convenience “opposite ends” suggestion may find candidates on the playable floor. It is only a suggestion: geometric extremes can be walls, scenery, roofs, or void. If authoring markers in-game is easier, allow entering two measured coordinates with a clearly selected coordinate frame.

### Proposed reusable template sidecar

Example only: these are **illustrative coordinates, not verified desert spawns**.

```json
{
  "schemaVersion": 1,
  "templateId": "desert",
  "sourceFile": "1-desert.schem",
  "sourceSha256": "REPLACE_WITH_ACTUAL_SHA256",
  "coordinateSpace": "schematic-local-min",
  "spawnPoint1": { "x": 99.5, "y": 2.0, "z": 30.5 },
  "spawnPoint2": { "x": 99.5, "y": 2.0, "z": 218.5 },
  "confirmed": false
}
```

SHA-256 fields in saved production data must contain a real hash; the placeholder above is documentation only.

Positions represent **player feet**, not eye height or the support block's coordinate. The GUI can center X/Z on a block (`+0.5`) while preserving exact coordinates entered by the user. JSON numbers may be fractional; do not truncate unless the actual SwimCore reader requires integers and an explicit compatible export mode is selected.

For each instance, compute both spawns with the same `P=B+L` transform used for content. Metadata does not choose Y automatically. WorldEdit's Y=68 is not a requirement to place the template minimum at 68 or teleport every player there.

### Safety checks

At minimum validate that each spawn is inside the instance's accepted playable bounds, has a suitable support surface, has sufficient player-body clearance, and is not inside a damaging block or forbidden fluid. Full-block air checks are conservative; partial blocks need collision-aware handling or explicit manual validation.

Use the marker's intended floor, not the highest non-air block in that column. Revalidate after moving, substituting blocks, or editing the arena. Keep facing data in the richer manifest if the minimal SwimCore JSON does not accept it.

## 11. Deterministic grid layout

### Spacing definition

The UI setting **Gap in chunks** means the number of completely empty chunk columns between neighboring **allocated arena footprints**. It does not mean distance between arena centers or between paste anchors.

For a template of width `W` and length `D`, pasted at a chunk-aligned minimum:

`footprintX = ceil(W / 16)`  
`footprintZ = ceil(D / 16)`

The visual block-to-block gap may be larger because the final chunk is only partly occupied. Define the guarantee in chunk coordinates.

### First-release layout: uniform cells

Use a single rectangular cell size based on the largest included footprint. This is predictable and easy to inspect. Smaller templates align to each cell's minimum corner initially; optional chunk-aligned centering can be added later.

Let:

- `N = sum(copyCount)`.
- `C` be columns; `R = ceil(N/C)`.
- `Fx` and `Fz` be maximum footprint sizes.
- `Gx` and `Gz` be gap sizes in chunks.
- `Cx0,Cz0` be grid minimum chunk coordinates.

For row-major slot `i`:

```text
column = i % C
row = floor(i / C)
chunkX = Cx0 + column * (Fx + Gx)
chunkZ = Cz0 + row * (Fz + Gz)
B.x = 16 * chunkX
B.z = 16 * chunkZ
B.y = selected minimum Y for this template
```

Grid dimensions without an exterior border:

```text
widthChunks  = C * Fx + (C - 1) * Gx
lengthChunks = R * Fz + (R - 1) * Gz
```

Fill unused cells in the final row with generated void too. An optional border adds its own explicit chunk count around these bounds.

Use natural numeric ordering for the supplied filenames: 1, 2, …, 15. Strip a leading numeric prefix for the suggested slug (`1-desert` → `desert`), show the result, and resolve duplicates before placement. An aggregate import count defaults to one if explicitly included.

Default slot ordering is template-major, then copy ordinal. Save actual assignments; never depend on filesystem enumeration order. An interleaved ordering option may be added without changing instance identity rules.

### Worked 450-arena example

The largest aligned footprint in the supplied collection is **13 × 17 chunks**. With thirty copies of each of fifteen maps, choose twenty columns and a **four-chunk gap** in both directions:

| Quantity | Value |
| --- | ---: |
| Arenas | 450 |
| Grid rows | 23 |
| Cell pitch | 17 × 21 chunks |
| Grid width | `20×13 + 19×4 = 336` chunks |
| Grid length | `23×17 + 22×4 = 479` chunks |
| Rectangle | 5,376 × 7,664 blocks |
| Explicitly generated columns | `336×479 = 160,944` |

The four-chunk gap is an example, not a universal server-isolation recommendation. Choose it according to arena visibility, simulation distance, projectiles, and server scene boundaries. ChunkDaddy controls placement; the server must enforce gameplay isolation.

Automatic column selection should approximately minimize the rectangle's physical aspect-ratio error, considering unequal X/Z pitch, and then present the suggestion. The user can override it.

### Collision handling

Compare the proposed grid rectangle and every arena footprint against existing content and reserved FFA/hub areas. The default is to reject collisions and offer a different origin. An explicit replace mode shows exactly which existing content and registered arenas will be affected.

Do not auto-shift a committed grid when a hub is later added. Placement changes can invalidate deployed coordinates. Save revisions and make relayout an explicit operation.

## 12. Generated void and export extent

### The contract

Every column `(cx,cz)` inside the inclusive export rectangle must be represented as a generated column in the output database. A column can contain content, or it can be all air. Omitting a database key and hoping the server generates void does not satisfy this requirement.

Track three states separately:

| State | Meaning |
| --- | --- |
| Absent | Outside the materialized document or not imported |
| Generated void | Explicit empty column with required metadata |
| Content | Explicit column with non-air blocks or relevant data |

Void-fill operates only on absent columns inside the export extent. It does not replace existing FFA or duel content.

### Define the rectangle explicitly

The output document has one authoritative Overworld export rectangle. Default it to the bounding rectangle of all committed content and planned grids, optionally with a border. Show it in the viewport and inspector.

If the FFA is near origin and the grid is thousands of blocks away, the entire intervening rectangle is included. Warn with the resulting column count and disk estimate before committing/exporting. Keep compositions compact unless there is a gameplay reason for that distance.

### Writer behavior

Create valid empty semantic columns and send them through the same versioned Bedrock writer as content columns. Ensure no pruning/optimization stage removes explicitly requested void columns. Whether empty subchunks can be omitted or require compact all-air payloads is a target-profile decision proven by fixtures; it must not erase column existence.

Generate correct version/finalization records, biome and height information, and any required records for the chosen writer. These are version-dependent. Reuse the version-specific implementation rather than copying a few numeric constants from the old world forge exporter.

The inspected Chunker writer family handles height/biome data separately from block subchunks, and its level writer has a void-generator path. These are useful implementation hooks, not evidence that an arbitrary empty intermediate column already passes the entire acceptance test. [Chunker column writer](https://github.com/HiveGamesOSS/Chunker/blob/31c91a92bd2dda746f3e41189b603fcfd1727f04/cli/src/main/java/com/hivemc/chunker/conversion/encoding/bedrock/v1_17_30/writer/ColumnWriter.java), [Chunker level writer](https://github.com/HiveGamesOSS/Chunker/blob/31c91a92bd2dda746f3e41189b603fcfd1727f04/cli/src/main/java/com/hivemc/chunker/conversion/encoding/bedrock/base/writer/BedrockLevelWriter.java).

### Outside the rectangle

Set a tested Bedrock void-generation configuration so traveling outside the prewritten region does not generate normal terrain. This is separate from explicitly generating all columns inside the rectangle. Test both behaviors.

Do not copy the FFA's `Generator=1` settings into the output: that risks new terrain generation beyond the arena region. Source tabs contribute selected content; output-world settings are owned by the output document.

## 13. Bedrock correctness and version policy

### “Latest” must resolve to an explicit profile

At the research date, Chunker's main branch lists Bedrock formats through **1.26.50**. The official samples reviewed identify **1.26.40.05** as a non-preview release and **1.26.50.27-preview** as a preview. Therefore, selecting the numerically largest Chunker enum is not a valid way to choose stable output. [Chunker version registry](https://github.com/HiveGamesOSS/Chunker/blob/31c91a92bd2dda746f3e41189b603fcfd1727f04/cli/src/main/java/com/hivemc/chunker/conversion/encoding/bedrock/BedrockDataVersion.java), [official stable samples](https://github.com/Mojang/bedrock-samples/releases/tag/v1.26.40.05), [official preview samples](https://github.com/Mojang/bedrock-samples/releases/tag/v1.26.50.27-preview).

Use 1.26.40 as a **candidate baseline for the initial compatibility spike**, not a claim that this guide has verified the latest available BDS hotfix. Before shipping, identify the current stable BDS/client builds, select the appropriate writer, and run the acceptance tests. The public release family uses the name 26.40 while internal data/dependency identifiers can retain 1.26.40. [Official 26.40 release notes](https://www.minecraft.net/en-us/article/minecraft-bedrock-edition-26-40).

A profile records:

- App release and worker protocol version.
- Exact Chunker commit and dependency locks.
- Writer-format identifier and palette/mapping revision.
- Stable/preview designation.
- Supported dimension heights.
- Tested BDS build, vanilla client build, and Tungsten revision.
- Result/date of each compatibility test.

UI wording should be “Latest verified stable — [resolved version]”. An implementation is not complete if that profile remains behind current stable without explicitly reporting the gap. Never claim forward compatibility with an untested future release.

### Avoid three common storage mistakes

1. Bedrock uses its own LevelDB dialect/compression behavior; a generic LevelDB dependency is not automatically sufficient.
2. Persisted block palettes and versioned state data are not arbitrary network runtime IDs. Runtime IDs from a live server session must not become durable block identities.
3. Updating only `level.dat` version tags does not convert chunk records or block-state schemas.

Use the writer's persistent block-state representation, proper NBT endianness and file headers, and coherent version metadata. Preserve all required block storage layers, including fluids. Treat heightmaps, biome data, finalization, and lighting behavior as part of world correctness, not optional preview information.

For a standard modern Overworld profile, validate placement against the profile's build-height range; the common range is Y=-64 through 319 inclusive. Do not hardcode that range into all dimension adapters. Do not silently clamp a tall schematic or apply an unexpected Y shift.

### Vanilla and Tungsten compatibility

BDS/vanilla client is the primary storage acceptance authority. Tungsten is a second required loader, not the source of a proprietary output format.

If BDS accepts an export but Tungsten does not, inspect Tungsten's world reader and mapping/version coverage. Either fix that compatibility issue or offer an explicitly tested common profile. Do not degrade world storage to an older PMMP-oriented format without identifying the incompatibility and making the target choice visible.

## 14. Moving world data correctly

Copying chunks means more than changing database-key X/Z bytes. For each supported record class, document a relocation policy.

| Data | Required behavior |
| --- | --- |
| Block subchunks | Move semantic content to destination column; re-encode for target profile |
| Block entities | Update absolute position and type-specific position references |
| Paired chests/containers | Preserve or rebuild valid pair relationships; handle selections cutting a pair |
| Signs, barrels, pots, campfires | Preserve supported payloads and consistent matching blocks |
| Ordinary entities | Preserve supported types with fresh IDs on copies and relocated references |
| Ticks/events | Translate supported positions or explicitly report/drop according to selected policy |
| Biomes/height/light metadata | Move or recompute as required; invalidate stale derived data |
| Maps, lodestones, structures, portals | Use explicit adapters for global references; report unsupported relocation |
| Command data | Do not blindly rewrite strings containing coordinate-like text |
| Player records | Exclude from region composition by default |
| Source world settings | Do not inherit through chunk paste |

Chunker's documented ordinary-entity/structure limitations require an import report. The supplied schematics have no ordinary entities, but the FFA database has not been audited here for them. Do not promise lossless FFA entity transfer without that scan. [Chunker limitations](https://github.com/HiveGamesOSS/Chunker/blob/31c91a92bd2dda746f3e41189b603fcfd1727f04/README.md).

For a structure block, its own world position moves; relative structure offsets are not automatically translated as absolute coordinates. The same principle applies to other type-specific fields. Use semantic adapters, never “add delta to every x/y/z integer in NBT.”

Unknown location-dependent records block a claimed lossless move. An explicit static-arena import policy can omit unneeded records, with a report and saved choice. Opaque pass-through is only safe when edition/version, location, and referenced identity remain compatible.

## 15. World behavior and vanilla block updates

The supplied asset README recommends `randomTickSpeed 0` and WorldEdit options that suppress neighbor/update effects during Java editing. The latter are **WorldEdit commands**, not BDS world settings.

For the arena-world preset, propose and show these output settings: disabled random ticks, disabled mob spawning, disabled fire spread, fixed daylight/weather as desired, and disabled command-block execution unless explicitly needed. Resolve supported setting names through the chosen writer/profile.

`randomTickSpeed=0` does not disable all block updates, scheduled ticks, gravity, fluid flow, or player-triggered neighbor changes. Offline pasting can avoid triggering updates during editing, but vanilla clients/BDS can still update unsupported decorations when chunks load or players interact.

The release test must inspect the winter, aquatic, foliage-heavy, and gravity-sensitive builds after loading and some interaction. If blocks fall or disappear, choose a concrete remedy: author-approved block substitutions, supported physical support, or accepting a documented gameplay difference. Do not claim universal freeze behavior from one gamerule.

Gameplay mutation/reset policy belongs to SwimCore. ChunkDaddy can provide pristine templates and bounds, but it does not reserve arenas or restore destroyed blocks while the server is running.

## 16. Exported JSON and instance identity

### Minimal server-facing file

Write UTF-8 `arenas.json` with exactly the requested top-level arena-name mapping. No wrapper object, comments, or trailing commas. The following illustrates arithmetic for the local desert points above with `B=(4096,-32,4096)` and a second copy one 272-block cell pitch to the east; these are not authored production spawns.

```json
{
  "desert-1": {
    "spawnPoint1": { "x": 4195.5, "y": -30, "z": 4126.5 },
    "spawnPoint2": { "x": 4195.5, "y": -30, "z": 4314.5 }
  },
  "desert-2": {
    "spawnPoint1": { "x": 4467.5, "y": -30, "z": 4126.5 },
    "spawnPoint2": { "x": 4467.5, "y": -30, "z": 4314.5 }
  }
}
```

The prompt's illustrative JSON contains a trailing comma after the final entry; actual exports must not.

### Naming and identity rules

- `templateSlug-copyOrdinal`, such as `desert-1` through `desert-30`.
- Template UUID and instance UUID are internal stable identities; names are readable export identifiers.
- Allocate ordinal once. Deleting `desert-2` does not rename `desert-3`.
- Copying an existing instance allocates a new name; moving it preserves its name.
- Reimporting a different file with the same slug requires rename or explicit replacement.
- Do not derive the template family by blindly splitting on `-`; `tropical-ruins` itself contains a hyphen.
- Validate key uniqueness after normalization, case policy, and any consumer restrictions.

The first fully configured fifteen-by-thirty export must contain **450 entries and 900 spawn positions**. Never omit unresolved entries quietly; show the exact missing templates or instances.

### Rich manifest alongside the simple JSON

Use a separate `chunkdaddy-manifest.json` for authoring provenance and server tooling that needs more than two positions. Suggested fields:

| Field | Purpose |
| --- | --- |
| `schemaVersion`, `exportId` | Format evolution and matching world/JSON outputs |
| `documentRevision` | Exact committed snapshot exported |
| `targetProfile` | Converter/writer identity and tested target versions |
| `dimension`, `exportChunkBounds` | Coordinate frame and generated extent |
| `templates` | UUID, slug, source hash, size, confirmed local markers |
| `instances` | UUID, export ID, template UUID, minimum corner, block/chunk bounds, row/column |
| `worldSpawn` | Output world entry point, separate from duel spawns |
| `conversionSummary` | Accepted substitutions and unsupported-data decisions |

Use the manifest to restore template/group information when reopening an exported world. Validate that its version/profile/content references still match; do not trust a stale manifest after outside editing. A foreign world without a manifest remains editable but does not magically recover all arena-template identities.

### SwimCore contract

The JSON names identify independent physical arena instances. SwimCore should load them into its own available/reserved/in-use/resetting lifecycle and group them using explicit template mappings where needed.

Before finalizing the exporter, verify the actual consumer's filename, root shape, coordinate numeric types, world selection, ID uniqueness rules, grouping logic, and reserve/release behavior. Do not assume a class or method name without reading its implementation.

A useful server acceptance scenario reserves all thirty copies of one map, verifies a thirty-first request cannot double-book, releases one, and verifies it can be reserved again. ChunkDaddy exports positions and identity; queue/reservation logic stays in SwimCore.

## 17. Export packaging and durability

### Output modes

| Mode | Contents and use |
| --- | --- |
| `.mcworld` | ZIP-compatible world archive for vanilla Bedrock import |
| `.zip` | Same world-root layout, convenient for deployment/unpacking |
| Directory | Direct world folder for BDS/Tungsten deployment |

At archive root place `level.dat`, `levelname.txt`, `db/`, and any required supported pack data/references. Put `arenas.json`, `chunkdaddy-manifest.json`, and the conversion report at world root as auxiliary files. Verify the extra files do not interfere with vanilla import. Do not add an unintended enclosing directory inside `.mcworld`.

Also write an adjacent `<world-name>.arenas.json` for convenient server configuration. It must contain the same bytes as the internal `arenas.json`. A companion manifest may carry the export ID and checksums. Resource-pack archives use `.mcpack`; they are not an alternative extension for world output. Microsoft's Chunker overview documents exporting converted worlds and the world-conversion workflow. [Microsoft Chunker overview](https://learn.microsoft.com/en-us/minecraft/creator/documents/chunkeroverview?view=minecraft-bedrock-stable).

Do not ZIP a database while its writer remains open. Wait for conversion tasks, flush and close the database, then package it. Include required LevelDB manifest/table/log files from the closed database; do not delete files merely because their names contain `LOG`.

### Publication sequence

1. Freeze a committed document revision and validate it.
2. Create a staging directory on the destination filesystem where possible.
3. Stream all rectangle columns, settings, manifest, JSON, and reports into staging.
4. Close the database and perform structural validation.
5. Create a temporary archive, including its authoritative internal JSON.
6. Write temporary companion files and validate their consistency.
7. Rename/publish outputs and mark completion only after all intended outputs succeed.

A filesystem rename does not atomically replace multiple unrelated files. For a crash between archive and sidecar publication, the JSON inside the archive remains authoritative; record the incomplete companion state and offer regeneration. Never label the export complete when only the world file succeeded.

Cancellation or disk-full failure preserves the previous completed export and the editable project. Offer to remove/reuse abandoned staging files on next launch.

### Project saving

Export is not a replacement for Save Project. Save a `.chunkdaddy` project manifest with content-addressed assets/cache data in a sibling directory, or a packaged project if that is simpler for the initial implementation. A project must reopen without the original Downloads files remaining at their old paths.

Record relative paths and hashes. Store native editor metadata separately from the Bedrock database. Never distribute machine-specific absolute paths as the only way to find a template.

## 18. Performance and concurrency

The fifteen templates contain approximately 86.4 million voxel positions. Thirty copies imply approximately **2.59 billion logical template positions** before accounting for the surrounding void rectangle. Dense 32-bit indices for all copies alone would take about **9.66 GiB**, before blocks' other data, entities, caches, and output buffers.

Therefore:

- Decode and convert each template once per target-profile/source-hash combination.
- Keep template content immutable; instances begin as references plus transforms.
- Use copy-on-write storage for edited chunks.
- Represent empty subchunks and repeated palette values compactly.
- Materialize only a bounded working set during export.
- Parallelize decoding, preview generation, and preparation of independent output columns.
- Use controlled database writes with bounded queues and explicit writer ownership.
- Reuse translation-invariant prepared block payloads only when encoding, alignment, and target profile match; always regenerate coordinates, IDs, and related records.

Exact pre-encoded subchunk reuse is conditional: chunk-aligned X/Z and compatible Y alignment make it easier. An arbitrary Y shift may repartition subchunks. Do not reuse whole database records with stale coordinates simply because the voxel pattern repeats.

Start with one long-lived worker process and one writer job per output database. Bound its task concurrency instead of spawning one JVM per schematic or arena. Set a documented heap budget and account for the native process too. Allow the user to lower the memory limit on smaller machines.

Progress stages should expose discovery, decoding, mapping, previewing, placing, writing columns, validating, and packaging. Count completed columns during export. Support cancellation between bounded work units; a force-killed worker must not invalidate the last saved project.

Performance goals are measured targets, not promises: responsive pan/zoom while conversion runs; no whole-world image allocation; stable memory below the configured budget; completion of the full 450-instance fixture on the supported desktop baseline. Record hardware, elapsed time, peak native/JVM memory, output size, and column count in benchmark results.

## 19. Worker interface and repository layout

### Small local protocol

Proposed operations:

| Operation | Input | Output |
| --- | --- | --- |
| `capabilities` | Protocol version | Supported schemas, writer profiles, worker build |
| `inspect_source` | Staged path | World roots or schematic inventory |
| `import_world` | Source, target profile, policy | Immutable document snapshot and report |
| `import_schematic` | Source, target profile | Template snapshot, size, provenance, report |
| `preview_tiles` | Snapshot, bounds, height mode | Tile-file descriptors |
| `validate_markers` | Template/instance and positions | Validations and diagnostic coordinates |
| `apply_edit` | Revision, selection, transform, policy | New revision, changed bounds, metadata changes |
| `export_world` | Frozen revision, rectangle, path | Closed Bedrock world and validation report |
| `cancel` | Job ID | Cancellation state |

Use versioned JSON Lines for small requests/events with request ID, job ID, type, and structured error codes. Use binary local files for image/chunk payloads. Stdout is protocol-only; stderr carries logs. Restrict payload paths to the job workspace and validate lengths/references.

On worker crash, show a concise error and keep the last committed revision. Restart the worker and reopen saved snapshots. Transaction IDs should prevent accidental duplicate placement if the UI retries after losing a completion message.

These operation names are **proposed ChunkDaddy APIs**, not existing Chunker command-line flags.

### Suggested repository paths

| Path | Responsibility |
| --- | --- |
| `CMakeLists.txt`, `CMakePresets.json` | Native build and root orchestration |
| `src/app/` | Main window, menus, tabs, settings |
| `src/view/` | Tiled viewport, overlays, input tools |
| `src/document/` | Document metadata, transactions, clipboard, history |
| `src/layout/` | Grid planner, checked geometry, instance naming |
| `src/worker/` | Process client, protocol, job lifecycle |
| `worker/` | Gradle Java module and Chunker integration adapters |
| `worker/.../schematic/` | Sponge v2/v3 readers and normalization |
| `worker/.../conversion/` | Mapping audit, relocation, intermediate cache codec |
| `worker/.../bedrock/` | Profile selection, explicit void, validation, export hooks |
| `tests/` | Focused unit/integration tests and small owned fixtures |
| `docs/` | This guide, profile matrix, build/run notes |
| `packaging/` | Windows/Linux distribution definitions |
| `third_party/` | Pinned dependency metadata and applicable notices |

Keep the actual project root as ChunkDaddy; do not create nested copies of the repository during implementation. Keep the external test worlds out of git unless their distribution is intended. Tests can accept a local fixture path matching the prompt's Windows folder or an equivalent Linux path.

Dependency notices are a release deliverable. Chunker is MIT-licensed; WorldEdit source carries GPL headers. Reading the specification does not require embedding WorldEdit. Review actual dependency licensing when pinning binaries, including Qt and the bundled runtime; do not copy reference code without following its license.

## 20. Implementation sequence and acceptance gates

Unchecked tasks below are future implementation work, not completed claims. Complete each gate with the smallest evidence that resolves its actual risk.

### Milestone 0 — Prove the format path before building the full editor

- [ ] Pin Chunker and build a headless worker on Windows and Linux.
- [ ] Expose supported target profiles; choose the initial stable candidate.
- [ ] Parse one v3 fixture (`8-cyberpunk`) and one v2 fixture (`5-tropical-ruins`).
- [ ] Bridge them to Chunker's semantic conversion path without losing palette states.
- [ ] Export a tiny world with both templates and a small explicit void rectangle.
- [ ] Import/open it with a vanilla client and start BDS using the extracted world.
- [ ] Visit content and void; save, stop, restart, and visit again.
- [ ] Confirm whether empty-column pruning, biome defaults, finalization, or lighting need adapter changes.

**Gate:** prove one real world writes correctly, including an empty gap. If direct Chunker integration needs a patch, keep it in a small adapter/fork and document the pinned change. Do not build a large GUI on an unverified writer assumption.

### Milestone 1 — Native shell, worlds, and viewer

- [ ] New void document, tabs, native open/save dialogs, drag/drop routing.
- [ ] Nested archive/world-root discovery for the actual uploaded container.
- [ ] FFA and duel reference worlds open from safe snapshots.
- [ ] Tiled top-down preview, chunk grid, coordinate readout, height slice.
- [ ] Source format/target profile and import errors are visible.
- [ ] Cancellation and worker restart preserve committed state.

**Gate:** open the FFA and output tabs, inspect the actual arena, and select its chunk bounds accurately.

### Milestone 2 — Chunk clipboard and composition

- [ ] Application clipboard with snapshot-backed copy/pending-cut/paste.
- [ ] Move and replace operations rewrite semantic data.
- [ ] Cross-tab transactions and undo/redo.
- [ ] Negative-coordinate and overlapping-move handling.
- [ ] Void clearing at vacated positions and explicit destination collisions.
- [ ] Save/reopen the composed project.

**Gate:** move/copy the FFA region into the new world, undo/redo it, export, and verify the selected build and relocated block entities.

### Milestone 3 — All schematic templates and spawns

- [ ] All fifteen individual inputs import with correct v2/v3 handling.
- [ ] Complete mapping/type report for their encountered data.
- [ ] Aggregate schematic kept separate from the individual batch.
- [ ] Two local markers can be authored, edited, validated, and saved per template.
- [ ] Source hash changes invalidate marker confirmation.
- [ ] Minimum-corner and WorldEdit-anchor placement produce identical results when configured equivalently.

**Gate:** every template has a verified preview and two confirmed positions; no unknown block silently becomes air.

### Milestone 4 — Grid and arena identity

- [ ] Per-template counts, gaps, columns, origin, and minimum Y.
- [ ] Deterministic natural ordering and stable instance IDs.
- [ ] Preview rectangle, chunk count, overlaps, and disk estimate.
- [ ] Thirty copies of each individual map produce 450 instances.
- [ ] Arena metadata follows complete moves/copies/cuts.
- [ ] Partial arena edits invalidate or update the corresponding metadata explicitly.

**Gate:** the grid has the configured gaps, no unapproved overlap, and every instance's world-space spawns match its transform.

### Milestone 5 — Full export and server integration

- [ ] Every column in the output rectangle is emitted, including unused last-row cells.
- [ ] Void generator behavior outside the rectangle is confirmed.
- [ ] `.mcworld`, `.zip`, and directory outputs have the correct root layout.
- [ ] Internal and adjacent arena JSON match exactly.
- [ ] Manifest/report and export revision identify the same world snapshot.
- [ ] Full dataset passes the target BDS/client checks.
- [ ] Output separately loads in Tungsten; limitations are resolved or precisely documented.
- [ ] Actual SwimCore JSON loader and reservation assumptions are verified.

**Gate:** deployable world plus 450-entry arena JSON; compatibility is demonstrated rather than inferred from converter success.

### Milestone 6 — Desktop distribution

- [ ] Windows package runs on a clean supported machine without installed Java.
- [ ] Linux package runs on a clean supported distribution with the selected Qt backend.
- [ ] Native file dialogs, Downloads path, spaces/Unicode paths, and drag/drop work.
- [ ] Cancellation, disk-full behavior, corrupt archives, and interrupted export preserve previous work.
- [ ] Dependency versions/notices and a concise build guide are included.
- [ ] Current stable target profile is verified and recorded before release.

**Gate:** normal users complete the entire workflow through the GUI on either platform.

## 21. Focused validation strategy

### Small automated tests with high value

| Test | Risk resolved |
| --- | --- |
| Tiny asymmetric v2/v3 schematic with known corner blocks | Wrong axis order, palette decoding, root handling |
| Cyberpunk origin/offset arithmetic | Double offset and mistaken spawn-anchor semantics |
| Negative block/chunk coordinates | Truncating division and off-by-one selections |
| Mixed-footprint grid with partial final row | Gap semantics, deterministic dimensions, void extent |
| One translated sign/container and overlap move | Content/reference relocation and source clobbering |
| Whole-arena move/copy/delete plus undo | World content and exported JSON becoming inconsistent |
| Empty gap saved/reloaded through writer | Missing columns disguised as generated void |
| Unknown palette state | Silent air substitution |
| Malformed varint and archive path traversal | Invalid input reaching unbounded allocation or filesystem writes |
| Failure after archive publication before sidecar | Mismatched or falsely completed exports |

Use miniature fixtures for mathematical/storage edge cases; do not repeatedly regenerate 450 maps to test a UI label. Run the full fixture when changing format handling, layout materialization, or packaging behavior that affects it.

### Production dataset checks

- All fifteen source templates recognized with observed dimensions and versions.
- Original block-entity count accounted for by preserved, converted, or explicitly handled records; translated copies do not share mutable IDs/references incorrectly.
- Every used palette state receives a documented mapping result.
- Exactly 450 unique arena IDs and 900 confirmed spawn positions for the default batch.
- Exact expected number of generated columns inside the chosen rectangle.
- No silent Y clipping, extra coordinate offset, or normal-terrain generation in gaps.
- Per-template representative visual checks, including fragile decorations.

### BDS and vanilla acceptance procedure

1. Export once and keep that artifact immutable as the candidate.
2. Extract a copy to a BDS test world's folder; set `level-name` to the selected folder name in the test server configuration.
3. Start the recorded BDS build and join with a matching vanilla client.
4. Visit the FFA, both spawns of each template's first copy, distant grid edges, and selected middle/last copies.
5. Inspect negative-Y content, doors/stairs, signs, liquids, lighting, and gravity-sensitive art.
6. Visit void gaps and a point just beyond the export rectangle.
7. Save and stop cleanly, restart, and revisit content and gaps.
8. Separately import the `.mcworld` into the vanilla client's local-world flow.
9. Record builds, world hash, observations, and any accepted substitutions.

Sampling in the client complements automated transform/coverage checks for every instance; it does not replace them.

### Tungsten and SwimCore acceptance procedure

Use a fresh copy of the original candidate export, so a prior BDS rewrite does not hide a writer incompatibility. Configure Tungsten to load that world using its existing deployment conventions. Verify content, spawns, gaps, save/reload, and then load the actual JSON consumer.

If BDS normalization is intentionally required, document it as part of export and test that explicitly. Prefer direct compatibility so users do not need a hidden manual repair step.

Do not claim Tungsten or SwimCore compatibility is tested until their actual code/runtime is available and the test is performed.

## 22. Final definition of done

ChunkDaddy is complete for this request when a user can:

1. Launch the application on Windows or Linux.
2. Create a void Bedrock output world.
3. Open the FFA source, select its arena chunks, and paste them into the output tab.
4. Select the fifteen individual schematics in one OS dialog.
5. Confirm two spawns per template once, set thirty copies and a chunk gap, and place the grid.
6. Add or move a future hub region through the same clipboard workflow.
7. Inspect the entire composition and its generated-void export rectangle.
8. Export to Downloads as `.mcworld`, `.zip`, or a world folder, with matching arena JSON.
9. Load the result in vanilla Bedrock/BDS and Tungsten, with SwimCore reserving distinct instances at the exported coordinates.
10. Reopen the project and change layouts without losing source identity, markers, or instance names.

The first engineering task is Milestone 0: prove the real schematic-to-Bedrock conversion and explicit void-column behavior. That resolves the main uncertainty early and makes the rest of the application a bounded desktop editing project.
