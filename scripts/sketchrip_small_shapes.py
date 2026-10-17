"""Fine three-dimensional surface evidence for directional, narrow blocks.

Unlike axis-plane histograms this keeps tilted faces. It is deliberately limited
 to recognized small-block families; visible geometry cannot prove properties
such as a lever's powered state.
"""

from __future__ import annotations

import collections

import numpy as np

from sketchrip_reference import VanillaAssets


FAMILIES = ("ladder", "torch", "wall_torch", "lever", "tripwire_hook", "end_rod",
            "iron_door", "_door", "_button", "_trapdoor", "_pressure_plate")
GRID = 8
FEATURE_LENGTH = GRID ** 3


def is_supported(block: str) -> bool:
    base = block.removeprefix("minecraft:").split("[", 1)[0]
    return base in {"ladder", "torch", "wall_torch", "lever", "tripwire_hook", "end_rod", "iron_door"} \
        or base.endswith(("_door", "_button", "_trapdoor", "_pressure_plate"))


def surface_samples(triangles: np.ndarray) -> tuple[np.ndarray, np.ndarray]:
    """Raster-friendly barycentric samples and triangle areas in block units."""
    if not len(triangles):
        return np.empty((0, 3)), np.empty(0)
    triangles = np.asarray(triangles, dtype=np.float64)
    area = 0.5 * np.linalg.norm(np.cross(triangles[:, 1] - triangles[:, 0],
                                         triangles[:, 2] - triangles[:, 0]), axis=1)
    barycentric = np.asarray(((.5, .25, .25), (.25, .5, .25),
                              (.25, .25, .5), (1/3, 1/3, 1/3)))
    points = np.einsum('sc,tcd->tsd', barycentric, triangles).reshape(-1, 3)
    weights = np.repeat(area / len(barycentric), len(barycentric))
    return points, weights


def vectorize(points: np.ndarray, weights: np.ndarray) -> np.ndarray:
    feature = np.zeros(FEATURE_LENGTH, dtype=np.float32)
    bins = np.clip(np.floor(points * GRID).astype(np.int32), 0, GRID - 1)
    locations = (bins[:, 0] * GRID + bins[:, 1]) * GRID + bins[:, 2]
    np.add.at(feature, locations, weights)
    mag = np.linalg.norm(feature)
    return feature / mag if mag > 1e-10 else feature


def model_vector(assets: VanillaAssets, state: str) -> np.ndarray:
    triangles = np.asarray([triangle for triangle, _uv, _tex in assets.faces(state)])
    points, weights = surface_samples(triangles)
    return vectorize(points, weights)


def preserve_hidden_powered(previous: str, candidate: str) -> str:
    """Geometry cannot prove powered=true on a newly inferred switch/button."""
    if 'powered=true' in candidate and 'powered=true' not in previous:
        return candidate.replace('powered=true', 'powered=false')
    return candidate


