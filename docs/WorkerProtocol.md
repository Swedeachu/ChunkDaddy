# Worker protocol

Versioned JSON Lines over the worker's standard streams. One JSON object per line.

- **Standard output carries protocol frames only.** Chunker and the JVM log to
  `System.out` in places, so the worker captures the real descriptor at startup and
  redirects `System.out` to standard error. A stray log line on the protocol stream would
  desynchronize the native client.
- **Standard error carries logs.** The application shows them in the Reports dock.
- **Large payloads never travel through the protocol.** Preview tiles and worlds are files
  inside the job workspace; only descriptors are exchanged.

## Frames

Request:

```json
{"id": 12, "type": "export_world", "documentId": "…", "destination": "…"}
```

Reply:

```json
{"id": 12, "ok": true, "result": { … }}
{"id": 12, "ok": false, "error": {"code": "export.empty", "message": "…"}}
```

Event, correlated with the request that caused it:

```json
{"id": 12, "event": "progress", "payload": {"stage": "writingColumns", "done": 4096, "total": 160944}}
```

The request id is also the job id. `cancel` is answered out of band so it never queues
behind the job it is cancelling.

## Operations

| Type | Purpose |
| --- | --- |
| `capabilities` | Protocol version, worker build, Chunker revision, supported schemas, target profiles |
| `inspect_source` | Extract a container if needed and list the world roots inside it |
| `new_document` | Create a void Bedrock world |
| `open_world` | Convert an existing Bedrock or Java world into a document |
| `close_document` | Forget a document. Clipboard entries captured from it stay valid |
| `document_info` | Current state of a document |
| `import_schematics` | Parse, convert and audit `.schem` files into templates |
| `set_template_spawns` | Author and validate a template's two local spawn markers |
| `place_grid` | Apply placements computed by the native layout planner |
| `copy_selection` | Capture a selection into the application clipboard; `cut` marks it pending |
| `paste_clipboard` | Commit a paste, and clear a pending cut's source in the same transaction |
| `move_selection` | Translate a selection on the chunk lattice |
| `clear_selection` | Remove the selected columns |
| `undo` / `redo` | Move between document revisions |
| `set_export_rectangle`, `set_world_spawn`, `set_document_name` | World settings |
| `preview_tiles` | Render a bounded area to a `.cdat` tile file |
| `validate_export` | Everything blocking an export, plus the column count and size estimate |
| `export_world` | Write the world, the arena JSON, the manifest and the report |
| `cancel` | Cancel a running job |
| `shutdown` | Finish and exit |

## Error codes

| Code | Meaning |
| --- | --- |
| `protocol.parse`, `protocol.field`, `protocol.unknown` | Malformed or unrecognized request |
| `source.missing` | The path does not exist |
| `document.missing` | No such open document |
| `selection.empty` | The request needs a selection |
| `clipboard.missing` | The clipboard entry is gone |
| `spawns.invalid` | Confirmation was requested but validation failed |
| `preview.tooLarge` | More than 4096 columns asked for in one render |
| `export.empty` | Nothing to export |
| `job.cancelled` | Cancelled by the user |
| `worker.failed` | Anything else, with the underlying cause in the message |
| `worker.notRunning`, `worker.died` | Produced by the native client, not the worker |

## Tile file format (`.cdat`)

Big-endian, written by `PreviewRenderer` and read by `TileCache`.

```
uint32  magic          0x43444154 ("CDAT")
uint32  formatVersion  1
int32   minChunkX
int32   minChunkZ
int32   widthChunks
int32   lengthChunks
repeat for each column, X outer then Z inner:
  uint8 state          0 absent, 1 generated void, 2 content
  if state == 2:
    uint32[256] ARGB   row-major, z * 16 + x
```

The three states are encoded separately on purpose: a black square alone cannot tell the
user whether a gap will be serialized as a generated void column or left absent.
