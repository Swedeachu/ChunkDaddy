#!/usr/bin/env python3
"""Known-answer tests for family and signed-riser geometry evidence."""

from __future__ import annotations

import unittest
from pathlib import Path

import numpy as np
import trimesh

from sketchrip_reference import VanillaAssets
from sketchrip_shape_evidence import infer, refine_facing, verify_legacy_roofs


PACK = Path(__file__).resolve().parent.parent / 'assets' / 'sketchrip' / 'vanilla-26.2.zip'


def scene(states: list[str], offsets=None, reverse: bool = False, z_up: bool = False):
    assets = VanillaAssets(PACK)
    meshes = []
    for index, state in enumerate(states):
        triangles = np.asarray([tri for tri, _, _ in assets.faces(state)], dtype=np.float64)
        if not len(triangles):
            continue
        triangles += np.asarray((index * 3, 0, 0) if offsets is None else offsets[index])
        if reverse:
            triangles = triangles[:, [0, 2, 1]]
        if z_up:
            triangles = triangles[:, :, [0, 2, 1]]
        vertex = triangles.reshape(-1, 3)
        faces = np.arange(len(vertex), dtype=np.int32).reshape(-1, 3)
        meshes.append(trimesh.Trimesh(vertices=vertex, faces=faces, process=False))
    return meshes


class GeometryEvidenceTests(unittest.TestCase):
    def test_strict_post_edit_roof_validation(self):
        from sketchrip_bedrock_stairs import reference_masks
        masks = reference_masks(PACK)
        state = 'minecraft:oak_stairs[facing=east,half=bottom,shape=straight]'
        blocks = {(0, 0, 0): state}
        original = masks[('bottom', 0, 'straight')]
        good = verify_legacy_roofs(blocks, {'observations':
                                   [{'voxel': [0, 0, 0], 'mask': original}]}, PACK)
        self.assertEqual(good['conflicts'], 0)
        bad = verify_legacy_roofs(blocks, {'observations':
                                  [{'voxel': [0, 0, 0], 'mask':
                                    masks[('bottom', 1, 'straight')]}]}, PACK)
        self.assertEqual(bad['conflicts'], 1)

    def test_new_buttons_cannot_infer_powered_true(self):
        from sketchrip_small_shapes import preserve_hidden_powered
        new = 'minecraft:stone_button[face=wall,facing=east,powered=true]'
        self.assertIn('powered=false', preserve_hidden_powered('minecraft:stone_button', new))
        self.assertEqual(preserve_hidden_powered(new, new), new)

    def test_stair_facing_four_directions(self):
        names = ('east', 'west', 'south', 'north')
        for reverse in (False, True):
            source = [f'minecraft:oak_stairs[facing={direction},half=bottom,shape=straight]'
                      for direction in names]
            meshes = scene(source, reverse=reverse)
            blocks = {(i * 3, 0, 0):
                      'minecraft:oak_stairs[facing=north,half=bottom,shape=straight]'
                      for i in range(4)}
            proposals, report = refine_facing(meshes, blocks, 1.0, 'y', axis_profile='vanilla')
            blocks.update(proposals)
            for i, direction in enumerate(names):
                self.assertIn(f'facing={direction}', blocks[(i * 3, 0, 0)])
            self.assertLessEqual(report['counts'].get('ambiguous', 0), 1)

    def test_slab_half_and_solid_controls(self):
        source = ['minecraft:oak_stairs[facing=east,half=bottom,shape=straight]',
                  'minecraft:oak_slab[type=bottom]', 'minecraft:oak_slab[type=top]',
                  'minecraft:oak_planks']
        meshes = scene(source)
        blocks = {(0, 0, 0): source[0], (3, 0, 0): 'minecraft:oak_planks',
                  (6, 0, 0): 'minecraft:oak_planks', (9, 0, 0): 'minecraft:oak_planks'}
        changes, report = infer(meshes, blocks, PACK, 1.0, 'y')
        self.assertEqual(changes[(3, 0, 0)], 'minecraft:oak_slab[type=bottom]')
        self.assertEqual(changes[(6, 0, 0)], 'minecraft:oak_slab[type=top]')
        self.assertNotIn((9, 0, 0), changes)

    def test_fence_without_plank_false_positive(self):
        source = ['minecraft:oak_stairs[facing=east,half=bottom,shape=straight]',
                  'minecraft:oak_fence[east=true,north=true,south=false,west=true]',
                  'minecraft:oak_planks']
        meshes = scene(source)
        blocks = {(0, 0, 0): source[0], (3, 0, 0): 'minecraft:oak_planks',
                  (6, 0, 0): 'minecraft:oak_planks'}
        changes, report = infer(meshes, blocks, PACK, 1.0, 'y')
        self.assertEqual(changes[(3, 0, 0)], 'minecraft:oak_fence')
        self.assertNotIn((6, 0, 0), changes)

    def test_glass_pane_not_full_glass(self):
        source = ['minecraft:oak_stairs[facing=east,half=bottom,shape=straight]',
                  'minecraft:red_stained_glass_pane[east=true,north=true,south=false,west=false]',
                  'minecraft:red_stained_glass']
        meshes = scene(source)
        blocks = {(0, 0, 0): source[0], (3, 0, 0): 'minecraft:red_stained_glass',
                  (6, 0, 0): 'minecraft:red_stained_glass'}
        changes, report = infer(meshes, blocks, PACK, 1.0, 'y')
        self.assertEqual(changes.get((3, 0, 0)), 'minecraft:red_stained_glass_pane')
        self.assertNotIn((6, 0, 0), changes)

    def test_explicit_wall_alias_only(self):
        wall = 'minecraft:cobblestone_wall[east=tall,north=none,south=tall,up=true,west=none]'
        source = ['minecraft:oak_stairs[facing=east,half=bottom,shape=straight]', wall]
        meshes = scene(source)
        blocks = {(0, 0, 0): source[0], (3, 0, 0): 'minecraft:light_gray_concrete'}
        without, _ = infer(meshes, blocks, PACK, 1.0, 'y')
        self.assertNotIn((3, 0, 0), without)
        with_alias, _ = infer(meshes, blocks, PACK, 1.0, 'y',
                              {'minecraft:light_gray_concrete':
                               {'wall': 'minecraft:cobblestone_wall'}})
        self.assertEqual(with_alias.get((3, 0, 0)), 'minecraft:cobblestone_wall')


if __name__ == '__main__':
    unittest.main()
