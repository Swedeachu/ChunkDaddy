"""Deterministic state-orientation regression tests from vanilla block models."""

from pathlib import Path
import sys

import numpy as np
import trimesh

sys.path.insert(0, str(Path(__file__).resolve().parent))

from sketchrip_reference import VanillaAssets
from sketchrip_state_inference import features, infer

PACK = Path(__file__).resolve().parent.parent / "assets" / "sketchrip" / "vanilla-26.2.zip"


def test_stair_family_and_orientation_equivalence():
    assets = VanillaAssets(PACK)
    original = {}
    baseline = {}
    vertices = []
    triangles = []

    for family in ("oak_stairs", "stone_brick_stairs", "spruce_stairs"):
        material = ("oak_planks" if family.startswith("oak_") else
                    "spruce_planks" if family.startswith("spruce_") else "stone_bricks")
        for state, _ in assets.enumerate_states(family):
            if len(original) >= 120:
                break
            try:
                faces = assets.faces(state)
            except (KeyError, ValueError):
                continue
            point = (len(original) % 20 * 3, 0, len(original) // 20 * 3)
            original[point] = state
            baseline[point] = "minecraft:" + material
            for face, _, _ in faces:
                start = len(vertices)
                vertices.extend((np.asarray(face) + point).tolist())
                triangles.append((start, start + 1, start + 2))

    for family in ("oak_planks", "stone_bricks", "spruce_planks"):
        point = (len(original) % 20 * 3, 0, len(original) // 20 * 3)
        original[point] = baseline[point] = "minecraft:" + family
        for face, _, _ in assets.faces(original[point]):
            start = len(vertices)
            vertices.extend((np.asarray(face) + point).tolist())
            triangles.append((start, start + 1, start + 2))

    mesh = trimesh.Trimesh(vertices=vertices, faces=triangles, process=False)
    proposals, report = infer([mesh], baseline, PACK, 1.0, "y")
    assert report["proposed_changes"] == len(proposals)
    assert not any(point in proposals for point in original if original[point] == baseline[point])

    for point, state in original.items():
        predicted = proposals.get(point, baseline[point])
        assert predicted.split("[")[0] == state.split("[")[0]
        if predicted != state:
            expected = features([face[0] for face in assets.faces(state)])
            actual = features([face[0] for face in assets.faces(predicted)])
            assert np.array_equal(expected, actual), (point, state, predicted)


def test_z_up_reference_stairs():
    assets = VanillaAssets(PACK)
    state = "minecraft:oak_stairs[facing=east,half=top,shape=straight]"
    triangles = np.asarray([face[0] for face in assets.faces(state)]).reshape(-1, 3)
    vertices = triangles[:, [0, 2, 1]]
    mesh = trimesh.Trimesh(vertices=vertices,
                           faces=np.arange(len(vertices)).reshape(-1, 3), process=False)
    proposals, _ = infer([mesh], {(0, 0, 0): "minecraft:oak_planks"}, PACK, 1.0, "z")
    assert proposals[(0, 0, 0)] == state
