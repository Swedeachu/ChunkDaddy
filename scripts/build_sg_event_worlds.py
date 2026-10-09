#!/usr/bin/env python3
"""Build size-limited SG worlds, keeping every copy of a template in one world folder."""
from __future__ import annotations
import argparse
import copy
import json
import os
import shutil
import struct
import subprocess
import zipfile
from pathlib import Path
from build_sg_events import prepare, sha256, texture_metadata, validate_manifest
from chunkdaddy_cli import Worker, progress_printer


def open_worker(args, label):
    return Worker(args.classpath, args.workspace, args.java, ['-Xmx' + args.heap], args.workspace / (label + '-worker.log'))


def make_plans(args, worker):
    plan, assets = prepare(args, worker)
    previous_path = args.swim_root / 'saved_worlds_sg_back_ups/composition-verification.json'
    previous = json.loads(previous_path.read_text()) if previous_path.exists() else {}
    counts = previous.get('recordsPerInstance', {})
    if not counts:
        for entry in previous.get('worlds', {}).values(): counts.update(entry['recordsPerInstance'])
    weights = {s['name']: counts.get(s['name'] + '@1',
               (s['rectangle']['maxChunkX'] - s['rectangle']['minChunkX'] + 1)
               * (s['rectangle']['maxChunkZ'] - s['rectangle']['minChunkZ'] + 1)) for s in plan['sources']}
    groups = [[] for _ in range(args.world_count)]
    totals = [0] * args.world_count
    for source in sorted(plan['sources'], key=lambda s: (-weights[s['name']], s['name'])):
        group = min(range(args.world_count), key=lambda i: (totals[i], len(groups[i]), i))
        groups[group].append(source)
        totals[group] += weights[source['name']]
    result = {}
    for number, group in enumerate(groups, 1):
        if not group: raise ValueError('Every world must have templates')
        name = 'SG_EVENTS' + (str(number) if number > 1 else '')
        part = copy.deepcopy(plan)
        part['name'] = name
        part['sources'] = sorted(copy.deepcopy(group), key=lambda s: s['name'])
        part['layout']['rows'] = len(group)
        for row, source in enumerate(part['sources']):
            for placement in source['placements']:
                placement['destChunkZ'] = row * part['layout']['strideZBlocks'] // 16
        result[name] = part
    return result, assets


def stage_assets(args, manifests, assets):
    staged = args.workspace / 'runtime-assets/sg'
    staged.mkdir(parents=True, exist_ok=True)
    index = {'schemaVersion': 2, 'worlds': {name: name + '/regions.json' for name in manifests}}
    for name, manifest in manifests.items():
        folder = staged / name
        folder.mkdir(exist_ok=True)
        (folder / 'regions.json').write_text(json.dumps(manifest, indent=2) + '\n')
    for name, asset in assets.items():
        folder = staged / name
        folder.mkdir(exist_ok=True)
        for field, filename in [('spawns', 'spawns.json'), ('pointsOfInterest', 'positions.json')]:
            (folder / filename).write_text(json.dumps(asset[field], indent=4) + '\n')
        shutil.copy2(args.workspace / (name + '.bin'), folder / 'storm-map.bin')
    (staged / 'regions.json').write_text(json.dumps(index, indent=2) + '\n')
    (args.workspace / 'world-index.json').write_text(json.dumps(index, indent=2) + '\n')


def check_archive(args, name, assets):
    archive = args.workspace / (name + '.zip')
    if archive.stat().st_size >= args.max_world_bytes:
        raise ValueError(f'{name} exceeds the {args.max_world_bytes}-byte limit; rebalance before publishing')
    with zipfile.ZipFile(archive) as world:
        if world.testzip() is not None: raise ValueError(f'Invalid ZIP: {name}')
        manifest = json.loads(world.read('regions.json'))
    subset = {template: assets[template] for template in manifest['templates']}
    validate_manifest(manifest, subset, args.copies, name)
    return manifest


def verify_world(args, worker, name):
    archive = args.workspace / (name + '.zip')
    directory = worker.send({'type': 'inspect_source', 'path': str(archive)})['worlds'][0]['directory']
    result = worker.send({'type': 'verify_regions', 'path': directory}, timeout=3600)
    core = args.swim_root / 'plugins/SwimCore'
    with (args.workspace / (name + '-pocketmine.log')).open('w') as log:
        subprocess.run([args.php, '-d', 'memory_limit=2G', 'tests/SgWorldLoadRegression.php', directory],
                       cwd=core, stdout=log, stderr=subprocess.STDOUT, check=True)
    print(json.dumps({'world': name, 'bytes': archive.stat().st_size,
                      'verifiedInstances': result['verifiedInstances'], 'comparedRecords': result['comparedRecords']}), flush=True)
    return result


