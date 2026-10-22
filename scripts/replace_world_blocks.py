#!/usr/bin/env python3
"""Apply a reviewable replacement/erosion recipe, export, reopen, and audit every voxel."""
from __future__ import annotations

import argparse
import json
from pathlib import Path

import numpy as np

from build_sg_events import sha256
from chunkdaddy_cli import Worker, progress_printer
from terrain_analysis import load_volume


def signature(entry):
    states = dict(entry['states'])
    # Bedrock's reader makes the common default explicit on newly placed dry blocks.
    if entry['type'] != 'AIR':
        states.setdefault('waterlogged', 'FALSE')
    if entry['type'].endswith('_LEAVES'):
        states.setdefault('update', 'FALSE')
    if entry['type'].endswith('_STAIRS'):
        states.setdefault('shape', 'STRAIGHT')
    if entry['type'] == 'IRON_BARS' or entry['type'].endswith('_FENCE') or entry['type'].endswith('_GLASS_PANE'):
        for face in ('north', 'south', 'east', 'west'):
            states.setdefault(face, 'FALSE')
    return entry['type'], tuple(sorted(states.items()))


def canonical(meta, blocks, registry):
    ids = []
    for entry in meta['palette']:
        key = signature(entry)
        ids.append(registry.setdefault(key, len(registry)))
    return np.asarray(ids, dtype=np.uint32)[blocks]


def audit(before, edited, reopened, recipe):
    bm, bb = load_volume(before)
    am, ab = load_volume(edited)
    rm, rb = load_volume(reopened)
    for key in ['minX', 'minY', 'minZ', 'width', 'height', 'depth']:
        if bm[key] != am[key] or am[key] != rm[key]:
            raise ValueError(f'Export changed volume bounds: {key}')
    registry = {}
    ac = canonical(am, ab, registry)
    rc = canonical(rm, rb, registry)
    differences = int(np.count_nonzero(ac != rc))
    if differences:
        raise ValueError(f'Reopened export differs at {differences} voxels')
    bc = canonical(bm, bb, registry)
    changed = bc != ac
    allowed_types = {t for operation in recipe['operations'] for t in operation['match']}
    allowed = np.asarray([p['type'] in allowed_types or recipe.get('clearWaterlogged', False)
                          and p['states'].get('waterlogged') == 'TRUE' for p in bm['palette']])[bb]
    # A bounded edit must preserve matching materials elsewhere in the world too.
    for axis, origin, length in [(2, 'minX', 'width'), (1, 'minY', 'height'), (0, 'minZ', 'depth')]:
        start = recipe.get(origin, bm[origin])
        stop = start + recipe.get(length, bm[length])
        coordinates = np.arange(bm[length]) + bm[origin]
        shape = [1, 1, 1]
        shape[axis] = bm[length]
        allowed &= ((coordinates >= start) & (coordinates < stop)).reshape(shape)
    unexpected = int(np.count_nonzero(changed & ~allowed))
    if unexpected:
        raise ValueError(f'Changed {unexpected} blocks outside the recipe match set')
    entities = lambda meta: sorted((e['x'], e['y'], e['z'], e['type']) for e in meta['blockEntities'])
    if entities(bm) != entities(am) or entities(am) != entities(rm):
        raise ValueError('Block entity positions/types changed')
    counts = {}
    for p, count in zip(rm['palette'], np.bincount(rb.ravel(), minlength=len(rm['palette']))):
        counts[p['type']] = counts.get(p['type'], 0) + int(count)
        if recipe.get('clearWaterlogged', False) and p['states'].get('waterlogged') == 'TRUE' and count:
            raise ValueError('Waterlogged blocks remain')
    for block_type in recipe.get('expectAbsent', []):
        if counts.get(block_type, 0):
            raise ValueError(f'Expected no remaining {block_type}, found {counts[block_type]}')
    return {'verifiedVoxels': int(bb.size), 'changedBlocks': int(changed.sum()),
            'unexpectedChanges': unexpected, 'reopenedDifferences': differences,
            'preservedBlockEntities': len(entities(bm)), 'counts': counts}


