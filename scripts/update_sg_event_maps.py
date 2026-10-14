#!/usr/bin/env python3
"""Replace edited SG templates in their existing worlds/slots using verified terrain alignment.

No relayout or coordinate metadata rewrite. Run without --publish to stage and validate.
Use --incoming for names like 'SG4 (Fixed).mcworld', or --updates template-to-path JSON.
"""
from __future__ import annotations

import argparse
import copy
import json
import os
import re
import shutil
import struct
import subprocess
import zipfile
from datetime import datetime, timezone
from pathlib import Path

from build_sg_events import color_rules, sha256, texture_metadata
from chunkdaddy_cli import Worker, progress_printer


def write_json(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, indent=2) + '\n', encoding='utf-8')


def discover(incoming, templates):
    aliases = {re.sub('[^a-z0-9]', '', name.lower()): name for name in templates}
    aliases.update({'sg4': 'SGFOUR', 'turbulance': 'turbulence', 'thezone85': 'zone85', 'phoenix': 'pheonix'})
    result = {}
    for path in sorted(incoming.glob('*.mcworld')):
        normalized = re.sub('[^a-z0-9]', '', re.sub(r'\bfixed\b', '', path.stem, flags=re.I).lower())
        name = aliases.get(normalized)
        if name not in templates: raise ValueError(f'Unknown map {path.name}; use --updates to specify its template')
        if name in result: raise ValueError(f'Duplicate update for {name}')
        result[name] = str(path.resolve())
    if not result: raise ValueError('No map updates found')
    return result


def snapshot_files(root):
    return {p.relative_to(root).as_posix(): sha256(p) for p in root.rglob('*') if p.is_file()}


def assert_inputs(args, report, allow_pending=False):
    sg = args.swim_root / 'plugins/SwimCore/data/maps/sg'
    current = snapshot_files(sg)
    if allow_pending:
        for name in report['updates']: current.pop(name + '/storm-map.bin.pending', None)
        for relative in report.get('runtimeAssetChanges', {}): current.pop(relative + '.pending', None)
    if current != report['originalRuntimeAssets']: raise ValueError('Runtime map assets changed during staging')
    for name, digest in report['originalWorlds'].items():
        if sha256(args.swim_root / 'savedWorlds' / (name + '.zip')) != digest: raise ValueError('Installed world changed during staging')
    for name, update in report['updates'].items():
        if sha256(Path(update['sourcePath'])) != update['sourceSha256']: raise ValueError(f'Edited source changed: {name}')


def verify_updated_terrain(args, report):
    """Decode packaged output independently and compare every replaced block type to its edit."""
    worker = Worker(args.classpath, args.workspace, args.java, ['-Xmx' + args.heap], args.workspace / 'terrain-verification-worker.log')
    try:
        for world, record in report['worlds'].items():
            archive = args.workspace / (world + '.zip')
            with zipfile.ZipFile(archive) as zipped:
                manifest = json.loads(zipped.read('regions.json'))
            directory = worker.send({'type': 'inspect_source', 'path': str(archive)})['worlds'][0]['directory']
            for name in record['updatedTemplates']:
                update = report['updates'][name]
                first = next(i for i in manifest['instances'] if i['template'] == name)
                print('Verifying exported terrain against edited source: ' + name, flush=True)
                result = worker.send({'type': 'align_bedrock_region', 'referencePath': directory, 'sourcePath': update['sourcePath'],
                                      'referenceRectangle': first['bounds'], 'profileId': args.profile, 'minimumSimilarity': 1.0},
                                     progress_printer(name + ' readback'), timeout=3600)
                if result['sourceToReferenceOffset'] != update['alignment']['sourceToReferenceOffset']:
                    raise ValueError('Exported terrain translation differs from verified alignment')
                if result['oldChestBlocks'] != update['alignment']['newChestBlocks'] or result['changedBlockTypes'] != 0:
                    raise ValueError('Exported chest/terrain contents differ from the edited source')
                update['exportedTerrainVerification'] = result
                write_json(args.workspace / 'update-report.json', report)
    finally:
        worker.close()
    report['terrainValidated'] = True
    write_json(args.workspace / 'update-report.json', report)


def replace_point(points, before, after):
    matches = [i for i, point in enumerate(points) if point == before]
    if len(matches) != 1: raise ValueError(f'Expected one exact position match for {before}, found {len(matches)}')
    points[matches[0]] = copy.deepcopy(after)


