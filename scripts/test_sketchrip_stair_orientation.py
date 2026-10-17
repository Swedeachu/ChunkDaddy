"""Exact and visibility-limited stair-facing regressions."""

import collections
import sys
from pathlib import Path

import numpy as np
import trimesh

sys.path.insert(0, str(Path(__file__).resolve().parent))

from sketchrip_reference import VanillaAssets
from sketchrip_stair_orientation import _features, classify_signature, infer, stair_templates

PACK = Path(__file__).resolve().parent.parent / 'assets' / 'sketchrip' / 'vanilla-26.2.zip'


def test_every_stair_orientation_uses_actual_interior_footprint():
    assets = VanillaAssets(PACK)
    for family in ('oak_stairs', 'stone_brick_stairs', 'spruce_stairs', 'quartz_stairs'):
        states, signatures = stair_templates(assets, family)
        assert len(states) == 40
        for index, source in enumerate(states):
            # Test deliberately unrelated initial orientations. Identical geometry
            # can have multiple vanilla state names and should not be force-changed.
            wrong = states[(index + 7) % len(states)]
            picked, score, margin, reason = classify_signature(signatures[index], states, signatures,
                                                              existing=wrong)
            if picked is not None:
                selected = signatures[states.index(picked)]
                assert np.allclose(selected, signatures[index], atol=0.012, rtol=0), (source, picked, score)
            else:
                assert reason in ('equivalent_geometry', 'ambiguous_orientation')


def _make_mesh(states, up_axis='y', remove_outer=False):
    assets = VanillaAssets(PACK)
    vertices, faces = [], []
    expected, baseline = {}, {}
    for index, state in enumerate(states):
        point = (index % 12 * 3, 0, index // 12 * 3)
        expected[point] = state
        # A correct stair material with deliberately wrong facing/shape/half.
        family = state.split('[', 1)[0]
        baseline[point] = family + '[facing=north,half=bottom,shape=straight]'
        for triangle, _, _ in assets.faces(state):
            tri = np.asarray(triangle)
            if remove_outer:
                center = np.mean(tri, axis=0)
                cross = np.cross(tri[1] - tri[0], tri[2] - tri[0])
                axis = np.argmax(np.abs(cross))
                if abs(center[axis]) < 1e-6 or abs(center[axis] - 1) < 1e-6:
                    continue
            tri = tri + np.asarray(point)
            if up_axis == 'z':
                tri = tri[:, [0, 2, 1]]
            index0 = len(vertices)
            vertices.extend(tri)
            faces.append((index0, index0 + 1, index0 + 2))
    return trimesh.Trimesh(vertices=np.asarray(vertices), faces=faces, process=False), expected, baseline


def test_full_raster_mesh_facing_half_and_corner_states():
    assets = VanillaAssets(PACK)
    states = [s for s, _ in assets.enumerate_states('stone_brick_stairs')]
    mesh, expected, baseline = _make_mesh(states)
    proposals, report = infer([mesh], baseline, PACK, 1.0, 'y')
    for point, actual in expected.items():
        predicted = proposals.get(point, baseline[point])
        va = _features(np.asarray([tri for tri, _, _ in assets.faces(actual)]))
        vb = _features(np.asarray([tri for tri, _, _ in assets.faces(predicted)]))
        assert np.allclose(va / np.linalg.norm(va), vb / np.linalg.norm(vb), atol=.013, rtol=0), (actual, predicted)
    assert report['stairs_examined'] == len(states)


def test_culled_exterior_stair_geometry_and_z_up():
    assets = VanillaAssets(PACK)
    states = [s for s, _ in assets.enumerate_states('oak_stairs')]
    mesh, expected, baseline = _make_mesh(states, up_axis='z', remove_outer=True)
    proposals, report = infer([mesh], baseline, PACK, 1.0, 'z')
    assert report['counts']['matched_internal_triangles'] > 100
    for point, actual in expected.items():
        predicted = proposals.get(point, baseline[point])
        va = _features(np.asarray([tri for tri, _, _ in assets.faces(actual)]))
        vb = _features(np.asarray([tri for tri, _, _ in assets.faces(predicted)]))
        assert np.allclose(va / np.linalg.norm(va), vb / np.linalg.norm(vb), atol=.013, rtol=0), (actual, predicted)


def test_no_or_weak_geometry_never_changes_direction():
    assets = VanillaAssets(PACK)
    states, references = stair_templates(assets, 'oak_stairs')
    predicted, _, _, reason = classify_signature(np.zeros(references.shape[1]), states, references)
    assert predicted is None and reason == 'missing_interior'
    assert infer([], {}, PACK, 1., 'y')[1]['counts']['no_stairs'] == 1


def test_tied_corner_states_have_identical_full_geometry():
    # It is only safe to choose a canonical tied corner state when the entire
    # reference mesh, not just its interior evidence, renders the same shape.
    assets = VanillaAssets(PACK)
    states, references = stair_templates(assets, 'oak_stairs')
    keys = {}
    for i, state in enumerate(states):
        key = tuple(sorted(tuple(sorted(tuple(np.rint(v * 4096).astype(int))
                                        for v in triangle))
                           for triangle, _, _ in assets.faces(state)))
        keys[i] = key
    for i, left in enumerate(states):
        for j in range(i + 1, len(states)):
            if np.max(np.abs(references[i] - references[j])) <= .012:
                assert keys[i] == keys[j], (left, states[j])
