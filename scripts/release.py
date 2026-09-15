"""Package exactly one release archive from a build that already exists.

Run `scripts/release-windows.bat` or `scripts/release-linux.sh` instead of this file;
they build first and then call this. This script never builds, so a release is always
made from artifacts you have already seen pass their tests.

What ends up in the archive is chosen by an allow list, not by deleting known junk from
the build directory. A build tree gains new intermediate files whenever the build system
changes, and a deny list silently starts shipping them; an allow list silently stops
shipping something instead, which is the failure you notice.
"""
from __future__ import annotations

import argparse
import hashlib
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import zipfile

ROOT = Path(__file__).resolve().parent.parent
WINDOWS = sys.platform == 'win32'
EXE = '.exe' if WINDOWS else ''

# Shared libraries that must come from the user's own system rather than from us.
#
# Shipping our copy of the C library, the C++ runtime, the graphics and display stack or
# the system's crypto and desktop integration is how a bundle that works on the build
# machine fails on every other one. This is the well-trodden list that AppImage tooling
# settled on for the same reason.
SYSTEM_LIBRARIES = (
    # Core runtime and toolchain.
    'ld-linux', 'libc.so', 'libm.so', 'libdl.so', 'libpthread', 'librt.so', 'libresolv',
    'libnsl', 'libstdc++', 'libgcc_s', 'libgomp',
    # Graphics, display server and input: these must match the running system.
    'libGL', 'libEGL', 'libOpenGL', 'libGLX', 'libGLdispatch', 'libX11', 'libxcb',
    'libXau', 'libXdmcp', 'libXext', 'libXrender', 'libXi', 'libXfixes', 'libXrandr',
    'libXcursor', 'libXcomposite', 'libXdamage', 'libICE', 'libSM', 'libdrm', 'libgbm',
    'libwayland', 'libepoxy', 'libinput', 'libevdev', 'libmtdev', 'libwacom', 'libgudev',
    'libts.so',
    # Session and device integration.
    'libdbus-1', 'libsystemd', 'libudev', 'libselinux', 'libcap', 'libuuid', 'libblkid',
    'libmount', 'libffi',
    # Crypto and network stacks: shipping these is an active hazard, and ChunkDaddy is
    # entirely offline.
    'libcrypto', 'libssl', 'libgnutls', 'libnettle', 'libhogweed', 'libgmp', 'libtasn1',
    'libp11-kit', 'libgcrypt', 'libgpg-error', 'libkrb5', 'libk5crypto', 'libcom_err',
    'libgssapi', 'libkeyutils', 'liblber', 'libldap', 'libsasl2', 'libcurl', 'libnghttp2',
    'libpsl', 'librtmp', 'libssh', 'libidn2', 'libunistring', 'libproxy', 'libpxbackend',
    'libduktape',
    # GLib and the GTK desktop stack.
    'libglib-2.0', 'libgobject', 'libgio-2.0', 'libgmodule', 'libgthread', 'libpcre2-8',
    'libgtk-3', 'libgdk-3', 'libgdk_pixbuf', 'libatk', 'libatspi', 'libcairo', 'libpango',
    'libthai', 'libdatrie', 'libfribidi', 'libpixman', 'libharfbuzz', 'libfontconfig',
    'libexpat',
    # Ubiquitous compression and image codecs.
    'libz.so', 'libbz2', 'liblzma', 'liblz4', 'libbrotli', 'libbsd', 'libmd.so',
)

# Qt plugin groups a Widgets application actually loads to draw itself.
#
# Deliberately short. Qt also ships theme, input-method, TLS and network-information
# plugins, and each of those drags a whole subsystem into the bundle: the GTK theme
# plugin alone brings GTK, Pango, Cairo and ATK. ChunkDaddy is an offline Widgets app, so
# none of them earn their cost. Without the GTK theme plugin Qt uses its own Fusion
# style, which is what the app is designed against anyway.
QT_PLUGIN_GROUPS = ('platforms', 'xcbglintegrations', 'imageformats', 'iconengines', 'styles')