def patch_manifest_position(manifest, patch):
    """Input coordinates are in the original source frame, never an instance's grid frame."""
    template = manifest['templates'][patch['template']]
    field = patch['field']
    if field not in ('spawns', 'pointsOfInterest'): raise ValueError('Only spawn/POI corrections are supported')
    before, after = patch['from'], patch['to']
    for point in (before, after):
        if set(point) != {'x', 'y', 'z'} or any(type(v) not in (int, float) for v in point.values()):
            raise ValueError('Position correction must contain numeric XYZ coordinates')
    bounds = template['sourceBounds']
    def local(point):
        return {'x': point['x'] - bounds['minChunkX'] * 16, 'y': point['y'], 'z': point['z'] - bounds['minChunkZ'] * 16}
    replace_point(template['metadata'][field], local(before), local(after))


def apply_position_updates(args, report):
    patches = report.get('positionUpdates', [])
    if not patches: return
    staged = args.workspace / 'runtime-assets/sg'
    manifests = {}
    changed_files = set()
    for patch in patches:
        name = patch['template']
        if name not in report['updates']: raise ValueError('Position corrections must target a replaced template')
        world = report['updates'][name]['world']
        if world not in manifests: manifests[world] = json.loads((staged / world / 'regions.json').read_text())
        patch_manifest_position(manifests[world], patch)
        filename = 'spawns.json' if patch['field'] == 'spawns' else 'positions.json'
        path = staged / name / filename
        points = json.loads(path.read_text())
        replace_point(points, patch['from'], patch['to'])
        write_json(path, points)
        changed_files.add(name + '/' + filename)
    for world, manifest in manifests.items():
        path = staged / world / 'regions.json'
        write_json(path, manifest)
        changed_files.add(world + '/regions.json')
        archive = args.workspace / (world + '.zip')
        temporary = archive.with_suffix('.metadata-pending.zip')
        # Replace just the companion; all archived world/database entry bytes are unchanged.
        with zipfile.ZipFile(archive) as original, zipfile.ZipFile(temporary, 'w', compression=zipfile.ZIP_DEFLATED, compresslevel=1) as output:
            for entry in original.infolist():
                if entry.filename == 'regions.json': output.writestr(entry, path.read_bytes())
                else:
                    with original.open(entry) as source, output.open(entry, 'w') as target: shutil.copyfileobj(source, target)
        if temporary.stat().st_size >= args.max_world_bytes: raise ValueError('Corrected world exceeds size limit')
        with zipfile.ZipFile(temporary) as check:
            if check.testzip() is not None or check.read('regions.json') != path.read_bytes(): raise ValueError('Corrected archive validation failed')
        os.replace(temporary, archive)
        record = report['worlds'][world]
        record.update({'bytes': archive.stat().st_size, 'sha256': sha256(archive)})
        directory = Path(record['replacement']['verifiedDirectory'])
        write_json(directory / 'regions.json', manifest)
        with (args.workspace / (world + '-pocketmine.log')).open('w') as log:
            subprocess.run([args.php, '-d', 'memory_limit=2G', 'tests/SgWorldLoadRegression.php', str(directory)],
                           cwd=args.swim_root / 'plugins/SwimCore', stdout=log, stderr=subprocess.STDOUT, check=True)
    report['runtimeAssetChanges'] = {relative: sha256(staged / relative) for relative in sorted(changed_files)}
    write_json(args.workspace / 'update-report.json', report)


