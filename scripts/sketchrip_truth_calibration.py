"""Calibrate atlas material IDs against a user-provided Bedrock world.

Reference geometry is split by chunk for held-out validation. Exact source states
cannot be inferred from a texture tile alone, so non-full-block families are
mapped to their base material for subsequent geometry classification.
"""

from __future__ import annotations

import collections
import hashlib
import json
import re
import struct
import tempfile
import zipfile
import zlib
from pathlib import Path

import numpy as np
from PIL import Image


def _varint(buf: bytes, pos: int) -> tuple[int, int]:
    value = 0
    shift = 0
    while True:
        byte = buf[pos]
        pos += 1
        value |= (byte & 127) << shift
        if byte < 128:
            return value, pos
        shift += 7
        if shift > 63:
            raise ValueError("Overlong varint")


def _log_records(path: Path):
    with path.open("rb") as handle:
        fragments = []
        while data := handle.read(32768):
            pos = 0
            while pos + 7 <= len(data):
                _, length, kind = struct.unpack_from("<IHB", data, pos)
                pos += 7
                if kind == 0 and length == 0:
                    break
                if pos + length > len(data):
                    raise ValueError("Truncated LevelDB log record")
                chunk = data[pos:pos + length]
                pos += length
                if kind == 1:
                    yield chunk
                elif kind == 2:
                    fragments = [chunk]
                elif kind == 3:
                    fragments.append(chunk)
                elif kind == 4:
                    fragments.append(chunk)
                    yield b"".join(fragments)
                    fragments = []


def _parse_batch(data: bytes):
    sequence, count = struct.unpack_from("<QI", data)
    pos = 12
    for index in range(count):
        operation = data[pos]
        pos += 1
        size, pos = _varint(data, pos)
        key = data[pos:pos + size]
        pos += size
        if operation == 1:
            size, pos = _varint(data, pos)
            value = data[pos:pos + size]
            pos += size
        else:
            value = None
        yield sequence + index, key, value


def _table_block(data: bytes):
    restarts = struct.unpack_from("<I", data, len(data) - 4)[0]
    limit = len(data) - 4 * (restarts + 1)
    previous = b""
    pos = 0
    while pos < limit:
        shared, pos = _varint(data, pos)
        suffix, pos = _varint(data, pos)
        size, pos = _varint(data, pos)
        key = previous[:shared] + data[pos:pos + suffix]
        pos += suffix
        value = data[pos:pos + size]
        pos += size
        previous = key
        yield key, value
    if pos != limit:
        raise ValueError("Corrupt LevelDB table block")


def _sst_records(path: Path):
    with path.open("rb") as handle:
        handle.seek(-48, 2)
        footer = handle.read(48)
        if footer[-8:] != bytes.fromhex("57fb808b247547db"):
            raise ValueError("Unsupported LevelDB SST footer")

        def read_handle(data: bytes, pos: int = 0):
            offset, pos = _varint(data, pos)
            size, pos = _varint(data, pos)
            return (offset, size), pos

        _, pos = read_handle(footer)
        index_handle, _ = read_handle(footer, pos)

        def read_block(block):
            offset, size = block
            handle.seek(offset)
            data = handle.read(size + 5)
            kind = data[size]
            raw = data[:size]
            if kind == 0:
                return raw
            if kind == 4:
                return zlib.decompress(raw, -15)
            if kind == 1:
                # LevelDB Snappy support is optional; fail explicitly rather
                # than silently omitting chunks from the reference world.
                try:
                    import snappy
                except ImportError as error:
                    raise RuntimeError("Snappy-compressed table requires python-snappy") from error
                return snappy.decompress(raw)
            raise ValueError(f"Unsupported SST compression: {kind}")

        for _, value in _table_block(read_block(index_handle)):
            block, _ = read_handle(value)
            for key, raw in _table_block(read_block(block)):
                sequence_and_type = int.from_bytes(key[-8:], "little")
                yield sequence_and_type >> 8, key[:-8], raw if (sequence_and_type & 255) else None


