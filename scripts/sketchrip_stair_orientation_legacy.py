#!/usr/bin/env python3
"""Visible-interior stair orientation from geometric surface footprints.

Texture identity and candidate stair family come from the normal SketchRip passes.
This module only changes facing/half/shape when observed *internal* tread/riser
geometry separates that state from other states of the same stair family.
"""

from __future__ import annotations

import collections
from pathlib import Path

import numpy as np

from sketchrip_reference import VanillaAssets

# Each interior plane has a 4x4 map over its two tangent directions.
# We explicitly retain both tangent coordinates, unlike the old 2x2 face bins.
SAMPLES = np.array([
    [0.8, 0.1, 0.1], [0.1, 0.8, 0.1], [0.1, 0.1, 0.8],
    [0.45, 0.45, 0.1], [0.45, 0.1, 0.45], [0.1, 0.45, 0.45],
], dtype=np.float64)
PLANE_RESOLUTION = 4
TANGENT_RESOLUTION = 4
SIGNATURE_SIZE = 3 * 3 * 4 * 4


def _features(tris: np.ndarray):
    """Weighted fractional-surface map, input vertices relative to voxel [0, 1]."""
    triangles = np.asarray(tris, dtype=np.float64).reshape(-1, 3, 3)
    out = np.zeros(SIGNATURE_SIZE, dtype=np.float32)
    if len(triangles) == 0:
        return out
    cross = np.cross(triangles[:, 1] - triangles[:, 0],
                     triangles[:, 2] - triangles[:, 0])
    norm = np.linalg.norm(cross, axis=1)
    axis = np.argmax(np.abs(cross), axis=1)
    rows = np.arange(len(triangles))
    plane = triangles[rows, 0, axis]
    quarter = np.rint(plane * 4).astype(np.int32)
    eligible = ((norm > 1e-10) &
                (np.abs(cross[rows, axis]) >= norm * 0.985) &
                (quarter >= 1) & (quarter <= 3) &
                (np.abs(plane * 4 - quarter) <= 0.06) &
                (np.max(triangles, axis=(1, 2)) < 2.0))
    if not np.any(eligible):
        return out
    selected = triangles[eligible]
    axis = axis[eligible]
    quarter = quarter[eligible]
    area = norm[eligible] * .5 / len(SAMPLES)
    samples = np.einsum('sv,tvc->tsc', SAMPLES, selected)
    other = np.asarray([[1, 2], [0, 2], [0, 1]])[axis]
    row = np.arange(len(selected))
    a = samples[row, :, other[:, 0]]
    b = samples[row, :, other[:, 1]]
    u = np.clip(np.floor(a * TANGENT_RESOLUTION).astype(np.int32), 0, 3)
    v = np.clip(np.floor(b * TANGENT_RESOLUTION).astype(np.int32), 0, 3)
    index = ((axis[:, None] * 3 + (quarter[:, None] - 1)) * 16 +
             u * 4 + v)
    np.add.at(out, index.reshape(-1), np.repeat(area, len(SAMPLES)))
    return out


def _norm_rows(data: np.ndarray):
    return data / np.maximum(np.linalg.norm(data, axis=1, keepdims=True), 1e-9)


def stair_templates(assets: VanillaAssets, family: str):
    """Reference signatures for every variant of a recognized stair family."""
    name = family.removeprefix('minecraft:')
    states = []
    vectors = []
    try:
        variants = assets.enumerate_states(name)
    except (KeyError, ValueError):
        return [], np.empty((0, SIGNATURE_SIZE), np.float32)
    for state, _ in variants:
        try:
            triangles = np.asarray([f[0] for f in assets.faces(state)])
        except (KeyError, ValueError, TypeError):
            continue
        vector = _features(triangles)
        if np.linalg.norm(vector) < .04:
            continue
        states.append(state)
        vectors.append(vector)
    if not vectors:
        return [], np.empty((0, SIGNATURE_SIZE), np.float32)
    return states, _norm_rows(np.stack(vectors).astype(np.float32))


def classify_signature(evidence: np.ndarray, states: list[str], references: np.ndarray,
                       existing: str | None = None, threshold: float = .88,
                       margin: float = .04):
    """Return a uniquely supported state; do not force a facing when shapes tie."""
    observed = evidence.astype(np.float32)
    length = float(np.linalg.norm(observed))
    if length < .04 or not len(states):
        return None, 0., 0., 'missing_interior'
    observed /= length
    scores = references @ observed
    order = np.argsort(scores)[::-1]
    first = int(order[0]); best = float(scores[first])
    # Identical signatures cannot establish semantic facing, but don't count
    # them as competing orientations when the resulting model is identical.
    distances = np.max(np.abs(references - references[first]), axis=1)
    different = np.flatnonzero(distances > .012)
    runner_up = float(np.max(scores[different])) if len(different) else -1.
    gap = best - runner_up
    equivalent = [states[i] for i in range(len(states)) if distances[i] <= .012]
    if best < threshold:
        return None, best, gap, 'weak_geometry'
    if gap < margin:
        return None, best, gap, 'ambiguous_orientation'
    if existing in equivalent:
        return existing, best, gap, 'preserved_equivalent'
    if len(equivalent) > 1:
        # Corner shape/facing keys can generate the same physical stair. If the
        # original state does not share that geometry, use a stable visible
        # representative; report its semantic orientation as underdetermined.
        return sorted(equivalent)[0], best, gap, 'equivalent_geometry'
    return states[first], best, gap, 'unique'


