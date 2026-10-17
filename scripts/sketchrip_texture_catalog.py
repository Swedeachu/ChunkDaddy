"""Texture-to-block references built from Minecraft JSON models and blockstates.

These are *candidate relationships*, not proof that a single face identifies a block.
A stair, slab and full block may all use the same source sprite.
"""

from __future__ import annotations

import collections
import io
import json
import re
import zipfile
from dataclasses import dataclass
from pathlib import Path

from PIL import Image


TEXTURE_PREFIX = "assets/minecraft/textures/block/"
MODEL_PREFIX = "assets/minecraft/models/"
STATE_PREFIX = "assets/minecraft/blockstates/"


@dataclass
class TextureCatalog:
    sprites: dict[str, Image.Image]
    candidates: dict[str, tuple[str, ...]]
    exact_visual_aliases: dict[str, tuple[str, ...]]
    missing_models: tuple[str, ...]


def model_texture_candidates(pack: zipfile.ZipFile) -> tuple[dict[str, set[str]], list[str]]:
    """Invert the authored blockstate -> model -> texture graph (including parents)."""
    members = set(pack.namelist())
    blockstates = sorted(p for p in members if p.startswith(STATE_PREFIX) and p.endswith(".json"))
    models = {}
    missing = set()

    def parse_model(ref: str) -> dict:
        if not isinstance(ref, str):
            return {}
        normalized = ref.removeprefix("minecraft:")
        if not normalized.startswith("block/"):
            return {}
        if normalized in models:
            return models[normalized]
        path = MODEL_PREFIX + normalized + ".json"
        if path not in members:
            missing.add(ref)
            models[normalized] = {}
            return {}
        try:
            record = json.loads(pack.read(path))
        except (ValueError, UnicodeDecodeError):
            missing.add(ref)
            models[normalized] = {}
            return {}
        parent = parse_model(record.get("parent", "")) if record.get("parent") else {}
        merged = {**parent, **record.get("textures", {})}
        models[normalized] = merged
        return merged

    inverted: dict[str, set[str]] = collections.defaultdict(set)
    for path in blockstates:
        block = "minecraft:" + Path(path).stem
        try:
            state = json.loads(pack.read(path))
        except (ValueError, UnicodeDecodeError):
            continue
        refs = []
        for value in state.get("variants", {}).values():
            refs.extend(value if isinstance(value, list) else [value])
        for entry in state.get("multipart", []):
            apply = entry.get("apply", [])
            refs.extend(apply if isinstance(apply, list) else [apply])
        for value in refs:
            if not isinstance(value, dict) or "model" not in value:
                continue
            textures = parse_model(value["model"])
            for material in textures.values():
                if not isinstance(material, str):
                    continue
                seen = set()
                while material.startswith("#") and material not in seen:
                    seen.add(material)
                    material = textures.get(material[1:], "")
                    if not isinstance(material, str):
                        break
                if not isinstance(material, str):
                    continue
                token = material.removeprefix("minecraft:")
                if token.startswith("block/"):
                    inverted[token.removeprefix("block/")].add(block)
    return inverted, sorted(missing)


def build_catalog(pack_path: Path) -> TextureCatalog:
    """Load all regular block textures, including those without name-based aliases."""
    pack_path = Path(pack_path)
    with zipfile.ZipFile(pack_path) as archive:
        refs, missing_models = model_texture_candidates(archive)
        sprites = {}
        for path in sorted(archive.namelist()):
            if not path.startswith(TEXTURE_PREFIX) or not path.endswith(".png"):
                continue
            token = Path(path).stem
            try:
                with Image.open(io.BytesIO(archive.read(path))) as source:
                    width = source.width
                    if width <= 0 or source.height < width:
                        continue
                    sprites[token] = source.crop((0, 0, width, width)).convert("RGBA").resize(
                        (16, 16), Image.Resampling.BOX)
            except (OSError, ValueError):
                continue
    aliases = collections.defaultdict(list)
    for name, image in sprites.items():
        aliases[image.tobytes()].append(name)
    exact_visual_aliases = {}
    for group in aliases.values():
        if len(group) > 1:
            for name in group:
                exact_visual_aliases[name] = tuple(sorted(group))
    return TextureCatalog(sprites, {k: tuple(sorted(v)) for k, v in refs.items()},
                          exact_visual_aliases, tuple(missing_models))


def load_cinnabar_vocabulary(archive: Path) -> dict[str, set[str]]:
    """Bedrock vocabulary for ID validation, not an invented Java/Bedrock crosswalk."""
    entry = "crates/assets/data/vanilla-pack-vocabulary-1.26.50.tsv"
    with zipfile.ZipFile(archive) as source:
        if entry not in source.namelist():
            raise ValueError("Cinnabar ZIP is missing its recorded v1.26.50 vocabulary")
        rows = source.read(entry).decode("utf-8").splitlines()
    grouped = collections.defaultdict(set)
    for row in rows:
        group, separator, key = row.partition("\t")
        if separator and group in {"block", "terrain_texture", "material"}:
            grouped[group].add(key)
    return dict(grouped)


def preferred_block(texture: str, candidates: tuple[str, ...], valid: set[str]) -> str | None:
    """Select a *default* block ID, keeping the complete candidate list separately."""
    from sketchrip import block_for_texture
    candidate_set = set(candidates)
    direct = "minecraft:" + texture
    if direct in candidate_set or direct.removeprefix("minecraft:") in valid:
        return direct
    inferred = block_for_texture(texture, valid)
    if inferred in candidate_set:
        return inferred
    if not candidate_set:
        return inferred
    # Texture-bearing plain blocks beat derivative geometries when their ID is
    # otherwise unknowable from one face (slab/stair/fence all use planks).
    def priority(name: str):
        return (any(name.endswith(suffix) for suffix in (
            "_stairs", "_slab", "_fence", "_fence_gate", "_wall", "_pane",
            "_door", "_trapdoor", "_button", "_pressure_plate")),
            abs(len(name) - len(direct)), name)
    return sorted(candidate_set, key=priority)[0]