def publish(args, manifests, assets, verified):
    saved = args.swim_root / 'savedWorlds'
    sg = args.swim_root / 'plugins/SwimCore/data/maps/sg'
    backup = args.swim_root / 'saved_worlds_sg_back_ups'
    previous = backup / 'previous_compositions'
    previous.mkdir(parents=True, exist_ok=True)
    for name in manifests:
        old = saved / (name + '.zip')
        if old.exists():
            target = previous / (name + '-' + sha256(old)[:12] + '.zip')
            if not target.exists(): shutil.copy2(old, target)
            if sha256(old) != sha256(target): raise ValueError('Previous composition backup mismatch')
    snapshot = previous / ('map-assets-' + sha256(sg / 'regions.json')[:12])
    if not snapshot.exists(): shutil.copytree(sg, snapshot)
    for source in sg.rglob('*'):
        if source.is_file() and sha256(source) != sha256(snapshot / source.relative_to(sg)):
            raise ValueError('Previous map asset backup mismatch')
    # Stage all world copies before changing any installed filename.
    for name in manifests:
        source = args.workspace / (name + '.zip')
        target = saved / (name + '.zip.pending')
        shutil.copy2(source, target)
        if sha256(source) != sha256(target): raise ValueError('World publication checksum mismatch')
    for name in manifests: os.replace(saved / (name + '.zip.pending'), saved / (name + '.zip'))
    staged = args.workspace / 'runtime-assets/sg'
    for name in [*assets, *manifests]:
        target = sg / name
        target.mkdir(exist_ok=True)
        for source in (staged / name).iterdir(): shutil.copy2(source, target / source.name)
    # The index is installed last, after both matching worlds and their metadata exist.
    temporary = sg / 'regions.json.pending'
    shutil.copy2(staged / 'regions.json', temporary)
    os.replace(temporary, sg / 'regions.json')
    for name in args.exclude:
        directory = sg / name
        if directory.exists():
            if directory.resolve().parent != sg.resolve() or not (snapshot / name).is_dir():
                raise ValueError('Unsafe excluded map asset path')
            shutil.rmtree(directory)
    index_path = backup / 'backup-index.json'
    index = json.loads(index_path.read_text())
    index.pop('composedWorldSha256', None)
    index.update({'composedWorlds': {name + '.zip': sha256(saved / (name + '.zip')) for name in manifests},
                  'templates': len(assets), 'instances': len(assets) * args.copies})
    index_path.write_text(json.dumps(index, indent=2) + '\n')
    (backup / 'composition-verification.json').write_text(json.dumps({'worlds': verified}, indent=2) + '\n')
    report = {'worlds': {name: {'bytes': (saved / (name + '.zip')).stat().st_size,
                               'templates': list(manifest['templates']),
                               'instances': len(manifest['instances'])} for name, manifest in manifests.items()},
              'templates': len(assets), 'copiesPerTemplate': args.copies, 'excluded': args.exclude,
              'maximumWorldBytes': args.max_world_bytes, 'minimumGapBlocks': args.gap}
    (backup / 'validation-summary.json').write_text(json.dumps(report, indent=2) + '\n')
    print(json.dumps({'published': report}), flush=True)


