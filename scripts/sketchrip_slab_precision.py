"""Conservative top/bottom slab correction using signed exposed half-height faces.

Winding is calibrated from visible unit-height planes, since the vanilla reference
mesh generator and external mesh exporters may have opposite triangle winding.
"""
from __future__ import annotations

import collections
import numpy as np


def _normal_sign(meshes, unit: float, minimum: np.ndarray, up_axis: str) -> tuple[int, dict]:
    """Return +1 when the source uses outward normals, -1 for inward normals."""
    yaxis = 2 if up_axis == "z" else 1
    top, bottom = 0., 0.
    for mesh in meshes:
        verts = np.asarray(mesh.vertices, dtype=np.float64)
        faces = np.asarray(mesh.faces, dtype=np.int64)
        for start in range(0, len(faces), 65536):
            t = (verts[faces[start:start + 65536]] - minimum) / unit
            if not len(t):
                continue
            n = np.cross(t[:, 1] - t[:, 0], t[:, 2] - t[:, 0])
            area = np.linalg.norm(n, axis=1)
            axis = np.argmax(np.abs(n), axis=1)
            y = t[:, 0, yaxis]
            yfraction = np.abs(y - np.rint(y))
            near_integer = (yfraction < .035) & (axis == yaxis) & (area > 1e-9)
            # For positive Y integer planes, top surfaces strongly dominate in an
            # above-ground Minecraft build; protect against highly mixed surfaces.
            if np.any(near_integer):
                ns = n[near_integer, yaxis]
                top += float(np.sum(np.abs(ns[ns > 0])))
                bottom += float(np.sum(np.abs(ns[ns < 0])))
    sign = 1 if top > bottom else -1
    return sign, {"integer_positive_area": round(top * .5, 2),
                  "integer_negative_area": round(bottom * .5, 2),
                  "winding": "outward" if sign == 1 else "inward",
                  "winding_decisive": max(top, bottom) > min(top, bottom) * 1.35}


def infer(meshes: list, blocks: dict, unit: float, up_axis: str,
          winding_override: int | None = None):
    """Fix only recognized slab `type=top/bottom`; never create slab IDs."""
    from sketchrip import guess_scale
    slab_points = [p for p, state in blocks.items() if state.split('[', 1)[0].endswith('_slab')
                   and 'type=double' not in state]
    if not slab_points:
        return {}, {"examined": 0, "proposed_changes": 0}
    unit = guess_scale(meshes, unit)
    minimum = np.rint(np.min([m.bounds[0] for m in meshes if len(m.vertices)], axis=0) / unit) * unit
    winding, diagnostics = _normal_sign(meshes, unit, minimum, up_axis)
    if winding_override in (-1, 1):
        winding = winding_override
        diagnostics['source'] = 'calibrated from stair geometry'
        diagnostics['winding_decisive'] = True
    if not diagnostics["winding_decisive"]:
        return {}, {"examined": len(slab_points), "proposed_changes": 0,
                    "abstained": len(slab_points), "reason": "ambiguous global winding", **diagnostics}
    pts = np.asarray(slab_points, dtype=np.int64)
    lo, hi = pts.min(axis=0), pts.max(axis=0)
    size = hi - lo + 1
    stride = np.asarray([int(size[1] * size[2]), int(size[2]), 1], dtype=np.int64)
    keys = (pts - lo) @ stride
    order = np.argsort(keys)
    sorted_keys = keys[order]
    up, down = np.zeros(len(pts), dtype=np.float64), np.zeros(len(pts), dtype=np.float64)
    axis_y = 2 if up_axis == 'z' else 1
    for mesh in meshes:
        v = np.asarray(mesh.vertices, dtype=np.float64)
        f = np.asarray(mesh.faces, dtype=np.int64)
        for start in range(0, len(f), 65536):
            tri = (v[f[start:start + 65536]] - minimum) / unit
            if not len(tri):
                continue
            n = np.cross(tri[:, 1] - tri[:, 0], tri[:, 2] - tri[:, 0])
            mag = np.linalg.norm(n, axis=1)
            plane = tri[:, 0, axis_y]
            at_half = (np.abs((plane - np.floor(plane)) - .5) < .035)
            axis = np.argmax(np.abs(n), axis=1)
            # Exclude long merged faces and slanted geometry.
            span = np.max(tri.max(axis=1) - tri.min(axis=1), axis=1)
            valid = at_half & (axis == axis_y) & (mag > 1e-8) & (span <= 1.1)
            valid &= np.abs(n[:, axis_y]) >= mag * .99
            if not np.any(valid):
                continue
            tri, n, mag = tri[valid], n[valid], mag[valid]
            centers = np.floor(tri.mean(axis=1) + 1e-5).astype(np.int64)
            if up_axis == 'z':
                centers = centers[:, [0, 2, 1]]
            inside = np.all((centers >= lo) & (centers <= hi), axis=1)
            if not np.any(inside):
                continue
            centers, n, mag = centers[inside], n[inside], mag[inside]
            lookup = (centers - lo) @ stride
            found = np.searchsorted(sorted_keys, lookup)
            present = found < len(sorted_keys)
            present[present] &= sorted_keys[found[present]] == lookup[present]
            if not np.any(present):
                continue
            ids = order[found[present]]
            n, mag = n[present], mag[present]
            positive = winding * n[:, axis_y] > 0
            np.add.at(up, ids[positive], mag[positive] * .5)
            np.add.at(down, ids[~positive], mag[~positive] * .5)
    proposals = {}
    examples = []
    for i, point in enumerate(slab_points):
        positive, negative = up[i], down[i]
        dominant = max(positive, negative)
        if dominant < .10 or dominant / max(min(positive, negative), .001) < 2.75:
            continue
        kind = 'bottom' if positive > negative else 'top'
        old = blocks[point]
        if 'type=' in old:
            import re
            new = re.sub(r'type=(?:top|bottom)', f'type={kind}', old)
        else:
            new = old + f'[type={kind}]'
        if old != new:
            proposals[point] = new
            if len(examples) < 32:
                examples.append({'position': point, 'previous': old, 'new': new,
                                 'top_face_area': round(positive, 3), 'bottom_face_area': round(negative, 3)})
    return proposals, {'examined': len(slab_points), 'proposed_changes': len(proposals),
                       'winding': diagnostics, 'sample': examples,
                       'policy': 'only reassign slab half on a strong signed Y=0.5 surface; abstain otherwise'}
