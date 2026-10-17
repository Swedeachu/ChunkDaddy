"""Known-answer top/underside footprints for bottom and top stair orientations."""

from pathlib import Path
import sys

import numpy as np
import trimesh

sys.path.insert(0, str(Path(__file__).resolve().parent))
from sketchrip_reference import VanillaAssets
from sketchrip_stair_roof import infer, roof_reference, roof_fingerprint

PACK = Path(__file__).resolve().parent.parent / 'assets/sketchrip/vanilla-26.2.zip'


def _mesh(states: dict, *, reverse=False, z_up=False, keep_horizontal_only=False):
    assets = VanillaAssets(PACK)
    vertices = []
    triangles = []
    for point, state in states.items():
        for triangle, _, _ in assets.faces(state):
            local = np.asarray(triangle, dtype=np.float64)
            if keep_horizontal_only and np.ptp(local[:, 1]) > 1e-7:
                continue
            transformed = local + np.asarray(point)
            if z_up:
                transformed = transformed[:, [0, 2, 1]]
            if reverse:
                transformed = transformed[[0, 2, 1]]
            pos = len(vertices)
            vertices.extend(transformed)
            triangles.append((pos, pos + 1, pos + 2))
    return trimesh.Trimesh(vertices=np.asarray(vertices), faces=triangles, process=False)


def _mask_for(assets, state):
    tris = np.asarray([tri for tri, _, _ in assets.faces(state)])
    return roof_fingerprint(tris, 0.0 if 'half=top' in state else 1.0)[0]


def test_all_40_stair_variants_have_matching_horizontal_footprints():
    assets = VanillaAssets(PACK)
    for family in ('stone_brick_stairs', 'oak_stairs'):
        states = [s for s, _ in assets.enumerate_states(family)]
        assert len(states) == 40
        source = {(index * 3, 0, 0): state for index, state in enumerate(states)}
        start = {pt: f'minecraft:{family}[facing=north,half=' +
                 ('top' if 'half=top' in state else 'bottom') + ',shape=straight]'
                 for pt, state in source.items()}
        for reverse, z_up in ((False, False), (True, False), (False, True)):
            mesh = _mesh(source, reverse=reverse, z_up=z_up)
            changes, report = infer([mesh], start, PACK, 1.0, 'z' if z_up else 'y')
            assert report['examined'] == 40
            for pt, expected in source.items():
                actual = changes.get(pt, start[pt])
                assert _mask_for(assets, actual) == _mask_for(assets, expected), (expected, actual)


def test_recessed_pits_fixed_by_top_footprint_not_riser_votes():
    assets = VanillaAssets(PACK)
    ref = roof_reference(assets, 'stone_brick_stairs', 'bottom')
    # Each 4x4 pit has 2x2 full brick centre surrounded by 12 stairs.
    # Rows are 2x2 high-roof masks in x/z quadrant order.
    masks = [[7, 3, 3, 11],
             [5, 0, 0, 10],
             [5, 0, 0, 10],
             [13, 12, 12, 14]]
    source = {}
    baseline = {}
    target_points = []
    for ox in (0, 8, 16):
        for z, row in enumerate(masks):
            for x, mask in enumerate(row):
                pt = ox + x, 0, z
                if mask:
                    source[pt] = ref[mask][0]
                    baseline[pt] = 'minecraft:stone_brick_stairs[facing=east,half=bottom,shape=outer_left]'
                    target_points.append(pt)
                else:
                    source[pt] = 'minecraft:stone_bricks'
                    baseline[pt] = 'minecraft:stone_bricks'
    mesh = _mesh(source, keep_horizontal_only=True)
    proposals, report = infer([mesh], baseline, PACK, 1.0, 'y')
    assert report['proposed_changes'] >= 24
    for pt in target_points:
        actual = proposals.get(pt, baseline[pt])
        assert _mask_for(assets, actual) == _mask_for(assets, source[pt]), (pt, actual)


def test_abstain_when_top_roof_is_covered_by_block_above():
    sources = {(0, 0, 0): 'minecraft:oak_stairs[facing=east,half=bottom,shape=straight]',
               (0, 1, 0): 'minecraft:oak_planks'}
    original = {(0, 0, 0): 'minecraft:oak_stairs[facing=north,half=bottom,shape=straight]',
                (0, 1, 0): 'minecraft:oak_planks'}
    mesh = _mesh(sources)
    changes, report = infer([mesh], original, PACK, 1.0, 'y')
    assert (0, 0, 0) not in changes
    assert report['counts']['blocked_above'] == 1


def test_wide_merged_roof_does_not_change_stair_orientation():
    # Source has only a large 2x1 rectangle, not enough per-cell geometry.
    verts = np.asarray([[0, 1, 0], [2, 1, 0], [2, 1, 1], [0, 1, 1]], dtype=float)
    mesh = trimesh.Trimesh(vertices=verts, faces=[[0, 1, 2], [0, 2, 3]], process=False)
    baseline = {(0, 0, 0): 'minecraft:oak_stairs[facing=north,half=bottom,shape=straight]',
                (1, 0, 0): 'minecraft:oak_stairs[facing=north,half=bottom,shape=straight]'}
    changes, report = infer([mesh], baseline, PACK, 1.0, 'y')
    assert changes == {}


def test_shared_roof_fingerprints_have_identical_geometry():
    assets = VanillaAssets(PACK)
    for family in ('oak_stairs', 'stone_brick_stairs', 'spruce_stairs'):
        for half in ('top', 'bottom'):
            lookup = roof_reference(assets, family, half)
            for _mask, states in lookup.items():
                if len(states) < 2:
                    continue
                def geometry(state):
                    return sorted(tuple(sorted(tuple(np.rint(v * 4096).astype(int))
                                               for v in triangle))
                                  for triangle, _, _ in assets.faces(state))
                reference = geometry(states[0])
                for state in states[1:]:
                    assert geometry(state) == reference, (states[0], state)


def test_top_stair_underside_occluded_below_abstains():
    states = {(0, 0, 0): 'minecraft:oak_planks',
              (0, 1, 0): 'minecraft:oak_stairs[facing=west,half=top,shape=straight]'}
    baseline = {(0, 0, 0): 'minecraft:oak_planks',
                (0, 1, 0): 'minecraft:oak_stairs[facing=north,half=top,shape=straight]'}
    proposals, report = infer([_mesh(states)], baseline, PACK, 1.0, 'y')
    assert (0, 1, 0) not in proposals
    assert report['counts']['blocked_below'] == 1
