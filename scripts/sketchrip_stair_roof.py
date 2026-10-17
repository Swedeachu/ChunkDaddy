#!/usr/bin/env python3
"""Reconstruct stair orientation from the *top roof* of voxel-local stair meshes.

A bottom stair exposes its high half on the integer Y+1 plane. The resulting
2x2 occupancy footprint identifies straight, outer and inner shapes directly,
without depending on triangle winding or a symmetric riser cosine histogram.

Only faces fully inside a stair column count: merged cross-column roof geometry
is ignored rather than attributed to the wrong cell. Missing/occluded evidence
never produces a guessed state.
"""

from __future__ import annotations

import collections
from pathlib import Path

import numpy as np

from sketchrip_reference import VanillaAssets


QUADRANTS = np.asarray([[.25, .25], [.75, .25],
                        [.25, .75], [.75, .75]], dtype=np.float64)


def roof_fingerprint(triangles: np.ndarray, plane: float = 1.0) -> tuple[int, float]:
    """Return 2x2 x/z occupancy bits at a specified local Y plane."""
    triangles = np.asarray(triangles, dtype=np.float64).reshape(-1, 3, 3)
    if not len(triangles):
        return 0, 0.
    flat = np.max(np.abs(triangles[:, :, 1] - plane), axis=1) < .02
    selected = triangles[flat]
    if not len(selected):
        return 0, 0.
    return _footprint(selected[:, :, [0, 2]])


def _footprint(xz: np.ndarray) -> tuple[int, float]:
    """Occupancy at quadrant centers; both winding conventions produce same bits."""
    a, b, c = xz[:, 0], xz[:, 1], xz[:, 2]
    va = b - a
    vb = c - a
    den = va[:, 0] * vb[:, 1] - va[:, 1] * vb[:, 0]
    valid = np.abs(den) > 1e-8
    if not np.any(valid):
        return 0, 0.
    a, va, vb, den = a[valid], va[valid], vb[valid], den[valid]
    diff = QUADRANTS[None, :, :] - a[:, None, :]
    u = (diff[:, :, 0] * vb[:, None, 1] - diff[:, :, 1] * vb[:, None, 0]) / den[:, None]
    v = (va[:, None, 0] * diff[:, :, 1] - va[:, None, 1] * diff[:, :, 0]) / den[:, None]
    inside = np.any((u >= -.0005) & (v >= -.0005) & (u + v <= 1.0005), axis=0)
    mask = sum((1 << index) for index, yes in enumerate(inside) if yes)
    area = float(np.sum(np.abs(den) * .5))
    return mask, area


def roof_reference(assets: VanillaAssets, family: str, half: str = "bottom"):
    """Lookup exposed horizontal footprint -> equivalent stair states."""
    candidates = collections.defaultdict(list)
    name = family.removeprefix('minecraft:')
    for state, _ in assets.enumerate_states(name):
        if f'half={half}' not in state:
            continue
        try:
            face_list = assets.faces(state)
        except (KeyError, ValueError, TypeError):
            continue
        mask, area = roof_fingerprint(np.asarray([tri for tri, _, _ in face_list]),
                                      plane=1.0 if half == "bottom" else 0.0)
        if mask and abs(area - mask.bit_count() * .25) <= .055:
            candidates[mask].append(state)
    return dict(candidates)


def _add_roof(projected: np.ndarray, ids: np.ndarray, footprints: np.ndarray,
              roof_area: np.ndarray, observed: np.ndarray, counts: collections.Counter):
    """Vectorized contribution of one horizontal batch, independent of winding."""
    a, b, c = projected[:, 0], projected[:, 1], projected[:, 2]
    va, vb = b - a, c - a
    den = va[:, 0] * vb[:, 1] - va[:, 1] * vb[:, 0]
    valid = np.abs(den) > 1e-8
    if not np.any(valid):
        return
    ids, a, va, vb, den = ids[valid], a[valid], va[valid], vb[valid], den[valid]
    diff = QUADRANTS[None, :, :] - a[:, None, :]
    u = (diff[:, :, 0] * vb[:, None, 1] - diff[:, :, 1] * vb[:, None, 0]) / den[:, None]
    v = (va[:, None, 0] * diff[:, :, 1] - va[:, None, 1] * diff[:, :, 0]) / den[:, None]
    within = (u >= -.0005) & (v >= -.0005) & (u + v <= 1.0005)
    bits = np.sum(within.astype(np.uint8) * np.asarray([1, 2, 4, 8], dtype=np.uint8), axis=1).astype(np.uint8)
    np.bitwise_or.at(footprints, ids, bits)
    np.add.at(roof_area, ids, np.abs(den) * .5)
    np.add.at(observed, ids, 1)
    counts['stair_roof_triangles'] += len(ids)


