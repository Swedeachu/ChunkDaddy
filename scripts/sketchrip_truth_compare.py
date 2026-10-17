"""Compare a SketchRip schematic with the *actual* block data in a Bedrock world.

Uses original world-space block coordinates; does not inflate accuracy by
counting air in generated chunks. Materials, shapes and stair facing/half are
reported separately because a correct solid material does not imply a correct
Minecraft block state.
"""

from __future__ import annotations

import argparse
import collections
import json
from pathlib import Path

import numpy as np

from sketchrip_schematic_read import read_sketchrip_schematic
from sketchrip_truth_calibration import bedrock_base_material, read_bedrock_world


def material_of(name: str) -> str:
    base = name.split("[", 1)[0].removeprefix("minecraft:")
    if base.endswith("_stairs"):
        base = {"polished_andesite_stairs": "polished_andesite",
                "smooth_sandstone_stairs": "smooth_sandstone"}.get(base,base[:-7]+"_planks")
    elif base.endswith("_slab"):
        base = base[:-5]
    elif base.endswith("_fence"):
        base = {"nether_brick_fence": "nether_bricks"}.get(base,base[:-6]+"_planks")
    elif base.endswith("_stained_glass_pane"):
        base = base.removesuffix("_pane")
    return "minecraft:" + base


def shape_of(name: str) -> str:
    """Identify physical block families; double slabs occupy full cubes."""
    base = name.split('[', 1)[0].removeprefix('minecraft:')
    if base.startswith('double_') and 'slab' in base:
        return 'double_slab'
    if base.endswith('_slab') and props(name).get('type') == 'double':
        return 'double_slab'
    base = name.split("[", 1)[0]
    if base.endswith("_stairs"):
        return "stairs"
    if "slab" in base:
        return "slab"
    if base.endswith("_fence"):
        return "fence"
    if "cobblestone_wall" in base:
        return "wall"
    if "trapdoor" in base:
        return "trapdoor"
    if base.endswith("button"):
        return "button"
    if "ladder" in base:
        return "ladder"
    if "pane" in base:
        return "pane"
    return "other"


def props(state: str):
    items = state.partition("[")[2].rstrip("]")
    return dict(part.split("=", 1) for part in items.split(",") if "=" in part)


def compare(world: Path, schematic: Path, translation=None):
    coords, code, state_names = read_bedrock_world(world)
    original = read_sketchrip_schematic(schematic)
    source = {tuple(map(int, p)): state_names[int(c)] for p, c in zip(coords, code)}
    locations = np.asarray(list(original), dtype=np.int32)
    if translation is None:
        inferred = coords.min(axis=0) - locations.min(axis=0)
        candidates = []
        minimum = coords.min(axis=0).astype(np.int32)
        size = (coords.max(axis=0) - minimum + 1).astype(np.int32)
        if np.prod(size.astype(np.int64)) > 500_000_000:
            raise ValueError("Reference world too large")
        occupied = np.zeros(tuple(size), dtype=np.uint8)
        relative_gt = coords - minimum
        occupied[relative_gt[:,0], relative_gt[:,1], relative_gt[:,2]] = 1
        for x in range(int(inferred[0])-3, int(inferred[0])+4):
            for y in range(int(inferred[1])-3, int(inferred[1])+4):
                for z in range(int(inferred[2])-3, int(inferred[2])+4):
                    positions = locations + np.array([x,y,z]) - minimum
                    good = np.all((positions >= 0) & (positions < size), axis=1)
                    samples = positions[good]
                    hits = int(np.count_nonzero(occupied[samples[:,0],samples[:,1],samples[:,2]]))
                    candidates.append((hits,(x,y,z)))
        _,translation = max(candidates)
    confusion = collections.Counter()
    shapes = collections.Counter()
    orientation = collections.Counter()
    overlap = 0
    correct = 0
    slab_halves = collections.Counter()
    doubles = collections.Counter()
    for (x,y,z),predicted in original.items():
        actual = source.get((x+translation[0],y+translation[1],z+translation[2]))
        if actual is None:
            continue
        overlap += 1
        predicted_mat=material_of(predicted)
        actual_mat=material_of(bedrock_base_material(actual))
        if predicted_mat == actual_mat:
            correct += 1
        else:
            confusion[(predicted_mat,actual_mat)] += 1
        expected_shape = shape_of(actual)
        predicted_shape = shape_of(predicted)
        shapes[(expected_shape,"expected")] += 1
        shapes[(predicted_shape,"predicted")] += 1
        if expected_shape == predicted_shape:
            shapes[(expected_shape,"correct")] += 1
        if expected_shape == 'slab':
            slab_halves['compared'] += 1
            expected_top = int(props(actual).get('top_slot_bit', -1))
            predicted_top = (props(predicted).get('type') == 'top')
            if expected_top in (0, 1) and expected_shape == predicted_shape:
                slab_halves['correct'] += int(predicted_top == bool(expected_top))
        if expected_shape == 'double_slab':
            doubles['compared'] += 1
            doubles['full_height_geometry'] += int(predicted_shape in ('other', 'double_slab'))
            doubles['exact_double_family'] += int(predicted_shape == 'double_slab')
        if expected_shape == "stairs" and predicted_shape == "stairs":
            facing = {"east":0,"west":1,"south":2,"north":3}.get(props(predicted).get("facing"),-1)
            true = props(actual)
            orientation["compared"] += 1
            orientation["facing_correct"] += int(facing == int(true.get("weirdo_direction",-1)))
            orientation["half_correct"] += int((props(predicted).get("half")=="top")
                                               == bool(int(true.get("upside_down_bit",-1))))
    categories = sorted({x for x,_ in shapes if x != "other"})
    return {
        "reference_nonair":len(source),"predicted_nonair":len(original),
        "translation":list(translation),"overlap":overlap,
        "occupancy_precision":round(overlap/max(len(original),1),6),
        "reference_coverage":round(overlap/max(len(source),1),6),
        "material_accuracy_on_overlap":round(correct/max(overlap,1),6),
        "material_correct":correct,
        "top_material_mistakes":[{"predicted":a,"actual":b,"count":n}
                                  for (a,b),n in confusion.most_common(40)],
        "shapes":{cat:{"reference_visible":shapes[(cat,"expected")],
                         "predicted":shapes[(cat,"predicted")],
                         "correct_family":shapes[(cat,"correct")]}
                  for cat in categories},
        "slab_halves":{"compared":slab_halves['compared'],
                       "correct":slab_halves['correct'],
                       "accuracy":round(slab_halves['correct']/max(slab_halves['compared'],1),6)},
        "double_slabs":{"compared":doubles['compared'],
                         "full_height_geometry":doubles['full_height_geometry'],
                         "exact_double_family":doubles['exact_double_family'],
                         "note":"Full cubes can be visually identical to double slabs"},
        "stairs_facing":{"compared":orientation["compared"],
                         "correct":orientation["facing_correct"],
                         "accuracy":round(orientation["facing_correct"]/max(orientation["compared"],1),6),
                         "half_correct":orientation["half_correct"]},
        "warning":"Surface/block-family scores do not imply hidden-block or exact state recovery."
    }


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--world",type=Path,required=True)
    parser.add_argument("--schematic",type=Path,required=True)
    parser.add_argument("--report",type=Path)
    args=parser.parse_args()
    output=compare(args.world,args.schematic)
    if args.report:
        args.report.parent.mkdir(parents=True,exist_ok=True)
        args.report.write_text(json.dumps(output,indent=2),encoding="utf-8")
    print(json.dumps(output,indent=2))


if __name__=="__main__":
    main()
