"""Deterministic geometry, palette and Sponge NBT regression tests for SketchRip."""

import gzip
import io
import json
import sys
import zipfile
from pathlib import Path

import numpy as np
import pytest
import trimesh
from PIL import Image

sys.path.insert(0, str(Path(__file__).resolve().parent))
from sketchrip import (TextureIndex, block_for_texture, download_sketchfab,
                       guess_scale, reconstruct, write_schematic)


def texture_zip(path):
    with zipfile.ZipFile(path, "w") as archive:
        for name, color in (("stone", (95, 95, 95, 255)), ("dirt", (132, 75, 45, 255))):
            archive.writestr(f"assets/minecraft/blockstates/{name}.json", "{}")
            content = io.BytesIO()
            Image.new("RGBA", (16, 16), color).save(content, format="PNG")
            archive.writestr(f"assets/minecraft/textures/block/{name}.png", content.getvalue())


def cube(position, image):
    base = trimesh.creation.box(extents=(1, 1, 1))
    # Unique triangle vertices ensure each face can carry its own UV rectangle.
    verts = base.vertices[base.faces].reshape(-1, 3) + np.array(position) + 0.5
    faces = np.arange(len(verts)).reshape(-1, 3)
    uvs = np.tile(np.array([[0, 0], [1, 0], [0, 1]], dtype=float), (len(faces), 1))
    visual = trimesh.visual.TextureVisuals(
        uv=uvs, material=trimesh.visual.material.SimpleMaterial(image=image))
    return trimesh.Trimesh(vertices=verts, faces=faces, visual=visual, process=False)


def test_texture_mapping():
    names = {"oak_log", "grass_block", "stone_bricks", "water", "dirt_path"}
    assert block_for_texture("oak_log_top", names) == "minecraft:oak_log"
    assert block_for_texture("grass_block_side_overlay", names) == "minecraft:grass_block"
    assert block_for_texture("dirt_path_top", names) == "minecraft:dirt_path"
    assert block_for_texture("fake_block_name", names) is None


def test_model_voxel_schematic_roundtrip(tmp_path):
    pack = tmp_path / "vanilla.zip"
    texture_zip(pack)
    stone = Image.new("RGBA", (16, 16), (95, 95, 95, 255))
    dirt = Image.new("RGBA", (16, 16), (132, 75, 45, 255))
    source = trimesh.Scene([cube((0, 0, 0), stone), cube((1, 0, 0), dirt)])
    glb = tmp_path / "cube.glb"
    glb.write_bytes(source.export(file_type="glb"))
    scene = trimesh.load(str(glb), force="scene", process=False)
    meshes = scene.dump(concatenate=False)
    assert guess_scale(meshes, None) == pytest.approx(1.0)
    index = TextureIndex(pack, tmp_path / "cache")
    voxels, report = reconstruct(meshes, index, min_similarity=0.98)
    assert voxels == {(0, 0, 0): "minecraft:stone", (1, 0, 0): "minecraft:dirt"}
    assert report["unique_atlas_tiles"] == 2
    assert report["counts"]["mapped_faces"] == 24
    schem = tmp_path / "cubes.schem"
    assert write_schematic(voxels, schem) == (2, 1, 1)
    content = gzip.decompress(schem.read_bytes())
    assert content.startswith(b"\x0a\x00\x00\x0a\x00\x09Schematic")
    assert b"minecraft:stone" in content
    assert b"minecraft:dirt" in content
    assert b"\x00\x00\x00\x02" in content
    # Cache works in a fresh instance, and doesn't need a texture scan.
    second = TextureIndex(pack, tmp_path / "cache")
    assert second.match(stone).block == "minecraft:stone"
    assert second.match(dirt).block == "minecraft:dirt"


def test_non_downloadable_model_needs_permission(monkeypatch, tmp_path):
    import requests
    class FakeResponse:
        def raise_for_status(self):
            return None
        def json(self):
            return {"isDownloadable": False}
    monkeypatch.setattr(requests.Session, "get", lambda *args, **kwargs: FakeResponse())
    with pytest.raises(PermissionError, match="not marked downloadable"):
        download_sketchfab("https://sketchfab.com/3d-models/name-" + "a" * 32,
                           tmp_path / "temp.zip", "")


