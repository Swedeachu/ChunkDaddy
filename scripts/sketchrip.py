#!/usr/bin/env python3
"""Reconstruct a Minecraft block schematic from a licensed Sketchfab mesh.

The ChunkDaddy worker, not this tool, owns Bedrock conversion and LevelDB output.
Requires numpy, Pillow and trimesh. scipy accelerates texture lookup if available.
"""

from __future__ import annotations

import argparse
import collections
import contextlib
import gzip
import hashlib
import io
import json
import os
import re
import shutil
import subprocess
import struct
import sys
import tempfile
import zipfile
from dataclasses import dataclass
from pathlib import Path
from urllib.parse import urlparse

import numpy as np
from PIL import Image


MODEL_ID = re.compile(r"(?:^|[-/])([a-f0-9]{32})(?:/?$|[?#])", re.I)
TEXTURE_PREFIX = "assets/minecraft/textures/block/"
MAX_ARCHIVE_BYTES = 2 * 1024 ** 3
MAX_SCHEMATIC_VOLUME = 512 * 1024 ** 2
DATA_VERSION = 4903  # Java 26.2, matching the bundled block-model/texture pack.
LEGACY_DATA_VERSION = 4189  # Java 1.21.4 in earlier SketchRip schematics.


@dataclass(frozen=True)
class Match:
    block: str
    similarity: float
    texture: str
    candidates: tuple[str, ...] = ()
    ambiguous: bool = False


def block_for_texture(texture: str, blocks: set[str]) -> str | None:
    """Choose a Java block identifier from a vanilla texture name."""
    aliases = {
        "grass_block_side_overlay": "grass_block", "grass_block_snow": "grass_block",
        "water_still": "water", "water_flow": "water", "lava_still": "lava",
        "lava_flow": "lava", "oak_leaves_opaque": "oak_leaves",
        "snow": "snow_block", "smooth_stone_slab_side": "smooth_stone",
        "dirt_path_side": "dirt_path", "dirt_path_top": "dirt_path",
    }
    name = aliases.get(texture, texture)
    if name in blocks:
        return "minecraft:" + name
    for suffix in ("_side", "_top", "_bottom", "_front", "_back", "_end",
                   "_inner", "_outer", "_up", "_down", "_on", "_off",
                   "_north", "_east", "_south", "_west", "_overlay"):
        if name.endswith(suffix) and name[:-len(suffix)] in blocks:
            return "minecraft:" + name[:-len(suffix)]
    for suffix in ("_side", "_top", "_bottom", "_front", "_back", "_end"):
        if name.endswith(suffix):
            return block_for_texture(name[:-len(suffix)], blocks)
    return None


def image_vector(image: Image.Image) -> np.ndarray:
    """Bounded 8x8 RGBA descriptor; normalizes texture resolution."""
    return np.asarray(image.convert("RGBA").resize((8, 8), Image.Resampling.BOX),
                      dtype=np.uint8).reshape(-1)


