#!/usr/bin/env python3
"""Drive the ChunkDaddy worker from a shell or a script.

The worker is a long lived process that speaks JSON Lines over stdin and stdout, so it is
already a CLI; what it lacks is request ids, result correlation, progress handling and a
way to feed one step's output into the next. This supplies those and nothing else.

Three modes:

  run PLAN.jsonl      Execute a plan: one request per line, results bound to names and
                      substituted into later requests. The normal way to script a job.
  call TYPE k=v ...   One request, for poking at a running idea from the shell.
  repl                Read requests from stdin, write results to stdout, ids added for
                      you. For piping from another program.

Every mode prints protocol results to stdout as JSON Lines and everything else - progress,
worker logs, timings - to stderr, so stdout stays machine readable when piped.
"""

from __future__ import annotations

import argparse
import json
import os
import re
import subprocess
import sys
import threading
import time
from pathlib import Path

PLACEHOLDER = re.compile(r"\$\{([^}]+)\}")


class WorkerError(RuntimeError):
    def __init__(self, code: str, message: str):
        super().__init__(f"{code}: {message}")
        self.code = code
        self.message = message


class Worker:
    """A running worker process, with request/response correlation."""

    def __init__(self, classpath: str, workspace: Path, java: str = "java",
                 jvm_args: list[str] | None = None, log: Path | None = None):
        workspace.mkdir(parents=True, exist_ok=True)
        command = [java, *(jvm_args or []), "-cp", classpath,
                   "gg.swim.chunkdaddy.worker.WorkerMain", "--workspace", str(workspace)]
        self.workspace = workspace
        self._log = log.open("w", encoding="utf-8") if log else None
        # Line buffered text pipes; the protocol is newline delimited both ways.
        self._process = subprocess.Popen(
            command, stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
            text=True, encoding="utf-8", bufsize=1)
        self._next_id = 1
        self._stderr = threading.Thread(target=self._drain_stderr, daemon=True)
        self._stderr.start()

    def _drain_stderr(self) -> None:
        # The worker redirects System.out to stderr so library logging cannot corrupt the
        # protocol stream. That makes stderr genuinely interesting, not noise to discard.
        for line in self._process.stderr:
            if self._log:
                self._log.write(line)
                self._log.flush()

    def send(self, request: dict, on_progress=None, timeout: float | None = None) -> dict:
        request_id = self._next_id
        self._next_id += 1
        frame = dict(request)
        frame["id"] = request_id
        self._process.stdin.write(json.dumps(frame) + "\n")
        self._process.stdin.flush()

        deadline = None if timeout is None else time.monotonic() + timeout
        while True:
            if deadline is not None and time.monotonic() > deadline:
                raise WorkerError("cli.timeout", f"no reply to {request.get('type')} in {timeout}s")
            line = self._process.stdout.readline()
            if not line:
                raise WorkerError("cli.closed", "the worker exited before replying")
            line = line.strip()
            if not line:
                continue
            reply = json.loads(line)
            if reply.get("id") != request_id:
                # Replies for other in-flight requests are not expected in this driver,
                # which keeps one request outstanding at a time.
                continue
            if "event" in reply:
                if on_progress:
                    on_progress(reply.get("payload", {}))
                continue
            if reply.get("ok"):
                return reply.get("result", {})
            error = reply.get("error", {})
            raise WorkerError(error.get("code", "unknown"), error.get("message", ""))

    def close(self) -> None:
        try:
            self.send({"type": "shutdown"}, timeout=30)
        except Exception:
            pass
        try:
            self._process.stdin.close()
        except Exception:
            pass
        self._process.wait(timeout=30)
        if self._log:
            self._log.close()


def resolve(value, bindings: dict):
    """Substitute ${name.path.0.field} from previously bound results."""
    if isinstance(value, dict):
        return {k: resolve(v, bindings) for k, v in value.items()}
    if isinstance(value, list):
        return [resolve(v, bindings) for v in value]
    if not isinstance(value, str):
        return value

    whole = PLACEHOLDER.fullmatch(value)
    if whole:
        # A lone placeholder keeps the referenced value's type, so numbers stay numbers.
        return lookup(whole.group(1), bindings)
    return PLACEHOLDER.sub(lambda m: str(lookup(m.group(1), bindings)), value)


def lookup(path: str, bindings: dict):
    current = bindings
    for part in path.split("."):
        if isinstance(current, list):
            current = current[int(part)]
        elif isinstance(current, dict):
            if part not in current:
                raise KeyError(f"'{path}' is not available; '{part}' missing")
            current = current[part]
        else:
            raise KeyError(f"'{path}' went past a leaf value at '{part}'")
    return current


