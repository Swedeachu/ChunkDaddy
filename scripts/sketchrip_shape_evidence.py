#!/usr/bin/env python3
"""Surface-measured block families and signed stair facing for Mineways GLBs.

These passes only use visible triangle geometry and never recover hidden blocks.
Block-family aliases must be explicitly supplied by a user-approved atlas map.
"""

from __future__ import annotations

import collections
import re

import numpy as np


def family_name(block: str, family: str) -> str | None:
    base = block.removeprefix('minecraft:').split('[', 1)[0]
    if base.endswith('_planks'):
        base = base.removesuffix('_planks')
    elif base in ('polished_andesite', 'smooth_sandstone', 'smooth_stone',
                  'quartz_block', 'stone_bricks', 'cobblestone', 'blackstone',
                  'bricks', 'mud_bricks'):
        base = {'quartz_block': 'quartz', 'bricks': 'brick',
                'stone_bricks': 'stone_brick',
                'mud_bricks': 'mud_brick'}.get(base, base)
    else:
        return None
    return 'minecraft:' + base + '_' + family


def scan(meshes: list, blocks: dict, unit: float, up_axis: str):
    """Accumulate surface areas per voxel without Python work per triangle."""
    from sketchrip import guess_scale

    pts = np.asarray(list(blocks), dtype=np.int64)
    if not len(pts):
        return pts, {}, {}
    unit = guess_scale(meshes, unit)
    low, high = pts.min(axis=0), pts.max(axis=0)
    size = high - low + 1
    if np.prod(size.astype(float)) > 2 ** 52:
        raise ValueError('Voxel coordinate range is too large')
    stride = np.asarray([int(size[1] * size[2]), int(size[2]), 1], dtype=np.int64)
    keys = (pts - low) @ stride
    order = np.argsort(keys)
    sorted_keys = keys[order]
    minimum = np.rint(np.min([m.bounds[0] for m in meshes if len(m.vertices)], axis=0)
                      / unit) * unit

    quarter = np.zeros((len(pts), 3, 5), dtype=np.float32)
    internal = np.zeros((len(pts), 3), dtype=np.float32)
    signed = np.zeros((len(pts), 3, 2), dtype=np.float32)
    half = np.zeros((len(pts), 2), dtype=np.float32)
    triangles_seen = np.zeros(len(pts), dtype=np.int32)
    full_height_side = np.zeros(len(pts), dtype=np.float32)
    for mesh in meshes:
        vertices = np.asarray(mesh.vertices, dtype=np.float64)
        faces = np.asarray(mesh.faces, dtype=np.int64)
        for start in range(0, len(faces), 65536):
            tri = (vertices[faces[start:start + 65536]] - minimum) / unit
            if up_axis == 'z':
                tri = tri[:, :, [0, 2, 1]]
            if not len(tri):
                continue
            normal = np.cross(tri[:, 1] - tri[:, 0], tri[:, 2] - tri[:, 0])
            magnitude = np.linalg.norm(normal, axis=1)
            axis = np.argmax(np.abs(normal), axis=1)
            row = np.arange(len(tri))
            extent = np.max(tri.max(axis=1) - tri.min(axis=1), axis=1)
            valid = ((magnitude > 1e-8) & (extent <= 1.1) &
                     (np.abs(normal[row, axis]) >= magnitude * .985))
            if not np.any(valid):
                continue
            tri, normal, magnitude, axis = (v[valid] for v in
                                             (tri, normal, magnitude, axis))
            cells = np.floor(tri.mean(axis=1) + 1e-5).astype(np.int64)
            valid = np.all((cells >= low) & (cells <= high), axis=1)
            if not np.any(valid):
                continue
            tri, normal, magnitude, axis, cells = (v[valid] for v in
                                                    (tri, normal, magnitude, axis, cells))
            hashes = (cells - low) @ stride
            locations = np.searchsorted(sorted_keys, hashes)
            present = locations < len(sorted_keys)
            present[present] &= sorted_keys[locations[present]] == hashes[present]
            if not np.any(present):
                continue
            tri, normal, magnitude, axis, cells = (v[present] for v in
                                                     (tri, normal, magnitude, axis, cells))
            ids = order[locations[present]]
            row = np.arange(len(ids))
            # Physical full-height side evidence separates double slabs
            # from texture-only, property-less half-slab guesses.
            vertical = (axis != 1) & ((tri[:, :, 1].max(axis=1) -
                                        tri[:, :, 1].min(axis=1)) >= .90)
            if np.any(vertical):
                np.add.at(full_height_side, ids[vertical],
                          (magnitude[vertical] * .5).astype(np.float32))
            fraction = tri[row, 0, axis] - cells[row, axis]
            mid = (fraction > .025) & (fraction < .975)
            if np.any(mid):
                weight = (magnitude[mid] * .5).astype(np.float32)
                np.add.at(internal, (ids[mid], axis[mid]), weight)
                direction = (normal[mid, axis[mid]] > 0).astype(np.int32)
                np.add.at(signed, (ids[mid], axis[mid], direction), weight)
            bin_index = np.rint(fraction * 4).astype(np.int32)
            matched = ((bin_index >= 0) & (bin_index <= 4) &
                       (np.abs(fraction * 4 - bin_index) < .14))
            if np.any(matched):
                np.add.at(quarter, (ids[matched], axis[matched], bin_index[matched]),
                          (magnitude[matched] * .5).astype(np.float32))
            yhalf = (axis == 1) & (np.abs(fraction - .5) < .035)
            if np.any(yhalf):
                direction = (normal[yhalf, 1] > 0).astype(np.int32)
                np.add.at(half, (ids[yhalf], direction),
                          (magnitude[yhalf] * .5).astype(np.float32))
            np.add.at(triangles_seen, ids, 1)
    return pts, {'quarter': quarter, 'internal': internal,
                 'signed': signed, 'half': half, 'faces': triangles_seen,
                 'full_height_side': full_height_side}, {'unit': unit}


