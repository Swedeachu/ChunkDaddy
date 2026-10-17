#!/usr/bin/env python3
"""One-time dependency setup, isolated to the ChunkDaddy source checkout.

The third-party sfrip package is fetched only when this command is explicitly run.
Its implementation is not bundled or silently installed by SketchRip.
"""

from __future__ import annotations

import argparse
import shutil
import subprocess
import sys
from pathlib import Path


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--skip-python", action="store_true")
    parser.add_argument("--skip-sfrip", action="store_true")
    args = parser.parse_args(argv)
    root = Path(__file__).resolve().parent.parent
    if not args.skip_python:
        subprocess.run([sys.executable, "-m", "pip", "install", "-r",
                        str(root / "requirements-sketchrip.txt")], check=True)
    if not args.skip_sfrip:
        node = shutil.which("node")
        npm = shutil.which("npm")
        if not node or not npm:
            raise SystemExit("Node.js 18+ with npm is needed for sfrip. Install from nodejs.org, then rerun setup.")
        result = subprocess.run([node, "--version"], check=True, capture_output=True, text=True)
        version = int(result.stdout.strip().lstrip("v").split(".")[0])
        if version < 18:
            raise SystemExit("sfrip requires Node 18 or newer")
        prefix = root / ".sketchrip-tools"
        prefix.mkdir(parents=True, exist_ok=True)
        # Keep generated npm dependencies out of the user's Git commits without
        # changing the repository's tracked .gitignore file.
        exclude = root / ".git" / "info" / "exclude"
        if exclude.is_file():
            existing = exclude.read_text(encoding="utf-8")
            if ".sketchrip-tools/" not in existing:
                with exclude.open("a", encoding="utf-8") as file:
                    file.write("\n.sketchrip-tools/\n")
        # Local install does not touch global npm packages or require admin rights.
        command = [npm, "install", "--prefix", str(prefix), "--no-audit", "--no-fund", "sfrip@0.1.0"]
        print("Installing third-party sfrip into", prefix, flush=True)
        try:
            subprocess.run(command, check=True)
        except subprocess.CalledProcessError:
            print("npm registry installation failed. Trying the official GitHub source.", file=sys.stderr)
            subprocess.run([npm, "install", "--prefix", str(prefix), "--no-audit", "--no-fund",
                            "git+https://github.com/Corpocore/sfrip.git"], check=True)
        installed = prefix / "node_modules" / "sfrip" / "sfrip.mjs"
        if not installed.is_file():
            raise RuntimeError("sfrip installed without its expected sfrip.mjs entrypoint")
        print("sfrip ready:", installed)
    print("SketchRip dependencies installed. Build ChunkDaddy with python scripts/build.py.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
