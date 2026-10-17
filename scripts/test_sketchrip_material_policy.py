"""Tests for real Mineways atlas sprites that were being mapped to furniture."""

from pathlib import Path
import sys
import numpy as np
import trimesh
from PIL import Image

sys.path.insert(0, str(Path(__file__).resolve().parent))
from sketchrip import TextureIndex
from sketchrip_material_policy import choose
from sketchrip_reference import VanillaAssets
from sketchrip_state_inference import infer

ROOT = Path(__file__).resolve().parent.parent
PACK = ROOT / "assets/sketchrip/vanilla-26.2.zip"
FIXTURES = ROOT / "fixtures/material-prior"


def test_real_arena_wood_and_wool_prior(tmp_path):
    logical = TextureIndex(PACK, cache_dir=tmp_path, match_mode="fast", catalog_mode="model")
    nearest = TextureIndex(PACK, cache_dir=tmp_path, match_mode="fast", catalog_mode="model",
                           material_policy="nearest")
    expectations = {
        "spruce_shelf_vs_stripped_log": ("spruce_planks", "spruce_planks"),
        "green_shulker_vs_wool": ("green_wool", "green_wool"),
        "black_shulker_vs_wool": ("black_shulker_box", "black_wool"),
        "bed_vs_wood": ("blue_bed", "oak_planks"),
    }
    for name, (old, new) in expectations.items():
        sample = Image.open(FIXTURES / (name + ".png"))
        assert nearest.match(sample).block == "minecraft:" + old
        assert logical.match(sample).block == "minecraft:" + new
    assert len(logical.logical_selections) >= 2


def test_exact_special_sprite_does_not_get_overridden(tmp_path):
    from sketchrip_texture_catalog import build_catalog
    catalog = build_catalog(PACK)
    index = TextureIndex(PACK, cache_dir=tmp_path, match_mode="fast", catalog_mode="model")
    for name in ("spruce_shelf", "green_shulker_box", "black_shulker_box", "green_wool"):
        assert index.match(catalog.sprites[name]).texture == name


def test_non_exact_pixel_candidate_prefers_non_decorative_block():
    distances = np.array([400**2, 430**2, 540**2], dtype=np.float64)
    blocks = ["minecraft:blue_bed", "minecraft:oak_planks", "minecraft:green_wool"]
    names = ["bed_down", "oak_planks", "green_wool"]
    result = choose(distances, np.array([0, 1, 2]), names, blocks)
    assert result.index == 1
    assert result.original_index == 0
    assert result.reason is not None


def test_shape_inference_needs_fractional_geometry(tmp_path):
    # Ordinary solid cube should remain ordinary plank, even though all six
    # faces share a texture used by stairs, slabs, fences, signs and shelves.
    box = trimesh.creation.box(extents=(1, 1, 1))
    box.apply_translation((0.5, 0.5, 0.5))
    predictions, report = infer([box], {(0, 0, 0): "minecraft:oak_planks"}, PACK,
                                1.0, "y")
    assert predictions == {}
    assert report["counts"].get("no_fractional_shape_evidence", 0) >= 1


def test_stairs_slabs_fences_require_real_shape_evidence():
    assets = VanillaAssets(PACK)
    cases = [
        ("minecraft:oak_stairs[facing=south,half=bottom,shape=straight]", "oak_planks", "oak_stairs"),
        ("minecraft:stone_brick_stairs[facing=east,half=bottom,shape=inner_left]", "stone_bricks", "stone_brick_stairs"),
        ("minecraft:oak_slab[type=top]", "oak_planks", "oak_slab"),
        ("minecraft:oak_fence[east=false,north=false,south=false,west=false]", "oak_planks", "oak_fence"),
    ]
    for state, base, family in cases:
        faces = assets.faces(state)
        vertices = np.asarray([triangle for triangle, _, _ in faces]).reshape(-1, 3)
        mesh = trimesh.Trimesh(vertices=vertices, faces=np.arange(len(vertices)).reshape(-1, 3), process=False)
        changes, report = infer([mesh], {(0, 0, 0): "minecraft:" + base}, PACK, 1.0, "y")
        assert changes[(0, 0, 0)].startswith("minecraft:" + family + "["), (state, report)
    # Equivalent stair orientations are reported, not marketed as fully proven.
    assert "equivalent_orientation_or_state" in report["counts"] or report["proposed_changes"] > 0


def test_rotated_mineways_sprites_prefer_pattern_over_color(tmp_path):
    assets = VanillaAssets(PACK)
    index = TextureIndex(PACK, cache_dir=tmp_path, match_mode="fast", catalog_mode="model")
    # The source atlas rotates tiles to pack them. A color-only texture
    # classifier turned rotated polished andesite into light gray wool.
    for sprite_name in ("polished_andesite", "stone_bricks", "spruce_planks", "bricks"):
        sprite = np.asarray(assets.sprite("block/" + sprite_name))
        for quarter_turns in range(4):
            rotated = Image.fromarray(np.rot90(sprite, quarter_turns).copy(), "RGBA")
            match = index.match(rotated)
            assert match.block == "minecraft:" + sprite_name, (sprite_name, quarter_turns, match)


def test_real_mineways_atlas_rotated_materials(tmp_path):
    fixture = ROOT / "fixtures" / "rotated-atlas"
    index = TextureIndex(PACK, cache_dir=tmp_path, match_mode="fast", catalog_mode="model")
    for name in ("polished_andesite", "spruce_planks", "bricks", "stone_bricks"):
        sample = Image.open(fixture / (name + ".png"))
        assert index.match(sample).block == "minecraft:" + name