def _named_nbt(data: bytes, pos: int):
    tag = data[pos]
    pos += 1
    size = struct.unpack_from("<H", data, pos)[0]
    pos += 2 + size
    return _nbt(data, pos, tag)


def _nbt(data: bytes, pos: int, tag: int):
    if tag == 1:
        return struct.unpack_from("<b", data, pos)[0], pos + 1
    if tag == 2:
        return struct.unpack_from("<h", data, pos)[0], pos + 2
    if tag == 3:
        return struct.unpack_from("<i", data, pos)[0], pos + 4
    if tag == 4:
        return struct.unpack_from("<q", data, pos)[0], pos + 8
    if tag == 8:
        size = struct.unpack_from("<H", data, pos)[0]
        pos += 2
        return data[pos:pos + size].decode("utf-8"), pos + size
    if tag == 10:
        entry = {}
        while data[pos] != 0:
            kind = data[pos]
            size = struct.unpack_from("<H", data, pos + 1)[0]
            name = data[pos + 3:pos + 3 + size].decode("utf-8")
            pos += 3 + size
            entry[name], pos = _nbt(data, pos, kind)
        return entry, pos + 1
    raise ValueError(f"Unsupported Bedrock palette NBT tag {tag}")


def _subchunk(value: bytes):
    if value[0] != 9:
        raise ValueError("Unsupported Bedrock subchunk encoding")
    count = value[1]
    y = int.from_bytes(value[2:3], "little", signed=True)
    pos = 3
    indices = None
    palette = None
    for storage in range(count):
        bits = value[pos] >> 1
        pos += 1
        if bits == 0:
            selected = np.zeros(4096, dtype=np.int32)
        else:
            step = 32 // bits
            words = (4096 + step - 1) // step
            packed = np.frombuffer(value, dtype="<u4", count=words, offset=pos)
            pos += 4 * words
            offsets = np.arange(4096, dtype=np.int32)
            selected = ((packed[offsets // step] >> ((offsets % step) * bits)) & ((1 << bits) - 1)).astype(np.int32)
        length = struct.unpack_from("<I", value, pos)[0]
        pos += 4
        block_states = []
        for _ in range(length):
            item, pos = _named_nbt(value, pos)
            block_states.append(item)
        if storage == 0:
            indices = selected
            palette = block_states
    if pos != len(value):
        raise ValueError("Subchunk contains trailing data")
    return y, indices, palette


def read_bedrock_world(world: Path):
    """Return coordinates and block-state names; ignores empty world columns."""
    with tempfile.TemporaryDirectory(prefix="sketchrip-truth-") as temp:
        root = Path(temp)
        with zipfile.ZipFile(world) as archive:
            for name in archive.namelist():
                if name.startswith("db/") and name.endswith((".ldb", ".log")) and ".." not in name:
                    archive.extract(name, root)
        db = root / "db"
        files = sorted((*db.glob("*.ldb"), *db.glob("*.log")), key=lambda path: int(path.stem))
        # Multiple generations can contain the same key; always select its newest
        # sequence number. Some Bedrock exports retain duplicate log/SST batches.
        records = {}
        for file in files:
            iterator = _sst_records(file) if file.suffix == ".ldb" else (
                item for record in _log_records(file) for item in _parse_batch(record))
            for seq, key, value in iterator:
                if len(key) != 10 or key[8] != 0x2f:
                    continue
                old = records.get(key)
                if old is None or seq > old[0]:
                    records[key] = (seq, value)
        names = {}
        positions = []
        codes = []
        for key, (_, value) in records.items():
            if value is None:
                continue
            chunk_x, chunk_z = struct.unpack_from("<ii", key)
            y, indices, states = _subchunk(value)
            for item in states:
                key_name = item.get("name", item.get("Name", "minecraft:air"))
                properties = item.get("states", item.get("Properties", {}))
                if properties:
                    suffix = ",".join(f"{k}={v}" for k, v in sorted(properties.items()))
                    key_name += f"[{suffix}]"
                if key_name not in names:
                    names[key_name] = len(names)
            listed = list(names)
            local = [item.get("name", item.get("Name", "minecraft:air")) for item in states]
            mask = np.asarray([item != "minecraft:air" for item in local], dtype=bool)[indices]
            valid = np.nonzero(mask)[0]
            if not len(valid):
                continue
            coords = np.stack((chunk_x * 16 + (valid >> 8),
                               y * 16 + (valid & 15),
                               chunk_z * 16 + ((valid >> 4) & 15)), axis=1).astype(np.int32)
            # Recreate full state names for this storage and look up their IDs.
            mapping = []
            for item in states:
                name = item.get("name", item.get("Name", "minecraft:air"))
                properties = item.get("states", item.get("Properties", {}))
                if properties:
                    name += "[" + ",".join(f"{k}={v}" for k, v in sorted(properties.items())) + "]"
                mapping.append(names[name])
            positions.append(coords)
            codes.append(np.asarray(mapping, dtype=np.uint16)[indices[valid]])
        if not positions:
            raise ValueError("Reference world has no non-air blocks")
        return np.concatenate(positions), np.concatenate(codes), list(names)


def bedrock_base_material(state: str) -> str:
    """Convert common legacy Bedrock block-state identifiers to Java names."""
    base, _, properties = state.partition("[")
    data = {}
    if properties:
        data = dict(part.split("=", 1) for part in properties.rstrip("]").split(","))
    name = base.removeprefix("minecraft:")
    color = data.get("color")
    if name == "planks":
        name = data.get("wood_type", "oak") + "_planks"
    elif name == "wood":
        species = data.get("wood_type", "oak")
        name = ("stripped_" if data.get("stripped_bit") == "1" else "") + species + "_wood"
    elif name in ("concrete", "concrete_powder", "stained_glass", "stained_glass_pane") and color:
        name = ("light_gray" if color == "silver" else color) + "_" + name
    elif name == "stained_hardened_clay" and color:
        name = color + "_terracotta"
    elif name == "stone":
        name = {"andesite_smooth": "polished_andesite", "granite_smooth": "polished_granite",
                "diorite_smooth": "polished_diorite"}.get(data.get("stone_type"), "stone")
    elif name == "stonebrick":
        name = {"chiseled": "chiseled_stone_bricks", "mossy": "mossy_stone_bricks",
                "cracked": "cracked_stone_bricks"}.get(data.get("stone_brick_type"), "stone_bricks")
    elif name == "sandstone" and data.get("sand_stone_type") == "smooth":
        name = "smooth_sandstone"
    elif name == "grass":
        name = "grass_block"
    elif name == "snow":
        name = "snow_block"
    elif name == "carpet" and color:
        name = ("light_gray" if color == "silver" else color) + "_carpet"
    elif name == "tallgrass":
        name = "fern" if data.get("tall_grass_type") == "fern" else "short_grass"
    elif name == "deadbush":
        name = "dead_bush"
    elif name == "leaves":
        name = data.get("old_leaf_type", "oak") + "_leaves"
    elif name.startswith("double_stone_block_slab") or name.startswith("stone_block_slab"):
        slab_type = (data.get("stone_slab_type_4") or data.get("stone_slab_type_3")
                     or data.get("stone_slab_type_2") or data.get("stone_slab_type"))
        name = {"polished_andesite": "polished_andesite", "smooth_sandstone": "smooth_sandstone",
                "quartz": "quartz_block", "stone": "stone"}.get(slab_type, slab_type or "stone")
    elif name == "wooden_slab":
        name = data.get("wood_type", "oak") + "_planks"
    elif name.endswith("_stairs"):
        name = {"smooth_sandstone_stairs": "smooth_sandstone",
                "polished_andesite_stairs": "polished_andesite"}.get(name, name.replace("_stairs", "_planks"))
    elif name.endswith("_fence"):
        name = {"nether_brick_fence": "nether_bricks"}.get(name, name.replace("_fence", "_planks"))
    elif name == "mangrove_leaves":
        pass
    elif name == "waterlily":
        name = "lily_pad"
    return "minecraft:" + name


def calibrate(glb: Path, world: Path, schematic: Path, output: Path,
              vanilla_pack: Path, min_training: int = 24,
              min_purity: float = 0.85):
    """Use a known-answer world to learn texture->base-material mappings.

    A deterministic spatial holdout is reserved for evaluation. Output entries
    may only generalize to GLBs using the SAME atlas pixels, not to unrelated
    Sketchfab models. GT interior blocks are never substituted into the output.
    """
    import sys
    import trimesh

    from sketchrip import Match, reconstruct
    from sketchrip_schematic_read import read_sketchrip_schematic

    print("[calibrate] reading Bedrock world",flush=True)
    coords, codes, names = read_bedrock_world(world)
    print("[calibrate] indexed Bedrock world",len(coords),flush=True)
    truth_min = coords.min(axis=0).astype(np.int32)
    truth_max = coords.max(axis=0).astype(np.int32)
    size = (truth_max - truth_min + 1).astype(np.int32)
    if np.prod(size.astype(np.int64)) > 500_000_000:
        raise ValueError("Reference world too large for in-memory calibration")
    volume = np.zeros(tuple(size), dtype=np.uint16)
    idx = coords - truth_min
    volume[idx[:, 0], idx[:, 1], idx[:, 2]] = codes + 1

    source = read_sketchrip_schematic(schematic)
    print("[calibrate] read baseline schematic",len(source),flush=True)
    positions = np.asarray(list(source), dtype=np.int32)
    current_names = np.asarray(list(source.values()))
    # Recover translation; do not assume MinBounds equals Bedrock MinBounds.
    start = truth_min - positions.min(axis=0)
    candidates = []
    for x in range(int(start[0])-3,int(start[0])+4):
        for y in range(int(start[1])-3,int(start[1])+4):
            for z in range(int(start[2])-3,int(start[2])+4):
                tr = positions + np.array((x, y, z))
                index = tr - truth_min
                valid = np.all((index >= 0) & (index < size), axis=1)
                candidate = index[valid]
                overlap = int(np.count_nonzero(volume[candidate[:,0],candidate[:,1],candidate[:,2]]))
                candidates.append((overlap,(x,y,z)))
    overlap, translation = max(candidates)
    print("[calibrate] registration",overlap,translation,flush=True)
    if overlap < len(positions) * 0.70:
        raise ValueError("Reference world does not align with reconstructed schematic")

    class AtlasIdentity:
        match_mode = "fast"
        fast_approx = True
        catalog_mode = "model"
        material_policy = "logical"
        logical_selections = {}
        names = []
        def __init__(self):
            self.images = {}
        def match(self, image: Image.Image) -> Match:
            pixels = np.asarray(image.convert("RGBA").resize((16,16),Image.Resampling.BOX),dtype=np.uint8)
            fingerprint = hashlib.sha256(pixels.tobytes()).hexdigest()
            block = "minecraft:t" + fingerprint
            self.images[fingerprint] = pixels
            return Match(block,1.0,"texture-fingerprint",(),False)

    scene = trimesh.load(str(glb), force="scene", process=False)
    meshes = [mesh for mesh in scene.dump(concatenate=False) if isinstance(mesh, trimesh.Trimesh)]
    index = AtlasIdentity()
    print("[calibrate] sampling GLB atlas",flush=True)
    sampled, scan_report = reconstruct(meshes,index,allow_uncertain=True)
    print("[calibrate] atlas sampled",len(sampled),flush=True)
    rows = collections.defaultdict(lambda: {"train": collections.Counter(),"test": collections.Counter()})
    source_by_coord = source
    pred_coords = np.asarray(list(sampled),dtype=np.int32)
    pred_names = list(sampled.values())
    abs_coords = pred_coords + np.asarray(translation)
    relative = abs_coords - truth_min
    valid = np.all((relative>=0)&(relative<size),axis=1)
    names_material = [bedrock_base_material(name) for name in names]
    tested = 0
    sample_count = 0
    for n in np.flatnonzero(valid):
        x,y,z=relative[n]
        code = int(volume[x,y,z])
        if code==0:
            continue
        fingerprint = pred_names[n].removeprefix("minecraft:t")
        label = names_material[code-1]
        world_x,world_z=int(abs_coords[n,0]),int(abs_coords[n,2])
        # Split by XZ chunk, not by triangle, to prevent correlated leakage.
        spatial=((world_x//16)*73856093)^((world_z//16)*19349663)
        partition="test" if (spatial&3)==0 else "train"
        rows[fingerprint][partition][label]+=1
        sample_count+=1

    trained={}
    rejected=[]
    for fingerprint,record in rows.items():
        total=sum(record["train"].values())
        if not total:
            rejected.append({"fingerprint":fingerprint,"reason":"missing_train"})
            continue
        best,n=record["train"].most_common(1)[0]
        purity=n/total
        if total<min_training or purity<min_purity:
            rejected.append({"fingerprint":fingerprint,"reason":"insufficient_evidence",
                             "train":total,"purity":purity,"choices":record["train"].most_common(8)})
            continue
        trained[fingerprint] = {"block":best,"note":f"Ground-truth calibrated; train={total}; purity={purity:.4f}",
                                "train_support":total,"train_purity":round(purity,4)}

    evaluated=0; correct=0; skipped=0; old_correct=0;old_evaluated=0
    confusion=collections.Counter()
    for fingerprint,record in rows.items():
        for expected,count in record["test"].items():
            evaluated+=count
            if fingerprint not in trained:
                skipped+=count
                continue
            inferred=trained[fingerprint]["block"]
            if inferred==expected:
                correct+=count
            else:
                confusion[(expected,inferred)]+=count

    report={"reference_world":str(world),"source_glb":str(glb),"baseline_schematic":str(schematic),
            "truth_nonair_blocks":len(coords),"baseline_surface_positions":len(source),
            "aligned_surface_positions":overlap,"translation":list(translation),
            "atlas_fingerprints":len(rows),"accepted_fingerprints":len(trained),
            "heldout_samples":evaluated,"heldout_covered":evaluated-skipped,
            "heldout_correct":correct,"heldout_accuracy_on_covered":round(correct/max(evaluated-skipped,1),6),
            "heldout_coverage":round((evaluated-skipped)/max(evaluated,1),6),
            "heldout_mistakes":sum(confusion.values()),
            "top_confusions":[{"expected":a,"predicted":b,"count":c}
                              for (a,b),c in confusion.most_common(30)],
            "rejected_tiles":rejected,"training_samples":sample_count,
            "warning":"This atlas calibration learns from the reference world and is valid only for identical source atlas pixels. Hidden geometry and block states remain unrecoverable from a GLB."}
    print("[calibrate] heldout",evaluated,correct,flush=True)
    output.parent.mkdir(parents=True,exist_ok=True)
    output.write_text(json.dumps({"source":"SketchRip supervised atlas calibration",
                                  "notes":report["warning"],"entries":trained},indent=2),encoding="utf-8")
    output.with_suffix(".report.json").write_text(json.dumps(report,indent=2),encoding="utf-8")
    return report


def main(argv=None):
    import argparse
    parser=argparse.ArgumentParser(description="Calibrate GLB atlas against a known Bedrock world")
    parser.add_argument("glb",type=Path)
    parser.add_argument("--world",type=Path,required=True)
    parser.add_argument("--baseline-schematic",type=Path,required=True)
    parser.add_argument("--out",type=Path,required=True)
    parser.add_argument("--min-training",type=int,default=24)
    parser.add_argument("--min-purity",type=float,default=.85)
    args=parser.parse_args(argv)
    report=calibrate(args.glb,args.world,args.baseline_schematic,args.out,None,args.min_training,args.min_purity)
    print(json.dumps({key:value for key,value in report.items() if key not in ("rejected_tiles","top_confusions")},indent=2))
    print("Report:",args.out.with_suffix(".report.json"))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
