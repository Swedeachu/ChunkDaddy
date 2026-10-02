#!/usr/bin/env bash
# Remove columns that hold nothing from a Bedrock world, leaving every other record alone.
#
#   scripts/void-clean.sh <world-or-archive> [output] [--dry-run] [--air-sub-chunks] [--folder]
#
# Uses the worker jar from the last build. Run scripts/build-linux.sh first if it is missing.
set -euo pipefail
root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
jar="$root/worker/build/dist/chunkdaddy-worker.jar"
if [[ ! -f "$jar" ]]; then
    echo "No worker jar at $jar - run scripts/build-linux.sh first." >&2
    exit 1
fi
java="java"
for candidate in "$root"/local/jdk/*/bin/java; do
    [[ -x "$candidate" ]] && java="$candidate" && break
done
exec "$java" -Xmx6g -cp "$jar" gg.swim.chunkdaddy.worker.VoidCleanerMain "$@"