def main():
    repo = Path(__file__).resolve().parents[1]
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--source', type=Path, required=True)
    parser.add_argument('--recipe', type=Path, required=True)
    parser.add_argument('--destination', type=Path, required=True)
    parser.add_argument('--workspace', type=Path, required=True)
    parser.add_argument('--profile', default='bedrock-1.26.40')
    args = parser.parse_args()
    if args.source.resolve() == args.destination.resolve():
        parser.error('Use a separate destination to preserve the source')
    args.workspace.mkdir(parents=True, exist_ok=True)
    args.destination.parent.mkdir(parents=True, exist_ok=True)
    source_hash = sha256(args.source)
    recipe = json.loads(args.recipe.read_text())
    worker = Worker(str(repo / 'worker/build/dist/chunkdaddy-worker.jar'), args.workspace,
                    str(repo / 'local/jdk/jdk-21.0.8+9/bin/java.exe'), ['-Xmx12g'], args.workspace / 'worker.log')

    def open_world(path):
        worlds = worker.send({'type': 'inspect_source', 'path': str(path)})['worlds']
        if len(worlds) != 1:
            raise ValueError('Expected exactly one world')
        source = worlds[0]
        doc = worker.send({'type': 'open_world', 'directory': source['directory'], 'edition': source['edition'],
                           'profileId': args.profile}, progress_printer('Import'), timeout=3600)['documentId']
        worker.send({'type': 'survey_region', 'documentId': doc}, timeout=3600)
        return doc

    def dump(doc, name, bounds=None):
        return worker.send({'type': 'terrain_volume', 'documentId': doc,
                            'directory': str(args.workspace / name), **(bounds or {})}, timeout=3600)

    try:
        doc = open_world(args.source)
        source_volume = dump(doc, 'before')
        bounds = {key: recipe[key] for key in ['minX', 'minY', 'minZ', 'width', 'height', 'depth'] if key in recipe}
        if bounds:
            source_volume = dump(doc, 'selection', bounds)
        # Bind this run to the exact inspected source, even for recipes without a permanent fingerprint.
        recipe['fingerprint'] = recipe.get('fingerprint', source_volume['fingerprint'])
        bound_recipe = args.workspace / 'recipe.json'
        bound_recipe.write_text(json.dumps(recipe, indent=2))
        request = {'type': 'replace_blocks', 'documentId': doc, 'recipe': str(bound_recipe)}
        preview = worker.send({**request, 'validateOnly': True}, timeout=3600)
        print(json.dumps({'preview': preview}), flush=True)
        result = worker.send(request, timeout=3600)
        (args.workspace / 'edit.json').write_text(json.dumps(result, indent=2))
        dump(doc, 'after')
        worker.send({'type': 'export_world', 'documentId': doc, 'destination': str(args.destination),
                     'mode': 'MCWORLD', 'worldName': args.destination.stem, 'writeVoidColumns': False,
                     'voidCleaner': True, 'writeCompanionJson': False}, progress_printer('Export'), timeout=3600)
        worker.send({'type': 'close_document', 'documentId': doc})
        doc = open_world(args.destination)
        dump(doc, 'reopened')
        worker.send({'type': 'close_document', 'documentId': doc})
        verification = audit(args.workspace / 'before', args.workspace / 'after', args.workspace / 'reopened', recipe)
        if sha256(args.source) != source_hash:
            raise ValueError('Source changed during processing')
        verification.update(sourceSha256=source_hash, exportSha256=sha256(args.destination),
                            exportBytes=args.destination.stat().st_size, edit=result)
        (args.workspace / 'verification.json').write_text(json.dumps(verification, indent=2))
        print(json.dumps(verification), flush=True)
    finally:
        worker.close()


if __name__ == '__main__':
    main()