def test_bounded_schematic_dimensions(tmp_path):
    with pytest.raises(ValueError, match="too large"):
        write_schematic({(0, 0, 0): "minecraft:stone",
                         (70000, 0, 0): "minecraft:dirt"}, tmp_path / "bad.schem")


def test_shared_texture_atlas_reverse_mapping(tmp_path):
    pack = tmp_path / "vanilla.zip"
    texture_zip(pack)
    atlas = Image.new("RGBA", (32, 16))
    atlas.paste(Image.new("RGBA", (16, 16), (95, 95, 95, 255)), (0, 0))
    atlas.paste(Image.new("RGBA", (16, 16), (132, 75, 45, 255)), (16, 0))
    one = cube((0, 0, 0), atlas)
    two = cube((1, 0, 0), atlas)
    one.visual.uv[:, 0] *= 0.5
    two.visual.uv[:, 0] = two.visual.uv[:, 0] * 0.5 + 0.5
    voxels, report = reconstruct([one, two], TextureIndex(pack, tmp_path / "cache"),
                                 min_similarity=0.99)
    assert voxels == {(0, 0, 0): "minecraft:stone", (1, 0, 0): "minecraft:dirt"}
    assert report["unique_atlas_tiles"] == 2
    assert report["face_coverage"] == pytest.approx(1.0)


def read_test_nbt(data):
    """Small independent binary-NBT decoder used only by the regression test."""
    import struct
    buffer = memoryview(data)
    position = 0

    def take(n):
        nonlocal position
        chunk = buffer[position:position + n]
        if len(chunk) != n:
            raise ValueError("NBT stream ended early")
        position += n
        return bytes(chunk)

    def number(fmt):
        return struct.unpack(">" + fmt, take(struct.calcsize(">" + fmt)))[0]

    def string():
        return take(number("H")).decode("utf-8")

    def payload(tag):
        if tag == 2:
            return number("h")
        if tag == 3:
            return number("i")
        if tag == 7:
            return take(number("i"))
        if tag == 9:
            child, count = number("b"), number("i")
            return [payload(child) for _ in range(count)]
        if tag == 10:
            result = {}
            while True:
                child = number("b")
                if child == 0:
                    return result
                name = string()
                result[name] = payload(child)
        if tag == 11:
            return [number("i") for _ in range(number("i"))]
        raise ValueError(f"Unexpected NBT tag {tag}")

    assert number("b") == 10
    assert string() == ""
    result = payload(10)
    assert position == len(buffer)
    return result


def test_schematic_nbt_layout(tmp_path):
    destination = tmp_path / "test.schem"
    write_schematic({(0, 0, 0): "minecraft:stone", (1, 0, 0): "minecraft:dirt"}, destination)
    document = read_test_nbt(gzip.decompress(destination.read_bytes()))["Schematic"]
    assert (document["Version"], document["DataVersion"]) == (3, 4903)
    assert (document["Width"], document["Height"], document["Length"]) == (2, 1, 1)
    assert document["Offset"] == [0, 0, 0]
    palette = document["Blocks"]["Palette"]
    blocks = document["Blocks"]["Data"]
    assert len(blocks) == 2
    assert (blocks[0], blocks[1]) == (palette["minecraft:stone"], palette["minecraft:dirt"])
    assert document["Blocks"]["BlockEntities"] == []


