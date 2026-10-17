"""Spatial orientation regressions for narrowly modeled vanilla blocks."""

from pathlib import Path
import sys

import numpy as np
import trimesh

sys.path.insert(0, str(Path(__file__).resolve().parent))

from sketchrip_reference import VanillaAssets
from sketchrip_small_shapes import infer as infer_small, model_vector
from sketchrip_material_context import reconcile_wood

PACK = Path(__file__).resolve().parent.parent / 'assets' / 'sketchrip' / 'vanilla-26.2.zip'


def _model_test(states):
    assets = VanillaAssets(PACK)
    vertices = []
    triangles = []
    expected = {}
    baseline = {}
    for i, state in enumerate(states):
        point = (i * 3, 0, 0)
        expected[point] = state
        baseline[point] = state.split('[', 1)[0]
        for face, _, _ in assets.faces(state):
            start = len(vertices)
            vertices.extend((np.asarray(face) + point).tolist())
            triangles.append((start, start + 1, start + 2))
    mesh = trimesh.Trimesh(vertices=vertices, faces=triangles, process=False)
    predictions, report = infer_small([mesh], baseline, PACK, 1.0, 'y')
    return expected, baseline, predictions, report


def test_all_four_ladder_directions():
    states = [f'minecraft:ladder[facing={f}]' for f in ('north', 'south', 'east', 'west')]
    expected, baseline, proposals, report = _model_test(states)
    assert report['proposals'] == 4
    assert all(proposals[p] == name for p, name in expected.items())


def test_wall_torch_directions():
    states = [f'minecraft:wall_torch[facing={f}]' for f in ('north', 'south', 'east', 'west')]
    expected, baseline, proposals, report = _model_test(states)
    assert report['proposals'] == 4
    assert all(proposals[p] == name for p, name in expected.items())


def test_button_wall_directions_and_floor_face():
    states = [f'minecraft:oak_button[face=wall,facing={f},powered=false]'
              for f in ('north', 'south', 'east', 'west')]
    expected, baseline, proposals, report = _model_test(states)
    assert all(proposals[p] == name for p, name in expected.items())


def test_wood_end_grain_reconciles_only_matching_trunks():
    blocks = {(0, 0, 0): 'minecraft:oak_log',
              (0, 1, 0): 'minecraft:stripped_spruce_log',
              (0, 2, 0): 'minecraft:stripped_spruce_log',
              (1, 1, 0): 'minecraft:terracotta',
              (0, 3, 0): 'minecraft:spruce_log'}
    sides = {(0, 0, 0): {'bark-id-1': 1},
             (0, 1, 0): {'bark-id-1': 1},
             (0, 2, 0): {'bark-id-1': 1},
             (0, 3, 0): {'bark-id-2': 1}}
    corrected, report = reconcile_wood(blocks, sides, {(0, 0, 0): {'oak': .98}})
    assert corrected[(0, 1, 0)] == 'minecraft:oak_log'
    assert corrected[(0, 2, 0)] == 'minecraft:oak_log'
    assert corrected[(0, 3, 0)] == 'minecraft:spruce_log'
    assert corrected[(1, 1, 0)] == 'minecraft:terracotta'
    assert report['corrected_positions'] == 2


def test_ambiguous_end_grain_does_not_force_species():
    blocks = {(0, 0, 0): 'minecraft:stripped_spruce_log'}
    result, info = reconcile_wood(blocks, {}, {(0, 0, 0): {'oak': .95, 'birch': .94}})
    assert result == blocks
    assert info['ambiguous_end_grain'] == 1


def test_plank_sprite_cannot_become_button_or_door():
    from sketchrip_state_inference import candidate_records
    assets = VanillaAssets(PACK)
    candidates, _ = candidate_records(assets, {'oak_planks', 'oak_button'})
    planks = [state for state, _ in candidates['oak_planks']]
    button = [state for state, _ in candidates['oak_button']]
    assert any(state.startswith('minecraft:oak_stairs[') for state in planks)
    assert not any(state.startswith('minecraft:oak_button[') for state in planks)
    assert not any(state.startswith('minecraft:oak_door[') for state in planks)
    assert any(state.startswith('minecraft:oak_button[') for state in button)
