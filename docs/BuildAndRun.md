# Building and running ChunkDaddy

## One-step setup

From a Git checkout or an extracted source folder:

| Platform | Build and test | Build, test and launch |
| --- | --- | --- |
| Windows x64 | Double-click `scripts\build-windows.bat` | `scripts\build-windows.bat -Run` |
| Linux | `bash scripts/build-linux.sh` | `bash scripts/build-linux.sh --run` |

Run from any working directory. Paths containing spaces are supported. Windows uses
PowerShell 5.1 (included with Windows); no developer terminal, vcpkg, Python, Qt, Java,
CMake or Ninja setup is needed beforehand. Git for Windows is required (normally already
installed to check out the repo). Missing Visual Studio C++ Build Tools are installed by
the Microsoft installer; it may request administrator approval or a restart.

Linux setup installs system dependencies through `apt-get` or `dnf`, using `sudo` when
necessary. Ubuntu 24.04+ and current Fedora are the intended package-manager targets.
Qt must be 6.4 or newer; older distributions may require a newer Qt installation.
For another distribution, install a C++20 compiler, Git, curl, Python 3.10+, and the Qt 6.4+
Core/Gui/Widgets/Test development packages, then run:

```bash
CHUNKDADDY_SKIP_SYSTEM_DEPS=1 bash scripts/build-linux.sh
```

Set `CMAKE_PREFIX_PATH` for a custom Linux Qt installation. Linux supports x86_64 and
ARM64; ARM64 is not locally verified. Windows setup currently supports x64 only.

First setup needs network access and several GB of disk space. It:

1. Fetches Chunker at commit `31c91a92bd2dda746f3e41189b603fcfd1727f04`.
2. Installs project-local Python 3.12.11, CMake 3.31.6 and Ninja 1.11.1.3 using uv 0.8.22.
3. Installs Qt 6.8.3 on Windows through aqtinstall 3.3.0; Linux uses distribution Qt.
4. Downloads Eclipse Temurin JDK 21.0.8+9 and verifies its published SHA-256 checksum.
5. Builds and tests the Java worker with the checked-in Gradle wrapper (9.7.1).
6. Builds the complete native application and runs CTest.
7. Places the worker and a trimmed Java runtime beside the executable; deploys Qt DLLs
   and plugins on Windows.
8. Exercises the real worker protocol, exports a tiny void world for every target profile,
   and checks that the native `WorkerClient` can start Java and create a document.

Every failing command stops setup with a nonzero exit code. Repeat the same command to
retry or rebuild; downloaded dependencies and build outputs are reused. Existing Chunker
checkouts with a different commit or local edits are preserved and reported as errors.
There is no need to create or commit a submodule manually.

## Rebuilding while developing

Use the same setup command after changing the source. If Python is already available,
you can also run `python scripts/build.py` on Windows or `python3 scripts/build.py` on
Linux. Running `build.py` directly from the `scripts` folder works too. Every entry point
initializes the compiler and project-local tools, installs missing dependencies, builds
incrementally, tests, and stages the application with its worker and runtime. A Visual
Studio developer terminal is not required; the bootstrap sets both compiler and Windows
SDK library paths before CMake runs.

Append `--run` to the Python command to launch the result. Close the app being rebuilt
on Windows first. To keep an existing session open, build separately:

```text
python scripts/build.py --build-dir build/windows-dev --run
```

The equivalent Windows launcher option is `scripts\build-windows.bat -BuildDir build/windows-dev -Run`.
Linux uses `bash scripts/build-linux.sh --build-dir build/linux-dev --run`. Repeated Linux setup skips
package-manager installation when the required system tools and Qt development packages
are already available.

### Preview regression checks

CTest covers fitted views larger than 4,096 chunks, automatic refresh after edits and
height-mode changes, stale replies after switching tabs, retry after preview errors,
and bounded request scheduling. Preview rendering uses 32×32-chunk batches, with one
request in flight. Distant views use reduced samples; zooming in reloads more detail.

An optional native integration test accepts `CHUNKDADDY_REAL_WORLD` (a local world path)
and `CHUNKDADDY_REAL_SCHEMATICS` (a directory containing the fifteen individual maps).
On Windows, run `test_viewpreview realWorldAndGridPreview` from the packaged build
directory with the Qt test libraries on PATH; on Linux, use `tests/test_viewpreview
realWorldAndGridPreview`. It checks opening the world, delete/undo/redo refresh,
and all 450 arena previews with eight-chunk gaps. Original input files are read only.
`CHUNKDADDY_UI_SCREENSHOTS=1` additionally saves `world-preview.png`.

Local Windows verification with the supplied FFA measured 305 ms for its initial preview
and 5,280 ms for the full 450-arena overview after world loading/placement. The largest
preview batch took 78 ms. These are local measurements, not cross-machine guarantees.

## Launching after setup

- Windows: `build/windows-release/chunkdaddy.exe`
- Linux: `build/linux-release/chunkdaddy`

