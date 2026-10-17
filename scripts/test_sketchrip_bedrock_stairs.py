"""Known-answer Bedrock 1.26.0 stair topology tests."""
from pathlib import Path

from sketchrip_bedrock_stairs import (DIRECTIONS, OFFSETS, change_state,
                                     predict_shape, reference_masks, solve)

PACK = Path(__file__).resolve().parents[1] / 'assets/sketchrip/vanilla-26.2.zip'
if not PACK.is_file():
    PACK = Path('/mnt/data/26.2 vanilla texture pack mc .zip')


def states(points, facings, half='bottom', family='stone_brick_stairs'):
    result = {}
    for p in points:
        result[p] = f'minecraft:{family}[facing={DIRECTIONS[facings[p]]},half={half},shape=straight]'
    return result


def measured(source, target=None):
    masks = reference_masks(PACK)
    facings = {p: DIRECTIONS.index(s.split('facing=')[1].split(',')[0]) for p, s in source.items()}
    half = {p: 'top' if 'half=top' in s else 'bottom' for p, s in source.items()}
    return {'observations': [
        {'voxel': list(p), 'mask': masks[(half[p], facings[p], predict_shape(p, facings[p], facings, half))]}
        for p in (target if target is not None else source)]}


def check(source, broken, visible=None):
    obs = measured(source, visible)
    result, report = solve(broken, obs, PACK)
    changed = dict(broken)
    changed.update(result)
    checkhalf = {p: ('top' if 'half=top' in s else 'bottom') for p, s in changed.items()}
    facing = {p: DIRECTIONS.index(s.split('facing=')[1].split(',')[0]) for p, s in changed.items()}
    model = reference_masks(PACK)
    for row in obs['observations']:
        p = tuple(row['voxel'])
        assert model[(checkhalf[p], facing[p], predict_shape(p, facing[p], facing, checkhalf))] == row['mask'], (p, report)
    return report


def ring(size=5, half='bottom', layer=0):
    points = {}
    for z in range(size):
        for x in range(size):
            if min(x, z, size - 1 - x, size - 1 - z) == 0:
                face = 0 if x == 0 else 1 if x == size - 1 else 2 if z == 0 else 3
                points[(x, layer, z)] = face
    return states(points, points, half=half)


def test_exact_straight_stairs_all_four_facings():
    for f in range(4):
        p = (0, 0, 0)
        source = states({p: f}, {p: f})
        for wrong in range(4):
            broken = states({p: wrong}, {p: wrong})
            report = check(source, broken)
            assert report['counts'].get('verified_roof_constraints') == 1


def test_inward_recess_rings_all_rotations_both_halves():
    for half in ('bottom', 'top'):
        for size in (3, 4, 5):
            for rotation in range(4):
                source = ring(size=size, half=half)
                old = {p: f'minecraft:stone_brick_stairs[facing={DIRECTIONS[(DIRECTIONS.index(s.split("facing=")[1].split(",")[0]) + rotation) % 4]},half={half},shape=outer_left]'
                       for p, s in source.items()}
                report = check(source, old)
                assert report['counts'].get('inconsistent_observations', 0) == 0


def test_disconnected_ring_preserves_other_block_materials():
    source = ring(size=4)
    wrong = {p: 'minecraft:stone_brick_stairs[facing=north,half=bottom,shape=straight]' for p in source}
    grass = (9, 0, 9)
    wrong[grass] = 'minecraft:green_terracotta'
    fixes, _ = solve(wrong, measured(source), PACK)
    assert grass not in fixes


def test_unrepresentable_roof_is_rejected():
    p = (0, 0, 0)
    source = states({p: 0}, {p: 0})
    fixes, report = solve(source, {'observations': [{'voxel': list(p), 'mask': 0}]}, PACK)
    assert fixes == {}


def test_top_and_bottom_neighbors_do_not_trigger_corners():
    points = {(0, 0, 0): 0, (1, 0, 0): 2}
    source = states(points, points)
    source[(1, 0, 0)] = source[(1, 0, 0)].replace('half=bottom', 'half=top')
    report = check(source, source)
    assert report['counts'].get('verified_roof_constraints') == 2


def test_reference_masks_all_40():
    assert len(reference_masks(PACK)) == 40
