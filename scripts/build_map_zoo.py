#!/usr/bin/env python3
"""Compose every world in an archive into a named, chunk-aligned map zoo with teleport JSON."""
from __future__ import annotations
import argparse
import json
import math
import os
import re
import shutil
import zipfile
from pathlib import Path
from build_sg_events import sha256
from chunkdaddy_cli import Worker, progress_printer


def write_json(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, indent=2) + '\n', encoding='utf-8')


def labels(path, root):
    relative = path.relative_to(root).as_posix()
    name = re.sub(r'\s*\(Zynthyr\)', '', path.stem, flags=re.I)
    name = re.sub(r'_Zynthyr_\d{4}-.*$', '', name, flags=re.I).replace('_', ' ')
    name = name.replace('Forest Kindom', 'Forest Kingdom')
    name = re.sub(r'\s+Maps$', '', name)
    category = path.parent.relative_to(root).as_posix()
    category = category.removeprefix('Maps/').replace('Extra Gamemodes/', '')
    return name.strip(), category, relative


def main():
    if os.name != 'nt': raise RuntimeError('Run locally on Windows')
    repo = Path(__file__).resolve().parents[1]
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--source', type=Path, required=True)
    parser.add_argument('--workspace', type=Path, required=True)
    parser.add_argument('--destination', type=Path, required=True)
    parser.add_argument('--java', default=str(repo / 'local/jdk/jdk-21.0.8+9/bin/java.exe'))
    parser.add_argument('--classpath', default=str(repo / 'worker/build/dist/chunkdaddy-worker.jar'))
    parser.add_argument('--heap', default='18g')
    parser.add_argument('--columns', type=int, default=6)
    parser.add_argument('--gap-chunks', type=int, default=10)
    parser.add_argument('--profile', default='bedrock-1.21.130')
    parser.add_argument('--publish', action='store_true')
    parser.add_argument('--inventory', type=Path, help='Reuse a prior survey for identical source archives')
    args = parser.parse_args()
    if args.columns < 1 or args.gap_chunks < 1: parser.error('Columns and gap must be positive')
    args.workspace.mkdir(parents=True, exist_ok=True)
    archive_hash = sha256(args.source)
    records = []
    worker = Worker(args.classpath, args.workspace, args.java, ['-Xmx' + args.heap], args.workspace / 'worker.log')
    try:
        features = worker.send({'type': 'capabilities'})['regionFeatures']
        if 'survey_region' not in features: raise ValueError('Rebuild ChunkDaddy: survey_region is required')
        extraction = worker.send({'type': 'inspect_source', 'path': str(args.source)})
        root = Path(extraction['scanRoot'])
        sources = sorted(root.rglob('*.mcworld'))
        if not sources: raise ValueError('Archive contains no mcworld sources')
        cached = json.loads(args.inventory.read_text()) if args.inventory else {}
        if cached and cached['archiveSha256'] != archive_hash: raise ValueError('Cached inventory belongs to another archive')
        old = {r['sourceFile']: r for r in cached.get('maps', [])}
        ids = set()
        for index, path in enumerate(sources, 1):
            name, category, relative = labels(path, root)
            slug = re.sub('[^a-z0-9]+', '-', name.lower()).strip('-')
            identity = re.sub('[^a-z0-9]+', '-', category.lower()).strip('-') + '/' + slug
            if identity in ids: raise ValueError('Duplicate map identifier: ' + identity)
            ids.add(identity)
            digest = sha256(path)
            cached_record = old.get(relative)
            if cached_record and cached_record['sourceSha256'] == digest:
                survey = cached_record['survey']
            else:
                print(f'Surveying {index}/{len(sources)}: {category} / {name}', flush=True)
                roots = worker.send({'type': 'inspect_source', 'path': str(path)})['worlds']
                if len(roots) != 1: raise ValueError(f'Expected one world: {relative}')
                world = roots[0]
                document = worker.send({'type': 'open_world', 'directory': world['directory'], 'edition': world['edition'], 'profileId': args.profile},
                                       progress_printer(name), timeout=3600)
                survey = worker.send({'type': 'survey_region', 'documentId': document['documentId']}, timeout=3600)
                worker.send({'type': 'close_document', 'documentId': document['documentId']})
            record = {'id': identity, 'name': name, 'category': category, 'sourceFile': relative,
                      'sourceSha256': digest, 'path': str(path), 'survey': survey}
            records.append(record)
            write_json(args.workspace / 'inventory.json', {'archiveSha256': archive_hash, 'maps': records})
            print(json.dumps({'map': name, **survey}), flush=True)
        rows = math.ceil(len(records) / args.columns)
        widths = [max((r['survey']['bounds']['widthChunks'] for i,r in enumerate(records) if i % args.columns == col), default=0) for col in range(args.columns)]
        depths = [max(r['survey']['bounds']['lengthChunks'] for i,r in enumerate(records) if i // args.columns == row) for row in range(rows)]
        xs = [sum(widths[:i]) + args.gap_chunks * i for i in range(args.columns)]
        zs = [sum(depths[:i]) + args.gap_chunks * i for i in range(rows)]
        sources_plan, entries = [], []
        for i, record in enumerate(records):
            col, row = i % args.columns, i // args.columns
            b = record['survey']['bounds']
            dx, dz = (xs[col] - b['minChunkX']) * 16, (zs[row] - b['minChunkZ']) * 16
            center = dict(record['survey']['center'])
            # A hollow arena can have void below its horizontal center. Keep the
            # spectator camera above the entire build rather than under its roof.
            center['y'] = max(center['y'], record['survey']['highestSectionBlockY'] + 10)
            translated = {'x': center['x'] + dx, 'y': center['y'], 'z': center['z'] + dz}
            entry = {k:record[k] for k in ['id','name','category','sourceFile','sourceSha256']}
            entry.update({'row': row, 'column': col, 'center': translated, 'yaw': 0, 'pitch': 45,
                          'sourceCenter': center, 'sourceBounds': b, 'translation': {'x':dx,'y':0,'z':dz},
                          'bounds': {'minChunkX': xs[col], 'minChunkZ': zs[row],
                                     'maxChunkX': xs[col] + b['widthChunks'] - 1, 'maxChunkZ': zs[row] + b['lengthChunks'] - 1}})
            entries.append(entry)
            sources_plan.append({'name': record['id'], 'path': record['path'], 'rectangle': b,
                                 'metadata': {'center': center, 'displayName': record['name'], 'category': record['category'], 'sourceFile': record['sourceFile']},
                                 'coordinatePaths': [{'path': '/center'}],
                                 'placements': [{'id': record['id'], 'destChunkX': xs[col], 'destChunkZ': zs[row]}]})
        for i, entry in enumerate(entries):
            a = entry['bounds']
            for other in entries[:i]:
                b = other['bounds']
                gap_x = max(a['minChunkX'] - b['maxChunkX'] - 1, b['minChunkX'] - a['maxChunkX'] - 1, 0)
                gap_z = max(a['minChunkZ'] - b['maxChunkZ'] - 1, b['minChunkZ'] - a['maxChunkZ'] - 1, 0)
                if max(gap_x, gap_z) < args.gap_chunks: raise ValueError('Map footprints violate requested spacing')
        index = {'schemaVersion': 1, 'world': 'MAP_ZOO', 'spacingChunks': args.gap_chunks, 'spacingBlocks': args.gap_chunks * 16,
                 'columns': args.columns, 'rows': rows, 'sourceArchiveSha256': archive_hash, 'maps': entries}
        write_json(args.workspace / 'MAP_ZOO.json', index)
        plan = {'type': 'compose_regions', 'name': 'MAP_ZOO', 'profileId': args.profile, 'sources': sources_plan,
                'layout': {'columns': args.columns, 'rows': rows, 'minimumGapBlocks': args.gap_chunks * 16}}
        write_json(args.workspace / 'compose-plan.json', plan)
        result = worker.send(plan, progress_printer('MAP_ZOO composition'), timeout=7200)
        manifest = json.loads(Path(result['regionManifestPath']).read_text())
        if len(manifest['instances']) != len(sources) or len(manifest['templates']) != len(sources): raise ValueError('Missing map')
        for entry, instance in zip(entries, manifest['instances']):
            point = manifest['templates'][entry['id']]['metadata']['center']
            expected = {axis: point[axis] + instance['offset'][axis] for axis in ['x','y','z']}
            if expected != entry['center']: raise ValueError('Teleport coordinates disagree with physical placement')
        first = entries[0]['center']
        worker.send({'type': 'set_world_spawn', 'documentId': result['documentId'], **first})
        staged = args.workspace / 'MAP_ZOO.zip'
        worker.send({'type': 'export_world', 'documentId': result['documentId'], 'destination': str(staged), 'mode': 'ZIP', 'worldName': 'MAP_ZOO',
                     'writeVoidColumns': False, 'voidCleaner': True, 'writeCompanionJson': False}, progress_printer('MAP_ZOO export'), timeout=7200)
        worker.send({'type': 'close_document', 'documentId': result['documentId']})
        # The same parseable index travels inside the ZIP and beside it for the independent viewer.
        with zipfile.ZipFile(staged, 'a', compression=zipfile.ZIP_DEFLATED, compresslevel=1) as output:
            output.writestr('MAP_ZOO.json', (args.workspace / 'MAP_ZOO.json').read_bytes())
            output.writestr('tungsten_world.json', '{"generator":"void"}\n')
        with zipfile.ZipFile(staged) as archive:
            if archive.testzip() is not None: raise ValueError('Corrupt export ZIP')
            if any('resource_pack' in e.filename or 'behavior_pack' in e.filename for e in archive.infolist()): raise ValueError('Unexpected bundled packs')
        verification = worker.send({'type': 'verify_regions', 'path': str(staged)}, timeout=3600)
        if verification['verifiedInstances'] != len(sources): raise ValueError('Incomplete region verification')
        write_json(args.workspace / 'verification.json', verification)
        if sha256(args.source) != archive_hash: raise ValueError('Input archive changed during composition')
        if args.publish:
            args.destination.parent.mkdir(parents=True, exist_ok=True)
            for source, destination in [(staged, args.destination), (args.workspace / 'MAP_ZOO.json', args.destination.with_suffix('.json'))]:
                pending = destination.with_name(destination.name + '.pending')
                shutil.copy2(source, pending)
                if sha256(source) != sha256(pending): raise ValueError('Publication checksum mismatch')
                os.replace(pending, destination)
        print(json.dumps({'maps': len(records), 'columns': args.columns, 'rows': rows, 'gapChunks': args.gap_chunks,
                          'bytes': staged.stat().st_size, 'destination': str(args.destination), 'published': args.publish}), flush=True)
    finally:
        worker.close()


if __name__ == '__main__': main()
