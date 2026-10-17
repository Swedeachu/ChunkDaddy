#!/usr/bin/env python3
"""Conservative candidate inference from voxel-local geometric planes.

This is an opt-in refinement, not an exact-state oracle. It proposes a model-defined
state only when its geometry fits and a source-texture relationship is established.
Indistinguishable states and weak matches retain the existing block vote.
"""

from __future__ import annotations

import collections
import json
import hashlib
from pathlib import Path

import numpy as np

from sketchrip_reference import VanillaAssets


FAMILY_TERMS = ("_stairs", "_slab", "_fence", "_fence_gate", "_wall", "_pane",
                "_door", "_trapdoor", "_leaves", "_log", "_wood",
                "iron_bars", "copper_bars", "ladder", "_button", "_pressure_plate",
                "torch", "lever", "lantern", "tripwire_hook", "_sign")
FEATURE_SIZE = 60


def features(triangles, limit: int | None = None):
    """60D weighted normal-plane/quadrant histogram (axis × plane × quadrant)."""
    hist = np.zeros(FEATURE_SIZE, dtype=np.float32)
    for tri in triangles if limit is None else triangles[:limit]:
        cross = np.cross(tri[1] - tri[0], tri[2] - tri[0])
        area2 = np.linalg.norm(cross)
        if area2 < 1e-7:
            continue
        axis = int(np.argmax(np.abs(cross)))
        if abs(cross[axis]) / area2 < 0.985:
            continue
        center = tri.mean(axis=0)
        plane = float(np.mean(tri[:, axis]))
        if plane < -0.2 or plane > 1.2:
            continue
        bin_plane = int(np.clip(round(plane * 4), 0, 4))
        other = [i for i in range(3) if i != axis]
        quadrant = int(center[other[0]] > 0.5) * 2 + int(center[other[1]] > 0.5)
        hist[axis * 20 + bin_plane * 4 + quadrant] += area2 * 0.5
    length = np.linalg.norm(hist)
    return hist / length if length > 1e-8 else hist


def candidate_records(assets: VanillaAssets, predicted_types: set[str]):
    """Load a compiled model-feature index, or produce it once from vanilla JSON.

    The cached lookup is immutable, validated against the texture pack hash, and
    contains no user-supplied GLB block IDs.
    """
    cache_path = assets.source.parent / "reference-model-features.npz"
    pack_hash = hashlib.sha256(assets.source.read_bytes()).hexdigest()
    raw = None
    if cache_path.is_file():
        with np.load(cache_path, allow_pickle=False) as data:
            if str(data["pack_sha256"]) == pack_hash:
                raw = (data["states"].tolist(), data["textures"].tolist(),
                       data["vectors"].astype(np.float32))
    rejected = 0
    if raw is None:
        states, textures, vectors = [], [], []
        for name in assets.states:
            if not any(term in name for term in FAMILY_TERMS):
                continue
            for state, _props in assets.enumerate_states(name):
                try:
                    model_faces = assets.faces(state)
                except (KeyError, ValueError, TypeError):
                    rejected += 1
                    continue
                vector = features([face[0] for face in model_faces])
                if not np.any(vector):
                    continue
                for texture in {key.split("/")[-1] for _, _, key in model_faces}:
                    states.append(state)
                    textures.append(texture)
                    vectors.append(vector)
        feature_vectors = np.stack(vectors).astype(np.float32)
        # Store a compiled material+geometry index instead of generating thousands
        # of model triangles every time a user imports a large Sketchfab arena.
        np.savez_compressed(cache_path, pack_sha256=pack_hash,
                            states=np.asarray(states), textures=np.asarray(textures),
                            vectors=feature_vectors.astype(np.float16))
        raw = states, textures, feature_vectors
    templates = collections.defaultdict(list)
    for state, texture, vector in zip(*raw):
        # A special block can be identified from a dedicated sprite whose name
        # is not its block ID (iron_door_bottom, lever, wall_torch, ...).
        # Index both material texture and the block family. Without the latter,
        # candidates for recognized doors and torches are never considered.
        owner = state.removeprefix("minecraft:").split("[", 1)[0]
        for key in {texture, owner}:
            if key not in predicted_types:
                continue
            # A bark/plank/stone texture is shared by many special blocks.
            # Never turn an ordinary cube into a button, pressure plate,
            # trapdoor or door merely because its surface resembles a partial
            # model. Those types require prior material-family recognition.
            if key != owner and owner.endswith(("_button", "_pressure_plate",
                                                 "_door", "_trapdoor")):
                continue
            if key != owner and owner in {"torch", "wall_torch", "lever", "ladder",
                                         "tripwire_hook", "end_rod"}:
                continue
            templates[key].append((state, vector))
    return templates, rejected


