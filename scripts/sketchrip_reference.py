#!/usr/bin/env python3
"""Deterministic vanilla-model calibration for SketchRip.

Uses the supplied Minecraft Java assets (blockstates, models and sprite textures), NOT
block names embedded in a GLB. It records collisions when models are visually identical.
The internal renderer is a calibration baseline, not a claim of Mineways/Bedrock parity.
"""

from __future__ import annotations

import argparse
import csv
import collections
import hashlib
import itertools
import json
import math
import re
import subprocess
import shutil
import os
import zipfile
from pathlib import Path

import numpy as np
from PIL import Image


DIRECTIONS = ("down", "up", "north", "south", "west", "east")
TARGETS = ("stairs", "slab", "ladder", "fence", "fence_gate", "wall", "iron_bars",
           "glass_pane", "door", "trapdoor", "leaves", "terracotta", "stone_bricks",
           "logs", "log", "pillar", "chiseled_bookshelf", "button", "torch", "rail")


class VanillaAssets:
    def __init__(self, source: Path):
        self.source = Path(source)
        self.archive = zipfile.ZipFile(source)
        self.paths = set(self.archive.namelist())
        self.model_cache = {}
        self.sprite_cache = {}
        self.states = sorted(p.split("/")[-1][:-5] for p in self.paths
                             if p.startswith("assets/minecraft/blockstates/") and p.endswith(".json"))

    def json(self, path: str):
        return json.loads(self.archive.read(path))

    def state_definition(self, name: str):
        return self.json(f"assets/minecraft/blockstates/{name}.json")

    def model(self, location: str):
        location = location.replace("minecraft:", "")
        if location in self.model_cache:
            return self.model_cache[location]
        path = f"assets/minecraft/models/{location}.json"
        if path not in self.paths:
            raise KeyError("Model not found: " + location)
        current = self.json(path)
        parent = current.get("parent")
        result = self.model(parent).copy() if parent else {}
        result["textures"] = {**result.get("textures", {}), **current.get("textures", {})}
        for key in ("elements", "ambientocclusion", "gui_light"):
            if key in current:
                result[key] = current[key]
        self.model_cache[location] = result
        return result

    def sprite(self, location: str):
        location = location.replace("minecraft:", "")
        if location in self.sprite_cache:
            return self.sprite_cache[location]
        path = f"assets/minecraft/textures/{location}.png"
        import io
        with Image.open(io.BytesIO(self.archive.read(path))) as image:
            width = image.width
            sprite = image.crop((0, 0, width, min(width, image.height))).convert("RGBA")
            if sprite.size != (16, 16):
                sprite = sprite.resize((16, 16), Image.Resampling.NEAREST)
        self.sprite_cache[location] = sprite
        return sprite

    @staticmethod
    def _properties(key: str):
        return dict(part.split("=", 1) for part in key.split(",") if "=" in part)

    def enumerate_states(self, name: str, max_multipart: int = 4096):
        definition = self.state_definition(name)
        if "variants" in definition:
            return [("minecraft:" + name + ("[" + key + "]" if key else ""),
                     self._properties(key)) for key in definition["variants"]]
        # Multipart conditions define model selection; enumerate valid boolean/directional
        # combinations conservatively without inventing a registry-wide default-state table.
        domains = collections.defaultdict(set)
        def discover(when):
            for key, value in when.items():
                if key in ("OR", "AND"):
                    for part in value:
                        discover(part)
                else:
                    domains[key].update(str(value).split("|"))
        for part in definition.get("multipart", []):
            discover(part.get("when", {}))
        if not domains:
            return [("minecraft:" + name, {})]
        keys = sorted(domains)
        values = [sorted(domains[key] | ({"false"} if "true" in domains[key] else set())) for key in keys]
        count = math.prod(len(v) for v in values)
        states = []
        if count <= max_multipart:
            combos = itertools.product(*values)
        else:
            # Include the all-false/none state, and one-property changes as targeted cases;
            # report incomplete coverage via catalog metadata, not as exhaustive results.
            base = tuple("false" if "false" in v else ("none" if "none" in v else v[0]) for v in values)
            candidate = [base]
            for i, choices in enumerate(values):
                for val in choices:
                    item = list(base)
                    item[i] = val
                    candidate.append(tuple(item))
            combos = dict.fromkeys(candidate)
        for combo in combos:
            props = dict(zip(keys, combo))
            key = ",".join(f"{k}={props[k]}" for k in keys)
            states.append(("minecraft:" + name + "[" + key + "]", props))
        return states

    @staticmethod
    def condition(when, properties):
        if not when:
            return True
        if "OR" in when:
            return any(VanillaAssets.condition(c, properties) for c in when["OR"])
        if "AND" in when:
            return all(VanillaAssets.condition(c, properties) for c in when["AND"])
        return all(properties.get(k) in str(v).split("|") for k, v in when.items())

    def selections(self, block_id: str):
        raw = block_id.removeprefix("minecraft:")
        name = raw.split("[", 1)[0]
        values = self._properties(raw.split("[", 1)[1].rstrip("]")) if "[" in raw else {}
        definition = self.state_definition(name)
        if "variants" in definition:
            key = ",".join(f"{k}={v}" for k, v in values.items())
            value = definition["variants"].get(key)
            if value is None:
                raise ValueError("Not a valid vanilla variant: " + block_id)
            return [value[0] if isinstance(value, list) else value]
        return [entry["apply"][0] if isinstance(entry["apply"], list) else entry["apply"]
                for entry in definition.get("multipart", [])
                if self.condition(entry.get("when"), values)]

    def faces(self, state: str):
        """Returns 3D triangles, UV rectangle and source texture per triangle."""
        output = []
        for selected in self.selections(state):
            model = self.model(selected["model"])
            textures = model.get("textures", {})
            for element in model.get("elements", []):
                low = np.array(element["from"], dtype=float) / 16.0
                high = np.array(element["to"], dtype=float) / 16.0
                for side, face in element.get("faces", {}).items():
                    x0, y0, z0 = low
                    x1, y1, z1 = high
                    quad = {
                        "north": [(x0, y0, z0), (x1, y0, z0), (x1, y1, z0), (x0, y1, z0)],
                        "south": [(x1, y0, z1), (x0, y0, z1), (x0, y1, z1), (x1, y1, z1)],
                        "west":  [(x0, y0, z1), (x0, y0, z0), (x0, y1, z0), (x0, y1, z1)],
                        "east":  [(x1, y0, z0), (x1, y0, z1), (x1, y1, z1), (x1, y1, z0)],
                        "up":    [(x0, y1, z0), (x1, y1, z0), (x1, y1, z1), (x0, y1, z1)],
                        "down":  [(x0, y0, z1), (x1, y0, z1), (x1, y0, z0), (x0, y0, z0)],
                    }[side]
                    quad = np.asarray(quad, dtype=float)
                    if "rotation" in element:
                        rot = element["rotation"]
                        quad = rotate(quad, rot["axis"], rot["angle"], np.asarray(rot.get("origin", [8, 8, 8])) / 16)
                    if selected.get("x"):
                        quad = rotate(quad, "x", selected["x"], np.array([0.5] * 3))
                    if selected.get("y"):
                        quad = rotate(quad, "y", selected["y"], np.array([0.5] * 3))
                    token = face["texture"]
                    for _ in range(12):
                        if isinstance(token, dict):
                            token = token.get("sprite", "")
                        if not isinstance(token, str):
                            raise ValueError("Non-string texture entry not supported: " + state)
                        if not token.startswith("#"):
                            break
                        token = textures.get(token[1:], "")
                        if isinstance(token, dict):
                            token = token.get("sprite", "")
                    if not token:
                        raise ValueError("Unresolved texture: " + state)
                    if not token.startswith("minecraft:"):
                        token = "minecraft:" + token
                    uv_rect = face.get("uv", [0, 0, 16, 16])
                    # Preserve UV orientation as specified by the model's rectangles.
                    ua, va, ub, vb = [float(v) for v in uv_rect]
                    texture_quad = np.array([(ua, vb), (ub, vb), (ub, va), (ua, va)])
                    uv_rot = (int(face.get("rotation", 0)) // 90) % 4
                    texture_quad = np.roll(texture_quad, uv_rot, axis=0)
                    for indices in ((0, 1, 2), (0, 2, 3)):
                        output.append((quad[list(indices)], texture_quad[list(indices)], token))
        if not output:
            raise ValueError("No renderable model faces: " + state)
        return output


def rotate(points, axis: str, degrees: float, origin):
    angle = math.radians(degrees)
    c, s = math.cos(angle), math.sin(angle)
    rotation = {
        "x": np.array([[1, 0, 0], [0, c, -s], [0, s, c]]),
        "y": np.array([[c, 0, s], [0, 1, 0], [-s, 0, c]]),
        "z": np.array([[c, -s, 0], [s, c, 0], [0, 0, 1]]),
    }[axis]
    return (points - origin) @ rotation.T + origin


SAMPLE_WEIGHTS = np.array([[0.60, 0.20, 0.20],
                           [0.20, 0.60, 0.20],
                           [0.20, 0.20, 0.60]], dtype=np.float64)


def triangle_pixels(image: np.ndarray, uv: np.ndarray) -> tuple:
    """Independently sampled texels retain in-tile orientation and UV rotation."""
    sample_uv = SAMPLE_WEIGHTS @ uv
    x = np.clip(np.floor(sample_uv[:, 0] + 1e-7).astype(int), 0, image.shape[1] - 1)
    y = np.clip(np.floor(sample_uv[:, 1] + 1e-7).astype(int), 0, image.shape[0] - 1)
    return tuple(bytes(c) for c in image[y, x])


def state_signature(faces, texture_hashes):
    # Keep each UV corner tied to its 3D vertex. Mirrored/rotated sprite directions
    # are distinguishable, but states rendering identical pixels/UVs are not.
    entries = []
    for vertices, uvs, texture in faces:
        corners = sorted(tuple(np.rint(np.concatenate((p * 256, uv * 64))).astype(int))
                         for p, uv in zip(vertices, uvs))
        entries.append((tuple(corners), texture_hashes[texture]))
    return hashlib.blake2b(repr(sorted(entries)).encode(), digest_size=20).hexdigest()



def placed_signature(faces, texture_hashes, coord, locations, atlas_size):
    """Compute expected fingerprint at the actual float32 precision of a GLB.

    glTF POSITION and TEXCOORD_0 are float32. At coordinates hundreds of blocks
    from the origin, quantizing the original double-precision JSON coordinates
    produced false mismatches on thin rotated petals. Mirror the serialized
    representation instead of calling those blocks unsupported.
    """
    entries = []
    shift = np.asarray(coord, dtype=np.float64)
    width, height = atlas_size
    for vertices, tex_uv, texture in faces:
        x, y = locations[texture]
        positioned = (np.asarray(vertices, dtype=np.float64) + shift).astype(np.float32)
        local = positioned.astype(np.float64) - shift
        packed_uv = np.column_stack(((x + tex_uv[:, 0]) / width,
                                     1 - (y + tex_uv[:, 1]) / height)).astype(np.float32)
        uv_local = np.column_stack((packed_uv[:, 0].astype(float) * width - x,
                                    (1 - packed_uv[:, 1].astype(float)) * height - y))
        corners = sorted(tuple(np.rint(np.concatenate((vertex * 256, uv * 64))).astype(int))
                         for vertex, uv in zip(local, uv_local))
        entries.append((tuple(corners), texture_hashes[texture]))
    return hashlib.blake2b(repr(sorted(entries)).encode(), digest_size=20).hexdigest()

def atlas_for_assets(assets, textures):
    names = sorted(textures)
    side = 18 * math.ceil(math.sqrt(len(names)))
    image = Image.new("RGBA", (side, side), (0, 0, 0, 0))
    locations = {}
    hashes = {}
    for index, name in enumerate(names):
        x = (index % (side // 18)) * 18 + 1
        y = (index // (side // 18)) * 18 + 1
        sprite = assets.sprite(name)
        image.paste(sprite, (x, y))
        locations[name] = (x, y)
        hashes[name] = hashlib.sha256(sprite.tobytes()).hexdigest()
    return image, locations, hashes


def fixture_states(assets, mode: str, limit: int):
    catalog = []
    details = []
    selected_groups = []
    for name in assets.states:
        states = assets.enumerate_states(name)
        details.append({"block": name, "states_enumerated": len(states), "is_multipart":
                        "multipart" in assets.state_definition(name)})
        if mode == "targeted" and not any(token in name for token in TARGETS):
            continue
        if mode == "defaults":
            states = states[:1]
        selected_groups.append([state for state, _ in states])
    # Interleave families so limited runs cover ladders, stairs, leaves, glass,
    # walls, etc., instead of using the first N alphabetically.
    for row in itertools.zip_longest(*selected_groups):
        catalog.extend(item for item in row if item is not None)
        if limit and len(catalog) >= limit:
            break
    if limit:
        catalog = catalog[:limit]
    return catalog, details


def write_mineways_script(path: Path, mode: str = "individual"):
    """Generate Mineways commands with explicit and documented export settings.

    The diagnostic mode preserves individual authored blocks, while merged mode
    reproduces face culling and combining that complicate Sketchfab reconstruction.
    These commands are from Mineways' scripting documentation, not Blender guesses.
    """
    if mode not in ("individual", "merged"):
        raise ValueError("Mineways export mode must be individual or merged")
    source = (path / "reference.schem").resolve().as_posix()
    destination = (path / f"mineways-reference-{mode}.obj").resolve().as_posix()
    script = ("// Headless Mineways Sponge v3 reference export\n"
              f"Minecraft world: {source}\n"
              "Set render type: Wavefront OBJ absolute indices\n"
              "File type: Export all textures to three large images\n"
              "Center model: no\n"
              "Use biomes: no\n"
              "Make Z the up direction instead of Y: no\n"
              "Export lesser blocks: YES\n"
              "Simplify mesh: no\n"
              "Export separate types: YES\n"
              "Split by block type: YES\n"
              f"Export individual blocks: {'YES' if mode == 'individual' else 'no'}\n"
              "Selection location: all\n"
              f"Export for rendering: {destination}\n"
              "Close\n")
    result = path / f"export_mineways_{mode}.mwscript"
    result.write_text(script, encoding="utf-8")
    if mode == "individual":
        (path / "export_mineways.mwscript").write_text(script, encoding="utf-8")
    return result


def generate(assets, path: Path, mode: str, limit: int):
    from sketchrip import write_schematic
    import trimesh
    path.mkdir(parents=True, exist_ok=True)
    states, details = fixture_states(assets, mode, limit)
    selected = []
    failures = []
    source_textures = set()
    for name in states:
        try:
            triangles = assets.faces(name)
            for _geometry, _uv, texture in triangles:
                assets.sprite(texture)  # Unsupported missing textures are reported per state.
            source_textures.update(face[2] for face in triangles)
            selected.append((name, triangles))
        except (ValueError, KeyError, FileNotFoundError, TypeError) as exc:
            failures.append({"state": name, "reason": str(exc)})
    atlas, locations, hashes = atlas_for_assets(assets, source_textures)
    atlas.save(path / "reference_atlas.png")
    columns = math.ceil(math.sqrt(len(selected)))
    verts, faces, uvs = [], [], []
    expected = {}
    fingerprints = collections.defaultdict(list)
    for index, (name, triangles) in enumerate(selected):
        coord = (index % columns * 3, 0, index // columns * 3)
        expected["%d,%d,%d" % coord] = name
        fingerprints[placed_signature(triangles, hashes, coord, locations,
                                      (atlas.width, atlas.height))].append(name)
        for triangle, triangle_uv, texture in triangles:
            x, y = locations[texture]
            vertices = triangle + np.array(coord, dtype=float)
            mapped = np.column_stack(((x + triangle_uv[:, 0]) / atlas.width,
                                      1.0 - (y + triangle_uv[:, 1]) / atlas.height))
            begin = len(verts)
            verts.extend(vertices.tolist())
            uvs.extend(mapped.tolist())
            faces.append([begin, begin + 1, begin + 2])
    mesh = trimesh.Trimesh(vertices=np.asarray(verts), faces=np.asarray(faces), process=False)
    mesh.visual = trimesh.visual.TextureVisuals(uv=np.asarray(uvs), image=atlas)
    glb = trimesh.exchange.gltf.export_glb(trimesh.Scene(mesh))
    (path / "reference.glb").write_bytes(glb)
    write_schematic({tuple(int(v) for v in key.split(",")): name for key, name in expected.items()}, path / "reference.schem")
    report = {
        "source": "Minecraft Java asset blockstates/models and texture sprites",
        "mode": mode, "selected": len(states), "renderable": len(selected),
        "skipped": failures, "reference_count": len(assets.states),
        "variant_and_multipart_counts": details,
        "triangle_count": len(faces), "unique_textures": len(source_textures),
        "expected": expected, "fingerprints": dict(fingerprints),
        "atlas_tile_px": 16, "atlas_stride": 18,
        "isolated_block_stride": 3, "columns": columns,
    }
    (path / "manifest.json").write_text(json.dumps(report, indent=2), encoding="utf-8")
    write_mineways_script(path, "individual")
    write_mineways_script(path, "merged")
    return report



def write_conformance_tables(path: Path, manifest: dict, result: dict):
    """Persist audit tables for every state, not only the first 100 examples."""
    signatures = {}
    for signature, states in manifest["fingerprints"].items():
        for state in states:
            signatures[state] = (signature, states)
    with (path / "state_conformance.csv").open("w", newline="", encoding="utf-8") as handle:
        writer = csv.writer(handle)
        writer.writerow(("state", "classification", "visual_equivalents", "equivalent_states"))
        for state in manifest["expected"].values():
            same = signatures[state][1]
            writer.writerow((state, "unique" if len(same) == 1 else "ambiguous",
                             len(same), " | ".join(same)))
    with (path / "unrendered_states.csv").open("w", newline="", encoding="utf-8") as handle:
        writer = csv.writer(handle)
        writer.writerow(("state", "reason"))
        for record in manifest["skipped"]:
            writer.writerow((record["state"], record["reason"]))
    summary = (
        "# SketchRip vanilla model conformance\n\n"
        f"- Enumerated states: **{manifest['selected']:,}**\n"
        f"- Renderable with static vanilla JSON models: **{manifest['renderable']:,}**\n"
        f"- Unrendered/special-renderer states: **{len(manifest['skipped']):,}**\n"
        f"- Uniquely recovered from GLB geometry + atlas pixels: **{result['uniquely_correct']:,}**\n"
        f"- Visually indistinguishable from another state: **{result['indistinguishable']:,}**\n"
        f"- Unmatched reconstruction fingerprints: **{result['wrong_or_unmatched']:,}**\n\n"
        "These results are for the **pack-JSON internal renderer**; they do NOT measure "
        "Mineways/Sketchfab fidelity or production SketchRip's exact-state accuracy.\n"
        "The ambiguity count is not a reconstruction defect that can be fixed by "
        "another texture metric: geometrically identical state renders are underdetermined.\n"
    )
    (path / "CONFORMANCE_SUMMARY.md").write_text(summary, encoding="utf-8")

def evaluate_internal(path: Path):
    """Vectorized GLB+atlas inverse matcher with identical-state ambiguity auditing.

    No source model IDs or metadata are used to identify individual blocks. All
    triangles are grouped by the known isolated spatial lattice, then matched to
    a compiled geometry+pixel signature table. For large catalogs, SIMD arrays
    replace hundreds of thousands of per-triangle NumPy allocations.
    """
    import trimesh
    manifest = json.loads((path / "manifest.json").read_text(encoding="utf-8"))
    scene = trimesh.load(path / "reference.glb", force="scene", process=False)
    meshes = scene.dump(concatenate=False)
    pixels = np.asarray(Image.open(path / "reference_atlas.png").convert("RGBA"))
    coords = collections.defaultdict(list)
    hash_cache = {}
    for mesh in meshes:
        uvs = np.asarray(mesh.visual.uv, dtype=np.float64)
        vertices = np.asarray(mesh.vertices, dtype=np.float64)
        triangles = np.asarray(mesh.faces, dtype=np.int64)
        for offset in range(0, len(triangles), 16000):
            ids = triangles[offset:offset + 16000]
            tri = vertices[ids]
            tex_uv = uvs[ids]
            centers = tri.mean(axis=1)
            # Each model lies inside a 1-block cell on a 3-block lattice.
            pos = np.zeros((len(ids), 3), dtype=np.int32)
            pos[:, (0, 2)] = np.floor(centers[:, (0, 2)] / 3 + 0.5).astype(np.int32) * 3
            mean_uv = tex_uv.mean(axis=1)
            px = np.clip(np.floor(mean_uv[:, 0] * pixels.shape[1]).astype(np.int32),
                         0, pixels.shape[1] - 1)
            py = np.clip(np.floor((1 - mean_uv[:, 1]) * pixels.shape[0]).astype(np.int32),
                         0, pixels.shape[0] - 1)
            sx = ((px - 1) // 18) * 18 + 1
            sy = ((py - 1) // 18) * 18 + 1
            valid = ((sx >= 0) & (sy >= 0) & (sx + 16 <= pixels.shape[1]) &
                     (sy + 16 <= pixels.shape[0]))
            if not valid.any():
                continue
            tri = tri[valid]
            tex_uv = tex_uv[valid]
            pos = pos[valid]
            sx = sx[valid]
            sy = sy[valid]
            tex_local = np.empty_like(tex_uv)
            tex_local[:, :, 0] = tex_uv[:, :, 0] * pixels.shape[1] - sx[:, None]
            tex_local[:, :, 1] = (1 - tex_uv[:, :, 1]) * pixels.shape[0] - sy[:, None]
            corners = np.rint(np.concatenate(((tri - pos[:, None, :]) * 256,
                                               tex_local * 64), axis=2)).astype(np.int64)
            sprite_coords = np.column_stack((sx, sy))
            for cell, corner, tile in zip(pos, corners, sprite_coords):
                position = tuple(int(v) for v in cell)
                tile_key = (int(tile[0]), int(tile[1]))
                sprite_hash = hash_cache.get(tile_key)
                if sprite_hash is None:
                    x, y = tile_key
                    sprite_hash = hashlib.sha256(pixels[y:y + 16, x:x + 16].tobytes()).hexdigest()
                    hash_cache[tile_key] = sprite_hash
                verts_with_uv = tuple(sorted(tuple(item) for item in corner))
                coords[position].append((verts_with_uv, sprite_hash))
    exact, ambiguous, failures = [], [], []
    all_signatures = manifest["fingerprints"]
    expected = manifest["expected"]
    for text_coord, truth in expected.items():
        pos = tuple(int(x) for x in text_coord.split(","))
        signature = hashlib.blake2b(repr(sorted(coords.get(pos, []))).encode(),
                                    digest_size=20).hexdigest()
        possibilities = all_signatures.get(signature, [])
        if not possibilities:
            failures.append({"coord": text_coord, "expected": truth,
                             "reason": "No matching geometry+pixels fingerprint"})
        elif len(possibilities) > 1:
            ambiguous.append({"coord": text_coord, "expected": truth,
                              "candidates": possibilities[:24], "candidate_count": len(possibilities)})
        elif possibilities[0] == truth:
            exact.append(truth)
        else:
            failures.append({"coord": text_coord, "expected": truth,
                             "guessed": possibilities[0]})
    result = {"states": len(expected), "uniquely_correct": len(exact),
              "indistinguishable": len(ambiguous), "wrong_or_unmatched": len(failures),
              "unique_accuracy": len(exact) / max(1, len(expected)),
              "deterministic_atlas_treatment": True, "atlas_tiles_read": len(hash_cache),
              "ambiguous_examples": ambiguous[:100], "failures": failures[:100],
              "limitations": ["The fixture uses pack JSON model geometry, not Mineways output.",
                              "Identical rendered models cannot resolve invisible blockstate properties.",
                              "Isolated cells do not test neighboring-block culling or connection updates."]}
    (path / "evaluation.json").write_text(json.dumps(result, indent=2), encoding="utf-8")
    write_conformance_tables(path, manifest, result)
    return result


def evaluate_external(path: Path, source: Path, texture_pack: Path, scale: float | None = None):
    """Run production SketchRip on a genuine Mineways OBJ/GLB and score against truth.

    This does NOT use the block IDs from the source schematic during reconstruction.
    It merely aligns the resulting sparse block coordinates with the fixture's grid,
    and compares source and inferred names afterwards.
    """
    import trimesh
    from sketchrip import TextureIndex, reconstruct
    manifest = json.loads((path / "manifest.json").read_text(encoding="utf-8"))
    expected = {tuple(map(int, k.split(","))): v for k, v in manifest["expected"].items()}
    scene = trimesh.load(str(source), force="scene", process=False)
    meshes = [m for m in scene.dump(concatenate=False) if isinstance(m, trimesh.Trimesh)]
    guesses, pipeline = reconstruct(meshes, TextureIndex(texture_pack), scale=scale, allow_uncertain=True)
    coords = np.array(list(guesses), dtype=np.int64)
    if not len(coords):
        raise RuntimeError("External model produced no block positions")
    best = None
    for orientation in range(4):
        for refl in (1, -1):
            # 0/90/180/270 rotations and reflection in XZ; no semantics from
            # source model IDs enters alignment or identification.
            transform = coords[:, [0, 2]].copy()
            if orientation % 2:
                transform = transform[:, ::-1]
            transform[:, 0] *= (-1 if orientation >= 2 else 1) * refl
            transform[:, 1] *= (-1 if orientation in (1, 2) else 1)
            minimum = transform.min(axis=0)
            anchor = np.rint((transform - minimum) / 3).astype(int) * 3
            transformed = {(int(pair[0]), int(guess[1]), int(pair[1])): block
                           for guess, pair, block in zip(coords, anchor, guesses.values())}
            # Collapse neighboring partial voxels by rounding them to the test
            # grid; production reconstruction still owns their original positions.
            from collections import Counter
            buckets = collections.defaultdict(Counter)
            for (x, y, z), block in transformed.items():
                ref = (int(np.rint(x / 3)) * 3, 0, int(np.rint(z / 3)) * 3)
                buckets[ref][block] += 1
            normalized = {k: v.most_common(1)[0][0] for k, v in buckets.items()}
            overlap = sum(pos in expected for pos in normalized)
            exact = sum(normalized[pos] == original for pos, original in expected.items() if pos in normalized)
            candidate = (overlap, exact, orientation, refl, normalized)
            if best is None or candidate[:2] > best[:2]:
                best = candidate
    _overlap, _exact, orientation, flip, observed = best
    errors = collections.Counter()
    exact = 0
    exact_type = 0
    unmatched = 0
    for pos, truth in expected.items():
        actual = observed.get(pos)
        if actual is None:
            unmatched += 1
        else:
            tname = truth.split("[")[0]
            aname = actual.split("[")[0]
            exact_type += (tname == aname)
            exact += (actual == truth)
            if tname != aname:
                errors[(truth, actual)] += 1
    result = {"source_mesh": str(source), "tested_states": len(expected),
              "exact_block_state": exact, "correct_block_type": exact_type,
              "missing_positions": unmatched,
              "outside_test_positions": len(set(observed) - set(expected)),
              "geometry_alignment": {"quadrant": orientation, "reflection": flip},
              "top_confusions": [{"expected": a, "actual": b, "count": n}
                                 for (a, b), n in errors.most_common(100)],
              "mesh_reconstruction": pipeline,
              "warning": "Tests only production SketchRip on a Mineways export; this scorer does not repair blockstates."}
    (path / "mineways-evaluation.json").write_text(json.dumps(result, indent=2), encoding="utf-8")
    return result


def run_mineways(path: Path, mineways_exe: Path, mode: str = "individual"):
    """Export the fixture through *real* Mineways when run on Windows.

    Mineways is independently installed by the user, never bundled. Both OBJ and
    converted GLB have to retain textured UVs or this stage fails loudly.
    """
    import trimesh
    mineways_exe = mineways_exe.expanduser().resolve()
    if not mineways_exe.is_file():
        raise FileNotFoundError("Mineways.exe not found: " + str(mineways_exe))
    if not (path / "reference.schem").is_file():
        raise FileNotFoundError("Generate a reference fixture first: " + str(path / "reference.schem"))
    if mode == "both":
        return {kind: run_mineways(path, mineways_exe, kind)
                for kind in ("individual", "merged")}
    script = write_mineways_script(path, mode).resolve()
    command = [str(mineways_exe), "-headless", str(script)]
    result = subprocess.run(command, cwd=path, text=True, capture_output=True,
                            encoding="utf-8", errors="replace", timeout=3600, check=False)
    (path / f"mineways-export-{mode}.log").write_text(result.stdout + "\n[STDERR]\n" + result.stderr,
                                                   encoding="utf-8")
    if result.returncode:
        raise RuntimeError("Mineways returned status " + str(result.returncode) +
                           "; see mineways-export-" + mode + ".log")
    obj = path / f"mineways-reference-{mode}.obj"
    if not obj.is_file() or obj.stat().st_size < 100:
        raise RuntimeError("Mineways did not write a usable OBJ; see mineways-export-" + mode + ".log")
    scene = trimesh.load(str(obj), force="scene", process=False)
    meshes = [m for m in scene.dump(concatenate=False) if isinstance(m, trimesh.Trimesh)]
    if not meshes or not any(getattr(m.visual, "uv", None) is not None for m in meshes):
        raise RuntimeError("OBJ import lost geometry or UVs; check Mineways MTL and PNG files")
    glb_path = path / f"mineways-reference-{mode}.glb"
    glb_path.write_bytes(trimesh.exchange.gltf.export_glb(scene))
    return {"obj": str(obj), "glb": str(glb_path), "mode": mode,
            "triangles": sum(len(m.faces) for m in meshes),
            "textures_present": sum(getattr(m.visual, "uv", None) is not None for m in meshes)}


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=("generate", "evaluate", "catalog", "evaluate-mineways", "mineways"))
    parser.add_argument("--textures", type=Path, default=Path(__file__).resolve().parent.parent / "assets/sketchrip/vanilla-26.2.zip")
    parser.add_argument("--out", type=Path, default=Path("sketchrip-reference"))
    parser.add_argument("--mode", choices=("targeted", "defaults", "all"), default="targeted")
    parser.add_argument("--limit", type=int, default=0)
    parser.add_argument("--mesh", type=Path, help="Mineways OBJ/GLB produced from reference.schem")
    parser.add_argument("--mineways-exe", type=Path, help="Path to genuine Mineways.exe (Windows)")
    parser.add_argument("--mineways-mode", choices=("individual", "merged", "both"), default="both",
                        help="Compare individual-block and merged-surface exports (default: both)")
    parser.add_argument("--scale", type=float, help="Explicit model-units-per-block calibration for Mineways")
    args = parser.parse_args(argv)
    if args.command == "mineways":
        if args.mineways_exe is None:
            parser.error("--mineways-exe is required")
        print(json.dumps(run_mineways(args.out, args.mineways_exe, args.mineways_mode), indent=2))
        return 0
    if args.command == "evaluate-mineways":
        if args.mesh is None:
            parser.error("--mesh is required for evaluate-mineways")
        result = evaluate_external(args.out, args.mesh, args.textures, args.scale)
        print(json.dumps({k: v for k, v in result.items() if k != "mesh_reconstruction"}, indent=2))
        return 0
    if args.command == "evaluate":
        print(json.dumps(evaluate_internal(args.out), indent=2)[:6000])
        return 0
    assets = VanillaAssets(args.textures)
    if args.command == "catalog":
        states, details = fixture_states(assets, args.mode, args.limit)
        print(json.dumps({"blocks": len(assets.states), "selected_states": len(states),
                          "definitions": details[:30]}, indent=2))
        return 0
    result = generate(assets, args.out, args.mode, args.limit)
    print(json.dumps({k: v for k, v in result.items() if k not in ("expected", "fingerprints", "variant_and_multipart_counts", "skipped")}, indent=2))
    print(f"Skipped model states: {len(result['skipped'])}; inspect manifest.json")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