def publish(args, report):
    assert_inputs(args, report, allow_pending=True)
    saved = args.swim_root / 'savedWorlds'
    sg = args.swim_root / 'plugins/SwimCore/data/maps/sg'
    backup_root = args.swim_root / 'saved_worlds_sg_back_ups'
    backup = backup_root / 'map_updates' / report['runId']
    if (backup / 'update-report.json').exists(): raise ValueError(f'Backup run already published: {backup}')
    backup.mkdir(parents=True, exist_ok=True)
    for filename in ['backup-index.json', 'composition-verification.json', 'validation-summary.json']:
        original = backup_root / filename
        if original.exists():
            (backup / 'bookkeeping').mkdir(exist_ok=True)
            target = backup / 'bookkeeping' / filename
            if not target.exists(): shutil.copy2(original, target)
    for name, digest in report['originalWorlds'].items():
        original = saved / (name + '.zip')
        if not (backup / original.name).exists(): shutil.copy2(original, backup / original.name)
        if sha256(backup / original.name) != digest: raise ValueError('World backup checksum mismatch')
    if not (backup / 'runtime-assets').exists():
        # Previous interrupted publication may have left our own temporary files here.
        shutil.copytree(sg, backup / 'runtime-assets', ignore=shutil.ignore_patterns('storm-map.bin.pending'))
    if snapshot_files(backup / 'runtime-assets') != report['originalRuntimeAssets']: raise ValueError('Asset backup checksum mismatch')
    # Keep the exact edited inputs for future rebuilds and provenance; runtime frame stays original.
    for name, update in report['updates'].items():
        target = backup / 'edited-sources' / (name + '.mcworld')
        target.parent.mkdir(exist_ok=True)
        if not target.exists(): shutil.copy2(update['sourcePath'], target)
        if sha256(target) != update['sourceSha256']: raise ValueError('Edited source backup checksum mismatch')
    pairs = [(args.workspace / (name + '.zip'), saved / (name + '.zip')) for name in report['worlds']]
    pairs += [(args.workspace / 'runtime-assets/sg' / name / 'storm-map.bin', sg / name / 'storm-map.bin') for name in report['updates']]
    pairs += [(args.workspace / 'runtime-assets/sg' / relative, sg / relative) for relative in report.get('runtimeAssetChanges', {})]
    for source, target in pairs:
        pending = target.with_name(target.name + '.pending')
        shutil.copy2(source, pending)
        if sha256(source) != sha256(pending): raise ValueError('Publication checksum mismatch')
    assert_inputs(args, report, allow_pending=True)
    installed = []
    try:
        for source, target in pairs:
            os.replace(target.with_name(target.name + '.pending'), target)
            installed.append(target)
    except BaseException:
        for target in reversed(installed):
            original = backup / target.name if target.parent == saved else backup / 'runtime-assets' / target.relative_to(sg)
            shutil.copy2(original, target)
        raise
    # Only explicitly corrected source points/manifests may change; all other assets are invariant.
    for relative, digest in report['originalRuntimeAssets'].items():
        if relative in {name + '/storm-map.bin' for name in report['updates']}: continue
        digest = report.get('runtimeAssetChanges', {}).get(relative, digest)
        if sha256(sg / relative) != digest: raise ValueError('Coordinate metadata changed during publication')
    report['published'] = True
    report['backupDirectory'] = str(backup)
    write_json(backup / 'update-report.json', report)
    write_json(args.workspace / 'update-report.json', report)
    latest = {'runId': report['runId'], 'report': 'map_updates/' + report['runId'] + '/update-report.json'}
    index_path = backup_root / 'backup-index.json'
    index = json.loads(index_path.read_text()) if index_path.exists() else {}
    index.setdefault('composedWorlds', {}).update({name + '.zip': record['sha256'] for name, record in report['worlds'].items()})
    index['latestMapUpdate'] = latest
    write_json(index_path, index)
    verification_path = backup_root / 'composition-verification.json'
    verification = json.loads(verification_path.read_text()) if verification_path.exists() else {}
    verification.setdefault('worlds', {}).update({name: record['verification'] for name, record in report['worlds'].items()})
    write_json(verification_path, verification)
    summary_path = backup_root / 'validation-summary.json'
    summary = json.loads(summary_path.read_text()) if summary_path.exists() else {}
    for name, record in report['worlds'].items():
        manifest = json.loads((sg / name / 'regions.json').read_text())
        summary.setdefault('worlds', {})[name] = {'bytes': record['bytes'], 'templates': list(manifest['templates']), 'instances': len(manifest['instances'])}
    summary['latestMapUpdate'] = latest
    write_json(summary_path, summary)
    print(json.dumps({'published': report['worlds'], 'backup': str(backup)}), flush=True)