def project_version() -> str:
    """The single source of truth for the version is the CMake project declaration."""
    text = (ROOT / 'CMakeLists.txt').read_text(encoding='utf-8')
    match = re.search(r'project\s*\(\s*ChunkDaddy\s+VERSION\s+([0-9]+(?:\.[0-9]+)*)', text)
    if not match:
        raise RuntimeError('No ChunkDaddy VERSION found in CMakeLists.txt')
    return match.group(1)


def is_only_shared_libraries(directory: Path, suffix: str) -> bool:
    """True for a directory holding nothing but plugin binaries.

    windeployqt drops Qt's plugins into directories of their own next to the executable.
    They are the only directories in a build tree made entirely of shared libraries, so
    this recognizes them without having to keep a list of Qt's plugin group names in
    step with whatever Qt decides to deploy.
    """
    files = [path for path in directory.rglob('*') if path.is_file()]
    return bool(files) and all(path.suffix.lower() == suffix for path in files)


def copy_common(build: Path, stage: Path) -> None:
    """The three things every platform ships: the app, the worker and its runtime."""
    app = build / f'chunkdaddy{EXE}'
    jar = build / 'worker/chunkdaddy-worker.jar'
    runtime = build / 'runtime'
    for required in (app, jar, runtime):
        if not required.exists():
            raise RuntimeError(
                f'{required} is missing. Build first with scripts/'
                + ('build-windows.bat' if WINDOWS else 'build-linux.sh') + '.')

    shutil.copy2(app, stage / app.name)
    (stage / 'worker').mkdir()
    shutil.copy2(jar, stage / 'worker' / jar.name)
    # The bundled Java runtime is why a user never installs Java. jlink writes its own
    # legal/ directory inside it; that has to travel with it.
    shutil.copytree(runtime, stage / 'runtime', symlinks=True)


def copy_licences(stage: Path) -> None:
    licences = stage / 'LICENSES'
    licences.mkdir()
    for source, name in ((ROOT / 'LICENSE', 'ChunkDaddy-LICENSE.txt'),
                         (ROOT / 'third_party/chunker-LICENSE.txt', 'Chunker-LICENSE.txt'),
                         (ROOT / 'third_party/NOTICES.md', 'THIRD-PARTY-NOTICES.md')):
        if source.exists():
            shutil.copy2(source, licences / name)
        else:
            raise RuntimeError(f'{source} is missing; a release may not ship without its notices.')


def collect_windows(build: Path, stage: Path) -> None:
    copy_common(build, stage)
    for entry in sorted(build.iterdir()):
        if entry.name in ('worker', 'runtime') or entry.name == f'chunkdaddy{EXE}':
            continue
        if entry.is_file() and (entry.suffix.lower() == '.dll' or entry.name == 'qt.conf'):
            shutil.copy2(entry, stage / entry.name)
        elif entry.is_dir() and is_only_shared_libraries(entry, '.dll'):
            shutil.copytree(entry, stage / entry.name)


def linked_libraries(binary: Path) -> dict[str, Path]:
    """Resolved shared library dependencies of a binary, by soname."""
    result = subprocess.run(['ldd', str(binary)], capture_output=True, text=True)
    libraries: dict[str, Path] = {}
    for line in result.stdout.splitlines():
        parts = line.split()
        if '=>' in parts and len(parts) >= 3 and parts[2].startswith('/'):
            libraries[parts[0]] = Path(parts[2])
    return libraries


def qt_plugin_directory() -> Path | None:
    for tool in ('qtpaths6', 'qtpaths', 'qmake6', 'qmake'):
        if not shutil.which(tool):
            continue
        flag = '--query' if tool.startswith('qtpaths') else '-query'
        result = subprocess.run([tool, flag, 'QT_INSTALL_PLUGINS'], capture_output=True, text=True)
        path = Path(result.stdout.strip())
        if result.returncode == 0 and path.is_dir():
            return path
    return None


