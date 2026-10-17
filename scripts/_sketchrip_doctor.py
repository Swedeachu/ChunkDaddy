#!/usr/bin/env python3
"""Dependency diagnostics for the optional SketchRip import pipeline.

Keep this module standard-library only. It must run even when numpy, Pillow,
trimesh and Node.js are all absent.
"""

from __future__ import annotations

import importlib.util
import json
import os
import shutil
import subprocess
import sys
from pathlib import Path


REQUIRED = {"numpy": "numpy", "PIL": "Pillow", "trimesh": "trimesh", "requests": "requests"}
OPTIONAL = {"scipy": "scipy"}


def missing_python() -> list[str]:
    return [package for module, package in REQUIRED.items()
            if importlib.util.find_spec(module) is None]


def doctor(root: Path, quiet: bool = False) -> tuple[bool, dict]:
    missing = missing_python()
    bundled = root / "assets" / "sketchrip" / "vanilla-26.2.zip"
    worker = root / "worker" / "build" / "dist" / "chunkdaddy-worker.jar"
    sfrip = root / ".sketchrip-tools" / "node_modules" / "sfrip" / "sfrip.mjs"
    system_sfrip = shutil.which("sfrip")
    node = shutil.which("node")
    info = {
        "python": sys.executable,
        "python_version": sys.version.split()[0],
        "required_python": {k: importlib.util.find_spec(k) is not None for k in REQUIRED},
        "optional_python": {k: importlib.util.find_spec(k) is not None for k in OPTIONAL},
        "missing_packages": missing,
        "node": node,
        "sfrip": str(sfrip) if sfrip.is_file() and node else system_sfrip,
        "texture_pack": str(bundled) if bundled.is_file() else None,
        "worker_jar": str(worker) if worker.is_file() else None,
        "worker_classpath_override": os.environ.get("CHUNKDADDY_CLASSPATH"),
    }
    python_ok = not missing
    acquisition_ok = bool(info["sfrip"])
    texture_ok = bool(info["texture_pack"])
    worker_ok = bool(info["worker_jar"] or info["worker_classpath_override"])
    info["local_glb_ready"] = python_ok and texture_ok
    info["sketchfab_acquisition_ready"] = acquisition_ok
    info["mcworld_export_ready"] = worker_ok
    if not quiet:
        print(json.dumps(info, indent=2))
        if missing:
            print("\nInstall missing Python libraries using the SAME interpreter:", file=sys.stderr)
            print(f'  "{sys.executable}" -m pip install -r requirements-sketchrip.txt', file=sys.stderr)
        if not acquisition_ok:
            print("\nInstall sfrip for Sketchfab URLs with:", file=sys.stderr)
            print(f'  "{sys.executable}" scripts/sketchrip_setup.py --skip-python', file=sys.stderr)
        if not worker_ok:
            print("\nBuild the Java worker with: python scripts/build.py", file=sys.stderr)
        if not texture_ok:
            print("\nSupply your texture pack with --textures path\\to\\pack.zip", file=sys.stderr)
        print("\nNote: These checks do NOT prove a live Sketchfab download or Bedrock client compatibility.")
    return python_ok, info