def _winding(points, blocks, half):
    calibration = np.zeros(2, dtype=np.float64)
    for i, point in enumerate(points):
        state = blocks[tuple(point)]
        if not state.split('[', 1)[0].endswith('_stairs') or 'half=' not in state:
            continue
        calibration += half[i] if 'half=bottom' in state else half[i, ::-1]
    decisive = max(calibration) >= .5 and max(calibration) > min(calibration) * 1.25
    return bool(calibration[1] >= calibration[0]), bool(decisive), calibration.tolist()


def infer(meshes: list, blocks: dict, pack, unit: float, up_axis: str,
          aliases: dict | None = None):
    """Promote solids to stairs/slabs/fences/walls only on shape evidence."""
    from sketchrip_reference import VanillaAssets

    points, geo, report = scan(meshes, blocks, unit, up_axis)
    if not len(points):
        return {}, {'examined': 0, 'proposed_changes': 0}
    supported = set(VanillaAssets(pack).states)
    normal_up, decisive, calibration = _winding(points, blocks, geo['half'])
    aliases = aliases or {}
    reasons = collections.Counter()
    changes = {}
    for i, point in enumerate(points):
        state = blocks[tuple(point)]
        base = state.split('[', 1)[0]
        if base.endswith('_slab') and '[' not in state:
            # A bare slab identifier may originate from the texture atlas.
            # Do not invent a half-state. Promote only with explicit, full-
            # height side geometry and no exposed half-height tread.
            if (geo['full_height_side'][i] >= .20 and
                    geo['quarter'][i, 1, 2] < .05):
                changes[tuple(map(int, point))] = base + '[type=double]'
                reasons['double_slab'] += 1
            continue
        if '[' in state or base.endswith(('_stairs', '_slab', '_fence', '_wall')):
            continue
        h = float(geo['quarter'][i, 1, 2])
        fx = float(geo['quarter'][i, 0, 1:4].sum())
        fz = float(geo['quarter'][i, 2, 1:4].sum())
        candidate = None
        reason = None
        if h >= .75 and max(fx, fz) >= .12:
            candidate = family_name(base, 'stairs')
            if candidate and decisive:
                upward = geo['half'][i, 1] > geo['half'][i, 0]
                half = 'bottom' if upward == normal_up else 'top'
                candidate += f'[facing=north,half={half},shape=straight]'
                reason = 'stairs'
            else:
                candidate = None
        elif h >= .75 and max(fx, fz) < .12:
            candidate = family_name(base, 'slab')
            if candidate and decisive:
                upward = geo['half'][i, 1] > geo['half'][i, 0]
                half = 'bottom' if upward == normal_up else 'top'
                candidate += f'[type={half}]'
                reason = 'slab'
            else:
                candidate = None
        elif fx >= .65 and fz >= .65 and h < .10:
            candidate = aliases.get(base, {}).get('wall')
            reason = 'wall'
        elif (geo['faces'][i] >= 6 and
              (base.endswith('_stained_glass') or base == 'minecraft:glass') and
              geo['internal'][i, 0] + geo['internal'][i, 2] >= .05):
            candidate = base + '_pane' if base != 'minecraft:glass' else 'minecraft:glass_pane'
            reason = 'pane'
        elif (geo['faces'][i] >= 6 and
              (base.endswith('_planks') or base == 'minecraft:nether_bricks') and
              geo['internal'][i, 0] >= .10 and geo['internal'][i, 2] >= .10 and h < .12):
            if base.endswith('_planks'):
                candidate = family_name(base, 'fence')
            elif base == 'minecraft:nether_bricks':
                candidate = 'minecraft:nether_brick_fence'
            reason = 'fence'
        elif (geo['faces'][i] >= 8 and h < .10 and
              geo['internal'][i, 0] + geo['internal'][i, 2] >= .01):
            candidate = aliases.get(base, {}).get('button')
            reason = 'button'
        if candidate and candidate.split('[', 1)[0].removeprefix('minecraft:') in supported:
            changes[tuple(map(int, point))] = candidate
            reasons[reason] += 1
    return changes, {'examined': len(points), 'proposed_changes': len(changes),
                     'families': dict(reasons), 'winding_calibration': calibration,
                     'winding_decisive': decisive,
                     'policy': 'fractional and inset geometric evidence only'}


