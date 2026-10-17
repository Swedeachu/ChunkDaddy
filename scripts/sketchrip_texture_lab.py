#!/usr/bin/env python3
"""Deterministic random block-texture stress testing for ChunkDaddy SketchRip.

The benchmark never reads a source filename from the query image. It counts
strict texture identity, separately from the weaker claim that a face tells
us which block family or visible state it belongs to.
"""

from __future__ import annotations

import argparse
import collections
import csv
import hashlib
import io
import json
import random
import sys
import time
from pathlib import Path

import numpy as np
from PIL import Image

from sketchrip import TextureIndex, write_schematic
from sketchrip_texture_catalog import build_catalog, load_cinnabar_vocabulary
from sketchrip_texture_classifier import CalibratedTextureMatcher


DEFAULT_PACK = Path(__file__).resolve().parent.parent / "assets/sketchrip/vanilla-26.2.zip"
MODES = ("exact", "atlas", "rotate", "jpeg90", "jpeg70", "brightness", "tint")


def transformed(sprite: Image.Image, mode: str, rng: random.Random) -> Image.Image:
    if mode == "exact":
        return sprite.copy()
    if mode == "atlas":
        # Put the tile in a Mineways-style cell surrounded by an opaque gutter.
        # The benchmark intentionally reads only the inner 16x16 pixels.
        canvas = Image.new("RGBA", (54, 54), (31, 64, 127, 255))
        canvas.paste(sprite, (19, 19))
        return canvas.crop((19, 19, 35, 35))
    if mode == "rotate":
        return sprite.rotate(rng.choice((90, 180, 270)))
    if mode in {"jpeg90", "jpeg70"}:
        buffer = io.BytesIO()
        sprite.convert("RGB").save(buffer, "JPEG", quality=int(mode[4:]), subsampling=0)
        buffer.seek(0)
        with Image.open(buffer) as decoded:
            return decoded.convert("RGBA")
    pixels = np.asarray(sprite).copy().astype(np.float32)
    if mode == "brightness":
        gains = rng.uniform(0.72, 1.28)
    elif mode == "tint":
        gains = np.asarray([rng.uniform(0.80, 1.20) for _ in range(3)])
    else:
        raise ValueError("Unknown transform mode: " + mode)
    pixels[:, :, :3] = np.clip(pixels[:, :, :3] * gains, 0, 255)
    return Image.fromarray(pixels.astype(np.uint8), "RGBA")


def benchmark(pack: Path, output: Path, cinnabar: Path | None, samples: int,
              seed: int, modes: tuple[str, ...] = MODES) -> dict:
    started = time.perf_counter()
    rng = random.Random(seed)
    index = TextureIndex(pack, output / "cache")
    matcher = CalibratedTextureMatcher(index)
    catalog = build_catalog(pack)
    visual_groups = collections.defaultdict(list)
    for name in index.names:
        visual_groups[catalog.sprites[name].tobytes()].append(name)
    distinguishable = [name for name in index.names
                       if len(visual_groups[catalog.sprites[name].tobytes()]) == 1]
    opaque = [name for name in distinguishable if np.all(
        np.asarray(catalog.sprites[name])[:, :, 3] == 255)]
    counters = collections.defaultdict(collections.Counter)
    wrong = []
    cases = []
    for mode in modes:
        eligible = opaque if mode.startswith("jpeg") else distinguishable
        for _ in range(samples):
            source = rng.choice(eligible)
            tile = transformed(catalog.sprites[source], mode, rng)
            result = matcher.identify(tile)
            correct = result.texture == source
            accepted = not result.ambiguous
            stats = counters[mode]
            stats["total"] += 1
            stats["correct"] += int(correct)
            stats["accepted"] += int(accepted)
            stats["accepted_correct"] += int(accepted and correct)
            stats["confident_wrong"] += int(accepted and not correct)
            stats["ambiguous"] += int(result.ambiguous)
            if not correct:
                record = {"mode": mode, "source": source,
                          "prediction": result.texture, "accepted": accepted,
                          "confidence": round(result.confidence, 4),
                          "margin": round(result.margin, 4)}
                wrong.append(record)
                if len(cases) < 500:
                    cases.append(record)
        print(f"{mode}: {counters[mode]['correct']}/{samples} correct, "
              f"{counters[mode]['confident_wrong']} confident errors", flush=True)
    raw = {}
    for mode, counter in counters.items():
        total = counter["total"]
        accepted = counter["accepted"]
        raw[mode] = {**dict(counter),
                     "accuracy": round(counter["correct"] / total, 6),
                     "accepted_precision": round(counter["accepted_correct"] / accepted, 6)
                     if accepted else None,
                     "accepted_coverage": round(accepted / total, 6)}
    cinnabar_data = None
    if cinnabar:
        vocabulary = load_cinnabar_vocabulary(cinnabar)
        supported = set(index.blocks)
        valid = supported & vocabulary["block"]
        cinnabar_data = {"source": "Cinnabar Bedrock v1.26.50 vocabulary",
                         "bedrock_blocks": len(vocabulary["block"]),
                         "bedrock_terrain_keys": len(vocabulary["terrain_texture"]),
                         "java_default_blocks_in_bedrock_vocabulary": len(valid),
                         "java_default_blocks_not_in_bedrock_vocabulary": sorted(supported - valid)}
    result = {
        "version": 1,
        "seed": seed,
        "samples_per_mode": samples,
        "texture_pack_sha256": hashlib.sha256(pack.read_bytes()).hexdigest(),
        "index_entries": len(index.names),
        "distinct_sprite_fingerprints": len(visual_groups),
        "distinguishable_texture_identities": len(distinguishable),
        "visually_duplicate_texture_groups": sorted(
            [list(v) for v in visual_groups.values() if len(v) > 1],
            key=lambda v: (-len(v), v[0])),
        "opaque_distinguishable_sprites": len(opaque),
        "metrics": raw,
        "confusions": collections.Counter((r["mode"], r["source"], r["prediction"])
                                       for r in wrong).most_common(100),
        "cinnabar": cinnabar_data,
        "elapsed_seconds": round(time.perf_counter() - started, 3),
        "interpretation": (
            "Synthetic, independent random sprite-image tests from the supplied vanilla pack. "
            "A correct texture identity is NOT proof of a correct block ID/state in GLB meshes. "
            "JPEG modes are limited to opaque sources because JPEG discards alpha. "
            "Visually identical sprites are removed from strict unique-identity accuracy and "
            "listed separately. Tint and brightness are synthetic stressors, not calibrated "
            "estimates of actual Mineways texture transformations."),
    }
    output.mkdir(parents=True, exist_ok=True)
    (output / "texture-lab-results.json").write_text(json.dumps(result, indent=2), encoding="utf-8")
    with (output / "texture-lab-confusions.csv").open("w", newline="", encoding="utf-8") as file:
        writer = csv.DictWriter(file, fieldnames=["mode", "source", "prediction", "accepted", "confidence", "margin"])
        writer.writeheader()
        writer.writerows(wrong)
    return result


