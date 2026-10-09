"""Read-only inventory of SG sources using the existing ChunkDaddy worker."""
import json
import struct
import argparse
import hashlib
from pathlib import Path
from chunkdaddy_cli import Worker

repo = Path(__file__).resolve().parents[1]
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--swim-root', type=Path, required=True)
parser.add_argument('--incoming', type=Path, required=True)
parser.add_argument('--workspace', type=Path, default=repo / 'scripts/sg_events_workspace')
args = parser.parse_args()
swim, incoming, workspace = args.swim_root, args.incoming, args.workspace
workspace.mkdir(parents=True, exist_ok=True)
sources = {p.name: swim / 'savedWorlds' / (p.name + '.zip')
           for p in (swim / 'plugins/SwimCore/data/maps/sg').iterdir() if p.is_dir() and (p / 'spawns.json').exists()}
sources.update({'SGA': incoming / 'SGA UPDATED.mcworld', 'SGFOUR': incoming / 'SG4 UPDATED.mcworld',
                'Flashpoint': incoming / 'Flashpoint.mcworld', 'Forlorn': incoming / 'Forlorn.mcworld',
                'Garrigal': incoming / 'garrigal.mcworld'})
worker = Worker(str(repo / 'worker/build/dist/chunkdaddy-worker.jar'), workspace,
                str(repo / 'local/jdk/jdk-21.0.8+9/bin/java.exe'), ['-Xmx8g'], workspace / 'inspect.log')
inventory = []
try:
    print(json.dumps(worker.send({'type': 'capabilities'})), flush=True)
    for name, path in sorted(sources.items()):
        if not path.exists(): path = swim / 'saved_worlds_sg_back_ups' / path.name
        roots = worker.send({'type': 'inspect_source', 'path': str(path)})['worlds']
        assert len(roots) == 1, (name, roots)
        state = worker.send({'type': 'open_world', 'directory': roots[0]['directory'],
                             'edition': roots[0]['edition'], 'name': name, 'profileId': 'bedrock-1.26.40'})
        result = {'name': name, 'path': str(path), 'bounds': state.get('importedBounds'),
                  'columns': state.get('importedColumns'), 'notices': state.get('notices')}
        with path.open('rb') as stream:
            result['sourceSha256'] = hashlib.file_digest(stream, 'sha256').hexdigest()
        binary = swim / 'plugins/SwimCore/data/maps/sg' / name / 'storm-map.bin'
        if binary.exists():
            payload = binary.read_bytes()
            result['texture'] = json.loads(payload[12:12 + struct.unpack('<I', payload[8:12])[0]])
        inventory.append(result)
        (workspace / 'inventory.json').write_text(json.dumps(inventory, indent=2))
        print(json.dumps(result), flush=True)
        worker.send({'type': 'close_document', 'documentId': state['documentId']})
finally:
    worker.close()
