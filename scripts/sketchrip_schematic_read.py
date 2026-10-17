"""Read SketchRip-authored Sponge v3 schematics for geometry-only retries.

Not a general NBT parser. Requires precisely the v3 layout emitted by
SketchRip.write_schematic, validates lengths and palette indices before use.
"""

import gzip
import struct
from pathlib import Path

import numpy as np


def read_sketchrip_schematic(path: Path) -> dict:
    with gzip.open(path, 'rb') as handle:
        data = handle.read()
    def locate(marker: bytes) -> int:
        if data.count(marker) != 1:
            raise ValueError(f"Not a supported SketchRip Sponge v3 schematic: {marker!r}")
        return data.index(marker) + len(marker)

    version = struct.unpack_from('>i', data, locate(b'\x03\x00\x07Version'))[0]
    if version != 3:
        raise ValueError('Expected Sponge v3 schematic')
    w = struct.unpack_from('>h', data, locate(b'\x02\x00\x05Width'))[0] & 65535
    h = struct.unpack_from('>h', data, locate(b'\x02\x00\x06Height'))[0] & 65535
    l = struct.unpack_from('>h', data, locate(b'\x02\x00\x06Length'))[0] & 65535
    volume = w * h * l
    if volume <= 0 or volume > 512 * 1024 ** 2:
        raise ValueError('Invalid schematic dimensions')

    offset = locate(b'\x0a\x00\x07Palette')
    names = {}
    while offset < len(data) and data[offset] != 0:
        if data[offset] != 3:
            raise ValueError('Only SketchRip int palette tags are supported')
        length = struct.unpack_from('>H', data, offset + 1)[0]
        name = data[offset + 3:offset + 3 + length].decode('utf-8')
        index = struct.unpack_from('>i', data, offset + 3 + length)[0]
        names[index] = name
        offset += 7 + length
    start = locate(b'\x07\x00\x04Data')
    size = struct.unpack_from('>i', data, start)[0]
    if size <= 0 or size > volume * 5:
        raise ValueError('Invalid schematic block array length')
    raw = np.frombuffer(data, dtype=np.uint8, count=size, offset=start + 4)
    ends = np.flatnonzero((raw & 128) == 0)
    if len(ends) != volume:
        raise ValueError('Schematic block count does not match dimensions')
    starts = np.empty(len(ends), dtype=np.int64)
    starts[0] = 0
    starts[1:] = ends[:-1] + 1
    values = np.zeros(len(ends), dtype=np.int32)
    for part in range(5):
        positions = starts + part
        valid = positions <= ends
        if not np.any(valid):
            break
        values[valid] |= (raw[positions[valid]].astype(np.int32) & 127) << (7 * part)
    populated = np.flatnonzero(values)
    block_names = [names.get(int(index)) for index in np.unique(values[populated])]
    if any(name is None for name in block_names):
        raise ValueError('Schematic contains an unknown palette index')
    blocks = {}
    for position in populated:
        index = int(values[position])
        name = names[index]
        if name == 'minecraft:air':
            continue
        x = int(position % w)
        z = int((position // w) % l)
        y = int(position // (w * l))
        blocks[(x, y, z)] = name
    return blocks
