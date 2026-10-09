#!/usr/bin/env python3
"""Compose and safely install Swim.gg SG_EVENTS with the ChunkDaddy CLI worker on Windows.

This is an application adapter. ChunkDaddy's compose_regions protocol remains game-neutral.
Run without --publish to leave a reviewable world and assets in the workspace.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import math
import os
import re
import shutil
import struct
import zipfile
from collections import Counter
from pathlib import Path

from chunkdaddy_cli import Worker, progress_printer


def load_positions(path: Path) -> list[dict]:
    text = path.read_text(encoding='utf-8-sig')
    # The supplied new spawn lists omit commas between several numeric XYZ members.
    repaired = re.sub(r'("[xyz]"\s*:\s*-?\d+(?:\.\d+)?)(\s+)(?="[xyz]"\s*:)', r'\1,\2', text)
    points = json.loads(repaired)
    if not isinstance(points, list) or not points:
        raise ValueError(f'Empty position list: {path}')
    for point in points:
        if set(point) != {'x', 'y', 'z'} or any(type(v) not in (int, float) or not math.isfinite(v) for v in point.values()):
            raise ValueError(f'Invalid coordinate in {path}: {point}')
    return points


def sha256(path: Path) -> str:
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def texture_metadata(path: Path) -> dict:
    payload = path.read_bytes()
    if payload[:8] != b'SWIMSGM2':
        raise ValueError(f'Invalid texture: {path}')
    length = struct.unpack('<I', payload[8:12])[0]
    if len(payload) - 12 - length != 128 * 128 * 4:
        raise ValueError(f'Invalid pixel length: {path}')
    return json.loads(payload[12:12 + length])


def color_rules() -> list[dict]:
    rules = [{'contains': ['glass', 'torch', 'flower', 'sapling', 'tall grass', 'short grass', 'fern',
                            'dead bush', 'vine', 'mushroom', 'rail', 'carpet', 'button', 'pressure plate',
                            'sign', 'banner', 'tripwire', 'redstone wire', 'repeater', 'comparator', 'lever',
                            'ladder', 'scaffolding', 'candle', 'skull', 'head', 'pot', 'crop', 'wheat',
                            'carrots', 'potatoes', 'beetroot', 'sugar cane', 'seagrass', 'kelp'], 'ignore': True}]
    groups = [(['water', 'bubble column'], (64, 64, 255)), (['lava', 'magma'], (255, 100, 0)),
              (['snow'], (255, 255, 255)), (['ice'], (160, 160, 255)),
              (['grass', 'moss', 'azalea'], (127, 178, 56)), (['leaves', 'leaf'], (87, 124, 41)),
              (['mycelium'], (112, 84, 92)), (['podzol', 'rooted dirt'], (129, 86, 49)),
              (['mud'], (61, 57, 52)), (['dirt', 'farmland'], (151, 109, 77)),
              (['red sand'], (216, 127, 51)), (['sandstone'], (216, 203, 140)), (['sand'], (247, 233, 163)),
              (['gravel'], (136, 126, 126)), (['clay'], (160, 167, 167)),
              (['deepslate', 'blackstone', 'basalt'], (64, 64, 64)),
              (['stone', 'cobblestone', 'andesite'], (112, 112, 112)),
              (['diorite', 'calcite', 'quartz'], (255, 252, 245)), (['granite'], (143, 96, 67)),
              (['netherrack', 'nether brick'], (111, 54, 52)), (['soul sand', 'soul soil'], (84, 64, 51)),
              (['end stone'], (220, 222, 158)), (['obsidian'], (44, 35, 56)), (['bedrock'], (84, 84, 84)),
              (['prismarine'], (74, 128, 116)), (['gold'], (243, 204, 55)), (['iron'], (208, 204, 194)),
              (['diamond'], (92, 219, 213)), (['emerald'], (49, 181, 85)), (['redstone'], (174, 36, 30)),
              (['lapis'], (42, 74, 151)), (['coal'], (47, 47, 47)), (['hay'], (191, 168, 54)),
              (['melon'], (115, 151, 53)), (['pumpkin'], (194, 113, 36)), (['cactus'], (48, 111, 45)),
              (['bamboo'], (92, 136, 50)), (['birch'], (247, 233, 163)), (['spruce'], (129, 86, 49)),
              (['dark oak'], (79, 59, 40)), (['acacia'], (174, 92, 59)), (['jungle'], (154, 123, 77)),
              (['mangrove'], (116, 54, 48)), (['cherry'], (218, 164, 168)), (['crimson'], (109, 50, 76)),
              (['warped'], (42, 127, 120)), (['log', 'wood', 'stem', 'hyphae', 'planks', 'bookshelf', 'chest'], (143, 119, 72)),
              (['light blue'], (91, 161, 211)), (['light gray'], (157, 157, 151)), (['magenta'], (181, 70, 194)),
              (['orange'], (216, 127, 51)), (['yellow'], (229, 229, 51)), (['lime'], (127, 204, 25)),
              (['pink'], (242, 127, 165)), (['gray'], (76, 76, 76)), (['cyan'], (76, 127, 153)),
              (['purple'], (127, 63, 178)), (['blue'], (51, 76, 178)), (['brown'], (102, 76, 51)),
              (['green'], (102, 127, 51)), (['red'], (153, 51, 51)), (['black'], (25, 25, 25)), (['white'], (240, 240, 240))]
    rules += [{'contains': terms, 'rgb': r << 16 | g << 8 | b} for terms, (r, g, b) in groups]
    return rules


def prepare(args, worker: Worker) -> tuple[dict, dict]:
    sg = args.swim_root / 'plugins/SwimCore/data/maps/sg'
    backup = args.swim_root / 'saved_worlds_sg_back_ups'
    existing = sorted(p.name for p in sg.iterdir() if p.is_dir() and (p / 'spawns.json').exists())
    new = {'Flashpoint': ('Flashpoint.mcworld', 'flashpoint spawns.json', 'flashpoint pigs.txt'),
           'Forlorn': ('Forlorn.mcworld', 'for lorn spawns.txt', 'forlorn pigs.txt'),
           'Garrigal': ('garrigal.mcworld', 'garrigal spawns.txt', 'garrigal pigs.txt')}
    updates = {'SGA': 'SGA UPDATED.mcworld', 'SGFOUR': 'SG4 UPDATED.mcworld'}
    inventory = {item['name']: item for item in json.loads(args.inventory.read_text())} if args.inventory else {}
    sources, assets = [], {}
    for name in sorted(set(existing) | set(new)):
        if name in getattr(args, 'exclude', []): continue;
        directory = sg / name
        source = args.swim_root / 'savedWorlds' / (name + '.zip')
        if not source.exists(): source = backup / (name + '.zip')
        if name in new:
            archive, spawns, pigs = new[name]
            source = args.incoming / archive
            spawn_points = load_positions(args.incoming / spawns)
            poi_points = load_positions(args.incoming / pigs)
        else:
            spawn_points = load_positions(directory / 'spawns.json')
            poi_points = load_positions(directory / 'positions.json')
        if name in updates: source = args.incoming / updates[name]
        if not source.is_file(): raise FileNotFoundError(source)
        points = spawn_points + poi_points
        center_x = math.floor((min(p['x'] for p in points) + max(p['x'] for p in points)) / 2 + 0.5)
        center_z = math.floor((min(p['z'] for p in points) + max(p['z'] for p in points)) / 2 + 0.5)
        if name not in new and name not in updates:
            texture = texture_metadata(directory / 'storm-map.bin')
            center_x, center_z = texture['centerX'], texture['centerZ']
        source_hash = sha256(source)
        inspected = inventory.get(name, {})
        bounds = inspected.get('bounds') if inspected.get('sourceSha256') == source_hash else None
        if bounds is None:
            root = worker.send({'type': 'inspect_source', 'path': str(source)})['worlds']
            if len(root) != 1: raise ValueError(f'Expected one world in {source}')
            state = worker.send({'type': 'open_world', 'directory': root[0]['directory'], 'edition': root[0]['edition'],
                                 'profileId': args.profile})
            bounds = state['importedBounds']
            worker.send({'type': 'close_document', 'documentId': state['documentId']})
        # A generous 800-block radius contains the 800-block initial storm even when
        # its random center moves within the POI hull, while excluding distant builder remnants.
        radius_x = max(800, max(abs(p['x'] - center_x) for p in points) + 400)
        radius_z = max(800, max(abs(p['z'] - center_z) for p in points) + 400)
        rectangle = {'minChunkX': max(bounds['minChunkX'], math.floor((center_x - radius_x) / 16)),
                     'minChunkZ': max(bounds['minChunkZ'], math.floor((center_z - radius_z) / 16)),
                     'maxChunkX': min(bounds['maxChunkX'], math.floor((center_x + radius_x) / 16)),
                     'maxChunkZ': min(bounds['maxChunkZ'], math.floor((center_z + radius_z) / 16))}
        for point in points:
            if not (rectangle['minChunkX'] * 16 <= point['x'] < (rectangle['maxChunkX'] + 1) * 16
                    and rectangle['minChunkZ'] * 16 <= point['z'] < (rectangle['maxChunkZ'] + 1) * 16):
                raise ValueError(f'{name} metadata lies outside the source region: {point}')
        texture = {'mapName': name, 'centerX': center_x, 'centerZ': center_z, 'radius': 256,
                   'scale': 2, 'width': 128, 'height': 128}
        record = {'name': name, 'path': str(source), 'rectangle': rectangle,
                  'metadata': {'spawns': spawn_points, 'pointsOfInterest': poi_points, 'texture': texture,
                               'sourceFile': source.name, 'sourceSha256': source_hash},
                  'coordinatePaths': [{'path': '/spawns/*'}, {'path': '/pointsOfInterest/*'},
                                      {'path': '/texture', 'x': 'centerX', 'y': '', 'z': 'centerZ'}]}
        if name in new or name in updates or getattr(args, 'regenerate_all', False):
            record['raster'] = {'destination': str(args.workspace / (name + '.rgba')), 'minX': center_x - 256,
                                'minZ': center_z - 256, 'colorRules': color_rules(), 'emptyRgb': 0x4040ff, 'emptyShade': 220}
        sources.append(record)
        assets[name] = {'spawns': spawn_points, 'pointsOfInterest': poi_points, 'texture': texture,
                        'generate': 'raster' in record}
    gap = math.ceil(args.gap / 16)
    max_width = max(s['rectangle']['maxChunkX'] - s['rectangle']['minChunkX'] + 1 for s in sources)
    max_depth = max(s['rectangle']['maxChunkZ'] - s['rectangle']['minChunkZ'] + 1 for s in sources)
    stride_x, stride_z = max_width + gap, max_depth + gap
    for row, source in enumerate(sources):
        # One template per row, one copy per column, all corners on the chunk lattice.
        source['placements'] = [{'id': source['name'] + '@' + str(column + 1),
                                 'destChunkX': column * stride_x, 'destChunkZ': row * stride_z}
                                for column in range(args.copies)]
    plan = {'type': 'compose_regions', 'name': 'SG_EVENTS', 'profileId': args.profile, 'sources': sources,
            'layout': {'columns': args.copies, 'rows': len(sources), 'minimumGapBlocks': gap * 16,
                       'strideXBlocks': stride_x * 16, 'strideZBlocks': stride_z * 16}}
    return plan, assets


def validate_manifest(manifest: dict, assets: dict, copies: int, world_name: str = 'SG_EVENTS') -> None:
    if manifest['world'] != world_name or set(manifest['templates']) != set(assets):
        raise ValueError('Export template set does not match the source set')
    if Counter(i['template'] for i in manifest['instances']) != Counter({name: copies for name in assets}):
        raise ValueError('Each template must have the requested number of copies')
    for name, template in manifest['templates'].items():
        ox, oz = template['sourceBounds']['minChunkX'] * 16, template['sourceBounds']['minChunkZ'] * 16
        for field in ['spawns', 'pointsOfInterest']:
            expected = [{'x': p['x'] - ox, 'y': p['y'], 'z': p['z'] - oz} for p in assets[name][field]]
            if expected != template['metadata'][field]: raise ValueError(f'Coordinate drift: {name}/{field}')
    footprints = []
    gap = manifest['layout']['minimumGapBlocks']
    for instance in manifest['instances']:
        b = instance['bounds']
        if instance['offset'] != {'x': b['minChunkX'] * 16, 'y': 0, 'z': b['minChunkZ'] * 16}:
            raise ValueError('Instance offset differs from physical chunk placement')
        box = (b['minChunkX'] * 16, b['minChunkZ'] * 16, (b['maxChunkX'] + 1) * 16, (b['maxChunkZ'] + 1) * 16)
        for old in footprints:
            dx = max(old[0] - box[2], box[0] - old[2], 0)
            dz = max(old[1] - box[3], box[1] - old[3], 0)
            if max(dx, dz) < gap: raise ValueError('Region footprints violate the minimum spacing')
        footprints.append(box)


def publish(args, archive: Path, manifest: dict, assets: dict) -> None:
    saved = args.swim_root / 'savedWorlds'
    backup = args.swim_root / 'saved_worlds_sg_back_ups'
    sg = args.swim_root / 'plugins/SwimCore/data/maps/sg'
    backup.mkdir(exist_ok=True)
    for name, asset in assets.items():
        binary = args.workspace / (name + '.bin') if asset['generate'] else sg / name / 'storm-map.bin'
        if texture_metadata(binary) != asset['texture']:
            raise ValueError(f'Staged binary header differs from its source coordinates: {name}')
    # Back up originals and their source metadata before publishing any replacement.
    for name in assets:
        old = saved / (name + '.zip')
        if old.exists():
            target = backup / old.name
            if target.exists() and sha256(target) != sha256(old):
                raise ValueError(f'Conflicting original backup: {target}')
            if not target.exists(): shutil.copy2(old, target)
            if sha256(old) != sha256(target): raise ValueError(f'Backup verification failed: {old}')
        directory = sg / name
        target = backup / 'map_metadata' / name
        if directory.exists() and not target.exists(): shutil.copytree(directory, target)
    originals = {p.name: sha256(p) for p in backup.glob('*.zip')}
    (backup / 'backup-index.json').write_text(json.dumps({'originalWorlds': originals,
        'composedWorldSha256': sha256(archive), 'templates': len(assets),
        'instances': len(manifest['instances'])}, indent=2), encoding='utf-8')
    verification = args.workspace / 'verification.json'
    if verification.exists(): shutil.copy2(verification, backup / 'composition-verification.json')
    destination = saved / 'SG_EVENTS.zip'
    temporary = destination.with_suffix('.zip.tmp')
    shutil.copy2(archive, temporary)
    if sha256(temporary) != sha256(archive): raise ValueError('World publication copy failed')
    os.replace(temporary, destination)
    for name, asset in assets.items():
        directory = sg / name
        directory.mkdir(exist_ok=True)
        for field, filename in [('spawns', 'spawns.json'), ('pointsOfInterest', 'positions.json')]:
            target = directory / filename
            if not target.exists() or load_positions(target) != asset[field]:
                target.write_text(json.dumps(asset[field], indent=4) + '\n', encoding='utf-8')
        if asset['generate']: shutil.copy2(args.workspace / (name + '.bin'), directory / 'storm-map.bin')
    (sg / 'regions.json').write_text(json.dumps(manifest, indent=2) + '\n', encoding='utf-8')
    # Only the SG event pool's old world archives are removed, after verified backups exist.
    for name in assets:
        old = saved / (name + '.zip')
        if old.is_file(): old.unlink()
    print(json.dumps({'published': str(destination), 'backup': str(backup), 'templates': len(assets),
                      'instances': len(manifest['instances'])}), flush=True)


def main() -> None:
    if os.name != 'nt': raise RuntimeError('This adapter is intended for local Windows execution')
    repo = Path(__file__).resolve().parents[1]
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--swim-root', type=Path, required=True)
    parser.add_argument('--incoming', type=Path, required=True)
    parser.add_argument('--workspace', type=Path, default=repo / 'scripts/sg_events_workspace')
    parser.add_argument('--inventory', type=Path)
    parser.add_argument('--java', default=str(repo / 'local/jdk/jdk-21.0.8+9/bin/java.exe'))
    parser.add_argument('--classpath', default=str(repo / 'worker/build/dist/chunkdaddy-worker.jar'))
    # PocketMine's storage protocol limit can lag behind its advertised client protocol.
    parser.add_argument('--profile', default='bedrock-1.21.130')
    parser.add_argument('--copies', type=int, default=5)
    parser.add_argument('--gap', type=int, default=208)
    parser.add_argument('--publish', action='store_true')
    parser.add_argument('--publish-staged', action='store_true', help='Verify and install the previously composed workspace')
    args = parser.parse_args()
    if args.copies < 1 or args.gap < 0: parser.error('Copies must be positive and spacing non-negative')
    args.workspace.mkdir(parents=True, exist_ok=True)
    archive = args.workspace / 'SG_EVENTS.zip'
    worker = Worker(args.classpath, args.workspace, args.java, ['-Xmx22g'], args.workspace / 'worker.log')
    try:
        capabilities = worker.send({'type': 'capabilities'})
        if 'compose_regions' not in capabilities.get('regionFeatures', []):
            raise RuntimeError('Build the updated ChunkDaddy worker before running this adapter')
        if args.publish_staged:
            assets = json.loads((args.workspace / 'assets.json').read_text())
            with zipfile.ZipFile(archive) as world:
                if world.testzip() is not None: raise ValueError('World ZIP failed CRC verification')
                manifest = json.loads(world.read('regions.json'))
            validate_manifest(manifest, assets, args.copies)
            verified = worker.send({'type': 'verify_regions', 'path': str(archive)}, timeout=3600)
            (args.workspace / 'verification.json').write_text(json.dumps(verified, indent=2), encoding='utf-8')
            print(json.dumps(verified), flush=True)
            publish(args, archive, manifest, assets)
            return
        plan, assets = prepare(args, worker)
        (args.workspace / 'compose-plan.json').write_text(json.dumps(plan, indent=2), encoding='utf-8')
        (args.workspace / 'assets.json').write_text(json.dumps(assets, indent=2), encoding='utf-8')
        result = worker.send(plan, progress_printer('SG composition'), timeout=3600)
        manifest = json.loads(Path(result['regionManifestPath']).read_text())
        validate_manifest(manifest, assets, args.copies)
        for name, asset in assets.items():
            if not asset['generate']: continue
            pixels = (args.workspace / (name + '.rgba')).read_bytes()
            if len(pixels) != 128 * 128 * 4: raise ValueError(f'Incomplete raster: {name}')
            header = json.dumps(asset['texture'], separators=(',', ':')).encode()
            (args.workspace / (name + '.bin')).write_bytes(b'SWIMSGM2' + struct.pack('<I', len(header)) + header + pixels)
        # A complete staged asset tree allows the real PHP pool to be checked before publication.
        staged = args.workspace / 'runtime-assets/sg'
        staged.mkdir(parents=True, exist_ok=True)
        for name, asset in assets.items():
            directory = staged / name
            directory.mkdir(exist_ok=True)
            for field, filename in [('spawns', 'spawns.json'), ('pointsOfInterest', 'positions.json')]:
                (directory / filename).write_text(json.dumps(asset[field]), encoding='utf-8')
            texture = args.workspace / (name + '.bin') if asset['generate'] else args.swim_root / 'plugins/SwimCore/data/maps/sg' / name / 'storm-map.bin'
            shutil.copy2(texture, directory / 'storm-map.bin')
        (staged / 'regions.json').write_text(json.dumps(manifest), encoding='utf-8')
        first = manifest['instances'][0]
        spawn = manifest['templates'][first['template']]['metadata']['spawns'][0]
        worker.send({'type': 'set_world_spawn', 'documentId': result['documentId'],
                     'x': int(spawn['x'] + first['offset']['x']), 'y': int(spawn['y']),
                     'z': int(spawn['z'] + first['offset']['z'])})
        validation = worker.send({'type': 'validate_export', 'documentId': result['documentId']})
        if validation.get('problems'): raise ValueError(validation['problems'])
        exported = worker.send({'type': 'export_world', 'documentId': result['documentId'],
                                'destination': str(archive), 'mode': 'ZIP', 'worldName': 'SG_EVENTS',
                                'writeVoidColumns': False, 'voidCleaner': True, 'writeCompanionJson': False},
                               progress_printer('SG export'), timeout=7200)
        with zipfile.ZipFile(archive) as world:
            if world.testzip() is not None: raise ValueError('World ZIP failed CRC verification')
            if not {'level.dat', 'levelname.txt', 'regions.json', 'db/CURRENT'}.issubset(world.namelist()):
                raise ValueError('Missing world database or metadata files')
            if json.loads(world.read('regions.json')) != manifest: raise ValueError('World and companion manifest differ')
        (args.workspace / 'export-result.json').write_text(json.dumps(exported, indent=2), encoding='utf-8')
        verified = worker.send({'type': 'verify_regions', 'path': str(archive)}, timeout=3600)
        (args.workspace / 'verification.json').write_text(json.dumps(verified, indent=2), encoding='utf-8')
        print(json.dumps(verified), flush=True)
        if args.publish: publish(args, archive, manifest, assets)
        else: print(json.dumps({'world': str(archive), 'manifest': result['regionManifestPath']}), flush=True)
    finally:
        worker.close()


if __name__ == '__main__':
    main()
