"""Set up, incrementally build, test and package ChunkDaddy on Windows or Linux."""
from __future__ import annotations

import argparse
import hashlib
import os
from pathlib import Path
import platform
import shutil
import subprocess
import sys
import tarfile
import tempfile
import urllib.request
import zipfile

ROOT = Path(__file__).resolve().parent.parent
PIN = '31c91a92bd2dda746f3e41189b603fcfd1727f04'
WINDOWS = sys.platform == 'win32'
LOCAL = ROOT / 'local' if WINDOWS else ROOT / 'local/linux'
EXE = '.exe' if WINDOWS else ''
QT_VERSION = '6.8.3'
JDK_VERSION = '21.0.8+9'


def run(*args: object, **kwargs):
    print('==>', ' '.join(map(str, args)), flush=True)
    try:
        return subprocess.run(list(map(str, args)), cwd=ROOT, check=True, **kwargs)
    except subprocess.CalledProcessError as error:
        if error.stderr:
            print(error.stderr, file=sys.stderr)
        raise


def sha256(path: Path) -> str:
    with path.open('rb') as source:
        return hashlib.file_digest(source, 'sha256').hexdigest()


def download(url: str, destination: Path):
    print(f'Downloading {url}', flush=True)
    partial = destination.with_suffix(destination.suffix + '.part')
    with urllib.request.urlopen(url, timeout=120) as response, partial.open('wb') as out:
        shutil.copyfileobj(response, out)
    partial.replace(destination)


def chunker():
    path = ROOT / 'third_party/chunker'
    if not (path / '.git').exists():
        if path.exists() and any(path.iterdir()):
            raise RuntimeError(f'{path} contains unversioned files; move them aside before setup.')
        run('git', 'clone', '--no-checkout', 'https://github.com/HiveGamesOSS/Chunker.git', path)
        run('git', '-C', path, 'checkout', '--detach', PIN)
    head = run('git', '-C', path, 'rev-parse', 'HEAD', capture_output=True, text=True).stdout.strip()
    if head != PIN:
        raise RuntimeError(f'Chunker is at {head}, expected {PIN}. Existing checkout was preserved.')
    if run('git', '-C', path, 'status', '--porcelain', capture_output=True, text=True).stdout.strip():
        raise RuntimeError('Chunker has local changes. Restore the pinned sources before building.')


