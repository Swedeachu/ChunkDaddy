"""Independent texture identity and model-reference checks, not state-recovery claims."""

from pathlib import Path
import sys
import io

import numpy as np
from PIL import Image

sys.path.insert(0, str(Path(__file__).resolve().parent))
from sketchrip import TextureIndex
from sketchrip_texture_catalog import build_catalog
from sketchrip_texture_classifier import CalibratedTextureMatcher
from sketchrip_texture_lab import transformed

PACK = Path(__file__).resolve().parent.parent / "assets/sketchrip/vanilla-26.2.zip"


def test_reference_resolves_exact_block_faces(tmp_path):
    index = TextureIndex(PACK, tmp_path)
    catalog = build_catalog(PACK)
    assert len(index.names) >= 1250
    assert index.match(catalog.sprites["green_terracotta"]).block == "minecraft:green_terracotta"
    assert index.match(catalog.sprites["bamboo_block"]).block == "minecraft:bamboo_block"
    assert index.match(catalog.sprites["stone_bricks"]).block == "minecraft:stone_bricks"
    assert index.match(catalog.sprites["stone"]).block == "minecraft:stone"
    assert "minecraft:stone_brick_stairs" in catalog.candidates["stone_bricks"]
    assert "minecraft:stone_bricks" in catalog.candidates["stone_bricks"]
    assert "minecraft:ladder" in catalog.candidates["ladder"]
    assert "minecraft:oak_stairs" in catalog.candidates["oak_planks"]


def test_exact_sprite_identity_and_collision_flagging(tmp_path):
    index = TextureIndex(PACK, tmp_path)
    catalog = build_catalog(PACK)
    classifier = CalibratedTextureMatcher(index)
    for name in index.names:
        guess = classifier.identify(catalog.sprites[name])
        if name in catalog.exact_visual_aliases:
            assert guess.ambiguous or name == guess.texture
        else:
            assert guess.texture == name
            assert not guess.ambiguous
    assert catalog.exact_visual_aliases


def test_opaque_lossy_jpeg_and_rotation_examples(tmp_path):
    import random
    rng = random.Random(328)
    index = TextureIndex(PACK, tmp_path)
    catalog = build_catalog(PACK)
    classifier = CalibratedTextureMatcher(index)
    for name in ("green_terracotta", "bamboo_block", "stone_bricks", "stone", "oak_planks"):
        raw = catalog.sprites[name]
        assert classifier.identify(raw).texture == name
        for rotation in (90, 180, 270):
            prediction = classifier.identify(raw.rotate(rotation))
            assert (prediction.texture == name or (prediction.ambiguous and name in prediction.candidates)
                    or (prediction.texture in catalog.sprites and
                        catalog.sprites[prediction.texture].tobytes() ==
                        raw.rotate(rotation).tobytes()))
        if np.all(np.asarray(raw)[:, :, 3] == 255):
            prediction = classifier.identify(transformed(raw, "jpeg90", rng))
            assert (prediction.texture == name or (prediction.ambiguous and name in prediction.candidates)
                    or (prediction.texture in catalog.sprites and
                        catalog.sprites[prediction.texture].tobytes() ==
                        raw.rotate(rotation).tobytes()))


def test_visual_identity_cannot_identify_stair_block_from_plank_face(tmp_path):
    index = TextureIndex(PACK, tmp_path)
    assert "minecraft:oak_stairs" in index.texture_candidates["oak_planks"]
    assert "minecraft:oak_slab" in index.texture_candidates["oak_planks"]
    assert "minecraft:oak_planks" in index.texture_candidates["oak_planks"]
