"""Known-answer fractional-height surface detection tests."""
import unittest
from pathlib import Path
import numpy as np
import trimesh
from sketchrip_reference import VanillaAssets
from sketchrip_shape_evidence import refine_thin_layers

PACK = Path(__file__).resolve().parent.parent / 'assets/sketchrip/vanilla-26.2.zip'


def mesh_for(states):
    assets = VanillaAssets(PACK)
    tris = []
    for i, state in enumerate(states):
        tri = np.asarray([t for t, _, _ in assets.faces(state)], dtype=np.float64)
        tris.append(tri + np.array([i*3, 0, 0]))
    all_tri = np.concatenate(tris)
    return [trimesh.Trimesh(vertices=all_tri.reshape(-1,3),
             faces=np.arange(len(all_tri)*3).reshape(-1,3),process=False)]


class ThinGeometryTests(unittest.TestCase):
    def test_snow_layers_carpet_and_full_block(self):
        states = ['minecraft:snow[layers=1]', 'minecraft:snow[layers=2]',
                  'minecraft:snow[layers=4]', 'minecraft:green_carpet',
                  'minecraft:snow_block', 'minecraft:green_wool']
        guessed = ['minecraft:snow_block'] * 3 + ['minecraft:green_wool'] + states[4:]
        blocks = {(i*3,0,0): guessed[i] for i in range(len(states))}
        updates, report = refine_thin_layers(mesh_for(states), blocks, 1., 'y')
        self.assertEqual(updates[(0,0,0)], states[0])
        self.assertEqual(updates[(3,0,0)], states[1])
        self.assertEqual(updates[(6,0,0)], states[2])
        self.assertEqual(updates[(9,0,0)], states[3])
        self.assertNotIn((12,0,0), updates)
        self.assertNotIn((15,0,0), updates)
        self.assertEqual(report['proposed_changes'],4)

    def test_surface_area_must_be_real(self):
        states = ['minecraft:green_carpet', 'minecraft:snow[layers=1]']
        blocks = {(0,0,0):'minecraft:green_wool',(3,0,0):'minecraft:snow_block'}
        updates,_=refine_thin_layers(mesh_for(states),blocks,1.,'y',min_roof_area=2.0)
        self.assertEqual(updates,{})

if __name__ == '__main__':unittest.main()
