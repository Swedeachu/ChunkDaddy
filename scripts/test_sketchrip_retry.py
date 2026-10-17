from pathlib import Path
import sys
sys.path.insert(0,str(Path(__file__).resolve().parent))
from sketchrip import write_schematic
from sketchrip_schematic_read import read_sketchrip_schematic


def test_schematic_roundtrip(tmp_path):
    expected = {(0, 0, 0):'minecraft:oak_stairs[facing=east,half=bottom,shape=straight]',
                (5, 1, 3):'minecraft:dirt',
                (3, 1, 1):'minecraft:oak_log[axis=y]'}
    path = tmp_path / 'map.schem'
    write_schematic(expected,path)
    assert read_sketchrip_schematic(path)==expected


def test_schematic_roundtrip_130_palette_entries(tmp_path):
    # The real arena has hundreds of state variants, so exercise multi-byte LEB128.
    blocks={(i,0,0):f'minecraft:unique_mock_block_{i}' for i in range(170)}
    path=tmp_path/'many.schem'
    write_schematic(blocks,path)
    assert read_sketchrip_schematic(path)==blocks