def collect_linux(build: Path, stage: Path, bundle_qt: bool) -> bool:
    """Assemble the Linux tree. Returns whether Qt was bundled."""
    copy_common(build, stage)
    os.chmod(stage / 'chunkdaddy', 0o755)

    bundled = False
    if bundle_qt:
        bundled = bundle_qt_runtime(build, stage)
        if not bundled:
            shutil.rmtree(stage / 'lib', ignore_errors=True)
            shutil.rmtree(stage / 'plugins', ignore_errors=True)

    launcher = stage / 'chunkdaddy.sh'
    launcher.write_text(
        '#!/bin/sh\n'
        '# Launch ChunkDaddy from wherever this archive was extracted.\n'
        'here=$(cd -- "$(dirname -- "$0")" && pwd)\n'
        '# Some unzip tools drop the executable bit; restore it rather than failing.\n'
        'chmod +x "$here/chunkdaddy" 2>/dev/null\n'
        'find "$here/runtime/bin" -type f -exec chmod +x {} + 2>/dev/null\n'
        '[ -d "$here/lib" ] && export LD_LIBRARY_PATH="$here/lib${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}"\n'
        '[ -d "$here/plugins" ] && export QT_PLUGIN_PATH="$here/plugins"\n'
        'exec "$here/chunkdaddy" "$@"\n',
        encoding='utf-8')
    os.chmod(launcher, 0o755)
    return bundled


def bundle_qt_runtime(build: Path, stage: Path) -> bool:
    """Copy Qt and its non-system dependencies next to the app, then prove it links.

    Verification is the point. A bundle assembled from `ldd` on the build machine can
    still be missing something on a user's machine; re-running `ldd` against only the
    bundled directory catches that here instead of in a bug report.
    """
    plugins = qt_plugin_directory()
    if plugins is None:
        print('Qt plugin directory not found; packaging against system Qt instead.')
        return False

    libraries = linked_libraries(build / 'chunkdaddy')
    wanted = {name: path for name, path in libraries.items()
              if not any(name.startswith(prefix) for prefix in SYSTEM_LIBRARIES)}
    if not any(name.startswith('libQt6') for name in wanted):
        print('The app does not link Qt dynamically; packaging against system Qt instead.')
        return False

    library_dir = stage / 'lib'
    library_dir.mkdir()
    plugin_dir = stage / 'plugins'
    plugin_dir.mkdir()

    for name, path in sorted(wanted.items()):
        shutil.copy2(path, library_dir / name, follow_symlinks=True)

    for group in QT_PLUGIN_GROUPS:
        source = plugins / group
        if source.is_dir():
            shutil.copytree(source, plugin_dir / group)

    # Plugins pull in libraries the executable itself never names, the platform plugin
    # above all. Follow those too, or the app starts and then cannot open a window.
    pending = [path for path in plugin_dir.rglob('*.so') if path.is_file()]
    for plugin in pending:
        for name, path in linked_libraries(plugin).items():
            if any(name.startswith(prefix) for prefix in SYSTEM_LIBRARIES):
                continue
            if not (library_dir / name).exists():
                shutil.copy2(path, library_dir / name, follow_symlinks=True)

    return verify_linux_bundle(stage)


def verify_linux_bundle(stage: Path) -> bool:
    # Resolve against the bundle *and* the system, because that is how the launcher
    # runs it: LD_LIBRARY_PATH is prepended, not replaced. Anything still "not found"
    # here is genuinely absent rather than merely unbundled.
    environment = dict(os.environ)
    existing = environment.get('LD_LIBRARY_PATH')
    environment['LD_LIBRARY_PATH'] = str(stage / 'lib') + (f':{existing}' if existing else '')
    missing: list[str] = []
    for binary in [stage / 'chunkdaddy', *(stage / 'plugins').rglob('*.so')]:
        result = subprocess.run(['ldd', str(binary)], capture_output=True, text=True, env=environment)
        missing += [f'{binary.name}: {line.split()[0]}'
                    for line in result.stdout.splitlines() if 'not found' in line]
    if missing:
        print('Bundled Qt did not resolve; packaging against system Qt instead.')
        for entry in missing[:10]:
            print(f'  {entry}')
        return False
    return True