def infer(meshes: list, blocks: dict, pack: Path, unit: float, up_axis: str,
          similarity_threshold: float = 0.93, gap_threshold: float = 0.045,
          max_faces: int = 3_000_000):
    """Refine textured block IDs from local geometry without Python triangle loops.

    Gather a compact per-voxel normal/plane/quadrant histogram with vectorized
    mesh batches. Match only voxels with positive fractional-plane evidence.
    Matching is independent of GLB material/object grouping and does not assume
    that adjacent or hidden faces are present.
    """
    from sketchrip import guess_scale

    if not blocks:
        return {}, {"proposed_changes": 0, "counts": {"empty_world": 1}}
    if sum(len(m.faces) for m in meshes) > max_faces:
        raise ValueError("Geometry inference input exceeds the per-run face limit")
    unit = guess_scale(meshes, unit)
    bounds = [np.asarray(m.bounds, dtype=np.float64) for m in meshes if len(m.vertices)]
    minimum = np.rint(np.min([box[0] for box in bounds], axis=0) / unit) * unit

    # Each existing block has exactly one compact 60-element accumulator. Avoid
    # a Python dictionary lookup for each of the 1.5M+ source triangles.
    points = np.asarray(list(blocks), dtype=np.int32)
    low = points.min(axis=0).astype(np.int64)
    high = points.max(axis=0).astype(np.int64)
    size = high - low + 1
    if np.prod(size.astype(np.float64)) > (1 << 52):
        raise ValueError("Voxel coordinate range is too large to index exactly")
    strides = np.asarray([int(size[1] * size[2]), int(size[2]), 1], dtype=np.int64)
    keys = ((points.astype(np.int64) - low) @ strides)
    sorted_index = np.argsort(keys)
    sorted_keys = keys[sorted_index]
    hist = np.zeros((len(points), FEATURE_SIZE), dtype=np.float32)
    fractional_area = np.zeros(len(points), dtype=np.float32)
    counts = collections.Counter()

    for mesh in meshes:
        vertices = np.asarray(mesh.vertices, dtype=np.float64)
        faces = np.asarray(mesh.faces, dtype=np.int64)
        for start in range(0, len(faces), 65536):
            triangles = vertices[faces[start:start + 65536]]
            if not len(triangles):
                continue
            normal = np.cross(triangles[:, 1] - triangles[:, 0],
                              triangles[:, 2] - triangles[:, 0])
            norm = np.linalg.norm(normal, axis=1)
            axis = np.argmax(np.abs(normal), axis=1)
            rows = np.arange(len(triangles))
            aligned = (norm > 1e-12) & (np.abs(normal[rows, axis]) > norm * 0.985)
            spans = np.max((triangles.max(axis=1) - triangles.min(axis=1)) / unit, axis=1)
            # Merged full-block faces span many voxels and cannot be assigned
            # from a single centroid; small faces carry local shape evidence.
            usable = aligned & (spans <= 1.10)
            if not np.any(usable):
                continue
            triangles = triangles[usable]
            normal = normal[usable]
            norm = norm[usable]
            axis = axis[usable]
            rows = np.arange(len(triangles))

            centers = (triangles.mean(axis=1) - minimum) / unit
            cells = np.floor(centers + 1e-5).astype(np.int64)
            plane = ((triangles[:, 0, :] - minimum) / unit)[rows, axis]
            boundary = np.rint(plane)
            snapped = np.abs(plane - boundary) < 0.09
            positive = normal[rows, axis] > 0
            # Identical face-side rules to SketchRip's main reconstruction.
            adjusted = np.floor(plane - positive.astype(float) * 0.0001).astype(np.int64)
            cells[rows, axis] = np.where(snapped, boundary.astype(np.int64)
                                         - positive.astype(np.int64), adjusted)
            # A face lying on a voxel boundary may belong to either side.
            # Prefer the side containing a reconstructed block. This is crucial
            # for the north/negative faces of isolated stairs and slabs, where
            # blindly subtracting the normal moves the face into empty space.
            before = cells.copy()
            after = cells.copy()
            before[rows, axis] = boundary.astype(np.int64) - 1
            after[rows, axis] = boundary.astype(np.int64)
            if up_axis == "z":
                before = before[:, [0, 2, 1]]
                after = after[:, [0, 2, 1]]
                cells = cells[:, [0, 2, 1]]
            before_available = np.zeros(len(cells), dtype=bool)
            after_available = np.zeros(len(cells), dtype=bool)
            for alternate, choice in ((before, True), (after, False)):
                valid_alt = snapped & np.all((alternate >= low) & (alternate <= high), axis=1)
                if not np.any(valid_alt):
                    continue
                positions_alt = np.flatnonzero(valid_alt)
                test_keys = ((alternate[positions_alt] - low) @ strides)
                found = np.searchsorted(sorted_keys, test_keys)
                available = found < len(sorted_keys)
                available[available] &= sorted_keys[found[available]] == test_keys[available]
                if choice:
                    before_available = np.zeros(len(cells), dtype=bool)
                    before_available[positions_alt] = available
                else:
                    after_available = np.zeros(len(cells), dtype=bool)
                    after_available[positions_alt] = available
            choose_before = snapped & before_available & ~after_available
            choose_after = snapped & after_available & ~before_available
            cells[choose_before] = before[choose_before]
            cells[choose_after] = after[choose_after]
            del before_available, after_available
            valid = np.all((cells >= low) & (cells <= high), axis=1)
            if not np.any(valid):
                continue
            cells = cells[valid]
            axis = axis[valid]
            plane = plane[valid]
            norm = norm[valid]
            centers = centers[valid]
            keys_batch = ((cells - low) @ strides)
            locations = np.searchsorted(sorted_keys, keys_batch)
            present = locations < len(sorted_keys)
            present[present] &= sorted_keys[locations[present]] == keys_batch[present]
            if not np.any(present):
                continue
            ids = sorted_index[locations[present]]
            axis = axis[present]
            plane = plane[present]
            norm = norm[present]
            centers = centers[present]
            cells = cells[present]

            # Features follow VanillaAssets.faces: (normal axis, fractional
            # plane quarter, local quadrant). Normalized only when scored.
            if up_axis == "z":
                physical = cells[:, [0, 2, 1]]
            else:
                physical = cells
            local_plane = plane - physical[np.arange(len(ids)), axis]
            plane_bin = np.clip(np.rint(local_plane * 4).astype(np.int64), 0, 4)
            if up_axis == "z":
                axis_world = np.where(axis == 1, 2, np.where(axis == 2, 1, axis))
                local_center = centers[:, [0, 2, 1]] - cells
            else:
                axis_world = axis
                local_center = centers - cells
            others = np.array([[1, 2], [0, 2], [0, 1]], dtype=np.int32)[axis_world]
            row = np.arange(len(ids))
            quadrant = (local_center[row, others[:, 0]] > 0.5).astype(np.int64) * 2 \
                       + (local_center[row, others[:, 1]] > 0.5).astype(np.int64)
            bins = axis_world * 20 + plane_bin * 4 + quadrant
            np.add.at(hist.ravel(), ids.astype(np.int64) * FEATURE_SIZE + bins, norm / (2 * unit * unit))
            fractional = (local_plane > 0.02) & (local_plane < 0.98)
            if np.any(fractional):
                np.add.at(fractional_area, ids[fractional],
                          (norm[fractional] / (2 * unit * unit)).astype(np.float32))
            counts["aligned_local_faces"] += len(ids)

    # Only geometry-bearing voxels are eligible. A small piece of grass or
    # neighboring slab mesh must never convert a correct solid-block guess.
    candidate_ids = np.flatnonzero(fractional_area >= 0.06)
    counts["fractional_geometry_voxels"] = len(candidate_ids)
    counts["no_fractional_shape_evidence"] = len(blocks) - len(candidate_ids)
    y_mid = hist[candidate_ids, 28:32].sum(axis=1)
    x_mid = hist[candidate_ids, 8:12].sum(axis=1)
    z_mid = hist[candidate_ids, 48:52].sum(axis=1)
    counts["half_height_faces"] = int(np.count_nonzero(y_mid >= 0.15))
    counts["half_vertical_faces"] = int(np.count_nonzero(np.maximum(x_mid, z_mid) >= 0.1))
    counts["stair_like_internal_planes"] = int(np.count_nonzero((y_mid >= 0.15) &
                                                                (np.maximum(x_mid, z_mid) >= 0.10)))
    counts["slab_like_internal_planes"] = int(np.count_nonzero((y_mid >= 0.3) &
                                                                (np.maximum(x_mid, z_mid) < 0.1)))
    if not len(candidate_ids):
        return {}, {"proposed_changes": 0, "sampled_voxels": 0,
                    "counts": dict(counts), "policy": "No fractional geometry evidence"}

    names = [blocks[tuple(point)] for point in points]
    base_names = {name.removeprefix("minecraft:").split("[")[0] for name in names}
    assets = VanillaAssets(pack)
    candidates, rejected = candidate_records(assets, base_names)
    grouped = collections.defaultdict(list)
    for index in candidate_ids:
        grouped[names[index].removeprefix("minecraft:").split("[")[0]].append(int(index))
    counts["partial_material_families"] = dict(collections.Counter({name: len(group) for name, group in grouped.items()}).most_common(20))

    proposals = {}
    samples = []
    # Java-style face culling makes visible reference shapes much sparser
    # than the source model. An adjacent full cube hides the corresponding
    # outer plane of the target voxel, not its fractional risers/treads.
    occluded = np.zeros(len(points), dtype=np.uint8)

    def is_occluder(state):
        name = state.removeprefix("minecraft:").split("[", 1)[0]
        if name in {"minecraft:air", "air", "water", "lava", "vine", "short_grass",
                    "snow", "ladder", "iron_bars", "cobweb"}:
            return False
        return not name.endswith(("_stairs", "_slab", "_fence", "_gate", "_door",
                                  "_trapdoor", "_pane", "_wall", "_leaves", "_glass",
                                  "_button", "_torch", "_flower", "_carpet"))

    dirs = ((-1, 0, 0), (1, 0, 0), (0, -1, 0),
            (0, 1, 0), (0, 0, -1), (0, 0, 1))
    for idx in candidate_ids:
        x, y, z = (int(v) for v in points[idx])
        flags = 0
        for bit, (dx, dy, dz) in enumerate(dirs):
            neighbor = blocks.get((x + dx, y + dy, z + dz))
            if neighbor is not None and is_occluder(neighbor):
                flags |= 1 << bit
        occluded[idx] = flags

    outer_visibility = np.ones((64, FEATURE_SIZE), dtype=np.float32)
    outer_offsets = ((0, 0), (0, 4), (1, 0),
                     (1, 4), (2, 0), (2, 4))
    for flags in range(64):
        for bit, (axis, plane) in enumerate(outer_offsets):
            if flags & (1 << bit):
                begin = axis * 20 + plane * 4
                outer_visibility[flags, begin:begin + 4] = 0
    counts["visibility_aware_voxels"] = int(np.count_nonzero(occluded[candidate_ids]))

    for base, voxel_ids in grouped.items():
        pool = candidates.get(base, [])
        if not pool:
            continue
        # Repeated model faces often reference the same texture repeatedly.
        # Deduplicate states before matrix multiplication.
        templates_by_state = {}
        for state, vector in pool:
            templates_by_state.setdefault(state, vector)
        states = list(templates_by_state)
        templates = np.asarray(list(templates_by_state.values()), dtype=np.float32)
        if not len(templates):
            continue
        templates /= np.maximum(np.linalg.norm(templates, axis=1, keepdims=True), 1e-9)
        positions = np.asarray(voxel_ids, dtype=np.intp)
        inside_mask = np.zeros(FEATURE_SIZE, dtype=np.float32)
        for direction in range(3):
            inside_mask[direction * 20 + 4:direction * 20 + 16] = 1.0
        inside_templates = templates * inside_mask
        inside_templates /= np.maximum(np.linalg.norm(inside_templates, axis=1, keepdims=True), 1e-9)
        stair_columns = [i for i, state in enumerate(states)
                         if state.split("[")[0].endswith("_stairs")]
        slab_columns = [i for i, state in enumerate(states)
                        if state.split("[")[0].endswith("_slab")]

        def canonical_family(value):
            name = value.split("[")[0]
            return "minecraft:oak_slab" if name == "minecraft:petrified_oak_slab" else name

        for start in range(0, len(positions), 1024):
            chosen = positions[start:start + 1024]
            evidence = hist[chosen]
            evidence = evidence / np.maximum(np.linalg.norm(evidence, axis=1, keepdims=True), 1e-9)
            # Mask faces expected to have been culled by a neighboring solid.
            # Calculate by 6-bit mask group rather than per triangle/voxel.
            full_scores = np.empty((len(chosen), len(templates)), dtype=np.float32)
            shape_masks = occluded[chosen]
            for flags in np.unique(shape_masks):
                rows_visible = shape_masks == flags
                visibility = outer_visibility[int(flags)]
                visible_evidence = evidence[rows_visible] * visibility
                visible_evidence /= np.maximum(np.linalg.norm(visible_evidence, axis=1,
                                                                keepdims=True), 1e-9)
                expected = templates * visibility
                expected /= np.maximum(np.linalg.norm(expected, axis=1, keepdims=True), 1e-9)
                full_scores[rows_visible] = visible_evidence @ expected.T
            interior = evidence * inside_mask
            interior /= np.maximum(np.linalg.norm(interior, axis=1, keepdims=True), 1e-9)
            inside_scores = interior @ inside_templates.T

            for row, voxel_id in enumerate(chosen):
                raw = names[voxel_id]
                best_id = int(np.argmax(full_scores[row]))
                best_score = float(full_scores[row, best_id])
                confidence_kind = "full_model"

                # Mineways deliberately omits occluded outer cube faces.
                # Compare exposed half-height and riser planes independently,
                # and only for a topology that can physically be a slab/stair.
                half_y = float(hist[voxel_id, 28:32].sum())
                half_vertical = float(hist[voxel_id, 8:12].sum() +
                                      hist[voxel_id, 48:52].sum())
                stair_like = half_y >= 0.15 and half_vertical >= 0.10
                slab_like = half_y >= 0.30 and half_vertical < 0.10
                eligible = stair_columns if stair_like else slab_columns if slab_like else []
                if eligible:
                    # The internal-plane score is weighted more heavily than
                    # the outer-faces score because the exporter merged/culls
                    # exposed faces of neighboring solids.
                    scored = (0.80 * inside_scores[row, eligible] +
                              0.20 * full_scores[row, eligible])
                    ordinal = int(np.argmax(scored))
                    shape_id = eligible[ordinal]
                    shape_confidence = float(inside_scores[row, shape_id])
                    exterior_confidence = float(full_scores[row, shape_id])
                    if shape_confidence >= 0.86 and exterior_confidence >= 0.42:
                        best_id = shape_id
                        best_score = float(scored[ordinal])
                        confidence_kind = "partial_planes"
                best_state = states[best_id]
                best_family = canonical_family(best_state)
                if confidence_kind == "full_model":
                    other_score = max((float(value) for j, value in enumerate(full_scores[row])
                                       if canonical_family(states[j]) != best_family), default=-1.0)
                    if best_score < similarity_threshold or best_score - other_score < gap_threshold:
                        counts["ambiguous_or_low_confidence"] += 1
                        continue
                    ranked = full_scores[row]
                else:
                    # A 'good' internal score does not prove the selected facing.
                    # Track equal-ranking orientations, then choose a stable
                    # representative of their identical visible geometry.
                    other_score = 0.0
                    ranked = (0.80 * inside_scores[row] + 0.20 * full_scores[row])
                same_family = [states[j] for j, value in enumerate(ranked)
                               if canonical_family(states[j]) == best_family
                               and float(value) >= best_score - 0.002]
                if len(same_family) > 1:
                    counts["visually_equivalent_states"] += 1
                    same_family = [name for name in same_family
                                   if not name.startswith("minecraft:petrified_oak_slab")]
                    if same_family:
                        # Do not replace the geometric optimum with the first
                        # alphabetically named facing. A cosine-score near-tie
                        # is not proof that two geometries are equivalent.
                        # Only canonicalize identical feature vectors.
                        target = templates[best_id]
                        exact_visual_equivalents = [name for name in same_family
                            if np.allclose(templates[states.index(name)], target,
                                           rtol=0.0, atol=1e-6)]
                        if exact_visual_equivalents:
                            best_state = sorted(exact_visual_equivalents)[0]

                point = tuple(int(v) for v in points[voxel_id])
                if best_state == raw:
                    continue
                proposals[point] = best_state
                family_type = ("stairs" if "_stairs" in best_family else
                               "slab" if "_slab" in best_family else
                               best_family.removeprefix("minecraft:").split("_")[-1])
                counts["inferred_" + family_type] += 1
                if len(samples) < 200:
                    samples.append({"voxel": list(point), "from": raw, "to": best_state,
                                    "confidence_kind": confidence_kind,
                                    "similarity": round(best_score, 4),
                                    "margin": round(best_score - other_score, 4),
                                    "fractional_area": round(float(fractional_area[voxel_id]), 3),
                                    "equivalent_states": same_family[:6]
                                    if len(same_family) > 1 else []})

    return proposals, {"proposed_changes": len(proposals), "inspect_first_200": samples,
                       "sampled_voxels": int(len(candidate_ids)),
                       "template_textures": len(candidates), "unsupported_templates": rejected,
                       "counts": dict(counts),
                       "policy": "Geometry-aware; abstain when family or orientation is ambiguous"}