def main():
    if os.name != 'nt': raise RuntimeError('Run locally on Windows')
    repo = Path(__file__).resolve().parents[1]
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--swim-root', type=Path, required=True)
    parser.add_argument('--incoming', type=Path, required=True)
    parser.add_argument('--workspace', type=Path, default=repo / 'scripts/sg_events_workspace/split3')
    parser.add_argument('--inventory', type=Path, default=repo / 'scripts/sg_events_workspace/inventory.json')
    parser.add_argument('--java', default=str(repo / 'local/jdk/jdk-21.0.8+9/bin/java.exe'))
    parser.add_argument('--classpath', default=str(repo / 'worker/build/dist/chunkdaddy-worker.jar'))
    parser.add_argument('--php', default='C:/PHP/bin/php/php.exe')
    parser.add_argument('--heap', default='18g')
    parser.add_argument('--profile', default='bedrock-1.21.130')
    parser.add_argument('--copies', type=int, default=3)
    parser.add_argument('--world-count', type=int, default=2)
    parser.add_argument('--max-world-bytes', type=int, default=100_000_000)
    parser.add_argument('--gap', type=int, default=208)
    parser.add_argument('--exclude', nargs='*', default=['construct', 'ExtractionZero', 'mockingjay'])
    parser.add_argument('--publish', action='store_true')
    parser.add_argument('--publish-staged', action='store_true')
    args = parser.parse_args()
    if args.copies < 1 or args.world_count < 1 or args.gap < 208: parser.error('Invalid copies, world count or spacing')
    if not args.inventory.exists(): args.inventory = None
    args.regenerate_all = True
    args.workspace.mkdir(parents=True, exist_ok=True)
    assets_file = args.workspace / 'assets.json'
    if args.publish_staged:
        assets = json.loads(assets_file.read_text())
        index = json.loads((args.workspace / 'world-index.json').read_text())
        manifests, verified = {}, {}
        worker = open_worker(args, 'verify-staged')
        try:
            for name in index['worlds']:
                manifests[name] = check_archive(args, name, assets)
                verified[name] = verify_world(args, worker, name)
        finally: worker.close()
    else:
        worker = open_worker(args, 'prepare')
        try: plans, assets = make_plans(args, worker)
        finally: worker.close()
        assets_file.write_text(json.dumps(assets, indent=2))
        (args.workspace / 'world-plans.json').write_text(json.dumps(plans, indent=2))
        manifests, verified = {}, {}
        for name, plan in plans.items():
            worker = open_worker(args, name)
            try:
                print('Composing ' + name, flush=True)
                result = worker.send(plan, progress_printer(name + ' composition'), timeout=3600)
                manifest = json.loads(Path(result['regionManifestPath']).read_text())
                subset = {template: assets[template] for template in manifest['templates']}
                validate_manifest(manifest, subset, args.copies, name)
                for template in subset:
                    pixels = (args.workspace / (template + '.rgba')).read_bytes()
                    if len(pixels) != 128 * 128 * 4: raise ValueError('Incomplete map raster')
                    header = json.dumps(assets[template]['texture'], separators=(',', ':')).encode()
                    (args.workspace / (template + '.bin')).write_bytes(b'SWIMSGM2' + struct.pack('<I', len(header)) + header + pixels)
                first = manifest['instances'][0]
                point = manifest['templates'][first['template']]['metadata']['spawns'][0]
                worker.send({'type': 'set_world_spawn', 'documentId': result['documentId'],
                             'x': int(point['x'] + first['offset']['x']), 'y': int(point['y']),
                             'z': int(point['z'] + first['offset']['z'])})
                validation = worker.send({'type': 'validate_export', 'documentId': result['documentId']})
                if validation.get('problems'): raise ValueError(validation['problems'])
                worker.send({'type': 'export_world', 'documentId': result['documentId'],
                             'destination': str(args.workspace / (name + '.zip')), 'mode': 'ZIP', 'worldName': name,
                             'writeVoidColumns': False, 'voidCleaner': True, 'writeCompanionJson': False},
                            progress_printer(name + ' export'), timeout=7200)
                worker.send({'type': 'close_document', 'documentId': result['documentId']})
                manifests[name] = check_archive(args, name, assets)
                verified[name] = verify_world(args, worker, name)
            finally: worker.close()
        stage_assets(args, manifests, assets)
    if set().union(*(set(m['templates']) for m in manifests.values())) != set(assets): raise ValueError('Missing template')
    if sum(len(m['templates']) for m in manifests.values()) != len(assets): raise ValueError('Template split across worlds')
    for name in assets:
        if texture_metadata(args.workspace / (name + '.bin')) != assets[name]['texture']: raise ValueError('Texture source frame changed')
    core = args.swim_root / 'plugins/SwimCore'
    subprocess.run([args.php, 'tests/SgRegionsRegression.php', str(args.workspace / 'runtime-assets'), '--aot'], cwd=core, check=True)
    (args.workspace / 'verification.json').write_text(json.dumps({'worlds': verified}, indent=2))
    if args.publish or args.publish_staged: publish(args, manifests, assets, verified)


if __name__ == '__main__': main()