def write_readme(stage: Path, version: str, platform_name: str, bundled_qt: bool) -> None:
    if WINDOWS:
        body = ('Double-click chunkdaddy.exe.\n\n'
                'Everything this needs is in this folder, including the Java runtime the\n'
                'conversion worker uses. Nothing has to be installed first. Keep the folder\n'
                'together; the app looks for worker\\ and runtime\\ beside the executable.\n')
    elif bundled_qt:
        body = ('Run ./chunkdaddy.sh\n\n'
                'Everything this needs is in this folder, including Qt and the Java runtime\n'
                'the conversion worker uses. Nothing has to be installed first. Use the .sh\n'
                'launcher rather than the bare binary: it points the app at the bundled Qt.\n')
    else:
        body = ('Run ./chunkdaddy.sh\n\n'
                'This build uses your system Qt, so install Qt 6.4 or newer first:\n'
                '  Debian/Ubuntu   sudo apt install libqt6widgets6\n'
                '  Fedora          sudo dnf install qt6-qtbase-gui\n\n'
                'The Java runtime the conversion worker uses is included; Java does not need\n'
                'to be installed. Keep the folder together; the app looks for worker/ and\n'
                'runtime/ beside the executable.\n')

    (stage / 'README.txt').write_text(
        f'ChunkDaddy {version} ({platform_name})\n'
        f'{"=" * (len(version) + len(platform_name) + 14)}\n\n'
        f'{body}\n'
        'Bedrock world composer and duel arena grid editor.\n'
        'Licences for ChunkDaddy and everything it bundles are in LICENSES/.\n',
        encoding='utf-8')


def write_zip(stage: Path, archive: Path, root_name: str) -> None:
    """Write the archive, keeping Unix permission bits so the binaries stay runnable."""
    archive.parent.mkdir(parents=True, exist_ok=True)
    if archive.exists():
        archive.unlink()
    entries = sorted(path for path in stage.rglob('*') if path.is_file() or path.is_symlink())
    with zipfile.ZipFile(archive, 'w', zipfile.ZIP_DEFLATED, compresslevel=9) as zip_file:
        for path in entries:
            relative = f'{root_name}/{path.relative_to(stage).as_posix()}'
            info = zipfile.ZipInfo(relative, date_time=(1980, 1, 1, 0, 0, 0))
            info.compress_type = zipfile.ZIP_DEFLATED
            mode = path.lstat().st_mode
            # The high sixteen bits carry the Unix mode; unzip restores it from there,
            # which is what keeps chunkdaddy and runtime/bin/java executable.
            info.external_attr = (mode & 0xFFFF) << 16
            if path.is_symlink():
                info.external_attr |= 0xA000 << 16
                zip_file.writestr(info, os.readlink(path))
            else:
                zip_file.writestr(info, path.read_bytes())


def main() -> None:
    parser = argparse.ArgumentParser(description='Package one release archive for GitHub.')
    parser.add_argument('--version', help='Override the version in the archive name')
    parser.add_argument('--build-dir', type=Path, help='Build directory to package')
    parser.add_argument('--output-dir', type=Path, default=ROOT / 'dist', help='Where to write it')
    parser.add_argument('--system-qt', action='store_true',
                        help='Linux: require the user to install Qt instead of bundling it')
    arguments = parser.parse_args()

    version = arguments.version or project_version()
    preset = 'windows-release' if WINDOWS else 'linux-release'
    build = (arguments.build_dir or ROOT / 'build' / preset).resolve()
    platform_name = 'windows-x64' if WINDOWS else f'linux-{os.uname().machine}'
    root_name = f'ChunkDaddy-{version}-{platform_name}'

    staging = arguments.output_dir / '.stage'
    shutil.rmtree(staging, ignore_errors=True)
    stage = staging / root_name
    stage.mkdir(parents=True)

    try:
        bundled_qt = False
        if WINDOWS:
            collect_windows(build, stage)
        else:
            bundled_qt = collect_linux(build, stage, not arguments.system_qt)
        copy_licences(stage)
        write_readme(stage, version, platform_name, bundled_qt)

        archive = arguments.output_dir / f'{root_name}.zip'
        write_zip(stage, archive, root_name)
    finally:
        shutil.rmtree(staging, ignore_errors=True)

    size = archive.stat().st_size
    digest = hashlib.sha256(archive.read_bytes()).hexdigest()
    print('\nUpload this one file to the GitHub release:\n')
    print(f'  {archive}')
    print(f'  {size / 1048576:.1f} MiB')
    print(f'  sha256 {digest}')


if __name__ == '__main__':
    try:
        main()
    except (RuntimeError, OSError, subprocess.SubprocessError) as error:
        print(f'\nRelease packaging failed: {error}', file=sys.stderr)
        sys.exit(1)
