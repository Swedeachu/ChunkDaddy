#!/usr/bin/env bash
# Build and assemble the verified Linux runtime; Qt remains a system dependency.
set -euo pipefail
root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)"
out="${1:-$root/build/package/linux/ChunkDaddy}"
bash "$root/setup.sh"
if [[ -e "$out" ]]; then
    echo "Output already exists: $out. Choose a new directory." >&2
    exit 1
fi
mkdir -p "$out/LICENSES"
cp "$root/build/linux-release/chunkdaddy" "$out/"
cp -R "$root/build/linux-release/worker" "$root/build/linux-release/runtime" "$out/"
cp "$root/third_party/NOTICES.md" "$out/LICENSES/"
cp "$root/third_party/chunker/LICENSE" "$out/LICENSES/chunker-LICENSE"
echo "Package: $out (requires system Qt 6)"