def refine_facing(meshes: list, blocks: dict, unit: float, up_axis: str,
                  axis_profile: str = 'mineways'):
    """Use signed stair risers to select facing; abstain on tied corners."""
    points, geo, report = scan(meshes, blocks, unit, up_axis)
    if not len(points):
        return {}, {'examined': 0, 'proposed_changes': 0}
    positive_up, decisive, calibration = _winding(points, blocks, geo['half'])
    if not decisive:
        return {}, {'examined': 0, 'proposed_changes': 0,
                    'reason': 'ambiguous mesh winding'}
    features = geo['signed'][:, [0, 0, 2, 2], [0, 1, 0, 1]]
    if not positive_up:
        features = features[:, [1, 0, 3, 2]]
    # Vanilla JSON model quads and Mineways export normal conventions do not
    # share the same east/west riser winding. Keep that distinction explicit.
    if axis_profile not in ('mineways', 'vanilla'):
        raise ValueError('Unknown stair riser axis convention')
    directions = (('east', 'west', 'south', 'north') if axis_profile == 'mineways'
                  else ('east', 'west', 'north', 'south'))
    changes = {}
    counts = collections.Counter()
    for i, point in enumerate(points):
        state = blocks[tuple(point)]
        if not state.split('[', 1)[0].endswith('_stairs'):
            continue
        counts['examined'] += 1
        observed = features[i]
        best = int(np.argmax(observed))
        runner_up = float(np.partition(observed, -2)[-2])
        if float(observed[best]) < .25 or float(observed[best]) - runner_up < .25:
            counts['ambiguous'] += 1
            continue
        facing = directions[best]
        if f'facing={facing}' in state:
            counts['already_correct'] += 1
            continue
        if 'facing=' not in state:
            counts['no_facing_property'] += 1
            continue
        changes[tuple(map(int, point))] = re.sub(r'facing=(east|west|south|north)',
                                                   f'facing={facing}', state)
        counts['corrected'] += 1
    return changes, {'examined': counts['examined'], 'proposed_changes': len(changes),
                     'counts': dict(counts),
                     'winding': 'positive outward' if positive_up else 'negative outward',
                     'policy': 'signed riser normals with decisive area margin'}


def verify_legacy_roofs(blocks: dict, roof_report: dict, pack):
    """Recheck Bedrock corner constraints *after* all facing changes have run.

    Earlier validation may become stale when a late-facing correction changes
    a connected stair group. This reports that conflict rather than silently
    presenting a solved corner arrangement as exact.
    """
    from sketchrip_bedrock_stairs import DIRECTIONS, predict_shape, properties, reference_masks

    observations = roof_report.get('observations', [])
    if not observations:
        return {'compared': 0, 'conflicts': 0, 'samples': []}
    masks = reference_masks(pack)
    half = {}
    facing = {}
    for point, state in blocks.items():
        if not state.split('[', 1)[0].endswith('_stairs'):
            continue
        attrs = properties(state)
        if attrs.get('facing') not in DIRECTIONS:
            continue
        half[point] = attrs.get('half', 'bottom')
        facing[point] = DIRECTIONS.index(attrs['facing'])
    mismatches = []
    matched = 0
    for record in observations:
        point = tuple(int(c) for c in record['voxel'])
        direction = facing.get(point)
        if direction is None:
            continue
        shape = predict_shape(point, direction, facing, half)
        predicted = masks.get((half[point], direction, shape))
        if predicted == record['mask']:
            matched += 1
        else:
            if len(mismatches) < 30:
                mismatches.append({'position': list(point), 'expected_mask': record['mask'],
                                   'predicted_mask': predicted, 'derived_shape': shape})
    compared = sum(tuple(int(c) for c in r['voxel']) in facing for r in observations)
    return {'compared': int(compared), 'matched': matched,
            'conflicts': compared - matched, 'samples': mismatches,
            'note': 'Checks older Bedrock neighbor-model constraints after final stair facing changes; source masks may also include occlusion artifacts.'}