def test_merged_mineways_plane_is_rasterized(tmp_path):
    pack = tmp_path / "vanilla.zip"
    texture_zip(pack)
    vertices = np.array([[0, 2, 0], [4, 2, 0], [4, 2, 4], [0, 2, 4]], float)
    # Top face made of just two triangles despite spanning sixteen Minecraft blocks.
    faces = np.array([[0, 2, 1], [0, 3, 2]])
    uv = np.array([[0, 0], [4, 0], [4, 4], [0, 4]], float)
    tex = trimesh.visual.TextureVisuals(
        uv=uv, material=trimesh.visual.material.SimpleMaterial(
            image=Image.new("RGBA", (16, 16), (95, 95, 95, 255))))
    mesh = trimesh.Trimesh(vertices=vertices, faces=faces, visual=tex, process=False)
    voxels, report = reconstruct([mesh], TextureIndex(pack, tmp_path / "cache"), scale=1.0,
                                 min_similarity=0.98)
    assert len(voxels) == 16
    assert set(voxels.values()) == {"minecraft:stone"}
    assert report["counts"]["merged_cells_mapped"] >= 16
    assert report["face_coverage"] == pytest.approx(1.0)


def test_sfrip_subprocess_integration(tmp_path, monkeypatch):
    from sketchrip import acquire_sfrip
    fixture = Path(__file__).resolve().parent.parent / "fixtures" / "four-blocks.glb"
    shim = tmp_path / "shim.py"
    shim.write_text(f'''import json, pathlib, shutil, sys
out = pathlib.Path(sys.argv[sys.argv.index("--out") + 1]) / "test-abcd1234"
out.mkdir(parents=True, exist_ok=True)
shutil.copyfile({str(fixture)!r}, out / "test.glb")
(out / "info.json").write_text(json.dumps({{"name":"Test", "author":"Builder", "uid":"a"*32,
                                         "geometry_count":12, "textures_failed":[],
                                         "textures_skipped": False}}))
print(out / "test.glb")
''')
    monkeypatch.setattr('sketchrip.find_sfrip', lambda command: [sys.executable, str(shim)])
    model = "https://sketchfab.com/3d-models/test-" + "a" * 32
    output, info = acquire_sfrip(model, tmp_path / "acquired")
    assert output.read_bytes() == fixture.read_bytes()
    assert info["backend"] == "sfrip"
    assert info["title"] == "Test"
    assert info["geometry_count"] == 12


def test_sfrip_rejects_incomplete_and_non_sketchfab(tmp_path, monkeypatch):
    from sketchrip import acquire_sfrip
    with pytest.raises(ValueError, match="sketchfab.com"):
        acquire_sfrip("https://example.com/3d-models/name-" + "a" * 32, tmp_path)
    class Result:
        returncode = 0
        stdout = str(tmp_path / "missing.glb") + "\n"
        stderr = ""
    monkeypatch.setattr('sketchrip.find_sfrip', lambda command: ["sfrip"])
    monkeypatch.setattr('sketchrip.subprocess.run', lambda *a, **k: Result())
    with pytest.raises(RuntimeError, match="missing or unsafe"):
        acquire_sfrip("https://sketchfab.com/3d-models/test-" + "a" * 32, tmp_path)


def test_chunkdaddy_worker_orchestration(monkeypatch, tmp_path):
    from sketchrip import export_world
    import chunkdaddy_cli

    requests = []
    class FakeWorker:
        def __init__(self, *args, **kwargs):
            pass
        def send(self, request, **kwargs):
            requests.append(request)
            if request["type"] == "new_document":
                return {"documentId": "doc-123"}
            if request["type"] == "import_schematics":
                return {"failures": [], "templates": [{"templateId": "template-123", "slug": "test", "issues": []}]}
            if request["type"] == "validate_export":
                return {"problems": []}
            if request["type"] == "export_world":
                return {"path": request["destination"], "ok": True}
            return {}
        def close(self):
            requests.append({"type": "close"})

    monkeypatch.setattr(chunkdaddy_cli, "Worker", FakeWorker)
    result = export_world(tmp_path / "map.schem", tmp_path / "map.mcworld", "Map", "bedrock-1.26.0",
                          "worker.jar", "java", 64, tmp_path / "workspace")
    assert result["ok"]
    assert [req["type"] for req in requests] == ["new_document", "import_schematics", "place_grid",
                                                 "validate_export", "export_world", "close"]
    assert requests[2]["placements"][0]["minY"] == 64
    assert requests[4]["mode"] == "MCWORLD"