def main():
    if os.name != 'nt': raise RuntimeError('Run locally on Windows')
    repo = Path(__file__).resolve().parents[1]
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--swim-root', type=Path, required=True)
    source = parser.add_mutually_exclusive_group(required=True)
    source.add_argument('--incoming', type=Path)
    source.add_argument('--updates', type=Path)
    parser.add_argument('--workspace', type=Path, required=True)
    parser.add_argument('--java', default=str(repo / 'local/jdk/jdk-21.0.8+9/bin/java.exe'))
    parser.add_argument('--classpath', default=str(repo / 'worker/build/dist/chunkdaddy-worker.jar'))
    parser.add_argument('--php', default='C:/PHP/bin/php/php.exe')
    parser.add_argument('--heap', default='12g')
    parser.add_argument('--profile', default='bedrock-1.21.130')
    parser.add_argument('--max-world-bytes', type=int, default=100_000_000)
    parser.add_argument('--position-updates', type=Path, help='Explicit exact-match source-frame spawn/POI corrections as a JSON array')
    parser.add_argument('--publish', action='store_true')
    parser.add_argument('--publish-staged', action='store_true')
    args = parser.parse_args()
    args.workspace.mkdir(parents=True, exist_ok=True)
    if args.publish_staged:
        report = json.loads((args.workspace / 'update-report.json').read_text())
        if not report.get('validated') or not report.get('terrainValidated'): raise ValueError('Staged update was not fully validated')
        for name, world in report['worlds'].items():
            if sha256(args.workspace / (name + '.zip')) != world['sha256']: raise ValueError('Staged world changed')
        for name, update in report['updates'].items():
            if sha256(args.workspace / 'runtime-assets/sg' / name / 'storm-map.bin') != update['binarySha256']: raise ValueError('Staged binary changed')
        for relative, digest in report.get('runtimeAssetChanges', {}).items():
            if sha256(args.workspace / 'runtime-assets/sg' / relative) != digest: raise ValueError('Staged coordinate correction changed')
        publish(args, report)
        return
    sg = args.swim_root / 'plugins/SwimCore/data/maps/sg'
    index = json.loads((sg / 'regions.json').read_text())
    manifests = {name: json.loads((sg / path).read_text()) for name, path in index['worlds'].items()}
    owners = {}
    for world, manifest in manifests.items():
        for name in manifest['templates']:
            if name in owners: raise ValueError('Template is split across worlds')
            owners[name] = world
    updates = discover(args.incoming, owners) if args.incoming else json.loads(args.updates.read_text())
    if not updates or set(updates) - set(owners): raise ValueError('Empty or unknown map updates')
    staged_assets = args.workspace / 'runtime-assets/sg'
    if staged_assets.exists(): raise ValueError('Use a fresh workspace for each staging run')
    shutil.copytree(sg, staged_assets)
    report = {'runId': datetime.now(timezone.utc).strftime('%Y%m%dT%H%M%S%fZ'), 'published': False,
              'originalRuntimeAssets': snapshot_files(sg), 'originalWorlds': {}, 'updates': {}, 'worlds': {}}
    if args.position_updates: report['positionUpdates'] = json.loads(args.position_updates.read_text())
    worker = Worker(args.classpath, args.workspace, args.java, ['-Xmx' + args.heap], args.workspace / 'worker.log')
    try:
        capabilities = worker.send({'type': 'capabilities'})
        for feature in ['align_bedrock_region', 'replace_bedrock_regions']:
            if feature not in capabilities['regionFeatures']: raise ValueError(f'Rebuild ChunkDaddy: missing {feature}')
        for world, manifest in manifests.items():
            changed = [name for name in updates if owners[name] == world]
            if not changed: continue
            installed = args.swim_root / 'savedWorlds' / (world + '.zip')
            report['originalWorlds'][world] = sha256(installed)
            with zipfile.ZipFile(installed) as archive:
                original_manifest = archive.read('regions.json')
                if json.loads(original_manifest) != manifest: raise ValueError('Installed world/runtime manifests disagree')
            base = worker.send({'type': 'inspect_source', 'path': str(installed)})['worlds'][0]['directory']
            sources, footprints = [], []
            for name in changed:
                path = Path(updates[name]).resolve()
                source_digest = sha256(path)
                instances = [i for i in manifest['instances'] if i['template'] == name]
                if len(instances) != 3: raise ValueError(f'Expected three existing instances of {name}')
                first = instances[0]
                print('Aligning ' + name + ' in ' + world, flush=True)
                alignment = worker.send({'type': 'align_bedrock_region', 'referencePath': base, 'sourcePath': str(path),
                                         'referenceRectangle': first['bounds'], 'profileId': args.profile},
                                        progress_printer(name + ' alignment'), timeout=3600)
                print(json.dumps({'map': name, **{k: v for k, v in alignment.items() if k != 'landmarkColumns'}}), flush=True)
                texture = texture_metadata(sg / name / 'storm-map.bin')
                local = manifest['templates'][name]['metadata']['texture']
                delta = alignment['sourceToReferenceOffset']
                center_x = local['centerX'] + first['offset']['x'] - delta['x']
                center_z = local['centerZ'] + first['offset']['z'] - delta['z']
                sources.append({'name': name, 'path': str(path), 'rectangle': alignment['sourceRectangle'],
                                'metadata': {}, 'coordinatePaths': [],
                                'placements': [{'id': i['id'], 'destChunkX': i['bounds']['minChunkX'], 'destChunkZ': i['bounds']['minChunkZ']} for i in instances],
                                'raster': {'destination': str(args.workspace / (name + '.rgba')), 'minX': int(center_x - 256), 'minZ': int(center_z - 256),
                                           'colorRules': color_rules(), 'emptyRgb': 0x4040ff, 'emptyShade': 220}})
                footprints.extend(i['bounds'] for i in instances)
                report['updates'][name] = {'world': world, 'sourcePath': str(path), 'sourceSha256': source_digest,
                                           'alignment': alignment, 'instanceIds': [i['id'] for i in instances],
                                           'textureHeader': texture}
                write_json(args.workspace / 'alignment-report.json', report)
            write_json(args.workspace / (world + '-overlay-plan.json'), {'sources': sources})
            composition = worker.send({'type': 'compose_regions', 'name': world, 'profileId': args.profile, 'sources': sources},
                                      progress_printer(world + ' overlay'), timeout=3600)
            for name in changed:
                pixels = (args.workspace / (name + '.rgba')).read_bytes()
                if len(pixels) != 128 * 128 * 4: raise ValueError('Incomplete map raster')
                binary = staged_assets / name / 'storm-map.bin'
                old_binary = (sg / name / 'storm-map.bin').read_bytes()
                header_end = 12 + struct.unpack('<I', old_binary[8:12])[0]
                binary.write_bytes(old_binary[:header_end] + pixels)
                if texture_metadata(binary) != report['updates'][name]['textureHeader']: raise ValueError('Binary coordinate frame changed')
                report['updates'][name]['binarySha256'] = sha256(binary)
            overlay = args.workspace / (world + '-overlay.zip')
            worker.send({'type': 'export_world', 'documentId': composition['documentId'], 'destination': str(overlay), 'mode': 'ZIP',
                         'worldName': world, 'writeVoidColumns': False, 'voidCleaner': True, 'writeCompanionJson': False},
                        progress_printer(world + ' export'), timeout=7200)
            worker.send({'type': 'close_document', 'documentId': composition['documentId']})
            destination = args.workspace / (world + '.zip')
            result = worker.send({'type': 'replace_bedrock_regions', 'basePath': base, 'overlayPath': str(overlay),
                                  'rectangles': footprints, 'destination': str(destination), 'mode': 'ZIP'}, timeout=3600)
            if destination.stat().st_size >= args.max_world_bytes: raise ValueError('Updated world exceeds size limit')
            with zipfile.ZipFile(destination) as archive:
                if archive.testzip() is not None: raise ValueError('Corrupt world ZIP')
                if archive.read('regions.json') != original_manifest: raise ValueError('Embedded coordinate manifest changed')
            verified = worker.send({'type': 'verify_regions', 'path': result['verifiedDirectory']}, timeout=3600)
            core = args.swim_root / 'plugins/SwimCore'
            with (args.workspace / (world + '-pocketmine.log')).open('w') as log:
                subprocess.run([args.php, '-d', 'memory_limit=2G', 'tests/SgWorldLoadRegression.php', result['verifiedDirectory']],
                               cwd=core, stdout=log, stderr=subprocess.STDOUT, check=True)
            report['worlds'][world] = {'bytes': destination.stat().st_size, 'sha256': sha256(destination),
                                       'updatedTemplates': changed, 'replacement': result, 'verification': verified}
            write_json(args.workspace / 'update-report.json', report)
            print(json.dumps({'world': world, 'bytes': destination.stat().st_size, 'verifiedInstances': verified['verifiedInstances']}), flush=True)
    finally:
        worker.close()
    verify_updated_terrain(args, report)
    apply_position_updates(args, report)
    core = args.swim_root / 'plugins/SwimCore'
    subprocess.run([args.php, 'tests/SgRegionsRegression.php', str(args.workspace / 'runtime-assets'), '--aot'], cwd=core, check=True)
    assert_inputs(args, report)
    report['validated'] = True
    write_json(args.workspace / 'update-report.json', report)
    if args.publish: publish(args, report)


if __name__ == '__main__': main()
