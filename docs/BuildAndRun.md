# Building and running ChunkDaddy

## One-step setup

From a Git checkout or an extracted source folder:

| Platform | Build and test | Build, test and launch |
| --- | --- | --- |
| Windows x64 | Double-click `setup.cmd` | `setup.cmd -Run` |
| Linux | `bash setup.sh` | `bash setup.sh --run` |

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
CHUNKDADDY_SKIP_SYSTEM_DEPS=1 bash setup.sh
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

Both passed 28 Java tests, all five CTest suites, the native worker integration test,
and one-column `.mcworld` exports for all three target profiles. Windows prerequisite
installation was exercised with Visual Studio already installed; installation of the
compiler on a clean Windows machine remains unverified. Linux apt dependencies were
installed in WSL; Fedora, ARM64, and the Linux graphical UI remain unverified.

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
- **Visual Studio requires reboot:** restart Windows and rerun `setup.cmd`.
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