def infer(meshes: list, blocks: dict, pack: Path, unit: float, up_axis: str):
    """Find supported stair shapes from exposed horizontal integer-Y faces.

    Does not change materials, slabs or other block families. Bottom stairs
    use Y+1 roof evidence; top stairs use their exposed Y underside. Either
    case abstains if the neighboring voxel obscures the relevant surface.
    """
    from sketchrip import guess_scale

    stair_points = [p for p, state in blocks.items()
                    if state.split('[', 1)[0].endswith('_stairs') and
                    ('half=bottom' in state or 'half=top' in state)]
    counts = collections.Counter()
    if not stair_points:
        return {}, {'examined': 0, 'proposed_changes': 0, 'counts': {'no_stairs': 1}}
    unit = guess_scale(meshes, unit)
    minimum = np.rint(np.min([np.asarray(mesh.bounds)[0] for mesh in meshes if len(mesh.vertices)], axis=0)
                      / unit) * unit

    pts = np.asarray(stair_points, dtype=np.int64)
    lo, hi = pts.min(axis=0), pts.max(axis=0)
    size = hi - lo + 1
    if np.prod(size.astype(np.float64)) > 2 ** 52:
        raise ValueError('Stair roof coordinate range too large')
    stride = np.asarray([int(size[1] * size[2]), int(size[2]), 1], dtype=np.int64)
    key_values = (pts - lo) @ stride
    order = np.argsort(key_values)
    ordered_keys = key_values[order]
    half_top = np.asarray(['half=top' in blocks[p] for p in stair_points], dtype=bool)

    # Integer-Y faces belong to the bottom stair below or top stair above.
    # Winding does not affect occupancy; inverted GLB triangles work identically.
    footprints = np.zeros(len(pts), dtype=np.uint8)
    roof_area = np.zeros(len(pts), dtype=np.float64)
    observed = np.zeros(len(pts), dtype=np.int32)
    for mesh in meshes:
        vertices = np.asarray(mesh.vertices, dtype=np.float64)
        faces = np.asarray(mesh.faces, dtype=np.int64)
        for start in range(0, len(faces), 65536):
            tri = (vertices[faces[start:start + 65536]] - minimum) / unit
            if up_axis == 'z':
                tri = tri[:, :, [0, 2, 1]]
            if not len(tri):
                continue
            y = tri[:, :, 1]
            yplane = np.rint(y.mean(axis=1))
            flat = np.max(np.abs(y - yplane[:, None]), axis=1) < .025
            if not np.any(flat):
                continue
            tri = tri[flat]
            yplane = yplane[flat].astype(np.int64)
            mid = tri.mean(axis=1)
            cell = np.floor(mid + 1e-5).astype(np.int64)
            # One integer plane may be the top of a bottom stair or the
            # underside of a top stair. Match only the appropriate half.
            for half in ('bottom', 'top'):
                candidate = cell.copy()
                candidate[:, 1] = yplane - (1 if half == 'bottom' else 0)
                inside = np.all((candidate >= lo) & (candidate <= hi), axis=1)
                if not np.any(inside):
                    continue
                selected, candidate = tri[inside], candidate[inside]
                local = selected - candidate[:, None, :]
                # This is the central correctness check: a merged roof across
                # neighbors must not masquerade as individual stair roofs.
                projected = local[:, :, [0, 2]]
                contained = ((projected.min(axis=(1, 2)) >= -.025) &
                             (projected.max(axis=(1, 2)) <= 1.025))
                if not np.any(contained):
                    continue
                projected, candidate = projected[contained], candidate[contained]
                keys = (candidate - lo) @ stride
                locations = np.searchsorted(ordered_keys, keys)
                present = locations < len(ordered_keys)
                present[present] &= ordered_keys[locations[present]] == keys[present]
                if not np.any(present):
                    continue
                projected = projected[present]
                ids = order[locations[present]]
                matching_half = half_top[ids] if half == 'top' else ~half_top[ids]
                if not np.any(matching_half):
                    continue
                projected, ids = projected[matching_half], ids[matching_half]
                _add_roof(projected, ids, footprints, roof_area, observed, counts)

    references = {}
    assets = VanillaAssets(pack)
    updates = {}
    samples = []
    observations = []
    for index, point in enumerate(stair_points):
        mask = int(footprints[index])
        if mask == 0:
            counts['no_roof'] += 1
            continue
        # Another block directly on the inspected side can contribute a face
        # on this plane. Occlusion is not evidence of a smaller stair.
        half = 'top' if half_top[index] else 'bottom'
        neighbor = (point[0], point[1] + (1 if half == 'bottom' else -1), point[2])
        if neighbor in blocks:
            counts['blocked_' + ('above' if half == 'bottom' else 'below')] += 1
            continue
        area = float(roof_area[index])
        expected_area = mask.bit_count() * .25
        if abs(area - expected_area) > .12:
            counts['incomplete_or_overlapping_roof'] += 1
            continue
        observations.append({"voxel": list(point), "mask": mask, "half": half, "roof_area": round(area, 6)})
        if mask == 15:
            counts['full_roof_no_facing_evidence'] += 1
            continue
        family = blocks[point].split('[', 1)[0]
        reference_key = (family, half)
        if reference_key not in references:
            references[reference_key] = roof_reference(assets, family, half)
        states = references[reference_key].get(mask, [])
        if not states:
            counts['unrecognized_roof'] += 1
            continue
        old = blocks[point]
        if old in states:
            counts['already_correct_roof'] += 1
            continue
        # Candidates sharing a horizontal footprint render identical vanilla
        # stair geometry in this blockstate dataset. The original semantic
        # state label can be unknowable, even when the visible shape is exact.
        new = sorted(states)[0]
        if new != old:
            updates[point] = new
            counts['fixed_roof'] += 1
            if len(samples) < 80:
                samples.append({'voxel': list(point), 'previous': old,
                                'new': new, 'roof_bits': format(mask, '04b'),
                                'roof_area': round(area, 3),
                                'equivalent_states': len(states)})
    return updates, {'observations': observations, 'examined': len(stair_points), 'proposed_changes': len(updates),
                     'counts': dict(counts), 'samples': samples,
                     'policy': 'bottom-stair Y+1 roof / top-stair Y underside footprints; abstain on occlusion or incomplete roofs'}
