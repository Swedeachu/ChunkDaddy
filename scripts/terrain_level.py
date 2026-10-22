#!/usr/bin/env python3
"""Analyze and apply reviewable, rigid-group terrain leveling through Chunk Daddy's worker."""
from __future__ import annotations

import argparse
import json
from pathlib import Path

from chunkdaddy_cli import Worker, progress_printer
from build_sg_events import sha256


def main():
    repo = Path(__file__).resolve().parents[1]
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('operation', choices=['inspect', 'validate', 'apply'])
    parser.add_argument('--source', type=Path, required=True)
    parser.add_argument('--workspace', type=Path, required=True)
    parser.add_argument('--plan', type=Path)
    parser.add_argument('--destination', type=Path)
    parser.add_argument('--profile', default='bedrock-1.26.40')
    args = parser.parse_args()
    if args.operation == 'apply' and (args.plan is None or args.destination is None):
        parser.error('apply requires --plan and --destination')
    if args.operation == 'validate' and args.plan is None:
        parser.error('validate requires --plan')
    args.workspace.mkdir(parents=True, exist_ok=True)
    original = sha256(args.source)
    worker = Worker(str(repo / 'worker/build/dist/chunkdaddy-worker.jar'), args.workspace,
                    str(repo / 'local/jdk/jdk-21.0.8+9/bin/java.exe'), ['-Xmx12g'], args.workspace / 'worker.log')
    try:
        roots = worker.send({'type': 'inspect_source', 'path': str(args.source)})['worlds']
        if len(roots) != 1:
            raise ValueError('Expected exactly one source world')
        source = roots[0]
        document = worker.send({'type': 'open_world', 'directory': source['directory'], 'edition': source['edition'],
                                'profileId': args.profile}, progress_printer('Import'), timeout=3600)
        identity = document['documentId']
        survey = worker.send({'type': 'survey_region', 'documentId': identity}, timeout=3600)
        print(json.dumps({'sourceSha256': original, 'survey': survey}), flush=True)
        if args.operation == 'inspect':
            volume = worker.send({'type': 'terrain_volume', 'documentId': identity,
                                  'directory': str(args.workspace / 'before')}, timeout=3600)
            print(json.dumps(volume), flush=True)
            (args.workspace / 'source.json').write_text(json.dumps({'sourceSha256': original, 'survey': survey}, indent=2))
        else:
            request = {'type': 'terrain_displace', 'documentId': identity, 'plan': str(args.plan)}
            validation = worker.send({**request, 'validateOnly': True}, timeout=3600)
            (args.workspace / 'validation.json').write_text(json.dumps(validation, indent=2))
            print(json.dumps(validation), flush=True)
            if not validation['valid']:
                raise ValueError('Displacement has collisions; nothing was changed')
            if args.operation == 'validate':
                worker.send({'type': 'close_document', 'documentId': identity})
                if sha256(args.source) != original:
                    raise ValueError('Source archive changed while processing')
                return
            result = worker.send(request, timeout=3600)
            (args.workspace / 'edit.json').write_text(json.dumps(result, indent=2))
            print(json.dumps(result), flush=True)
            worker.send({'type': 'terrain_volume', 'documentId': identity,
                         'directory': str(args.workspace / 'after')}, timeout=3600)
            worker.send({'type': 'export_world', 'documentId': identity, 'destination': str(args.destination),
                         'mode': 'MCWORLD', 'worldName': args.destination.stem, 'writeVoidColumns': False,
                         'voidCleaner': True, 'writeCompanionJson': False}, progress_printer('Export'), timeout=3600)
        worker.send({'type': 'close_document', 'documentId': identity})
        if sha256(args.source) != original:
            raise ValueError('Source archive changed while processing')
    finally:
        worker.close()


if __name__ == '__main__':
    main()
