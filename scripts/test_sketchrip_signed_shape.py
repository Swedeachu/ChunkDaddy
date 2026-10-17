"""Adversarial tests: reversed triangle winding and top/bottom slab evidence."""

import sys
from pathlib import Path
import numpy as np
import trimesh

sys.path.insert(0, str(Path(__file__).parent))
from sketchrip_reference import VanillaAssets
from sketchrip_stair_orientation import infer as stair_infer, _features, stair_templates, classify_signature
from sketchrip_slab_precision import infer as slab_infer

PACK = Path(__file__).resolve().parent.parent / 'assets/sketchrip/vanilla-26.2.zip'


def models(states, reverse=False, hide_bottom=True):
    a = VanillaAssets(PACK)
    verts = []
    faces = []
    original = {}
    for index, name in enumerate(states):
        position = (index * 3, 0, 0)
        original[position] = name
        for tri, _, _ in a.faces(name):
            t = np.asarray(tri, dtype=np.float64)
            n = np.cross(t[1]-t[0], t[2]-t[0])
            # Mimic Mineways' culled bottom/exterior faces where requested.
            if hide_bottom and np.argmax(np.abs(n)) == 1 and np.max(np.abs(t[:, 1])) < 1e-5:
                continue
            if reverse:
                t = t[[0, 2, 1]]
            start = len(verts)
            verts.extend(t + position)
            faces.append([start, start+1, start+2])
    return trimesh.Trimesh(vertices=verts, faces=faces, process=False), original


def test_signed_stair_reference_separates_inverse_risers():
    a = VanillaAssets(PACK)
    states, refs = stair_templates(a, 'stone_brick_stairs')
    assert len(states) == 40
    for i, state in enumerate(states):
        result, score, gap, reason = classify_signature(refs[i], states, refs)
        assert result is None or np.allclose(refs[states.index(result)], refs[i], atol=.012, rtol=0), (state, result, score, reason)


def test_reversed_winding_stairs_with_occluded_bottom():
    a = VanillaAssets(PACK)
    states = [s for s, _ in a.enumerate_states('oak_stairs') if 'shape=straight' in s]
    assert len(states) == 8
    for reverse in (False, True):
        mesh, truth = models(states, reverse=reverse)
        incorrect = {pt: 'minecraft:oak_stairs[facing=north,half=bottom,shape=straight]' for pt in truth}
        proposed, report = stair_infer([mesh], incorrect, PACK, 1, 'y')
        # This model's thin interior may not reveal the original semantic state;
        # if changed, visible model evidence must agree with the source.
        a = VanillaAssets(PACK)
        ts, features = stair_templates(a, 'oak_stairs')
        for pt, correct in truth.items():
            chosen = proposed.get(pt, incorrect[pt])
            if report['counts'].get('missing_interior', 0) == len(states):
                continue
            ci = ts.index(correct)
            pi = ts.index(chosen)
            assert np.max(np.abs(features[ci] - features[pi])) < .055, (reverse, correct, chosen, report['counts'])


def test_slab_half_never_guessed_from_filename_only():
    for reverse in (False, True):
        actual = ['minecraft:oak_slab[type=bottom]', 'minecraft:oak_slab[type=top]']
        # Add a thick flat roof top to make triangle winding calibration decisive.
        actual += ['minecraft:oak_planks'] * 8
        mesh, truth = models(actual, reverse=reverse)
        baseline = {pt: ('minecraft:oak_slab[type=top]' if 'type=bottom' in st else
                         'minecraft:oak_slab[type=bottom]' if 'type=top' in st else st) for pt, st in truth.items()}
        proposed, diag = slab_infer([mesh], baseline, 1, 'y')
        # Uncertain winding must abstain; it must never assert a wrong correction.
        for pt in list(truth)[:2]:
            if pt in proposed:
                assert proposed[pt] == truth[pt], (reverse, proposed, diag)


def test_slab_top_bottom_with_explicit_stair_calibrated_winding():
    actual = ['minecraft:oak_slab[type=bottom]', 'minecraft:oak_slab[type=top]']
    for reverse in (False, True):
        mesh, truth = models(actual, reverse=reverse, hide_bottom=False)
        inverted = {pt: ('minecraft:oak_slab[type=top]' if 'type=bottom' in state
                         else 'minecraft:oak_slab[type=bottom]') for pt, state in truth.items()}
        proposals, details = slab_infer([mesh], inverted, 1., 'y', winding_override=1 if reverse else -1)
        assert len(proposals) == 2, (reverse, proposals, details)
        assert all(proposals[p] == s for p, s in truth.items())
