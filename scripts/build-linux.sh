#!/usr/bin/env bash
set -euo pipefail
root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$root"
if [[ "$(uname -s)" != Linux ]]; then
    echo 'This entry point supports Linux. Use scripts/build-windows.bat on Windows.' >&2
    exit 1
fi
if [[ "${CHUNKDADDY_SKIP_SYSTEM_DEPS:-0}" != 1 ]]; then
    elevate=()
    if (( EUID != 0 )); then elevate=(sudo); fi
    if command -v g++ >/dev/null && command -v git >/dev/null && command -v curl >/dev/null \
        && command -v python3 >/dev/null && pkg-config --atleast-version=6.4 Qt6Widgets Qt6Test 2>/dev/null; then
        echo 'System build dependencies are already installed.'
    elif command -v apt-get >/dev/null; then
        "${elevate[@]}" apt-get update
        "${elevate[@]}" apt-get install -y build-essential git curl ca-certificates python3 python3-venv \
            qt6-base-dev qt6-base-dev-tools libgl1-mesa-dev libxkbcommon-dev
    elif command -v dnf >/dev/null; then
        "${elevate[@]}" dnf install -y gcc-c++ git curl ca-certificates python3 \
            qt6-qtbase-devel mesa-libGL-devel libxkbcommon-devel
    else
        echo 'Install C++20 compiler, Git, curl, Python 3.10+, and Qt 6.4+ development packages.' >&2
        echo 'Then rerun with CHUNKDADDY_SKIP_SYSTEM_DEPS=1 bash scripts/build-linux.sh.' >&2
        exit 1
    fi
fi
export UV_PYTHON_INSTALL_DIR="$root/local/linux/python"
export UV_CACHE_DIR="$root/local/linux/uv-cache"
mkdir -p "$root/local/linux/uv"
case "$(uname -m)" in
    x86_64) uv_arch=x86_64 ;;
    aarch64) uv_arch=aarch64 ;;
    *) echo 'Supported Linux architectures: x86_64 and aarch64.' >&2; exit 1 ;;
esac
if [[ ! -x "$root/local/linux/uv/uv" ]]; then
    curl --fail --location --retry 3 "https://github.com/astral-sh/uv/releases/download/0.8.22/uv-${uv_arch}-unknown-linux-gnu.tar.gz" -o "$root/local/linux/uv.tar.gz"
    tar -xzf "$root/local/linux/uv.tar.gz" --strip-components=1 -C "$root/local/linux/uv"
fi
if [[ ! -x "$root/local/linux/tools/bin/python" ]]; then
    "$root/local/linux/uv/uv" venv --python 3.12.11 --managed-python "$root/local/linux/tools"
fi
"$root/local/linux/uv/uv" pip install --python "$root/local/linux/tools/bin/python" -r "$root/scripts/build-requirements.txt"
export PATH="$root/local/linux/tools/bin:$PATH"
exec "$root/local/linux/tools/bin/python" "$root/scripts/build.py" --prepared "$@"
