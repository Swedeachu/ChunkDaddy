"""Exercise the distributed worker using its bundled runtime and real JSONL protocol."""
import json
import os
from pathlib import Path
import queue
import subprocess
import sys
import tempfile
import threading
import zipfile


def main():
    build = Path(sys.argv[1]).resolve()
    java = build / ('runtime/bin/java.exe' if os.name == 'nt' else 'runtime/bin/java')
    with tempfile.TemporaryDirectory(prefix='chunkdaddy-smoke-') as workspace:
        with tempfile.TemporaryFile(mode='w+', encoding='utf-8') as errors:
            process = subprocess.Popen(
                [str(java), '-XX:+UseZGC', '-jar', str(build / 'worker/chunkdaddy-worker.jar'),
                 '--workspace', workspace], stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                stderr=errors, text=True, encoding='utf-8')
            replies = queue.Queue()

            def read():
                for line in process.stdout:
                    replies.put(line)
                replies.put(None)

            reader = threading.Thread(target=read, daemon=True)
            reader.start()
            next_id = 0

            def request(kind, **payload):
                nonlocal next_id
                next_id += 1
                process.stdin.write(json.dumps(dict(id=next_id, type=kind, **payload)) + '\n')
                process.stdin.flush()
                while True:
                    line = replies.get(timeout=60)
                    if line is None:
                        raise RuntimeError('Worker exited before replying')
                    reply = json.loads(line)
                    if 'event' in reply:
                        continue
                    if reply.get('id') != next_id or not reply.get('ok'):
                        raise RuntimeError(f'Unexpected worker response: {reply}')
                    return reply['result']

            try:
                capabilities = request('capabilities')
                assert capabilities['protocolVersion'] == 1, capabilities
                assert capabilities['javaVersion'].startswith('21.'), capabilities
                profiles = capabilities['targetProfiles']
                assert profiles, capabilities
                for profile in profiles:
                    document = request('new_document', name='Setup smoke test', profileId=profile['id'])
                    assert document['name'] == 'Setup smoke test', document
                    assert document['materializedColumns'] == 0, document
                    info = request('document_info', documentId=document['documentId'])
                    assert info['profileId'] == profile['id'], info
                    # A real tiny export exercises Chunker's mappings, LevelDB writer,
                    # archive packaging and the jlink module set, not just class loading.
                    destination = Path(workspace) / (profile['id'] + '.mcworld')
                    exported = request('export_world', documentId=document['documentId'],
                        destination=str(destination), mode='MCWORLD',
                        rectangle=dict(minChunkX=0, minChunkZ=0, maxChunkX=0, maxChunkZ=0))
                    assert exported['totalColumns'] == 1, exported
                    assert exported['voidColumns'] == 1, exported
                    with zipfile.ZipFile(destination) as archive:
                        assert 'level.dat' in archive.namelist(), archive.namelist()
                        assert 'arenas.json' in archive.namelist(), archive.namelist()
                        assert any(name.startswith('db/') for name in archive.namelist())
                    request('close_document', documentId=document['documentId'])
                request('shutdown')
                process.stdin.close()
                assert process.wait(timeout=15) == 0
                print('Worker smoke test passed: capabilities, create/export/close for every profile, shutdown.')
            except BaseException:
                process.kill()
                process.wait()
                errors.seek(0)
                print(errors.read(), file=sys.stderr)
                raise
            finally:
                process.stdout.close()
                if not process.stdin.closed:
                    process.stdin.close()
                reader.join(timeout=5)


if __name__ == '__main__':
    main()