class TextureIndex:
    """Cached palette, exact descriptor hashing and fast nearest-neighbor fallback."""

    def __init__(self, pack: Path, cache_dir: Path | None = None,
                 atlas_map: Path | None = None,
                 match_mode: str = "auto", catalog_mode: str = "model",
                 material_policy: str = "logical"):
        if match_mode not in ("auto", "fast", "calibrated"):
            raise ValueError("match_mode must be auto, fast or calibrated")
        if catalog_mode not in ("legacy", "model"):
            raise ValueError("catalog_mode must be legacy or model")
        if material_policy not in ("logical", "nearest"):
            raise ValueError("material_policy must be logical or nearest")
        self.material_policy = material_policy
        self.catalog_mode = catalog_mode
        self.match_mode = match_mode
        self.fast_approx = match_mode == "fast"
        pack = pack.expanduser().resolve()
        manual_atlas_map = atlas_map is not None
        # Atlas pixel overrides belong to one known export. The old implicit
        # FFA-specific override file polluted unrelated Mineways scenes because
        # identical sprite hashes can refer to different source materials.
        # Only apply explicitly selected, user-audited atlas maps.
        self.atlas_overrides = {}
        self.manual_atlas_map = manual_atlas_map
        if atlas_map is not None:
            if not atlas_map.is_file():
                raise FileNotFoundError(f"Atlas override map not found: {atlas_map}")
            payload = json.loads(atlas_map.read_text(encoding="utf-8"))
            self.atlas_overrides = payload.get("entries", {})
        if not pack.is_file():
            raise FileNotFoundError(f"Vanilla texture ZIP not found: {pack}")
        fingerprint = hashlib.sha256(pack.read_bytes()).hexdigest()[:20]
        cache_dir = cache_dir or (Path.home() / ".cache" / "chunkdaddy" / "sketchrip")
        cache_dir.mkdir(parents=True, exist_ok=True)
        self.texture_candidates = {}
        if catalog_mode == "legacy":
            cache_path = cache_dir / f"textures-v4-{fingerprint}.npz"
            if cache_path.is_file():
                with np.load(cache_path, allow_pickle=False) as data:
                    self.descriptors = data["descriptors"]
                    self.descriptors16 = data["descriptors16"]
                    self.blocks = data["blocks"].tolist()
                    self.names = data["names"].tolist()
            else:
                descriptors, descriptors16, names, block_ids = [], [], [], []
                with zipfile.ZipFile(pack) as archive:
                    paths = archive.namelist()
                    valid = {Path(p).stem for p in paths
                             if p.startswith("assets/minecraft/blockstates/") and p.endswith(".json")}
                    for path in paths:
                        if not path.startswith(TEXTURE_PREFIX) or not path.endswith(".png"):
                            continue
                        name = Path(path).stem
                        block = block_for_texture(name, valid)
                        if block is None:
                            continue
                        with Image.open(io.BytesIO(archive.read(path))) as source:
                            # Vertical animation strips store the first 16x16 frame at the top.
                            tile_size = source.width
                            sprite = source.crop((0, 0, tile_size, min(tile_size, source.height)))
                            descriptors.append(image_vector(sprite))
                            descriptors16.append(np.asarray(sprite.convert("RGBA").resize(
                                (16, 16), Image.Resampling.BOX), dtype=np.uint8).reshape(-1))
                        names.append(name)
                        block_ids.append(block)
                if not descriptors:
                    raise ValueError("ZIP contains no compatible vanilla block textures")
                self.descriptors = np.stack(descriptors)
                self.descriptors16 = np.stack(descriptors16)
                self.blocks = block_ids
                self.names = names
                np.savez_compressed(cache_path, descriptors=self.descriptors,
                                    descriptors16=self.descriptors16,
                                    blocks=np.asarray(self.blocks), names=np.asarray(self.names))
        else:
            # v5 includes all block sprites and uses blockstate/model references to
            # retain candidate families. The old filename-heuristic index silently
            # excluded hundreds of sprites and guessed derivative block names.
            from sketchrip_texture_catalog import build_catalog, preferred_block
            from sketchrip_texture_catalog import model_texture_candidates
            cache_path = cache_dir / f"textures-v5-{fingerprint}.npz"
            self.texture_candidates = {}
            if cache_path.is_file():
                with np.load(cache_path, allow_pickle=False) as data:
                    self.descriptors = data["descriptors"]
                    self.descriptors16 = data["descriptors16"]
                    self.blocks = data["blocks"].tolist()
                    self.names = data["names"].tolist()
                with zipfile.ZipFile(pack) as archive:
                    refs, _ = model_texture_candidates(archive)
                    self.texture_candidates = {k: tuple(sorted(v)) for k, v in refs.items()}
            else:
                catalog = build_catalog(pack)
                with zipfile.ZipFile(pack) as archive:
                    valid = {Path(p).stem for p in archive.namelist()
                             if p.startswith("assets/minecraft/blockstates/") and p.endswith(".json")}
                descriptors, descriptors16, names, block_ids = [], [], [], []
                for name, sprite in catalog.sprites.items():
                    block = preferred_block(name, catalog.candidates.get(name, ()), valid)
                    if block is None:
                        continue
                    descriptors.append(image_vector(sprite))
                    descriptors16.append(np.asarray(sprite, dtype=np.uint8).reshape(-1))
                    names.append(name)
                    block_ids.append(block)
                self.texture_candidates = catalog.candidates
                if not descriptors:
                    raise ValueError("ZIP contains no compatible vanilla block textures")
                self.descriptors = np.stack(descriptors)
                self.descriptors16 = np.stack(descriptors16)
                self.blocks = block_ids
                self.names = names
                np.savez_compressed(cache_path, descriptors=self.descriptors,
                                    descriptors16=self.descriptors16,
                                    blocks=np.asarray(self.blocks), names=np.asarray(self.names))
        self.exact = collections.defaultdict(list)
        self.exact16 = collections.defaultdict(list)
        for i, entry in enumerate(self.descriptors):
            self.exact[entry.tobytes()].append(i)
            self.exact16[self.descriptors16[i].tobytes()].append(i)
        self.nearest = None
        with contextlib.suppress(ImportError):
            from scipy.spatial import cKDTree
            self.nearest = cKDTree(self.descriptors.astype(np.float32))
        self.tile_cache: dict[bytes, Match] = {}
        self.logical_selections: dict[str, dict] = {}
        self.robust_matcher = None

    def _record_logical_selection(self, selection) -> None:
        if selection.reason is None:
            return
        original = self.blocks[selection.original_index]
        selected = self.blocks[selection.index]
        key = original + " -> " + selected
        entry = self.logical_selections.setdefault(key, {"from": original, "to": selected,
                                                        "texture_before": self.names[selection.original_index],
                                                        "texture_after": self.names[selection.index],
                                                        "unique_tiles": 0})
        entry["unique_tiles"] += 1

    def _fast_rotated_match(self, image: Image.Image, count: int) -> Match:
        """Fast full-pixel texture matching in all four Minecraft UV rotations.

        Mineways packs rotated 16x16 sprites. Comparing only the original
        orientation makes patterned stone and planks look like plain wool.
        KNN runs on four 8x8 descriptors; the final score uses all RGBA pixels.
        """
        from sketchrip_material_policy import choose

        original = np.asarray(image.resize((16, 16), Image.Resampling.BOX), dtype=np.uint8)
        closest = {}
        for rotation in range(4):
            rotated = np.rot90(original, rotation).copy()
            # Pixel-perfect rotations are valid exact matches too. These are
            # not penalized as 'visually similar' decorative materials.
            hits = self.exact16.get(rotated.tobytes())
            if hits:
                unique = {self.blocks[i] for i in hits}
                selected = hits[0]
                return Match(self.blocks[selected], 1.0 if len(unique) == 1 else 0.82,
                             self.names[selected],
                             self.texture_candidates.get(self.names[selected], ()),
                             len(unique) > 1)
            coarse = image_vector(Image.fromarray(rotated, "RGBA")).astype(np.float32)
            if self.nearest is not None:
                _, indices = self.nearest.query(coarse, k=min(count, len(self.names)))
                indices = np.atleast_1d(indices).astype(np.int32)
            else:
                diff = self.descriptors.astype(np.float32) - coarse
                distance = np.einsum("ij,ij->i", diff, diff)
                indices = np.argpartition(distance, min(count - 1, len(distance) - 1))[:count]
            difference = self.descriptors16[indices].astype(np.float32) - rotated.reshape(-1)
            distances = np.einsum("ij,ij->i", difference, difference)
            for index, distance in zip(indices, distances):
                i = int(index)
                if i not in closest or distance < closest[i]:
                    closest[i] = float(distance)

        indices = np.fromiter(closest, dtype=np.int32)
        distances = np.fromiter(closest.values(), dtype=np.float64)
        ranking = np.argsort(distances)
        selection = choose(distances, indices, self.names, self.blocks) \
            if self.material_policy == "logical" else None
        selected = int(selection.index) if selection is not None else int(indices[ranking[0]])
        if selection is not None:
            self._record_logical_selection(selection)
        d = float(distances[np.where(indices == selected)[0][0]])
        similarity = max(0.0, 1.0 - np.sqrt(d) / (255.0 * np.sqrt(original.size)))
        second = next((i for i in indices[ranking] if self.blocks[int(i)] != self.blocks[selected]), None)
        ambiguous = (second is not None and abs(np.sqrt(float(distances[np.where(indices == second)[0][0]]))
                                               - np.sqrt(d)) < 7.5)
        if ambiguous:
            similarity = min(similarity, 0.82)
        return Match(self.blocks[selected], float(similarity), self.names[selected],
                     self.texture_candidates.get(self.names[selected], ()), ambiguous)

    def match(self, image: Image.Image) -> Match:
        """Match complete texels first; refine nearby 8x8 descriptors at 16x16.

        An 8x8-only match blurred patterned stone into other block types. Distinct
        Minecraft sprites that quantize to the same coarse RGB descriptor must be
        disambiguated by their full pixel pattern, or flagged as genuinely ambiguous.
        """
        image = image.convert("RGBA")
        coarse = image_vector(image)
        full = np.asarray(image.resize((16, 16), Image.Resampling.BOX),
                          dtype=np.uint8).reshape(-1)
        key = full.tobytes()
        existing = self.tile_cache.get(key)
        if existing is not None:
            return existing
        # Pixel-exact vanilla sprites take priority over visual guesses in the
        # FFA atlas override table. Entries remain overridable only for pixels
        # genuinely different from all vanilla sprites.
        if key in self.exact16 and not (self.manual_atlas_map and image.size == (16, 16)
                                        and hashlib.sha256(np.asarray(image).tobytes()).hexdigest()
                                        in self.atlas_overrides):
            matches = self.exact16[key]
            index = matches[0]
            block_types = {self.blocks[i] for i in matches}
            similarity = 1.0 if len(matches) == 1 else 0.82
            answer = Match(self.blocks[index], similarity, self.names[index],
                           self.texture_candidates.get(self.names[index], ()),
                           len(matches) != 1)
        else:
            override = None
            if image.size == (16, 16):
                sprite_hash = hashlib.sha256(np.asarray(image).tobytes()).hexdigest()
                override = self.atlas_overrides.get(sprite_hash)
            if override is not None:
                block = override["block"]
                if not re.fullmatch(r"minecraft:[a-z0-9_]+(?:\[[a-z0-9_=,]+\])?", block):
                    raise ValueError(f"Invalid atlas override block: {block!r}")
                answer = Match(block, 1.0, "atlas-override:" + override.get("note", "review required"))
            else:
                # The calibrated matcher searches rotated sprites and compares
                # full RGB structure, alpha and plausible lighting gains. Keep
                # the exact identity distinct from a block type/state hypothesis.
                if self.catalog_mode == "legacy" or self.fast_approx:
                    answer = self._fast_rotated_match(image,
                                                      64 if self.catalog_mode == "legacy" else 96)
                else:
                    if self.robust_matcher is None:
                        from sketchrip_texture_classifier import CalibratedTextureMatcher
                        self.robust_matcher = CalibratedTextureMatcher(self)
                    prediction = self.robust_matcher.identify(image)
                    similarity = min(prediction.confidence, 0.82) if prediction.ambiguous else prediction.confidence
                    answer = Match(prediction.block, similarity, prediction.texture,
                                   self.texture_candidates.get(prediction.texture, ()),
                                   prediction.ambiguous)
        self.tile_cache[key] = answer
        return answer


def safe_extract(source: Path, destination: Path) -> None:
    with zipfile.ZipFile(source) as archive:
        files = archive.infolist()
        if len(files) > 10000 or sum(x.file_size for x in files) > MAX_ARCHIVE_BYTES:
            raise ValueError("Model archive exceeds safety limits")
        for item in files:
            member = Path(item.filename)
            if member.is_absolute() or ".." in member.parts or (item.external_attr >> 16) & 0o170000 == 0o120000:
                raise ValueError(f"Unsafe model archive member: {item.filename}")
            output = destination / member
            if item.is_dir():
                output.mkdir(parents=True, exist_ok=True)
            else:
                output.parent.mkdir(parents=True, exist_ok=True)
                with archive.open(item) as src, output.open("wb") as dest:
                    shutil.copyfileobj(src, dest)


