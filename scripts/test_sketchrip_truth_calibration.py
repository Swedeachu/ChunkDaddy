"""Bedrock reference-world calibration safety and decoding tests."""

from __future__ import annotations

import struct
import tempfile
import unittest
import zipfile
from pathlib import Path

import numpy as np

from sketchrip_truth_calibration import bedrock_base_material, read_bedrock_world


def nbt_name(name: str) -> bytes:
    data = name.encode("utf-8")
    return struct.pack("<H", len(data)) + data


def block_state(name: str) -> bytes:
    # Little-endian NBT compound {name:string, states:{}}.
    return (b"\x0a\x00\x00" + b"\x08" + nbt_name("name") + nbt_name(name)
            + b"\x0a" + nbt_name("states") + b"\x00" + b"\x00")


def reference_subchunk() -> bytes:
    # Version 9, one layer, section Y=4, palette [air, blackstone].
    words = np.zeros(128, dtype="<u4")
    words[0] = 1
    return (b"\x09\x01\x04\x02" + words.tobytes() + struct.pack("<I", 2)
            + block_state("minecraft:air") + block_state("minecraft:blackstone"))


def put_varint(value: int) -> bytes:
    result = bytearray()
    while value >= 128:
        result.append((value & 127) | 128)
        value >>= 7
    result.append(value)
    return bytes(result)


def reference_world(path: Path) -> None:
    key = struct.pack("<iiBB", 1, -1, 0x2f, 4)
    value = reference_subchunk()
    batch = (struct.pack("<QI", 1, 1) + b"\x01" + put_varint(len(key)) + key
             + put_varint(len(value)) + value)
    log = struct.pack("<IHB", 0, len(batch), 1) + batch
    with zipfile.ZipFile(path, "w") as archive:
        archive.writestr("db/000001.log", log)


class TruthCalibrationTests(unittest.TestCase):
    def test_read_single_paletted_block(self):
        with tempfile.TemporaryDirectory() as folder:
            world = Path(folder) / "reference.mcworld"
            reference_world(world)
            coords, codes, names = read_bedrock_world(world)
        self.assertEqual(coords.tolist(), [[16, 64, -16]])
        self.assertEqual(names[int(codes[0])], "minecraft:blackstone")

    def test_bedrock_material_aliases(self):
        cases = {
            "minecraft:planks[wood_type=birch]": "minecraft:birch_planks",
            "minecraft:stone[stone_type=andesite_smooth]": "minecraft:polished_andesite",
            "minecraft:stained_hardened_clay[color=cyan]": "minecraft:cyan_terracotta",
            "minecraft:concrete[color=green]": "minecraft:green_concrete",
            "minecraft:wood[pillar_axis=y,stripped_bit=0,wood_type=spruce]": "minecraft:spruce_wood",
            "minecraft:stone_block_slab3[stone_slab_type_3=polished_andesite,top_slot_bit=1]":
                "minecraft:polished_andesite",
            "minecraft:polished_andesite_stairs[upside_down_bit=0,weirdo_direction=3]":
                "minecraft:polished_andesite",
        }
        for source, expected in cases.items():
            with self.subTest(source=source):
                self.assertEqual(bedrock_base_material(source), expected)


if __name__ == "__main__":
    unittest.main()

class AtlasIsolationTests(unittest.TestCase):
    def test_no_ffa_scene_overrides_by_default(self):
        import sys
        from sketchrip import TextureIndex
        pack = Path(__file__).resolve().parent.parent / "assets" / "sketchrip" / "vanilla-26.2.zip"
        with tempfile.TemporaryDirectory() as folder:
            lookup = TextureIndex(pack, cache_dir=Path(folder))
            self.assertFalse(lookup.manual_atlas_map)
            self.assertEqual(lookup.atlas_overrides, {})
