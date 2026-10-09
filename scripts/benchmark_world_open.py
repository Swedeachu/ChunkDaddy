#!/usr/bin/env python3
"""Measure archive extraction, first preview, complete import and overview rendering."""
import argparse
import json
import shutil
import time
import uuid
from pathlib import Path

from chunkdaddy_cli import Worker


def main():
    root = Path(__file__).resolve().parent.parent
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("world", type=Path)
    parser.add_argument("--worker", type=Path, default=root / "worker/build/dist/chunkdaddy-worker.jar")
    parser.add_argument("--java", default=shutil.which("java"))
    parser.add_argument("--workspace", type=Path, default=root / "local/benchmarks" / str(uuid.uuid4()))
    parser.add_argument("--overview", action="store_true", help="Render the full bounds at the viewer's distant zoom resolution")
    parser.add_argument("--progressive", action="store_true", help="Measure time to first view and background completion separately")
    args = parser.parse_args()
    if not args.java:
        parser.error("Java 21 is required; supply --java when it is not on PATH")
    args.workspace.mkdir(parents=True, exist_ok=True)
    worker = Worker(str(args.worker), args.workspace, args.java, ["-XX:+UseZGC"], args.workspace / "worker.log")
    report = {"world": str(args.world.resolve()), "worker": str(args.worker.resolve())}
    try:
        start = time.perf_counter()
        source = worker.send({"type": "inspect_source", "path": str(args.world.resolve())}, timeout=1200)
        report["inspectSeconds"] = time.perf_counter() - start
        if len(source["worlds"]) != 1:
            raise ValueError("Supply a file or directory containing exactly one world")
        world = source["worlds"][0]
        start = time.perf_counter()
        opened = worker.send({"type": "open_world", "directory": world["directory"], "edition": world["edition"],
                              "progressive": args.progressive}, timeout=1800)
        report["openSeconds"] = time.perf_counter() - start
        report["columns"] = opened["importedColumns"]
        report["bounds"] = opened.get("contentBounds")
        report["initialLoadedColumns"] = opened.get("loadedColumns", report["columns"])
        if opened.get("loading"):
            initial = opened["initialViewBounds"]
            first = time.perf_counter()
            worker.send({"type": "preview_tiles", "documentId": opened["documentId"], "pixelsPerChunk": 16,
                         "area": initial}, timeout=300)
            report["firstPreviewSeconds"] = time.perf_counter() - first
            while opened.get("loading"):
                time.sleep(0.25)
                opened = worker.send({"type": "document_info", "documentId": opened["documentId"]}, timeout=300)
                if opened.get("loadError"):
                    raise RuntimeError(opened["loadError"])
            report["completeSeconds"] = time.perf_counter() - start
            report["columns"] = opened["totalColumns"]
            report["bounds"] = opened.get("contentBounds")
        if args.overview and report["bounds"]:
            start = time.perf_counter()
            bounds = report["bounds"]
            batches = 0
            for x in range(bounds["minChunkX"], bounds["maxChunkX"] + 1, 32):
                for z in range(bounds["minChunkZ"], bounds["maxChunkZ"] + 1, 32):
                    worker.send({"type": "preview_tiles", "documentId": opened["documentId"], "pixelsPerChunk": 4,
                                 "area": {"minChunkX": x, "minChunkZ": z,
                                          "maxChunkX": min(x + 31, bounds["maxChunkX"]),
                                          "maxChunkZ": min(z + 31, bounds["maxChunkZ"])}}, timeout=300)
                    batches += 1
            report["overviewSeconds"] = time.perf_counter() - start
            report["overviewBatches"] = batches
        (args.workspace / "benchmark.json").write_text(json.dumps(report, indent=2), encoding="utf-8")
        print(json.dumps(report, indent=2))
    finally:
        worker.close()


if __name__ == "__main__":
    main()