def progress_printer(label: str):
    state = {"last": 0.0}

    def report(payload: dict):
        now = time.monotonic()
        done, total = payload.get("done"), payload.get("total")
        # Throttle: a large export emits thousands of these and only the shape matters.
        if now - state["last"] < 2.0 and not (total and done == total):
            return
        state["last"] = now
        message = payload.get("message") or payload.get("stage") or ""
        if total:
            pct = 100.0 * done / total if total else 0.0
            print(f"  [{label}] {message} {done}/{total} ({pct:.1f}%)", file=sys.stderr, flush=True)
        elif message:
            print(f"  [{label}] {message}", file=sys.stderr, flush=True)

    return report


def run_plan(worker: Worker, plan_path: Path, stop_on_error: bool) -> int:
    bindings: dict = {}
    failures = 0
    with plan_path.open(encoding="utf-8") as handle:
        for number, raw in enumerate(handle, start=1):
            raw = raw.strip()
            if not raw or raw.startswith("#"):
                continue
            step = json.loads(raw)
            save = step.pop("_save", None)
            note = step.pop("_note", None)
            timeout = step.pop("_timeout", None)
            step = resolve(step, bindings)

            label = step.get("type", "?")
            if note:
                print(f"\n=== {label}: {note}", file=sys.stderr, flush=True)
            else:
                print(f"\n=== {label}", file=sys.stderr, flush=True)
            started = time.monotonic()
            try:
                result = worker.send(step, on_progress=progress_printer(label), timeout=timeout)
            except WorkerError as error:
                failures += 1
                elapsed = time.monotonic() - started
                print(f"  FAILED after {elapsed:.1f}s: {error}", file=sys.stderr, flush=True)
                print(json.dumps({"step": number, "type": label, "ok": False,
                                  "error": {"code": error.code, "message": error.message}}))
                sys.stdout.flush()
                if stop_on_error:
                    return failures
                continue
            elapsed = time.monotonic() - started
            print(f"  ok in {elapsed:.1f}s", file=sys.stderr, flush=True)
            if save:
                bindings[save] = result
            print(json.dumps({"step": number, "type": label, "ok": True,
                              "seconds": round(elapsed, 3), "result": result}))
            sys.stdout.flush()
    return failures


def parse_pairs(pairs: list[str]) -> dict:
    request: dict = {}
    for pair in pairs:
        if "=" not in pair:
            raise SystemExit(f"expected key=value, got {pair!r}")
        key, _, raw = pair.partition("=")
        try:
            # JSON first so numbers, booleans, arrays and objects pass through typed.
            request[key] = json.loads(raw)
        except json.JSONDecodeError:
            request[key] = raw
    return request


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--classpath", default=os.environ.get("CHUNKDADDY_CLASSPATH"),
                        help="worker classpath (default $CHUNKDADDY_CLASSPATH)")
    parser.add_argument("--java", default=os.environ.get("CHUNKDADDY_JAVA", "java"))
    parser.add_argument("--workspace", type=Path, default=Path("chunkdaddy-workspace"))
    parser.add_argument("--worker-log", type=Path, help="write the worker's stderr here")
    parser.add_argument("--jvm", action="append", default=[], help="extra JVM argument")
    parser.add_argument("--keep-going", action="store_true", help="continue after a failed step")
    sub = parser.add_subparsers(dest="mode", required=True)

    run = sub.add_parser("run", help="execute a JSONL plan")
    run.add_argument("plan", type=Path)

    call = sub.add_parser("call", help="send one request")
    call.add_argument("type")
    call.add_argument("pairs", nargs="*")

    sub.add_parser("repl", help="requests on stdin, results on stdout")

    args = parser.parse_args()
    if not args.classpath:
        raise SystemExit("set --classpath or CHUNKDADDY_CLASSPATH")

    worker = Worker(args.classpath, args.workspace, args.java, args.jvm, args.worker_log)
    try:
        if args.mode == "run":
            return 1 if run_plan(worker, args.plan, not args.keep_going) else 0
        if args.mode == "call":
            request = parse_pairs(args.pairs)
            request["type"] = args.type
            result = worker.send(request, on_progress=progress_printer(args.type))
            print(json.dumps(result, indent=2))
            return 0
        for raw in sys.stdin:
            raw = raw.strip()
            if not raw:
                continue
            try:
                result = worker.send(json.loads(raw), on_progress=progress_printer("repl"))
                print(json.dumps({"ok": True, "result": result}), flush=True)
            except WorkerError as error:
                print(json.dumps({"ok": False,
                                  "error": {"code": error.code, "message": error.message}}),
                      flush=True)
        return 0
    finally:
        worker.close()


if __name__ == "__main__":
    sys.exit(main())
