#!/usr/bin/env python3
"""Known-answer double-slab geometry and scoring regressions."""
from pathlib import Path
import unittest
import numpy as np
import trimesh
from sketchrip_reference import VanillaAssets
from sketchrip_shape_evidence import infer
from sketchrip_truth_compare import shape_of

PACK = Path(__file__).resolve().parent.parent / 'assets' / 'sketchrip' / 'vanilla-26.2.zip'

class DoubleSlabTests(unittest.TestCase):
    def test_double_slab_not_half_slab(self):
        self.assertEqual(shape_of('minecraft:double_stone_block_slab4[stone_slab_type_4=stone]'), 'double_slab')
        self.assertEqual(shape_of('minecraft:smooth_stone_slab[type=double]'), 'double_slab')
        self.assertEqual(shape_of('minecraft:smooth_stone_slab[type=top]'), 'slab')
        self.assertEqual(shape_of('minecraft:stone_block_slab4[top_slot_bit=1]'), 'slab')

    def test_full_height_evidence_required(self):
        assets = VanillaAssets(PACK)
        states = ['minecraft:oak_stairs[facing=north,half=bottom,shape=straight]',
                  'minecraft:smooth_stone_slab[type=double]',
                  'minecraft:smooth_stone_slab[type=top]',
                  'minecraft:smooth_stone_slab[type=bottom]',
                  'minecraft:oak_planks']
        tris = []
        for i, state in enumerate(states):
            for face, _, _ in assets.faces(state):
                tris.append(face + np.asarray([i * 3, 0, 0]))
        vertex = np.asarray(tris).reshape(-1, 3)
        faces = np.arange(len(vertex),dtype=np.int32).reshape(-1,3)
        scene = [trimesh.Trimesh(vertices=vertex, faces=faces, process=False)]
        blocks = {(i * 3, 0, 0): ('minecraft:smooth_stone_slab' if i in (1,2,3) else states[i])
                  for i in range(len(states))}
        changes, report = infer(scene, blocks, PACK, 1.0, 'y')
        self.assertEqual(changes.get((3, 0, 0)), 'minecraft:smooth_stone_slab[type=double]')
        self.assertNotEqual(changes.get((6, 0, 0)), 'minecraft:smooth_stone_slab[type=double]')
        self.assertNotEqual(changes.get((9, 0, 0)), 'minecraft:smooth_stone_slab[type=double]')
        self.assertNotIn((12, 0, 0), changes)
        self.assertEqual(report['families'].get('double_slab'), 1)

if __name__ == '__main__': unittest.main()
