# SketchRip: Java source resolver / Shelf mapping fix

**Install:** Extract the contents of this ZIP into the ROOT of the existing ChunkDaddy checkout and replace `scripts/sketchrip.py` and `scripts/test_sketchrip.py`. Do not delete or replace the other SketchRip files. The already-built Java worker and C++ GUI do not change; no recompilation is required for this Python-only patch.

## Root cause

Previous `write_schematic()` generated Sponge v3 with Java `DataVersion=4189` (Java 1.21.4) while it classified textures from the bundled Java **26.2** asset pack. Blocks such as `minecraft:spruce_shelf` and `minecraft:jungle_shelf` were added in Java 1.21.9 and the earlier source resolver treated them as air. ChunkDaddy rightly blocks this during `import_schematics` rather than deleting map blocks.

The pinned Chunker JavaDataVersion at commit `31c91a92bd2dda746f3e41189b603fcfd1727f04` lists Java 26.2 at DataVersion `4903`. New SketchRip schematics now use this value.

## Retry WITHOUT reprocessing the GLB

After extracting this patch into `C:\Users\camde\source\repos\ChunkDaddy`, run in PowerShell from that directory:

```powershell
python scripts/chunkdaddy_cli.py --sketchrip "$HOME\Downloads\FFA-Arena.schem" --profile bedrock-1.26.0 --output "$HOME\Downloads\FFA-Arena.mcworld"
```

This works on an existing SketchRip `.schem` (new and old). If its version is 4189, a corrected copy is written to `FFA-Arena-java26.2.schem` without overwriting the original. The Java worker receives a retagged staging copy. If it reports further mapping issues, the source is preserved for debugging.

Normal GLB imports also stamp Java 26.2 now:

```powershell
python scripts/chunkdaddy_cli.py --sketchrip "$glb" --texture-catalog legacy --texture-matcher fast --allow-uncertain --profile bedrock-1.26.0 --output "$HOME\Downloads\FFA-Arena.mcworld"
```

## Validation

The update passed 21 Python regression tests against the latest packaged SketchRip tree. The prior FFA schematic was separately retagged and its uncompressed NBT compared byte-for-byte aside from the four-byte DataVersion. The pinned JavaDataVersion source was checked for version 4903.

**Not validated here:** A full Java worker / Bedrock client export. A newer source version ensures a corresponding resolver is selected, but does not guarantee that every inferred block or Bedrock conversion has a supported mapping. No unsupported blocks are silently deleted or substituted. The existence of shelves in the inferred map is **not evidence those were the original blocks**; SketchRip's material classification may still be wrong.