def test_full_url_to_schematic_glue_with_fake_acquisition(tmp_path, monkeypatch):
    """Real mesh/texture/NBT pipeline; only the network downloader is substituted."""
    from sketchrip import main
    fixture = Path(__file__).resolve().parent.parent / "fixtures" / "four-blocks.glb"
    pack = Path(__file__).resolve().parent.parent / "assets" / "sketchrip" / "vanilla-26.2.zip"
    shim = tmp_path / "fake_sfrip.py"
    shim.write_text('''import json, pathlib, shutil, sys
out = pathlib.Path(sys.argv[sys.argv.index("--out") + 1]) / "sample-12345678"
out.mkdir(parents=True, exist_ok=True)
shutil.copyfile(sys.argv[-1], out / "sample.glb")
(out / "info.json").write_text(json.dumps({"name": "Sample", "geometry_count": 4,
                                             "textures_skipped": False, "textures_failed": []}))
print(out / "sample.glb")
''')
    # The fixture path is carried as an extra argument by the mocked launcher.
    monkeypatch.setattr('sketchrip.find_sfrip', lambda command: [sys.executable, str(shim), str(fixture)])
    # The shim needs --out to parse correctly and the final fixture argument untouched.
    # Put the fixture path in the environment instead for subprocess transparency.
    shim.write_text(shim.read_text().replace('sys.argv[-1]', '"' + str(fixture).replace('\\', '\\\\') + '"'))
    output = tmp_path / "result.mcworld"
    assert main(["https://sketchfab.com/3d-models/arena-" + "a" * 32,
                 "--acquire", "sfrip", "--dry-run", "--textures", str(pack),
                 "--output", str(output)]) == 0
    report = json.loads(output.with_suffix(".sketchrip.json").read_text())
    assert report["voxel_count"] == 4
    assert report["source"]["backend"] == "sfrip"
    assert output.with_suffix(".schem").is_file()
    assert not output.is_file()


def test_standard_library_doctor_survives_missing_site_packages(tmp_path):
    """The original crash must turn into instructions, not a traceback."""
    import subprocess
    cli = Path(__file__).resolve().parent / "chunkdaddy_cli.py"
    process = subprocess.run([sys.executable, "-S", str(cli), "--sketchrip", "--doctor"],
                             text=True, capture_output=True, check=False)
    assert "missing_packages" in process.stdout
    assert "trimesh" in process.stdout
    assert "pip install" in process.stderr
    assert "Traceback" not in process.stderr
    assert process.returncode == 1