def download_sketchfab(url: str, output: Path, token: str) -> dict:
    """Use only Sketchfab's authorized Download API, never viewer-cache extraction."""
    import requests

    parsed = urlparse(url)
    if parsed.hostname not in {"sketchfab.com", "www.sketchfab.com"}:
        raise ValueError("Only sketchfab.com model URLs are accepted")
    found = MODEL_ID.search(parsed.path)
    if found is None:
        raise ValueError("Sketchfab URL must contain a 32-character model ID")
    uid = found.group(1)
    endpoint = f"https://api.sketchfab.com/v3/models/{uid}"
    session = requests.Session()
    metadata = session.get(endpoint, timeout=30)
    metadata.raise_for_status()
    model = metadata.json()
    if not model.get("isDownloadable", False):
        raise PermissionError("Sketchfab model is not marked downloadable. Supply an authorized local mesh or world/schematic instead.")
    if not token:
        raise PermissionError("Download requires SKETCHFAB_TOKEN (authorized Sketchfab API access).")
    authorization = {"Authorization": f"Bearer {token}"}
    request = session.get(endpoint + "/download", headers=authorization, timeout=30)
    if request.status_code in (401, 403):
        raise PermissionError("Sketchfab download rejected these credentials or this model.")
    request.raise_for_status()
    archive_url = request.json().get("gltf", {}).get("url")
    if not archive_url:
        raise ValueError("Sketchfab did not offer a glTF archive for this model")
    output.parent.mkdir(parents=True, exist_ok=True)
    with session.get(archive_url, stream=True, timeout=60) as response:
        response.raise_for_status()
        size = 0
        with output.open("wb") as destination:
            for chunk in response.iter_content(1 << 20):
                size += len(chunk)
                if size > MAX_ARCHIVE_BYTES:
                    raise ValueError("Sketchfab download exceeds 2 GiB limit")
                destination.write(chunk)
    return {"title": model.get("name"), "author": model.get("user", {}).get("displayName"),
            "license": model.get("license", {}).get("label"), "url": url, "uid": uid}


def find_sfrip(command: str | None = None) -> list[str]:
    """Use an explicit command, a repo-local npm install, or the user's PATH."""
    if command:
        executable = shutil.which(command) or (str(Path(command).resolve()) if Path(command).is_file() else None)
        if executable:
            if executable.lower().endswith(".mjs"):
                node = shutil.which("node")
                if not node:
                    raise FileNotFoundError("Node.js 18+ is required to run sfrip.mjs")
                return [node, executable]
            return [executable]
        raise FileNotFoundError(f"sfrip executable not found: {command}")

    root = Path(__file__).resolve().parent.parent
    local_script = root / ".sketchrip-tools" / "node_modules" / "sfrip" / "sfrip.mjs"
    node = shutil.which("node")
    if local_script.is_file() and node:
        return [node, str(local_script)]
    external = shutil.which("sfrip")
    if external:
        return [external]
    raise FileNotFoundError(
        "sfrip is not installed. Run SKETCHRIP_SETUP.bat (Windows), "
        "or 'npm install --prefix .sketchrip-tools sfrip@0.1.0' from the repo root. "
        "Node.js 18+ is required. You can still import local GLB files without sfrip.")


def acquire_sfrip(url: str, output_dir: Path, command: str | None = None,
                  merge: str = "objects", max_edge: int = 2048,
                  timeout: int = 3600) -> tuple[Path, dict]:
    """Invoke sfrip as an independent, replaceable Sketchfab acquisition backend.

    This integration does not bundle third-party code or assume that every viewer
    upload is compatible. The caller is responsible for model usage rights.
    """
    parsed = urlparse(url)
    if parsed.scheme != "https" or parsed.hostname not in {"sketchfab.com", "www.sketchfab.com"}:
        raise ValueError("sfrip acquisition requires an HTTPS sketchfab.com URL")
    if MODEL_ID.search(parsed.path) is None:
        raise ValueError("Sketchfab URL has no valid 32-character model UID")
    if merge not in {"off", "objects", "groups", "materials", "all"}:
        raise ValueError("Unknown sfrip merge mode")
    if max_edge not in (0, 2048, 4096):
        raise ValueError("sfrip texture limit must be 0, 2048 or 4096")
    output_dir.mkdir(parents=True, exist_ok=True)
    argv = [*find_sfrip(command), url, "--out", str(output_dir),
            f"--merge={merge}", "--yes", "--quiet", "--no-color"]
    if max_edge:
        argv.extend(["--max-edge", str(max_edge)])
    print("Acquiring textured GLB through sfrip...", file=sys.stderr, flush=True)
    try:
        run = subprocess.run(argv, text=True, encoding="utf-8", errors="replace",
                             capture_output=True, timeout=timeout, check=False)
    except subprocess.TimeoutExpired as exc:
        raise TimeoutError(f"sfrip exceeded its {timeout}s timeout") from exc
    if run.returncode:
        raise RuntimeError(f"sfrip failed (exit {run.returncode}): " +
                           (run.stderr or run.stdout)[-2500:].strip())
    paths = [Path(line.strip().strip('"')) for line in run.stdout.splitlines() if line.strip().endswith(".glb")]
    if len(paths) != 1:
        raise RuntimeError("sfrip did not report exactly one GLB path. " +
                           (run.stderr or run.stdout)[-1200:])
    glb = paths[0].resolve()
    if not glb.is_relative_to(output_dir.resolve()) or not glb.is_file() or glb.stat().st_size < 20:
        raise RuntimeError("sfrip reported a missing or unsafe output GLB")
    # Never trust a path printed by another program until the GLB container header
    # and declared length have been checked. This also catches truncated downloads.
    with glb.open("rb") as source_file:
        header = source_file.read(12)
    magic, version, length = struct.unpack("<4sII", header)
    if magic != b"glTF" or version != 2 or length != glb.stat().st_size:
        raise RuntimeError("sfrip returned a malformed or incomplete GLB container")
    info = glb.parent / "info.json"
    details = json.loads(info.read_text(encoding="utf-8")) if info.is_file() else {}
    if details.get("textures_skipped") or details.get("textures_failed") or details.get("geometry_count") == 0:
        raise RuntimeError("sfrip returned incomplete geometry/textures. See " + str(info))
    return glb, {"backend": "sfrip", "url": url, "author": details.get("author"),
                 "title": details.get("name"), "uid": details.get("uid"),
                 "raw_info": str(info), "geometry_count": details.get("geometry_count")}


