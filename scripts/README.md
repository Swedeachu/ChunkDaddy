# Setup and development builds

Use these same launchers for a fresh checkout and every subsequent source change.
They install missing build dependencies, fetch the pinned Chunker source, build and
test the worker and desktop app, and bundle the required runtime files. Existing
downloads and build outputs are reused for incremental builds.

Run from this folder:

| Platform | Setup, build and test | Also launch the app |
| --- | --- | --- |
| Windows x64 | Double-click `build-windows.bat` | `build-windows.bat -Run` |
| Linux | `bash build-linux.sh` | `bash build-linux.sh --run` |

The launchers find the repository relative to their own location, so the current
working directory does not matter. Windows needs no preinstalled Python or developer
terminal. Missing Visual Studio tools can require administrator approval; Linux
system dependencies can require sudo. First setup requires internet access.

Windows produces `../build/windows-release/chunkdaddy.exe`; Linux produces
`../build/linux-release/chunkdaddy`. Close a running Windows build before rebuilding it,
or pass `-BuildDir build/windows-dev` to the batch launcher to keep that session open.
Build directories passed to these launchers are relative to the repository root.

`setup-windows.ps1` handles Windows dependencies and compiler setup. `build-linux.sh`
handles Linux dependencies. Both invoke the shared `build.py` orchestration.
If Python is already installed, `python build.py --run` works too (`python3` on Linux)
and automatically invokes the appropriate setup first.

See [the build guide](../docs/BuildAndRun.md) for supported systems and troubleshooting.
