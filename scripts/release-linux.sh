#!/usr/bin/env bash
# One click: build ChunkDaddy, then write the single zip to upload to a GitHub release.
set -euo pipefail
root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$root"

echo 'Building ChunkDaddy before packaging...'
bash "$root/scripts/build-linux.sh"

# build-linux.sh bootstraps this Python; it exists by the time the build succeeds.
python="$root/local/linux/tools/bin/python"
if [[ ! -x "$python" ]]; then
    echo "Could not find the build's Python at $python." >&2
    exit 1
fi

echo
echo 'Packaging the release archive...'
exec "$python" "$root/scripts/release.py" "$@"