def guess_scale(meshes: list, override: float | None) -> float:
    """Infer a real lattice interval, never a logarithmic histogram bin centre.

    Sketchfab normalizes the bounding box of Mineways scenes. On the FFA arena
    the raw edges cluster around 1/142, while the old log-bin centre was
    0.00701058, enough to displace the far border by more than a block.
    """
    if override is not None:
        if not np.isfinite(override) or override <= 0:
            raise ValueError("--scale must be a positive finite model-units-per-block value")
        return override
    edges = []
    bounds = []
    for mesh in meshes:
        if len(mesh.vertices):
            bounds.append(np.asarray(mesh.bounds, dtype=np.float64))
    for mesh in meshes[:8]:
        faces = np.asarray(mesh.faces[:120000], dtype=np.int64)
        if not len(faces):
            continue
        triangles = np.asarray(mesh.vertices)[faces]
        sides = np.abs(np.concatenate((triangles[:, 1] - triangles[:, 0],
                                       triangles[:, 2] - triangles[:, 0]), axis=0)).ravel()
        edges.append(sides[np.isfinite(sides) & (sides > 1e-7)])
    if not edges or not bounds:
        raise ValueError("Model contains no measurable mesh edges")
    values = np.concatenate(edges)
    rounded = np.round(np.log2(values) * 32).astype(np.int32)
    bins, counts = np.unique(rounded, return_counts=True)
    approximate = float(2.0 ** (bins[counts.argmax()] / 32.0))
    # The mode selects a frequency neighbourhood, NOT a representative edge.
    inlier = values[(values > approximate * 0.94) & (values < approximate * 1.06)]
    if len(inlier) < max(16, len(values) // 100):
        raise ValueError("No convincing Minecraft unit edge length")
    representative = float(np.median(inlier))
    start = np.min([b[0] for b in bounds], axis=0)
    end = np.max([b[1] for b in bounds], axis=0)
    span = end - start
    # Recover the original integer voxel span, improving sub-percent calibration.
    candidates = []
    for extent in span:
        dimensions = round(float(extent) / representative)
        if dimensions <= 0:
            continue
        candidate = float(extent) / dimensions
        if abs(candidate / representative - 1.0) < 0.025:
            candidates.append(candidate)
    if not candidates:
        return representative
    return float(np.median(candidates))


def atlas_parent_tile(rect: tuple[int, int, int, int], image: Image.Image) -> tuple[int, int, int, int]:
    """Expand a cropped Mineways UV fragment to its original 16x16 tile.

    The particular Mineways atlas in the supplied model uses a 16px cell with a
    2px gutter (x starts 1+18n, y starts -1+18n). Requiring all corners to fall
    inside the tile avoids incorrectly snapping arbitrary textures or UV seams.
    """
    x0, y0, x1, y1 = rect
    if image.size != (1024, 1024):
        return rect
    tx = 1 + 18 * ((x0 - 1) // 18)
    ty = -1 + 18 * ((y0 + 1) // 18)
    if (0 <= tx and 0 <= ty and tx + 16 <= 1024 and ty + 16 <= 1024
            and x0 >= tx and y0 >= ty and x1 <= tx + 16 and y1 <= ty + 16):
        return tx, ty, tx + 16, ty + 16
    return rect

def material_image(mesh) -> Image.Image | None:
    material = getattr(getattr(mesh, "visual", None), "material", None)
    if material is None:
        return None
    image = getattr(material, "baseColorTexture", None)
    if image is None:
        image = getattr(material, "image", None)
    if image is None:
        return None
    if isinstance(image, Image.Image):
        return image.convert("RGBA")
    return Image.fromarray(np.asarray(image)).convert("RGBA")


def face_tile(image: Image.Image, triangle_uv: np.ndarray) -> tuple[int, int, int, int] | None:
    if not np.isfinite(triangle_uv).all():
        return None
    low = triangle_uv.min(axis=0)
    high = triangle_uv.max(axis=0)
    width, height = image.size
    x0 = int(round(float(low[0]) * width))
    x1 = int(round(float(high[0]) * width))
    y0 = int(round((1.0 - float(high[1])) * height))
    y1 = int(round((1.0 - float(low[1])) * height))
    if x1 <= x0 or y1 <= y0 or x1 - x0 > 256 or y1 - y0 > 256:
        return None
    # Atlases may wrap UVs at 1.0, but rectangles crossing a wrap seam are ambiguous.
    if x0 < 0 or y0 < 0 or x1 > width or y1 > height:
        return None
    return x0, y0, x1, y1


def merged_face_cells(triangle: np.ndarray, triangle_uv: np.ndarray,
                      axis: int, origin: np.ndarray, unit: float,
                      image: Image.Image, textures: TextureIndex,
                      cache: dict, similarity: float) -> tuple[list, int]:
    """Rasterize a merged coplanar face into lattice cells, sampling UVs per block.

    The half-voxel test means each cell is visited once across a triangle pair
    except for cells exactly on the shared diagonal, where weighted voting is harmless.
    """
    other = [i for i in range(3) if i != axis]
    plane = (triangle[:, other] - origin[other]) / unit
    low = np.floor(plane.min(axis=0)).astype(int)
    high = np.ceil(plane.max(axis=0)).astype(int)
    size = (high - low).clip(0)
    if np.prod(size) > 250000:
        raise ValueError("A merged face exceeds 250,000 grid cells; supply a subdivided GLB")
    if 0 in size:
        return [], 0
    a, b, c = plane
    denominator = (b[1] - c[1]) * (a[0] - c[0]) + (c[0] - b[0]) * (a[1] - c[1])
    if abs(denominator) < 1e-8:
        return [], 0
    # Grid cell centers in the two in-plane dimensions.
    grid = np.indices((int(size[0]), int(size[1])), dtype=float).reshape(2, -1).T
    grid += low + 0.5
    w0 = ((b[1] - c[1]) * (grid[:, 0] - c[0]) +
          (c[0] - b[0]) * (grid[:, 1] - c[1])) / denominator
    w1 = ((c[1] - a[1]) * (grid[:, 0] - c[0]) +
          (a[0] - c[0]) * (grid[:, 1] - c[1])) / denominator
    keep = (w0 >= -1e-6) & (w1 >= -1e-6) & (w0 + w1 <= 1 + 1e-6)
    cells = grid[keep] - 0.5
    if not len(cells):
        return [], 0

    # Evaluate the affine UV field from the three corners. UV follows glTF's
    # bottom-left convention, while numpy images start in the top-left.
    affine = np.linalg.solve(np.column_stack((plane, np.ones(3))), triangle_uv)
    delta_uv = affine[:2]
    base_uv = (cells + 0.5) @ delta_uv + affine[2]
    src = np.asarray(image.convert("RGBA"))
    h, w = src.shape[:2]
    offsets = (np.arange(8, dtype=np.float64) + 0.5) / 8 - 0.5
    rows, cols = np.meshgrid(offsets, offsets, indexing="ij")
    offsets2 = np.stack((cols, rows), axis=-1)
    offsets_uv = offsets2 @ delta_uv
    xy_uv = np.empty((8, 8, 2), dtype=np.float64)
    answers = []
    for cell, center_uv in zip(cells.astype(int), base_uv):
        xy_uv[:] = center_uv + offsets_uv
        # glTF repeat wrap; texture atlas UVs wholly inside [0,1] are unaffected.
        px = np.clip(np.floor(np.mod(xy_uv[..., 0], 1.0) * w).astype(int), 0, w - 1)
        py = np.clip(np.floor((1.0 - np.mod(xy_uv[..., 1], 1.0)) * h).astype(int), 0, h - 1)
        # When the UV center belongs to a known Mineways atlas cell, match the
        # complete 16x16 cell instead of an arbitrary cropped triangle fragment.
        # Keep the general UV-sampled path for other images and tiled textures.
        sample_rect = (int(px[4, 4]), int(py[4, 4]), int(px[4, 4]) + 1, int(py[4, 4]) + 1)
        parent = atlas_parent_tile(sample_rect, image)
        if parent != sample_rect:
            tile = src[parent[1]:parent[3], parent[0]:parent[2]]
        else:
            tile = src[py, px]
        signature = tile.tobytes()
        match = cache.get(signature)
        if match is None:
            match = textures.match(Image.fromarray(tile, "RGBA"))
            cache[signature] = match
        answers.append(((int(cell[0]), int(cell[1])), match))
    return answers, len(cells)


def reconstruct(meshes: list, textures: TextureIndex, scale: float | None = None,
                min_similarity: float = 0.83, up_axis: str = "y",
                allow_uncertain: bool = False) -> tuple[dict[tuple[int, int, int], str], dict]:
    import trimesh

    if up_axis not in ("y", "z"):
        raise ValueError("--up-axis must be y or z")
    face_count = sum(len(m.faces) for m in meshes)
    if textures.match_mode == "auto":
        textures.fast_approx = face_count >= 250000
    unit = guess_scale(meshes, scale)
    # Input coordinate order is x/y/z; output schematic order is x/y/z, Y vertical.
    extents = [np.asarray(m.bounds) for m in meshes if len(m.vertices)]
    minimum = np.min([bounds[0] for bounds in extents], axis=0)
    votes: dict[tuple[int, int, int], dict[str, float]] = {}
    # End-grain and bark evidence is separate from the nearest-sprite vote.
    # Correct species only when a real end-grain texture anchors the trunk.
    from sketchrip_material_context import LOG_END, reconcile_wood
    log_end_grain = collections.defaultdict(dict)
    log_side_tiles = collections.defaultdict(dict)

    def record_log_evidence(point, match, dominant_axis, texture_tile, weight):
        texture = match.texture
        if match.similarity >= .94 and dominant_axis == 1:
            found = LOG_END.fullmatch(texture)
            if found:
                grain = log_end_grain[point]
                grain[found.group(1)] = max(grain.get(found.group(1), 0.0), match.similarity)
        if texture.endswith(("_log", "_log_side", "_wood", "_side"))                 and match.block.endswith(("_log", "_wood")):
            side = log_side_tiles[point]
            side[texture_tile] = side.get(texture_tile, 0.0) + weight
    counts = collections.Counter()
    uncertain = collections.Counter()
    atlas_cache: dict[tuple[int, int, int, int, int], Match] = {}
    merged_cache: dict[bytes, Match] = {}
    covered_triangles = 0

    # sfrip may emit separate OPAQUE and BLEND materials for the SAME RGB atlas.
    # The BLEND copy has an artificial per-pixel opacity factor, corrupting
    # reverse texture lookup if treated as a distinct source texture. Group by
    # exact RGB content, then use the atlas retaining the most source alpha.
    canonical_images = {}
    for mesh in meshes:
        image = material_image(mesh)
        if image is None:
            continue
        pixels = np.asarray(image)
        rgb_key = hashlib.blake2b(pixels[:, :, :3].tobytes(), digest_size=16).digest()
        alpha_weight = int(pixels[:, :, 3].sum(dtype=np.int64))
        old = canonical_images.get(rgb_key)
        if old is None or alpha_weight > old[0]:
            canonical_images[rgb_key] = (alpha_weight, image)
    counts["canonical_texture_atlases"] = len(canonical_images)

    # Backwards-compatible FFA-specific atlas patches, but ONLY when the
    # complete source atlas RGB fingerprint matches the audited FFA source.
    # The old implicit overrides applied to every Sketchfab world and poisoned
    # unrelated atlases sharing superficially similar 16x16 sprites.
    if not textures.manual_atlas_map and bytes.fromhex("5f069e3434e26e01e8a9caf393ac7b0d") in canonical_images:
        old_map = (Path(__file__).resolve().parent.parent / "assets" / "sketchrip"
                   / "ffa-arena-atlas-overrides.json")
        if old_map.is_file():
            textures.atlas_overrides = json.loads(old_map.read_text(encoding="utf-8")).get("entries", {})
            counts["ffa_atlas_overrides_verified_source"] = len(textures.atlas_overrides)

    for mesh_index, mesh in enumerate(meshes):
        image = material_image(mesh)
        if image is not None:
            pixels = np.asarray(image)
            rgb_key = hashlib.blake2b(pixels[:, :, :3].tobytes(), digest_size=16).digest()
            replacement = canonical_images.get(rgb_key)
            if replacement is not None:
                if int(pixels[:, :, 3].sum(dtype=np.int64)) < replacement[0]:
                    counts["canonicalized_material_faces"] += len(mesh.faces)
                image = replacement[1]
        uv = getattr(mesh.visual, "uv", None)
        if image is None or uv is None:
            counts["no_texture_faces"] += len(mesh.faces)
            continue
        uv = np.asarray(uv)
        faces = np.asarray(mesh.faces, dtype=np.int64)
        vertices = np.asarray(mesh.vertices, dtype=np.float64)
        if len(uv) != len(vertices):
            counts["invalid_uv_faces"] += len(faces)
            continue
        # Multiple meshes may share the same 1024x1024 image. Grouping the
        # cache by its actual object image identity avoids redundant comparisons.
        for start in range(0, len(faces), 50000):
            indices = faces[start:start + 50000]
            tri = vertices[indices]
            cross = np.cross(tri[:, 1] - tri[:, 0], tri[:, 2] - tri[:, 0])
            length = np.linalg.norm(cross, axis=1)
            dominant = np.argmax(np.abs(cross), axis=1)
            axes = np.arange(len(indices))
            aligned = (length > 1e-12) & (np.abs(cross[axes, dominant]) / np.maximum(length, 1e-12) > 0.985)
            minimums = tri.min(axis=1)
            maximums = tri.max(axis=1)
            spans = (maximums - minimums) / unit
            # Fast path for ordinary triangles; merged Mineways faces use rasterization.
            small = np.sum(spans > 1.10, axis=1) == 0
            centers = (tri.mean(axis=1) - minimum) / unit
            voxel = np.floor(centers + 1e-5).astype(np.int32)
            planes = (tri[:, 0, :] - minimum) / unit
            plane_indices = np.rint(planes[axes, dominant]).astype(np.int32)
            grid_aligned = np.abs(planes[axes, dominant] - plane_indices) < 0.09
            # Interior faces of slabs, stairs, fences and panes legitimately sit
            # at fractional coordinates. Only non-axis-aligned faces are excluded.
            counts["partial_block_faces"] += int(np.count_nonzero(aligned & ~grid_aligned))
            counts["non_axis_faces"] += int(np.count_nonzero(~aligned))
            positive = cross[axes, dominant] > 0
            adjusted = np.floor(planes[axes, dominant] - positive.astype(float) * 0.0001).astype(np.int32)
            # Boundary planes are snapped exactly; interior planes stay within
            # the containing block coordinate instead of being discarded.
            voxel[axes, dominant] = np.where(grid_aligned,
                                                plane_indices - positive.astype(np.int32), adjusted)
            if up_axis == "z":
                voxel = voxel[:, [0, 2, 1]]
            batch_uv = uv[indices]
            for j in np.nonzero(aligned & small)[0]:
                point = tuple(int(x) for x in voxel[j])
                rect = face_tile(image, batch_uv[j])
                if rect is None:
                    counts["invalid_tile_faces"] += 1
                    continue
                rect = atlas_parent_tile(rect, image)
                signature = (id(image), *rect)
                match = atlas_cache.get(signature)
                if match is None:
                    match = textures.match(image.crop(rect))
                    atlas_cache[signature] = match
                if match.texture.startswith("atlas-override:"):
                    counts["atlas_override_faces"] += 1
                if match.similarity < min_similarity:
                    uncertain[(match.texture, round(match.similarity, 2))] += 1
                    counts["uncertain_faces"] += 1
                    if not allow_uncertain:
                        continue
                bucket = votes.setdefault(point, {})
                weight = float(length[j]) * max(match.similarity, 0.01)
                bucket[match.block] = bucket.get(match.block, 0.0) + weight
                record_log_evidence(point, match, int(dominant[j]) if up_axis == "y"
                                    else (2 if dominant[j] == 1 else 1 if dominant[j] == 2 else 0),
                                    signature, weight)
                counts["mapped_faces"] += 1
                covered_triangles += 1
            # Minecraft flowers, tall grass and crossed plant planes are often
            # diagonal in XZ even though the block grid itself is not. Recover
            # the containing cell only for known plant-like materials; slanted
            # roof/terrain polygons remain excluded, never voxelized blindly.
            plant_names = {"short_grass", "tall_grass", "fern", "large_fern", "dead_bush",
                           "vine", "red_tulip", "dandelion", "oxeye_daisy", "poppy",
                           "lilac", "rose_bush", "azure_bluet", "sweet_berry_bush",
                           "azalea", "flowering_azalea", "sunflower", "grass", "seagrass"}
            for j in np.nonzero((~aligned) & small)[0]:
                rect = face_tile(image, batch_uv[j])
                if rect is None:
                    continue
                rect = atlas_parent_tile(rect, image)
                signature = (id(image), *rect)
                match = atlas_cache.get(signature)
                if match is None:
                    match = textures.match(image.crop(rect))
                    atlas_cache[signature] = match
                name = match.block.partition(":")[2].split("[")[0]
                if name not in plant_names:
                    continue
                if match.similarity < min_similarity and not allow_uncertain:
                    counts["uncertain_crossed_plant_faces"] += 1
                    continue
                point3 = np.floor((tri[j].mean(axis=0) - minimum) / unit + 1e-5).astype(np.int32)
                if up_axis == "z":
                    point3 = point3[[0, 2, 1]]
                point = tuple(int(v) for v in point3)
                bucket = votes.setdefault(point, {})
                bucket[match.block] = bucket.get(match.block, 0.0) + max(float(match.similarity), 0.01)
                counts["mapped_crossed_plant_faces"] += 1
                covered_triangles += 1
                if match.texture.startswith("atlas-override:"):
                    counts["atlas_override_faces"] += 1

            # Merged coplanar quads may span dozens of blocks and must not be
            # ignored. Sample the texel coordinates of every exposed grid cell.
            for j in np.nonzero(aligned & ~small)[0]:
                try:
                    found, total = merged_face_cells(tri[j], batch_uv[j], int(dominant[j]),
                                                     minimum, unit, image, textures,
                                                     merged_cache, min_similarity)
                except np.linalg.LinAlgError:
                    counts["degenerate_uv_faces"] += 1
                    continue
                counts["merged_cells_seen"] += total
                mapped = False
                for (a, b), match in found:
                    if match.texture.startswith("atlas-override:"):
                        counts["atlas_override_merged_cells"] += 1
                    if match.similarity < min_similarity:
                        counts["uncertain_merged_cells"] += 1
                        uncertain[(match.texture, round(match.similarity, 2))] += 1
                        if not allow_uncertain:
                            continue
                    voxel_point = np.zeros(3, dtype=int)
                    in_plane = [i for i in range(3) if i != int(dominant[j])]
                    voxel_point[in_plane] = (a, b)
                    voxel_point[int(dominant[j])] = (plane_indices[j] - int(positive[j])
                                                        if grid_aligned[j] else adjusted[j])
                    if up_axis == "z":
                        voxel_point = voxel_point[[0, 2, 1]]
                    point = tuple(int(x) for x in voxel_point)
                    bucket = votes.setdefault(point, {})
                    weight = max(match.similarity, 0.01)
                    bucket[match.block] = bucket.get(match.block, 0.0) + weight
                    record_log_evidence(point, match, int(dominant[j]) if up_axis == "y"
                                        else (2 if dominant[j] == 1 else 1 if dominant[j] == 2 else 0),
                                        match.texture, weight)
                    counts["merged_cells_mapped"] += 1
                    mapped = True
                if mapped:
                    covered_triangles += 1
    if not votes:
        raise ValueError("No textured, axis-aligned Minecraft block faces could be reconstructed")
    blocks = {point: max(scores, key=scores.get) for point, scores in votes.items()}
    blocks, log_context_report = reconcile_wood(blocks, log_side_tiles, log_end_grain)
    report = {"scale_model_units_per_block": unit, "counts": dict(counts),
              "voxel_count": len(blocks), "wood_grain_reconciliation": log_context_report, "unique_atlas_tiles": len(atlas_cache) + len(merged_cache),
              "block_counts": dict(collections.Counter(blocks.values())),
              "model_bounds": {"min": minimum.tolist(),
                               "dimensions_blocks": ((np.max([b[1] for b in extents], axis=0) - minimum) / unit).round(3).tolist()},
              "texture_matcher": "fast" if textures.fast_approx else "calibrated",
              "indexed_textures": len(textures.names),
              "uncertain": [{"texture": key[0], "similarity": key[1], "faces": value}
                            for key, value in uncertain.most_common(50)]}
    report["face_coverage"] = (covered_triangles / max(1, sum(len(m.faces) for m in meshes)))
    report["texture_catalog"] = textures.catalog_mode
    report["logical_material_prior"] = {
        "policy": textures.material_policy,
        "unique_tile_reassignments": sorted(textures.logical_selections.values(),
                                            key=lambda item: -item["unique_tiles"])[:100],
    }
    report["requires_review"] = (bool(uncertain) or report["face_coverage"] < 0.90
                                 or counts["partial_block_faces"] > 0 or counts["atlas_override_faces"] > 0)
    return blocks, report


def nbt_name(name: str) -> bytes:
    data = name.encode("utf-8")
    return struct.pack(">H", len(data)) + data


def nbt_int(name: str, value: int) -> bytes:
    return b"\x03" + nbt_name(name) + struct.pack(">i", value)


def nbt_short(name: str, value: int) -> bytes:
    return b"\x02" + nbt_name(name) + struct.pack(">h", value if value < 32768 else value - 65536)


def nbt_int_array(name: str, values: tuple[int, ...]) -> bytes:
    return b"\x0b" + nbt_name(name) + struct.pack(">i", len(values)) + struct.pack(f">{len(values)}i", *values)


def nbt_compound(name: str, content: bytes) -> bytes:
    return b"\x0a" + nbt_name(name) + content + b"\x00"


def nbt_byte_array(name: str, data: bytes) -> bytes:
    return b"\x07" + nbt_name(name) + struct.pack(">i", len(data)) + data


def encode_varints(palette_indices: np.ndarray) -> bytes:
    # Most schematics have <128 palette entries, so one byte per block dominates.
    if palette_indices.max(initial=0) < 128:
        return palette_indices.astype(np.uint8).tobytes()
    out = bytearray()
    for value in palette_indices.flat:
        item = int(value)
        while item >= 128:
            out.append((item & 127) | 128)
            item >>= 7
        out.append(item)
    return bytes(out)


def write_schematic(blocks: dict[tuple[int, int, int], str], path: Path) -> tuple[int, int, int]:
    minimum = np.min(np.array(list(blocks), dtype=np.int32), axis=0)
    maximum = np.max(np.array(list(blocks), dtype=np.int32), axis=0)
    dimensions = (maximum - minimum + 1).tolist()
    x, y, z = (int(v) for v in dimensions)
    volume = x * y * z
    if any(v > 65535 for v in dimensions) or volume > MAX_SCHEMATIC_VOLUME:
        raise ValueError(f"Schematic too large: {x}x{y}x{z} ({volume} blocks)")
    palette = ["minecraft:air", *sorted({v for v in blocks.values() if v != "minecraft:air"})]
    mapping = {block: i for i, block in enumerate(palette)}
    data = np.zeros(volume, dtype=np.uint16 if len(palette) <= 65535 else np.uint32)
    for (bx, by, bz), block in blocks.items():
        xx, yy, zz = bx - int(minimum[0]), by - int(minimum[1]), bz - int(minimum[2])
        data[xx + zz * x + yy * x * z] = mapping[block]
    palette_body = b"".join(nbt_int(key, value) for key, value in mapping.items())
    content = (nbt_int("Version", 3) + nbt_int("DataVersion", DATA_VERSION)
               + nbt_short("Width", x) + nbt_short("Height", y) + nbt_short("Length", z)
               + nbt_int_array("Offset", (0, 0, 0))
               + nbt_compound("Blocks", nbt_compound("Palette", palette_body)
                              + nbt_byte_array("Data", encode_varints(data))
                              + b"\x09" + nbt_name("BlockEntities") + b"\x0a" + struct.pack(">i", 0)))
    path.parent.mkdir(parents=True, exist_ok=True)
    with gzip.open(path, "wb", compresslevel=4) as file:
        file.write(nbt_compound("", nbt_compound("Schematic", content)))
    return x, y, z


def retag_sketchrip_schematic(source: Path, destination: Path) -> tuple[int, int]:
    """Upgrade the Java source DataVersion of an existing SketchRip v3 schematic.

    Only the exact, named NBT int tag written by SketchRip is modified. Old source
    files are never overwritten. A non-SketchRip or unexpectedly versioned file
    must be inspected instead of silently having its version metadata falsified.
    This does not convert block states; it selects Chunker's matching resolver.
    """
    with gzip.open(source, "rb") as file:
        payload = bytearray(file.read())
    marker = b"\x03\x00\x0bDataVersion"
    if payload.count(marker) != 1:
        raise ValueError("Expected exactly one SketchRip Sponge v3 DataVersion int tag")
    offset = payload.find(marker) + len(marker)
    if len(payload) < offset + 4:
        raise ValueError("Truncated schematic DataVersion")
    old_version = struct.unpack_from(">i", payload, offset)[0]
    if old_version not in (DATA_VERSION, LEGACY_DATA_VERSION):
        raise ValueError(f"Schematic DataVersion {old_version} was not authored by this SketchRip "
                         "build; refusing to rewrite unknown version metadata")
    if old_version != DATA_VERSION:
        struct.pack_into(">i", payload, offset, DATA_VERSION)
    destination.parent.mkdir(parents=True, exist_ok=True)
    with gzip.open(destination, "wb", compresslevel=4) as file:
        file.write(payload)
    return old_version, DATA_VERSION


def export_world(schematic: Path, output: Path, name: str, profile: str,
                 classpath: str, java: str, world_y: int, workspace: Path) -> dict:
    from chunkdaddy_cli import Worker, progress_printer

    worker = Worker(classpath, workspace, java=java, jvm_args=["-Xmx6g"])
    try:
        document = worker.send({"type": "new_document", "name": name, "profileId": profile})
        result = worker.send({"type": "import_schematics", "paths": [str(schematic)]},
                             on_progress=progress_printer("schematic"), timeout=900)
        if result.get("failures") or len(result.get("templates", [])) != 1:
            raise ValueError("Schematic import failed: " + json.dumps(result.get("failures")))
        template = result["templates"][0]
        if template.get("issues"):
            raise ValueError("Chunker reported block mapping issues: " + json.dumps(template["issues"])[:1800])
        worker.send({"type": "place_grid", "documentId": document["documentId"],
                     "placements": [{"templateId": template["templateId"], "slug": template["slug"],
                                     "minX": 0, "minY": world_y, "minZ": 0, "ordinal": 1}]})
        check = worker.send({"type": "validate_export", "documentId": document["documentId"],
                             "profileId": profile})
        if check.get("problems"):
            raise ValueError("World validation failed: " + json.dumps(check["problems"]))
        return worker.send({"type": "export_world", "documentId": document["documentId"],
                            "destination": str(output), "mode": "MCWORLD", "worldName": name,
                            "profileId": profile, "voidCleaner": True,
                            "writeVoidColumns": False, "writeCompanionJson": False},
                           on_progress=progress_printer("export"), timeout=3600)
    finally:
        worker.close()


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("model", nargs="?", help="Sketchfab URL, GLB/glTF/OBJ/ZIP, or an existing SketchRip .schem")
    parser.add_argument("--output", "-o", type=Path, help=".mcworld destination (default ~/Downloads)")
    parser.add_argument("--textures", type=Path, help="Vanilla assets ZIP with block textures")
    parser.add_argument("--atlas-map", type=Path, help="JSON file mapping exact 16x16 RGBA sprite fingerprints to block names")
    parser.add_argument("--scale", type=float, help="model-space units per Minecraft block (default auto)")
    parser.add_argument("--up-axis", choices=("y", "z"), default="y")
    parser.add_argument("--similarity", type=float, default=0.83)
    parser.add_argument("--material-policy", choices=("logical", "nearest"), default="logical",
                        help="logical favors ordinary materials over approximate shelf/bed/shulker matches")
    parser.add_argument("--texture-catalog", choices=("legacy", "model"), default="legacy",
                        help="legacy preserves prior arena behavior; model is experimental full sprite coverage")
    parser.add_argument("--texture-matcher", choices=("auto", "fast", "calibrated"),
                        default="auto", help="use fast KNN for large maps, calibrated matcher for smaller meshes")
    parser.add_argument("--allow-uncertain", action="store_true")
    parser.add_argument("--refine-schematic", type=Path,
                        help="reuse an existing SketchRip schematic and rerun only geometry state inference")
    parser.add_argument("--stair-orientation-only", action="store_true",
                        help="refine only existing stair facing/half/shape from internal GLB surfaces; requires --refine-schematic")
    parser.add_argument("--require-exact-stairs", action="store_true",
                        help="refuse Bedrock export if observed stair footprints cannot all be reproduced by its target-version topology")
    parser.add_argument("--precision-shapes", action="store_true",
                        help="opt-in signed stair/slab geometry and conservative plank-to-fence inference")
    parser.add_argument("--precision-no-slabs", action="store_true",
                        help="with --precision-shapes, skip slab top/bottom state corrections")
    parser.add_argument("--precision-no-fences", action="store_true",
                        help="with --precision-shapes, skip plank-to-fence proposals")
    parser.add_argument("--infer-states", action="store_true",
                        help="experimental high-confidence geometry refinement for slabs, stairs, panes, ladders, etc.")
    parser.add_argument("--world-y", type=int, default=64)
    parser.add_argument("--profile", default="bedrock-1.26.0",
                        choices=("bedrock-1.26.0", "bedrock-1.26.40", "bedrock-1.26.50",
                                 "bedrock-1.21.130"))
    default_jar = Path(__file__).resolve().parent.parent / "worker" / "build" / "dist" / "chunkdaddy-worker.jar"
    parser.add_argument("--classpath", default=os.environ.get("CHUNKDADDY_CLASSPATH")
                        or (str(default_jar) if default_jar.exists() else None),
                        help="Java worker classpath (or CHUNKDADDY_CLASSPATH)")
    parser.add_argument("--java", default=os.environ.get("CHUNKDADDY_JAVA", "java"))
    parser.add_argument("--dry-run", action="store_true", help="produce .schem and report only")
    parser.add_argument("--acquire", choices=("auto", "sfrip", "official"), default="auto",
                        help="Sketchfab acquisition method (auto prefers sfrip when installed)")
    parser.add_argument("--sfrip-command", help="path to a custom sfrip executable")
    parser.add_argument("--sfrip-merge", default="objects",
                        choices=("off", "objects", "groups", "materials", "all"))
    parser.add_argument("--sfrip-max-edge", type=int, default=2048, choices=(0, 2048, 4096))
    parser.add_argument("--acquire-timeout", type=int, default=3600)
    parser.add_argument("--keep-acquired", type=Path,
                        help="store the GLB and its info.json after downloading (directory)")
    args = parser.parse_args(argv)
    model = args.model or input("Sketchfab URL or local mesh: ").strip()
    if not model:
        parser.error("Model URL or mesh is required")
    if args.acquire_timeout <= 0:
        parser.error("--acquire-timeout must be positive")
    if not 0.0 <= args.similarity <= 1.0:
        parser.error("--similarity must be between 0 and 1")
    identifier = MODEL_ID.search(urlparse(model).path) if model.startswith(("https://", "http://")) else None
    source_name = (Path(urlparse(model).path).stem if identifier else Path(model).stem)
    output_name = re.sub(r"[^a-zA-Z0-9_.-]+", "_", source_name).strip("._-")[:70] or "sketchrip"
    output = (args.output or (Path.home() / "Downloads" / (output_name + ".mcworld"))).expanduser().resolve()
    if output.suffix.lower() != ".mcworld":
        parser.error("--output must end with .mcworld")
    if not args.dry_run and not args.classpath:
        parser.error("--classpath or CHUNKDADDY_CLASSPATH is required for world export")
    if args.world_y % 16 != 0:
        parser.error("--world-y must be chunk-aligned (a multiple of 16)")
    # Re-export an already reconstructed SketchRip schematic. This bypasses both
    # texture matching and the 1.5M-triangle GLB load on converter-only retries.
    if not model.startswith(("https://", "http://")) and Path(model).suffix.lower() == ".schem":
        schematic = Path(model).expanduser().resolve()
        if not schematic.is_file():
            raise FileNotFoundError(f"Schematic not found: {schematic}")
        with tempfile.TemporaryDirectory(prefix="chunkdaddy-sketchrip-export-") as folder:
            staging = Path(folder)
            repaired = staging / "retagged.schem"
            before, after = retag_sketchrip_schematic(schematic, repaired)
            print(f"Schematic source Java DataVersion: {before} -> {after}", file=sys.stderr)
            # Keep the corrected schematic next to the requested output for inspection.
            corrected = output.with_suffix(".schem")
            if schematic != corrected:
                shutil.copyfile(repaired, corrected)
            else:
                # The user selected the existing stem as output. Never overwrite
                # their input; write a clearly named upgraded copy instead.
                corrected = output.with_name(output.stem + "-java26.2.schem")
                shutil.copyfile(repaired, corrected)
            print(f"Compatible schematic: {corrected}", file=sys.stderr)
            if args.dry_run:
                return 0
            result = export_world(repaired, output, output.stem, args.profile,
                                  args.classpath, args.java, args.world_y,
                                  staging / "worker")
            print(json.dumps(result, indent=2))
            return 0

    pack = args.textures
    if pack is None:
        bundled = Path(__file__).resolve().parent.parent / "assets" / "sketchrip" / "vanilla-26.2.zip"
        choices = list(Path.cwd().glob("*vanilla*texture*.zip"))
        if bundled.is_file():
            pack = bundled
        elif len(choices) == 1:
            pack = choices[0]
        elif sys.stdin.isatty():
            pack = Path(input("Vanilla texture pack ZIP: ").strip())
        else:
            parser.error("Pass --textures path/to/vanilla.zip")
    import trimesh

    with tempfile.TemporaryDirectory(prefix="chunkdaddy-sketchrip-") as folder:
        staging = Path(folder)
        source = Path(model).expanduser()
        attribution = {"local_model": str(source.resolve())}
        if model.startswith(("https://", "http://")):
            acquire = args.acquire
            if acquire == "auto":
                try:
                    find_sfrip(args.sfrip_command)
                    acquire = "sfrip"
                except FileNotFoundError:
                    acquire = "official" if os.environ.get("SKETCHFAB_TOKEN") else "sfrip"
            if acquire == "sfrip":
                source, attribution = acquire_sfrip(
                    model, staging / "acquired", args.sfrip_command,
                    args.sfrip_merge, args.sfrip_max_edge, args.acquire_timeout)
            else:
                source = staging / "sketchfab.zip"
                attribution = download_sketchfab(model, source, os.environ.get("SKETCHFAB_TOKEN", ""))
                attribution["backend"] = "official"
            if args.keep_acquired:
                preserved = args.keep_acquired.expanduser().resolve()
                preserved.mkdir(parents=True, exist_ok=True)
                if source.suffix.lower() == ".glb":
                    persistent = preserved / (attribution.get("uid", "model") + ".glb")
                    shutil.copyfile(source, persistent)
                    if (source.parent / "info.json").is_file():
                        shutil.copyfile(source.parent / "info.json", preserved / (persistent.stem + ".info.json"))
                else:
                    shutil.copyfile(source, preserved / (attribution.get("uid", "model") + ".zip"))
        if not source.is_file():
            raise FileNotFoundError(f"Model asset not found: {source}")
        if source.suffix.lower() == ".zip":
            extracted = staging / "model"
            safe_extract(source, extracted)
            candidates = sorted(extracted.rglob("*.gltf")) + sorted(extracted.rglob("*.glb"))
            if not candidates:
                raise ValueError("Archive does not contain a glTF scene or GLB file")
            source = candidates[0]
        print(f"Loading geometry: {source}", file=sys.stderr)
        scene = trimesh.load(str(source), force="scene", process=False)
        meshes = [mesh for mesh in scene.dump(concatenate=False)
                  if isinstance(mesh, trimesh.Trimesh)]
        if not meshes:
            raise ValueError("Model contains no triangle meshes")
        if args.refine_schematic is not None:
            # Fast geometry-only retries do not reclassify the million source
            # triangles against the texture atlas. Preserve existing block IDs.
            from sketchrip_schematic_read import read_sketchrip_schematic
            reference = args.refine_schematic.expanduser().resolve()
            if not reference.is_file():
                raise FileNotFoundError(f"Schematic not found: {reference}")
            print(f"Reading existing reconstructed blocks: {reference}", file=sys.stderr)
            blocks = read_sketchrip_schematic(reference)
            report = {"source": attribution, "refinement_input": str(reference),
                      "scale_model_units_per_block": guess_scale(meshes, args.scale),
                      "counts": {"input_blocks": len(blocks)},
                      "requires_review": True}
            print(f"Refining geometry over {sum(len(m.faces) for m in meshes):,} triangles...", file=sys.stderr)
        else:
            print(f"Matching textures across {sum(len(m.faces) for m in meshes):,} triangles...", file=sys.stderr)
            blocks, report = reconstruct(meshes, TextureIndex(pack, atlas_map=args.atlas_map,
                                                      match_mode=args.texture_matcher,
                                                      catalog_mode=args.texture_catalog,
                                                      material_policy=args.material_policy), args.scale,
                                         args.similarity, args.up_axis, args.allow_uncertain)
            report["source"] = attribution
        if args.precision_shapes and args.refine_schematic is None:
            args.infer_states = True
        if args.stair_orientation_only and args.refine_schematic is None:
            parser.error("--stair-orientation-only requires --refine-schematic")
        if args.infer_states or args.refine_schematic is not None:
            if args.stair_orientation_only:
                if args.precision_shapes:
                    from sketchrip_stair_orientation import infer as refine_stairs
                else:
                    from sketchrip_stair_orientation_legacy import infer as refine_stairs
                reoriented, orientation_report = refine_stairs(
                    meshes, blocks, pack, report["scale_model_units_per_block"], args.up_axis)
                blocks.update(reoriented)
                report["stair_orientation_refinement"] = orientation_report
                if reoriented:
                    report["requires_review"] = True
                report["block_counts"] = dict(collections.Counter(blocks.values()))
            else:
                from sketchrip_state_inference import infer
                changes, shape_report = infer(meshes, blocks, pack,
                                              report["scale_model_units_per_block"], args.up_axis)
                blocks.update(changes)
                # Slanted lever/torch faces were discarded by the axis-plane model.
                # Refine only recognized directional small-block families using a
                # bounded per-voxel 3D surface signature, leaving weak cases alone.
                from sketchrip_small_shapes import infer as infer_small_shapes
                directional, directional_report = infer_small_shapes(
                    meshes, blocks, pack, report["scale_model_units_per_block"], args.up_axis)
                blocks.update(directional)
                report["small_block_orientation"] = directional_report
                report["geometry_state_inference"] = shape_report
                if changes or directional:
                    report["requires_review"] = True
                report["block_counts"] = dict(collections.Counter(blocks.values()))
                # Refine the selected stair orientations after coarse shape inference.
                # Only strong interior treads/risers can change facing or half.
                if args.precision_shapes:
                    from sketchrip_stair_orientation import infer as refine_stairs
                else:
                    from sketchrip_stair_orientation_legacy import infer as refine_stairs
                reoriented, orientation_report = refine_stairs(
                    meshes, blocks, pack, report["scale_model_units_per_block"], args.up_axis)
                blocks.update(reoriented)
                report["stair_orientation_refinement"] = orientation_report
                if reoriented:
                    report["requires_review"] = True
                report["block_counts"] = dict(collections.Counter(blocks.values()))
            # Wood fencing has narrow posts and rails on both horizontal axes.
            # Only promote a plank to a fence when its local geometry supports
            # a specific connection model, never on texture similarity alone.
            if args.precision_shapes and not args.stair_orientation_only and not args.precision_no_fences:
                from sketchrip_fence_precision import infer as infer_wood_fences
                fence_changes, fence_report = infer_wood_fences(
                    meshes, blocks, pack, report["scale_model_units_per_block"], args.up_axis)
                blocks.update(fence_changes)
                report["fence_geometry_inference"] = fence_report
                if fence_changes:
                    report["requires_review"] = True
                    report["block_counts"] = dict(collections.Counter(blocks.values()))
            # Half-height triangles carry a signed normal that separates top
            # slabs from bottom slabs. Keep this separate from approximate
            # full-model cosine matching so a slab cannot flip on a name tie.
            if args.precision_shapes and not args.stair_orientation_only and not args.precision_no_slabs:
                from sketchrip_slab_precision import infer as correct_slab_halves
                calibration = report.get("stair_orientation_refinement", {}).get("winding", {})
                winding = {"flipped": 1, "original": -1,
                           "flipped_from_exterior": 1}.get(calibration.get("stair_surface_winding"))
                slab_changes, slab_report = correct_slab_halves(
                    meshes, blocks, report["scale_model_units_per_block"], args.up_axis,
                    winding_override=winding)
                blocks.update(slab_changes)
                report["slab_half_refinement"] = slab_report
                if slab_changes:
                    report["requires_review"] = True
                    report["block_counts"] = dict(collections.Counter(blocks.values()))
        # Roof faces at integer Y belong to the stair immediately below. The
        # old internal-riser histogram omits those faces, losing the strongest
        # orientation/inner-vs-outer evidence in open stair recesses.
        if args.precision_shapes and (args.infer_states or args.refine_schematic is not None):
            from sketchrip_stair_roof import infer as refine_stair_roofs
            roof_changes, roof_report = refine_stair_roofs(
                meshes, blocks, pack, report["scale_model_units_per_block"], args.up_axis)
            # Bedrock before 1.26.50 cannot store the Java `shape` as a
            # `minecraft:corner` property (see Chunker's STAIRS version group).
            # The client's neighborhood rules derive corners from adjacent
            # facings. Do not apply per-voxel roof guesses here: solve the
            # connected stair system so the exported facings reproduce the
            # observed GLB geometry in the target Bedrock version.
            if args.profile in ("bedrock-1.26.0", "bedrock-1.26.40"):
                from sketchrip_bedrock_stairs import solve as solve_bedrock_stairs
                topology_changes, topology_report = solve_bedrock_stairs(
                    blocks, roof_report, pack)
                blocks.update(topology_changes)
                report["bedrock_stair_topology"] = topology_report
                report["requires_review"] = bool(topology_changes) or report.get("requires_review", False)
            else:
                # In 1.26.50+ Chunker carries the corner state explicitly.
                blocks.update(roof_changes)
                report["requires_review"] = bool(roof_changes) or report.get("requires_review", False)
            report["stair_roof_refinement"] = roof_report
            report["block_counts"] = dict(collections.Counter(blocks.values()))
        report["texture_uncertainty_policy"] = ("Geometry-only: original texture guesses unchanged"
            if args.refine_schematic is not None else
            "Strictly block if >0.05% of faces are uncertain; smaller counts are exported with a review warning.")
        report_path = output.with_suffix(".sketchrip.json")
        report_path.parent.mkdir(parents=True, exist_ok=True)
        total_faces = sum(len(m.faces) for m in meshes)
        # Do not abort a multi-million-face arena for a few dozen residual
        # texture fragments. The report still records them and requires review.
        if report["counts"].get("uncertain_faces", 0) > max(16, int(total_faces * 0.0005)) and not args.allow_uncertain:
            report["status"] = "blocked_uncertain_textures"
            report_path.write_text(json.dumps(report, indent=2), encoding="utf-8")
            raise ValueError(f"{report['counts']['uncertain_faces']} faces had uncertain texture matches. "
                             f"Inspect {report_path} or rerun with --allow-uncertain.")
        destination = output.with_suffix(".schem")
        dimensions = write_schematic(blocks, destination)
        report["schematic_dimensions"] = dimensions
        report["schematic_path"] = str(destination)
        report["notes"] = ["Mesh surfaces do not reveal hidden blocks or exact original block states.",
                           "Atlas override mappings include visually inferred block species and always require review.",
                           "Fractional surfaces are assigned to parent voxels, but block shapes/states require additional inference.",
                           "Bedrock 1.26.0 writer compatibility requires client verification."]
        report_path.write_text(json.dumps(report, indent=2), encoding="utf-8")
        print(f"Schematic: {destination}\nReport: {report_path}", file=sys.stderr)
        # A source Java corner may not be representable in old Bedrock format:
        # the state is derived from neighbors, not stored independently.
        # Keep the diagnostics/schematic for inspection but refuse to claim
        # an exact .mcworld when the user explicitly requests strict parity.
        unsatisfied = report.get("bedrock_stair_topology", {}).get("counts", {}).get("inconsistent_observations", 0)
        if args.require_exact_stairs and unsatisfied:
            raise ValueError(f"{unsatisfied} stair components cannot reproduce the observed GLB footprints "
                             f"under {args.profile} neighborhood rules. Diagnostic: {report_path}. "
                             "Try Bedrock 1.26.50 (explicit corner state) if your clients can load it.")
        if args.dry_run:
            return 0
        result = export_world(destination, output, output.stem, args.profile,
                              args.classpath, args.java, args.world_y, staging / "worker")
        print(json.dumps(result, indent=2))
        return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except (OSError, ValueError, PermissionError, RuntimeError, TimeoutError) as error:
        print(f"SketchRip: {error}", file=sys.stderr)
        sys.exit(1)