def randomized_world(pack: Path, output: Path, seed: int, count: int) -> dict:
    """Generate ground-truth plain-block cells for true Mineways/sfrip validation."""
    import math
    index = TextureIndex(pack, output / "cache")
    plain = sorted(set(block for name, block in zip(index.names, index.blocks)
                       if name == block.removeprefix("minecraft:")
                       and block not in {"minecraft:air", "minecraft:water", "minecraft:lava"}))
    if not plain:
        raise ValueError("No plain block identities were found")
    rng = random.Random(seed)
    width = int(math.ceil(math.sqrt(count)))
    blocks = {}
    expected = []
    for i in range(count):
        x = (i % width) * 2
        z = (i // width) * 2
        name = rng.choice(plain)
        blocks[(x, 0, z)] = name
        expected.append({"x": x, "y": 0, "z": z, "block": name})
    output.mkdir(parents=True, exist_ok=True)
    path = output / "randomized-source.schem"
    dimensions = write_schematic(blocks, path)
    manifest = {"seed": seed, "block_count": count,
                "unique_types": len(set(blocks.values())), "dimensions": dimensions,
                "block_palette": plain, "expected": expected,
                "note": "Real exported GLB inverse reconstruction must be evaluated separately; "
                        "this known-answer schematic does not contain an exported mesh."}
    (output / "randomized-source.json").write_text(json.dumps(manifest, indent=2), encoding="utf-8")
    return {"schematic": str(path), "types": manifest["unique_types"], "dimensions": dimensions}


def randomized_mesh(pack: Path, output: Path, seed: int, count: int) -> dict:
    """Known-answer, unoptimized GLB atlas mesh to exercise production reconstruction.

    This intentionally tests *full-cube texture->block ID* mapping, not stairs,
    leaves, Mineways rendering, or hidden-face reconstruction.
    """
    import math
    import trimesh

    catalog = build_catalog(pack)
    index = TextureIndex(pack, output / "cache")
    eligible = sorted(name for i, name in enumerate(index.names)
                      if index.blocks[i] == "minecraft:" + name and name in catalog.sprites
                      and name not in catalog.exact_visual_aliases)
    rng = random.Random(seed)
    if count > 20000:
        raise ValueError("Mesh fixture is capped at 20,000 cubes")
    chosen = ["green_terracotta", "bamboo_block", "stone_bricks", "stone", "oak_planks"]
    chosen = [x for x in chosen if x in eligible]
    chosen.extend(rng.choice(eligible) for _ in range(max(0, count - len(chosen))))
    chosen = chosen[:count]
    atlas = Image.new("RGBA", (1024, 1024), (0, 0, 0, 0))
    tex_to_rect = {}
    for i, name in enumerate(sorted(set(chosen))):
        row, col = divmod(i, 56)
        x0, y0 = 1 + 18 * col, 17 + 18 * row
        atlas.paste(catalog.sprites[name], (x0, y0))
        tex_to_rect[name] = (x0, y0)
    cube = trimesh.creation.box(extents=(1.0, 1.0, 1.0))
    base_vertices = cube.vertices[cube.faces].reshape(-1, 3)
    geometry = np.empty((len(chosen) * len(base_vertices), 3), dtype=np.float64)
    uv = np.empty((len(geometry), 2), dtype=np.float64)
    expected = {}
    width = math.ceil(math.sqrt(count))
    for i, name in enumerate(chosen):
        position = ((i % width) * 2, 0, (i // width) * 2)
        expected[position] = "minecraft:" + name
        start = i * len(base_vertices)
        end = start + len(base_vertices)
        geometry[start:end] = base_vertices + np.asarray(position) + 0.5
        x0, y0 = tex_to_rect[name]
        # Each triangle's UV bbox spans the same source atlas tile; exact-texture
        # identity is the measurement, not face-orientation conformance.
        triangle_uv = np.asarray([[x0 / 1024, 1 - (y0 + 16) / 1024],
                                  [(x0 + 16) / 1024, 1 - (y0 + 16) / 1024],
                                  [x0 / 1024, 1 - y0 / 1024]])
        uv[start:end] = np.tile(triangle_uv, (len(base_vertices) // 3, 1))
    mesh = trimesh.Trimesh(vertices=geometry,
                           faces=np.arange(len(geometry)).reshape(-1, 3),
                           visual=trimesh.visual.TextureVisuals(uv=uv, image=atlas),
                           process=False)
    output.mkdir(parents=True, exist_ok=True)
    glb_path = output / "randomized-textured-cubes.glb"
    trimesh.Scene([mesh]).export(glb_path)
    source_path = output / "randomized-textured-cubes.schem"
    write_schematic(expected, source_path)
    actual_mesh = trimesh.load(glb_path, force="scene", process=False).dump(concatenate=False)
    guesses, summary = __import__("sketchrip").reconstruct(actual_mesh, index, allow_uncertain=True)
    correct = sum(guesses.get(position) == name for position, name in expected.items())
    misclassified = [{"x": point[0], "y": point[1], "z": point[2],
                      "expected": name, "actual": guesses.get(point)}
                     for point, name in expected.items() if guesses.get(point) != name]
    audit = {"source_glb": str(glb_path), "source_schem": str(source_path),
             "seed": seed, "count": len(expected), "unique_block_types": len(set(expected.values())),
             "correct_block_ids": correct, "accuracy": correct / len(expected),
             "missing": sum(point not in guesses for point in expected),
             "extra_blocks": len(set(guesses) - set(expected)),
             "misclassified": misclassified[:250],
             "reconstruction_summary": summary,
             "limitations": "Full-cube synthetic atlas fixture, not a real Mineways-rendered Minecraft world."}
    (output / "randomized-mesh-evaluation.json").write_text(json.dumps(audit, indent=2), encoding="utf-8")
    return {"tested": len(expected), "correct": correct, "accuracy": audit["accuracy"],
            "missing": audit["missing"], "extra_blocks": audit["extra_blocks"]}


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--textures", type=Path, default=DEFAULT_PACK)
    parser.add_argument("--cinnabar", type=Path, help="Cinnabar source ZIP, for Bedrock ID cross-checks")
    parser.add_argument("--out", type=Path, default=Path("fixtures/texture-lab"))
    parser.add_argument("--samples", type=int, default=1000, help="Independent random samples per transform")
    parser.add_argument("--seed", type=int, default=20261015)
    parser.add_argument("--modes", default=",".join(MODES))
    parser.add_argument("--mesh-blocks", type=int, default=1000,
                        help="Generate a known-answer GLB and score the production importer (0 to skip)")
    parser.add_argument("--world-blocks", type=int, default=2500,
                        help="Generate a random-source .schem (0 to skip)")
    args = parser.parse_args(argv)
    if not 1 <= args.samples <= 1000000:
        parser.error("--samples must be between 1 and 1000000")
    if not 0 <= args.world_blocks <= 1000000:
        parser.error("--world-blocks must be between 0 and 1000000")
    if not 0 <= args.mesh_blocks <= 20000:
        parser.error("--mesh-blocks must be between 0 and 20000")
    modes = tuple(args.modes.split(","))
    if any(x not in MODES for x in modes):
        parser.error("--modes must be comma-separated from " + ",".join(MODES))
    results = benchmark(args.textures, args.out, args.cinnabar, args.samples, args.seed, modes)
    if args.world_blocks:
        print("Random block reference:", randomized_world(args.textures, args.out, args.seed,
                                                            args.world_blocks))
    if args.mesh_blocks:
        print("Real GLB reconstruction:", randomized_mesh(args.textures, args.out, args.seed,
                                                           args.mesh_blocks))
    print(json.dumps({"metrics": results["metrics"], "elapsed_seconds": results["elapsed_seconds"]}, indent=2))
    return 0


if __name__ == "__main__":
    sys.exit(main())
