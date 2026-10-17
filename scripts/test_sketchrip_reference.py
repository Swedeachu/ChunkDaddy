"""Minecraft model/UV/state conformance tests; no known-name GLB shortcuts."""

from __future__ import annotations

import json
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))

from sketchrip_reference import VanillaAssets, state_signature, atlas_for_assets, fixture_states, generate, evaluate_internal

PACK = Path(__file__).resolve().parent.parent / "assets" / "sketchrip" / "vanilla-26.2.zip"


def signature(assets, state):
    faces = assets.faces(state)
    tex = {t for _, _, t in faces}
    _, _, hashes = atlas_for_assets(assets, tex)
    return state_signature(faces, hashes)


def test_catalog_includes_all_blockstate_files():
    assets = VanillaAssets(PACK)
    assert len(assets.states) > 1100
    assert len(fixture_states(assets, "all", 0)[0]) > 9000
    for block in ("stone_brick_stairs", "stone_brick_slab", "ladder", "oak_fence", "iron_bars",
                  "glass_pane", "oak_leaves", "green_terracotta", "bamboo_block"):
        assert block in assets.states


def test_facing_ladders_have_distinct_geometry():
    assets = VanillaAssets(PACK)
    sigs = {signature(assets, f"minecraft:ladder[facing={face}]")
            for face in ("north", "south", "east", "west")}
    assert len(sigs) == 4


def test_stone_brick_stairs_have_shape_and_half_states():
    assets = VanillaAssets(PACK)
    for name in ("stone_brick_stairs", "oak_stairs"):
        a = f"minecraft:{name}[facing=north,half=bottom,shape=straight]"
        b = f"minecraft:{name}[facing=north,half=top,shape=straight]"
        c = f"minecraft:{name}[facing=north,half=bottom,shape=outer_left]"
        assert len({signature(assets, a), signature(assets, b), signature(assets, c)}) == 3


def test_slab_heights_are_distinct():
    assets = VanillaAssets(PACK)
    states = [f"minecraft:stone_brick_slab[type={typ}]" for typ in ("bottom", "top", "double")]
    assert len({signature(assets, state) for state in states}) == 3


def test_fences_and_panes_have_connection_states():
    assets = VanillaAssets(PACK)
    for name in ("oak_fence", "iron_bars", "glass_pane", "cobblestone_wall"):
        definition = assets.state_definition(name)
        assert "multipart" in definition
        states = assets.enumerate_states(name)
        assert len(states) >= 5
        examples = [signature(assets, state) for state, _ in states[:5]]
        assert len(set(examples)) > 1, name


def test_model_texture_terracotta_not_bamboo():
    assets = VanillaAssets(PACK)
    block_texture = {name: {face[2] for face in assets.faces(assets.enumerate_states(name)[0][0])}
                     for name in ("green_terracotta", "bamboo_block", "stone_bricks", "stone")}
    assert block_texture["green_terracotta"] == {"minecraft:block/green_terracotta"}
    assert block_texture["stone_bricks"] == {"minecraft:block/stone_bricks"}
    assert block_texture["stone"] == {"minecraft:block/stone"}
    assert block_texture["green_terracotta"] != block_texture["bamboo_block"]


def test_reference_glb_roundtrip_and_ambiguity(tmp_path):
    assets = VanillaAssets(PACK)
    report = generate(assets, tmp_path, "targeted", 120)
    assert len(report["expected"]) > 0
    result = evaluate_internal(tmp_path)
    assert result["wrong_or_unmatched"] == 0
    assert result["uniquely_correct"] + result["indistinguishable"] == result["states"]
    # Recovered state IDs come from geometry+UV+pixel hashes, not GLB metadata.
    assert not json.loads((tmp_path / "manifest.json").read_text())["expected"].get("none")


def test_mineways_script_references_generated_schematic(tmp_path):
    assets = VanillaAssets(PACK)
    generate(assets, tmp_path, "defaults", 8)
    script = (tmp_path / "export_mineways.mwscript").read_text()
    assert "Minecraft world:" in script
    assert "reference.schem" in script
    assert "Set render type: Wavefront OBJ absolute indices" in script
    assert "Export for rendering:" in script


def test_conservative_state_inference_improves_known_fixture(tmp_path):
    """No hard-coded expected states enter inference; only compare after reconstruction."""
    import trimesh
    from sketchrip import TextureIndex, reconstruct
    from sketchrip_state_inference import infer

    assets = VanillaAssets(PACK)
    generate(assets, tmp_path, "targeted", 180)
    truth = {tuple(map(int, key.split(","))): state for key, state in
             json.loads((tmp_path / "manifest.json").read_text())["expected"].items()}
    scene = trimesh.load(tmp_path / "reference.glb", force="scene", process=False)
    meshes = [mesh for mesh in scene.dump(concatenate=False)
              if isinstance(mesh, trimesh.Trimesh)]
    initial, _ = reconstruct(meshes, TextureIndex(PACK, cache_dir=tmp_path / "cache"),
                             scale=1.0, allow_uncertain=True)
    proposals, report = infer(meshes, initial, PACK, 1.0, "y")
    assert report["proposed_changes"] == len(proposals)
    assert len(proposals) >= 5
    assert sum(value == truth.get(position) for position, value in proposals.items()) >= 5
    assert not any(initial.get(position) == truth.get(position) and new != truth.get(position)
                   for position, new in proposals.items())
    # Inferences retain state properties rather than stripping all orientations.
    assert any("[" in state and "=" in state for state in proposals.values())


def test_mineways_script_uses_real_documented_commands(tmp_path):
    from sketchrip_reference import write_mineways_script
    for mode in ("individual", "merged"):
        script = write_mineways_script(tmp_path, mode)
        code = script.read_text()
        assert f"Export individual blocks: {'YES' if mode == 'individual' else 'no'}" in code
        assert "Set render type: Wavefront OBJ absolute indices" in code
        assert "Selection location: all" in code
        assert "Export lesser blocks: YES" in code
        assert f"mineways-reference-{mode}.obj" in code
        assert "Close\n" in code
