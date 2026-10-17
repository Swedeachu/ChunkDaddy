"""Conservative fence detection from post/rail geometry, never from plank pixels alone.

Only considers blocks that were already classified as a corresponding plank type;
requires internal narrow faces on BOTH horizontal axes and compares 3D surface
occupancy with 16 vanilla fence connection states.
"""
from __future__ import annotations

import collections
import numpy as np

from sketchrip_reference import VanillaAssets
from sketchrip_small_shapes import surface_samples, vectorize, model_vector, FEATURE_LENGTH, GRID


def infer(meshes: list, blocks: dict, pack, unit: float, up_axis: str):
    from sketchrip import guess_scale
    candidates = [(pt, val) for pt, val in blocks.items()
                  if val.split('[', 1)[0].removeprefix('minecraft:').endswith('_planks')]
    if not candidates:
        return {}, {'examined': 0, 'proposed_changes': 0}
    unit = guess_scale(meshes, unit)
    pts = np.asarray([p for p, _ in candidates], dtype=np.int64)
    low, high = pts.min(axis=0), pts.max(axis=0)
    size = high - low + 1
    stride = np.asarray([int(size[1] * size[2]), int(size[2]), 1], dtype=np.int64)
    keys = (pts - low) @ stride
    order = np.argsort(keys)
    sorted_keys = keys[order]
    area = np.zeros((len(pts), 3), dtype=np.float32)
    fine = np.zeros((len(pts), FEATURE_LENGTH), dtype=np.float32)
    minimum = np.rint(np.min([m.bounds[0] for m in meshes if len(m.vertices)], axis=0) / unit) * unit
    observed_faces = 0
    for mesh in meshes:
        v = np.asarray(mesh.vertices, dtype=np.float64)
        f = np.asarray(mesh.faces, dtype=np.int64)
        for start in range(0, len(f), 65536):
            tris = (v[f[start:start + 65536]] - minimum) / unit
            if not len(tris):
                continue
            if up_axis == 'z':
                tris = tris[:, :, [0, 2, 1]]
            n = np.cross(tris[:, 1] - tris[:, 0], tris[:, 2] - tris[:, 0])
            norm = np.linalg.norm(n, axis=1)
            axis = np.argmax(np.abs(n), axis=1)
            row = np.arange(len(tris))
            plane = tris[row, 0, axis]
            fraction = plane - np.floor(plane)
            # Do not let a neighboring whole-block boundary count as a fence.
            internal = ((norm > 1e-8) & (np.abs(n[row, axis]) >= norm * .985)
                        & (fraction > .18) & (fraction < .82)
                        & (np.max(tris.max(axis=1)-tris.min(axis=1), axis=1) <= 1.05))
            if not np.any(internal):
                continue
            tr = tris[internal]
            norm, axis = norm[internal], axis[internal]
            center = tr.mean(axis=1)
            cells = np.floor(center + 1e-5).astype(np.int64)
            valid = np.all((cells >= low) & (cells <= high), axis=1)
            if not np.any(valid):
                continue
            tr, norm, axis, cells = tr[valid], norm[valid], axis[valid], cells[valid]
            pos = (cells-low)@stride
            ix = np.searchsorted(sorted_keys,pos)
            found = ix < len(sorted_keys)
            found[found] &= sorted_keys[ix[found]] == pos[found]
            if not np.any(found):
                continue
            tr, norm, axis, cells = tr[found], norm[found], axis[found], cells[found]
            ids = order[ix[found]]
            np.add.at(area, (ids, axis), norm * .5)
            # Per-face barycentric samples, accounting for the model's visible
            # geometry without relying on texture/material names.
            sample, weight = surface_samples(tr - cells[:, None, :])
            target = np.repeat(ids, 4)
            b = np.clip(np.floor(sample * GRID).astype(np.int64), 0, GRID-1)
            cell = (b[:, 0] * GRID + b[:, 1]) * GRID + b[:, 2]
            np.add.at(fine.ravel(), target * FEATURE_LENGTH + cell, weight)
            observed_faces += len(ids)
    assets = VanillaAssets(pack)
    reference = {}
    proposals = {}
    samples = []
    rejected = collections.Counter()
    for index in np.flatnonzero((area[:, 0] >= .15) & (area[:, 2] >= .15)):
        point, original = candidates[int(index)]
        family = original.removeprefix('minecraft:').split('[', 1)[0].replace('_planks', '_fence')
        if family not in reference:
            try:
                states = assets.enumerate_states(family)
                items = [(state, model_vector(assets, state)) for state, _ in states]
                items = [(state, vector) for state, vector in items if np.linalg.norm(vector) > 1e-7]
                reference[family] = items
            except (KeyError, ValueError, TypeError):
                reference[family] = []
        pool = reference[family]
        if not pool:
            rejected['unknown_fence_family'] += 1
            continue
        observed = fine[index]
        mag = np.linalg.norm(observed)
        if mag <= .1:
            continue
        observed = observed / mag
        scores = np.array([float(observed @ vec) for _, vec in pool], dtype=np.float32)
        order_scores = np.argsort(scores)[::-1]
        best, second = int(order_scores[0]), int(order_scores[1])
        similarity, gap = float(scores[best]), float(scores[best] - scores[second])
        if similarity < .64 or gap < .018:
            rejected['weak_or_ambiguous_fence_geometry'] += 1
            continue
        new = pool[best][0]
        proposals[point] = new
        if len(samples) < 32:
            samples.append({'point': point, 'from': original, 'to': new,
                            'similarity': round(similarity, 4), 'margin': round(gap, 4)})
    return proposals, {'examined': len(candidates), 'fractional_faces': observed_faces,
                       'geometry_candidates': int(np.count_nonzero((area[:, 0] >= .15) & (area[:, 2] >= .15))),
                       'proposed_changes': len(proposals), 'rejected': dict(rejected), 'samples': samples,
                       'policy': 'only wood plank families with characteristic post faces on both axes and a clear model winner'}