def test_glb_grid_with_real_vanilla_atlas(tmp_path):
    """Import a textured GLB with many different tiles; compare every block."""
    pack = Path(__file__).resolve().parent.parent / "assets" / "sketchrip" / "vanilla-26.2.zip"
    names = ["stone", "dirt", "cobblestone", "oak_planks"]
    atlas = Image.new("RGBA", (32, 32))
    with zipfile.ZipFile(pack) as archive:
        for index, name in enumerate(names):
            file = f"assets/minecraft/textures/block/{name}.png"
            with Image.open(io.BytesIO(archive.read(file))) as picture:
                atlas.paste(picture.convert("RGBA").crop((0, 0, 16, 16)),
                            ((index % 2) * 16, (index // 2) * 16))

    size = 32
    vertices, faces, uvs = [], [], []
    for x in range(size):
        for z in range(size):
            block = (x // 5 + z // 7) % 4
            tx, tz = block % 2, block // 2
            index = len(vertices)
            vertices.extend(((x, 0, z), (x, 0, z + 1), (x + 1, 0, z + 1), (x + 1, 0, z)))
            uv_x0, uv_x1 = tx * .5, (tx + 1) * .5
            uv_y0, uv_y1 = 1 - (tz + 1) * .5, 1 - tz * .5
            uvs.extend(((uv_x0, uv_y0), (uv_x0, uv_y1),
                        (uv_x1, uv_y1), (uv_x1, uv_y0)))
            faces.extend(((index, index + 1, index + 2),
                          (index, index + 2, index + 3)))

    mesh = trimesh.Trimesh(vertices=np.asarray(vertices), faces=np.asarray(faces),
                           visual=trimesh.visual.TextureVisuals(
                               uv=np.asarray(uvs),
                               material=trimesh.visual.material.SimpleMaterial(image=atlas)),
                           process=False)
    glb = tmp_path / "grid.glb"
    glb.write_bytes(trimesh.Scene([mesh]).export(file_type="glb"))
    loaded = trimesh.load(str(glb), force="scene", process=False).dump(concatenate=False)
    blocks, report = reconstruct(loaded, TextureIndex(pack, tmp_path / "cache"),
                                 min_similarity=.98)
    assert len(blocks) == size ** 2
    assert report["counts"]["mapped_faces"] == size ** 2 * 2
    assert report["uncertain"] == []
    assert report["scale_model_units_per_block"] == 1.0
    for x in range(size):
        for z in range(size):
            assert blocks[(x, -1, z)] == f"minecraft:{names[(x // 5 + z // 7) % 4]}"


def test_normalized_mineways_dimensions_preserve_true_scale(tmp_path):
    from sketchrip import guess_scale
    image = Image.new("RGBA", (16, 16), (95, 95, 95, 255))
    two = [cube((0, 0, 0), image), cube((306, 141, 306), image)]
    for mesh in two:
        mesh.vertices[:] /= 142.0
    assert guess_scale(two, None) == pytest.approx(1.0 / 142.0, rel=1e-5)


def test_mineways_uv_fragment_snaps_to_original_atlas_tile():
    from sketchrip import atlas_parent_tile
    atlas = Image.new("RGBA", (1024, 1024))
    assert atlas_parent_tile((293, 1007, 301, 1023), atlas) == (289, 1007, 305, 1023)
    assert atlas_parent_tile((379, 899, 395, 915), atlas) == (379, 899, 395, 915)
    assert atlas_parent_tile((0, 0, 16, 16), atlas) == (0, 0, 16, 16)
    assert atlas_parent_tile((0, 0, 16, 16), Image.new("RGBA", (16, 16))) == (0, 0, 16, 16)


def test_duplicate_rgb_atlas_prefers_preserved_opacity(tmp_path):
    pack = tmp_path / "vanilla.zip"
    texture_zip(pack)
    opaque = Image.new("RGBA", (16, 16), (95, 95, 95, 255))
    faded = Image.new("RGBA", (16, 16), (95, 95, 95, 35))
    one = cube((0, 0, 0), opaque)
    two = cube((1, 0, 0), faded)
    blocks, report = reconstruct([one, two], TextureIndex(pack, tmp_path / "cache"),
                                 min_similarity=0.98)
    assert blocks == {(0, 0, 0): "minecraft:stone", (1, 0, 0): "minecraft:stone"}
    assert report["counts"]["canonical_texture_atlases"] == 1


def test_exact_16x16_sprite_override_requires_matching_bytes(tmp_path):
    import hashlib
    pack = tmp_path / "vanilla.zip"
    texture_zip(pack)
    image = Image.new("RGBA", (16, 16), (95, 95, 95, 255))
    fingerprint = hashlib.sha256(np.asarray(image).tobytes()).hexdigest()
    palette = tmp_path / "palette.json"
    palette.write_text(json.dumps({"entries": {
        fingerprint: {"block": "minecraft:dirt", "note": "test-specific override"}
    }}))
    lookup = TextureIndex(pack, tmp_path / "cache", atlas_map=palette)
    assert lookup.match(image).block == "minecraft:dirt"
    assert lookup.match(image).texture.startswith("atlas-override:")
    different = Image.new("RGBA", (16, 16), (132, 75, 45, 255))
    assert lookup.match(different).block == "minecraft:dirt"
    assert not lookup.match(different).texture.startswith("atlas-override:")


def test_retag_preserves_shelf_palette_and_original(tmp_path):
    """The previous Java 1.21.4 stamp must not erase 26.2 shelf palette entries."""
    import gzip
    import struct
    from sketchrip import retag_sketchrip_schematic, write_schematic
    before = tmp_path / "original.schem"
    after = tmp_path / "upgraded.schem"
    write_schematic({(0, 0, 0): "minecraft:spruce_shelf",
                     (1, 0, 0): "minecraft:jungle_shelf"}, before)
    tagged = gzip.decompress(before.read_bytes())
    tag = b"\x03\x00\x0bDataVersion"
    assert tagged.count(tag) == 1
    offset = tagged.index(tag) + len(tag)
    legacy = bytearray(tagged)
    struct.pack_into(">i", legacy, offset, 4189)
    with gzip.open(before, "wb") as stream:
        stream.write(legacy)
    assert retag_sketchrip_schematic(before, after) == (4189, 4903)
    rewritten = gzip.decompress(after.read_bytes())
    assert rewritten[:offset] == tagged[:offset]
    assert rewritten[offset + 4:] == tagged[offset + 4:]
    assert struct.unpack_from(">i", rewritten, offset)[0] == 4903
    assert gzip.decompress(before.read_bytes()) == bytes(legacy)
    assert b"minecraft:jungle_shelf" in rewritten
    assert b"minecraft:spruce_shelf" in rewritten


def test_retag_rejects_unknown_data_version(tmp_path):
    import gzip
    import struct
    from sketchrip import retag_sketchrip_schematic, write_schematic
    source = tmp_path / "unknown.schem"
    write_schematic({(0, 0, 0): "minecraft:stone"}, source)
    payload = bytearray(gzip.decompress(source.read_bytes()))
    marker = b"\x03\x00\x0bDataVersion"
    struct.pack_into(">i", payload, payload.index(marker) + len(marker), 12345)
    with gzip.open(source, "wb") as stream:
        stream.write(payload)
    with pytest.raises(ValueError, match="refusing to rewrite"):
        retag_sketchrip_schematic(source, tmp_path / "should-not-exist.schem")


def test_shelf_texture_pack_matches_newer_java_version():
    from sketchrip import DATA_VERSION
    assert DATA_VERSION == 4903


def test_existing_schematic_skips_mesh_matching_and_exports(tmp_path, monkeypatch):
    """Retry a converter failure without reloading the 1.5M triangle source GLB."""
    import gzip
    import struct
    import sketchrip
    source = tmp_path / "old.schem"
    output = tmp_path / "converted.mcworld"
    sketchrip.write_schematic({(0, 0, 0): "minecraft:stone",
                              (1, 0, 0): "minecraft:spruce_shelf"}, source)
    payload = bytearray(gzip.decompress(source.read_bytes()))
    marker = b"\x03\x00\x0bDataVersion"
    struct.pack_into(">i", payload, payload.index(marker) + len(marker), 4189)
    with gzip.open(source, "wb") as stream:
        stream.write(payload)
    calls = []

    def fake_export(schematic, destination, name, profile, classpath, java, world_y, workspace):
        nbt = gzip.decompress(schematic.read_bytes())
        assert struct.unpack_from(">i", nbt, nbt.index(marker) + len(marker))[0] == 4903
        assert b"minecraft:spruce_shelf" in nbt
        calls.append((destination, profile))
        return {"path": str(destination)}

    monkeypatch.setattr(sketchrip, "export_world", fake_export)
    assert sketchrip.main([str(source), "--output", str(output), "--classpath", "unused",
                           "--profile", "bedrock-1.26.0"]) == 0
    assert calls == [(output, "bedrock-1.26.0")]
    assert (tmp_path / "converted.schem").is_file()
    assert struct.unpack_from(">i", gzip.decompress(source.read_bytes()),
                              gzip.decompress(source.read_bytes()).index(marker) + len(marker))[0] == 4189