def java() -> Path:
    # Use a known JDK even when PATH contains an older/newer incompatible Java.
    jdk = LOCAL / 'jdk' / f'jdk-{JDK_VERSION}'
    if not (jdk / f'bin/jlink{EXE}').exists():
        os_name = 'windows' if WINDOWS else 'linux'
        arch = {'AMD64': 'x64', 'x86_64': 'x64', 'aarch64': 'aarch64'}.get(platform.machine())
        if not arch:
            raise RuntimeError(f'Unsupported CPU: {platform.machine()}')
        extension = 'zip' if WINDOWS else 'tar.gz'
        name = f'OpenJDK21U-jdk_{arch}_{os_name}_hotspot_21.0.8_9.{extension}'
        url = f'https://github.com/adoptium/temurin21-binaries/releases/download/jdk-21.0.8%2B9/{name}'
        archive = LOCAL / name
        checksum_file = LOCAL / f'{name}.sha256.txt'
        download(url + '.sha256.txt', checksum_file)
        expected = checksum_file.read_text().split()[0]
        if not archive.exists() or sha256(archive) != expected:
            download(url, archive)
        if sha256(archive) != expected:
            raise RuntimeError('Java archive checksum mismatch')
        jdk.parent.mkdir(exist_ok=True)
        if WINDOWS:
            with zipfile.ZipFile(archive) as source:
                source.extractall(jdk.parent)
        else:
            with tarfile.open(archive) as source:
                source.extractall(jdk.parent, filter='data')
    os.environ['JAVA_HOME'] = str(jdk)
    os.environ['PATH'] = str(jdk / 'bin') + os.pathsep + os.environ['PATH']
    os.environ['GRADLE_USER_HOME'] = str(LOCAL / 'gradle')
    return jdk


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--run', action='store_true', help='Launch the app after all checks pass')
    parser.add_argument('--build-dir', type=Path, help='Separate output directory (for example while another build is open)')
    parser.add_argument('--prepared', action='store_true', help=argparse.SUPPRESS)
    args = parser.parse_args()
    # A file association or ordinary terminal does not have MSVC's INCLUDE/LIB
    # environment. All entry points must pass through the same bootstrap, including
    # repeat development builds. The private flag breaks the bootstrap recursion.
    if not args.prepared:
        if WINDOWS:
            command = ['powershell.exe', '-NoProfile', '-ExecutionPolicy', 'Bypass',
                       '-File', ROOT / 'scripts/setup-windows.ps1']
            if args.run:
                command.append('-Run')
            if args.build_dir:
                command.extend(['-BuildDir', args.build_dir.resolve()])
        else:
            command = ['bash', ROOT / 'scripts/build-linux.sh']
            if args.run:
                command.append('--run')
            if args.build_dir:
                command.extend(['--build-dir', args.build_dir.resolve()])
        run(*command)
        return
    preset = 'windows-release' if WINDOWS else 'linux-release'
    build = args.build_dir.resolve() if args.build_dir else ROOT / 'build' / preset
    app = build / f'chunkdaddy{EXE}'
    if WINDOWS and app.exists():
        # Windows locks running executables. Fail before expensive builds without
        # closing the user's app or risking unsaved edits.
        try:
            with app.open('r+b'):
                pass
        except PermissionError as error:
            raise RuntimeError(f'Close {app} before rebuilding, or use --build-dir build/windows-dev '
                               'to keep that session open.') from error
    LOCAL.mkdir(parents=True, exist_ok=True)
    if not shutil.which('git'):
        raise RuntimeError('Git is required. Install Git and rerun the launcher in scripts/.')
    chunker()
    jdk = java()
    qt = LOCAL / f'Qt/{QT_VERSION}/msvc2022_64'
    if WINDOWS:
        if not (qt / 'bin/windeployqt.exe').exists():
            run(sys.executable, '-m', 'aqt', 'install-qt', 'windows', 'desktop', QT_VERSION,
                'win64_msvc2022_64', '--archives', 'qtbase', '--outputdir', LOCAL / 'Qt')
        os.environ['PATH'] = str(qt / 'bin') + os.pathsep + os.environ['PATH']
    gradle = [ROOT / 'worker/gradlew.bat'] if WINDOWS else ['sh', ROOT / 'worker/gradlew']
    run(*gradle, '--project-dir', ROOT / 'worker', '--project-cache-dir', LOCAL / 'gradle-project',
        'test', 'installWorker', '--console=plain', '--no-daemon', '--max-workers=4')
    configure = ['cmake', '--preset', preset, '-B', build]
    if WINDOWS:
        configure.append(f'-DCMAKE_PREFIX_PATH={qt}')
    run(*configure)
    run('cmake', '--build', build, '--parallel', min(os.cpu_count() or 2, 8))
    run('ctest', '--test-dir', build, '--output-on-failure')
    worker = build / 'worker'
    worker.mkdir(exist_ok=True)
    shutil.copy2(ROOT / 'worker/build/dist/chunkdaddy-worker.jar', worker)
    runtime = build / 'runtime'
    if not (runtime / f'bin/java{EXE}').exists():
        # jlink requires a nonexistent destination. Build separately so interrupted
        # setup never leaves a partially installed runtime at the final path.
        with tempfile.TemporaryDirectory(dir=build) as staging:
            output = Path(staging) / 'runtime'
            run(jdk / f'bin/jlink{EXE}', '--add-modules',
                'java.base,java.logging,java.management,java.xml,java.desktop,jdk.zipfs,jdk.unsupported',
                '--strip-debug', '--no-man-pages', '--no-header-files', '--compress=zip-6', '--output', output)
            output.rename(runtime)
    if WINDOWS:
        run(qt / 'bin/windeployqt.exe', '--release', '--no-translations', build / 'chunkdaddy.exe')
    run(sys.executable, ROOT / 'scripts/smoke-worker.py', build)
    run(build / f'test_workerclient{EXE}', timeout=90)
    print(f'\nBuild and checks passed. Launch: {app}', flush=True)
    if args.run:
        subprocess.Popen([str(app)], cwd=build)


if __name__ == '__main__':
    try:
        main()
    except (RuntimeError, OSError, subprocess.SubprocessError) as error:
        print(f'\nSetup failed: {error}', file=sys.stderr)
        sys.exit(1)