def infer(meshes: list, blocks: dict, pack, unit: float, up_axis: str,
          confidence: float = .76, margin: float = .018):
    """Recover visible facing/half/attached states only when supported by local mesh."""
    candidates = [(p, b) for p, b in blocks.items() if is_supported(b)]
    if not candidates:
        return {}, {"recognized": 0, "proposals": 0}
    points = np.asarray([p for p, _ in candidates], dtype=np.int64)
    low = points.min(axis=0)
    high = points.max(axis=0)
    size = high - low + 1
    strides = np.asarray([int(size[1] * size[2]), int(size[2]), 1], dtype=np.int64)
    keys = (points - low) @ strides
    order = np.argsort(keys)
    index = keys[order]
    accumulated = np.zeros((len(candidates), FEATURE_LENGTH), dtype=np.float32)
    minimum = np.rint(np.min([m.bounds[0] for m in meshes if len(m.vertices)], axis=0) / unit) * unit
    counted = 0

    for mesh in meshes:
        vertices = np.asarray(mesh.vertices, dtype=np.float64)
        faces = np.asarray(mesh.faces, dtype=np.int32)
        for start in range(0, len(faces), 65536):
            triangles = (vertices[faces[start:start + 65536]] - minimum) / unit
            centers = triangles.mean(axis=1)
            if up_axis == "z":
                triangles = triangles[:, :, [0, 2, 1]]
                centers = centers[:, [0, 2, 1]]
            cells = np.floor(centers + 1e-5).astype(np.int64)
            inside = np.all((cells >= low) & (cells <= high), axis=1)
            if not np.any(inside):
                continue
            selected = np.flatnonzero(inside)
            lookup = (cells[selected] - low) @ strides
            found = np.searchsorted(index, lookup)
            valid = found < len(index)
            valid[valid] &= index[found[valid]] == lookup[valid]
            if not np.any(valid):
                continue
            selected = selected[valid]
            identifiers = order[found[valid]]
            tris = triangles[selected] - cells[selected, None, :]
            samples, areas = surface_samples(tris)
            # Discard samples outside the target cell rather than attributing
            # a neighboring voxel's wall to the selected directional block.
            sample_cells = np.repeat(identifiers, 4)
            valid_points = np.all((samples >= -.02) & (samples <= 1.02), axis=1)
            samples = samples[valid_points]
            sample_cells = sample_cells[valid_points]
            areas = areas[valid_points]
            binned = np.clip(np.floor(samples * GRID).astype(np.int32), 0, GRID - 1)
            indices = ((binned[:, 0] * GRID + binned[:, 1]) * GRID + binned[:, 2])
            np.add.at(accumulated.ravel(), sample_cells * FEATURE_LENGTH + indices, areas)
            counted += len(tris)

    assets = VanillaAssets(pack)
    model_vectors = {}
    proposals = {}
    ambiguous = collections.Counter()
    report_samples = []
    for i, (point, raw) in enumerate(candidates):
        base = raw.removeprefix('minecraft:').split('[', 1)[0]
        if base not in model_vectors:
            model_vectors[base] = []
            try:
                states = assets.enumerate_states(base)
            except (KeyError, ValueError):
                states = []
            for state, _ in states:
                try:
                    vector = model_vector(assets, state)
                except (KeyError, ValueError, TypeError):
                    continue
                if np.any(vector):
                    model_vectors[base].append((state, vector))
        choices = model_vectors[base]
        observed = accumulated[i]
        length = np.linalg.norm(observed)
        if length < 1e-8 or not choices:
            ambiguous['no_surface_evidence'] += 1
            continue
        observed = observed / length
        scores = np.array([float(observed @ model) for _, model in choices])
        best = int(np.argmax(scores))
        quality = float(scores[best])
        tied = [j for j in range(len(choices)) if scores[j] >= quality - margin]
        geometrically_same = all(np.allclose(choices[j][1], choices[best][1],
                                          rtol=0, atol=1e-6) for j in tied)
        if quality < confidence:
            ambiguous['weak_geometry'] += 1
            continue
        if len(tied) > 1 and not geometrically_same:
            ambiguous['facing_not_resolved'] += 1
            continue
        # Repeated exact visual geometry may correspond to different invisible
        # block properties (powered, waterlogged, hinge, etc.). Those properties
        # cannot be inferred, so keep source values where available and default
        # only when the source block has no properties.
        best_state = choices[best][0]
        if len(tied) > 1:
            previous = next((choices[j][0] for j in tied if choices[j][0] == raw), None)
            best_state = previous or sorted(choices[j][0] for j in tied)[0]
            ambiguous['invisible_properties'] += 1
        best_state = preserve_hidden_powered(raw, best_state)
        if best_state != raw:
            proposals[point] = best_state
            if len(report_samples) < 100:
                report_samples.append({'point': list(point), 'from': raw, 'to': best_state,
                                       'score': round(quality, 4), 'tied': len(tied)})
    return proposals, {'recognized': len(candidates), 'surface_triangles': counted,
                       'proposals': len(proposals), 'rejected': dict(ambiguous),
                       'samples': report_samples,
                       'notice': 'Only visible geometry is matched; powered and hidden properties are not recoverable'}