def infer(meshes: list, blocks: dict, pack: Path, unit: float, up_axis: str):
    """Post-refine stair states using only observed fractional risers and treads.

    All faces are traversed in vectorized batches. No per-triangle Python loops,
    no block-material changes, and no changes to slab/door/torch states.
    """
    from sketchrip import guess_scale

    stair_points = [point for point, state in blocks.items()
                    if state.split('[', 1)[0].endswith('_stairs')]
    counts = collections.Counter()
    if not stair_points:
        return {}, {'proposed_changes': 0, 'counts': {'no_stairs': 1}}
    unit = guess_scale(meshes, unit)
    minimum = np.rint(np.min([np.asarray(m.bounds)[0] for m in meshes if len(m.vertices)], axis=0)
                      / unit) * unit
    coords = np.asarray(stair_points, dtype=np.int64)
    low, high = coords.min(axis=0), coords.max(axis=0)
    size = high - low + 1
    if np.prod(size.astype(float)) > 2 ** 52:
        raise ValueError('Stair coordinate indexing range too large')
    stride = np.asarray([int(size[1] * size[2]), int(size[2]), 1], dtype=np.int64)
    indices = (coords - low) @ stride
    sorted_order = np.argsort(indices)
    sorted_keys = indices[sorted_order]
    hist = np.zeros((len(stair_points), SIGNATURE_SIZE), dtype=np.float32)

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
            norm = np.linalg.norm(normal, axis=1)
            axis = np.argmax(np.abs(normal), axis=1)
            row = np.arange(len(tri))
            plane = tri[row, 0, axis]
            local_plane = plane - np.floor(plane)
            quarter = np.rint(local_plane * 4)
            eligible = ((norm > 1e-10) &
                        (np.abs(normal[row, axis]) >= norm * .985) &
                        (quarter >= 1) & (quarter <= 3) &
                        (np.abs(local_plane * 4 - quarter) <= .06) &
                        (np.max(tri.max(axis=1) - tri.min(axis=1), axis=1) <= 1.05))
            if not np.any(eligible):
                continue
            tri = tri[eligible]
            normal = normal[eligible]
            norm = norm[eligible]
            axis = axis[eligible]
            quarter = quarter[eligible].astype(np.int32)
            cell = np.floor(tri.mean(axis=1) + 1e-5).astype(np.int64)
            valid = np.all((cell >= low) & (cell <= high), axis=1)
            if not np.any(valid):
                continue
            tri = tri[valid]
            normal = normal[valid]
            norm = norm[valid]
            axis = axis[valid]
            quarter = quarter[valid]
            cell = cell[valid]
            keys = (cell - low) @ stride
            locations = np.searchsorted(sorted_keys, keys)
            present = locations < len(sorted_keys)
            present[present] &= sorted_keys[locations[present]] == keys[present]
            if not np.any(present):
                continue
            tri = tri[present]
            norm = norm[present]
            axis = axis[present]
            quarter = quarter[present]
            cell = cell[present]
            ids = sorted_order[locations[present]]
            samples = np.einsum('sv,tvc->tsc', SAMPLES, tri - cell[:, None, :])
            other = np.asarray([[1, 2], [0, 2], [0, 1]])[axis]
            row = np.arange(len(samples))
            u = np.clip(np.floor(samples[row, :, other[:, 0]] * 4).astype(np.int32), 0, 3)
            v = np.clip(np.floor(samples[row, :, other[:, 1]] * 4).astype(np.int32), 0, 3)
            bins = ((axis[:, None] * 3 + (quarter[:, None] - 1)) * 16 + u * 4 + v)
            weights = norm * .5 / len(SAMPLES)
            offsets = ids[:, None] * SIGNATURE_SIZE + bins
            np.add.at(hist.ravel(), offsets.reshape(-1), np.repeat(weights, len(SAMPLES)))
            counts['matched_internal_triangles'] += len(ids)

    assets = VanillaAssets(pack)
    pools = {}
    proposals = {}
    samples = []
    for i, point in enumerate(stair_points):
        original = blocks[point]
        family = original.split('[', 1)[0]
        if family not in pools:
            pools[family] = stair_templates(assets, family)
        states, reference = pools[family]
        state, similarity, gap, reason = classify_signature(hist[i], states, reference, existing=original)
        counts[reason] += 1
        if state is not None and state != original:
            proposals[point] = state
            if len(samples) < 80:
                samples.append({'voxel': list(point), 'from': original, 'to': state,
                                'similarity': round(similarity, 4), 'margin': round(gap, 4)})
    return proposals, {'proposed_changes': len(proposals), 'stairs_examined': len(stair_points),
                       'counts': dict(counts), 'samples': samples,
                       'policy': 'visible internal tread/riser footprint, conservatively abstain on orientation ties'}