The worker and Java runtime are beside the executable, so no Java installation or PATH
changes are needed to run it. Linux still needs its system Qt libraries. A graphical
desktop session is required to display the UI; build and smoke checks work headlessly.

Windows tool downloads live in `local/`; Linux downloads live in `local/linux/` so WSL
and Windows can use the same source folder. Native build trees are separate. Do not run
Windows and Linux worker builds simultaneously in the same source folder: Gradle's Java
outputs under `worker/build/` are shared. No permanent user PATH changes are made.

## Manual development builds

Install a C++20 compiler, CMake 3.21+, Ninja, Qt 6.4+ and JDK 21, and fetch the pinned
Chunker sources using setup once. Then, with the tools on PATH:

```bash
# Linux
sh worker/gradlew --project-dir worker test installWorker
cmake --preset linux-release
cmake --build --preset linux-release
ctest --preset linux-release
```

On Windows use `worker\gradlew.bat` and the `windows-release` preset in an MSVC developer
terminal. Set `CMAKE_PREFIX_PATH` to the Qt kit. vcpkg is optional: explicitly pass
`-DCMAKE_TOOLCHAIN_FILE=.../scripts/buildsystems/vcpkg.cmake` if you use it.

The `linux-core-only` preset builds the logic library and tests without Qt Widgets.
The optional native `test_workerclient` integration executable needs the staged worker
and runtime; setup runs it after deployment rather than registering it in ordinary CTest.

The automated Windows/Ubuntu builds in `.github/workflows/build.yml` execute these same
setup entry points. CI results are only established when that workflow actually runs.

## Local verification (14 September 2026)

| Environment | Result |
| --- | --- |
| Windows 11 x64, MSVC 19.51, Qt 6.8.3 | Full setup/build passed; application window and bundled worker launched successfully |
| Ubuntu 26.04 x64 under WSL, GCC 15.2, Qt 6.10.2 | Full build and headless runtime checks passed |

Windows passed 40 Java tests; Linux passed the preceding 39-test suite. The final
snapshot optimization passed on Windows; its repeat Linux run was stopped at the user's
request. Both passed all seven CTest suites, the native worker integration test,
and one-column `.mcworld` exports for all three target profiles. Windows prerequisite
installation was exercised with Visual Studio already installed; installation of the
compiler on a clean Windows machine remains unverified. Linux apt dependencies were
installed in WSL; Fedora, ARM64, and the Linux graphical UI remain unverified.

The export regression test writes and reopens a world twice, checks chest contents,
sign text, suspicious sand and banners, verifies that export leaves the source objects
unchanged, and checks generated void columns across region boundaries. Windows also
exported the supplied `PVP_ZONE_FFA.mcworld` with 30 copies of each of the 15 individual
schematics and at least eight chunks between grid cells: 450 arenas, 900 spawn positions,
266,252 columns, and a 193.5 MiB archive. Its internal and companion arena JSON matched
byte for byte. This is conversion verification; the Minecraft acceptance checks below
are still separate.

World operations use a layout-managed progress dialog with wrapped labels and elapsed
time. Unknown totals use an activity bar; export switches back to activity during database
flush and packaging, and closes only on the worker's final reply. Dialog layout was also
checked at 100% and 150% Windows scaling.

Large coordinate maps use hash-map snapshots that handle colliding keys efficiently. A 262,144-column
regression guards against the former quadratic pause at the end of world import and
when committing edits to a large rectangular world.

## Where things are written

| Content | Location |
| --- | --- |
| Worker workspace | Platform local application data, `ChunkDaddy/workspace/<session>` |
| Preview tiles | `<workspace>/tiles/*.cdat` |
| Extracted containers | `<workspace>/sources/<id>/` |
| Export staging | Temporary directory beside the chosen destination |
| Build tools and caches | `local/` (ignored by Git) |
| Fetched Chunker | `third_party/chunker/` (ignored, pinned by setup and Gradle) |

Exports default to Downloads and then remember the chosen directory.

## Troubleshooting

- **Worker missing or Java fails to start:** rerun setup. It copies the worker and bundles
  the matching runtime. Check the Reports dock's worker log for details.
- **Visual Studio requires reboot:** restart Windows and rerun `scripts\build-windows.bat`.
- **Download fails:** check proxy/firewall/network access, then rerun setup. Downloads use
  GitHub, PyPI, the Qt mirrors, Gradle's distribution/plugin services and Maven Central.
- **Chunker pin mismatch or modified files:** preserve any local work, then restore the
  pinned revision. An intentional upgrade also needs updates in `scripts/build.py`,
  `worker/chunker/build.gradle.kts`, `TargetProfile.java` and the target-profile docs.
- **Switching compilers or Qt kits:** use a separate CMake build directory or remove only
  the corresponding generated native build tree before reconfiguring.

A successful build/export does not establish Minecraft compatibility. The BDS, vanilla
client and Tungsten acceptance procedure remains in `TargetProfiles.md`. Project saving
is still unimplemented; see the design guide and README for the existing feature scope.
