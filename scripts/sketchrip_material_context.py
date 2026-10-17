"""Conservative multi-face material reconciliation for reconstructed block meshes.

A bark texture can look like another wood species. A visible log end, on the
other hand, often distinguishes its tree species. Use that evidence only on
connected logs with the same observed side-atlas texture; never convert dirt,
terracotta, or other brown cubes by color/popularity alone.
"""

from __future__ import annotations

import collections
import re

LOG_END = re.compile(r"^(oak|spruce|birch|jungle|acacia|dark_oak|mangrove|cherry|pale_oak)_log_top$")


def reconcile_wood(blocks: dict, side_tiles: dict, end_grain: dict,
                   maximum_trunk_length: int = 48) -> tuple[dict, dict]:
    corrected = dict(blocks)
    changes = {}
    ambiguous = 0
    for point, candidates in end_grain.items():
        if point not in blocks:
            continue
        original = blocks[point].removeprefix('minecraft:').split('[', 1)[0]
        if not (original.endswith(('_log', '_wood')) or original.startswith('stripped_')):
            continue
        strong = [(species, value) for species, value in candidates.items() if value >= .90]
        if not strong:
            continue
        species, score = max(strong, key=lambda entry: entry[1])
        if any(other != species and strength >= score * .95 for other, strength in strong):
            ambiguous += 1
            continue
        anchored = 'minecraft:' + species + '_log'
        if original != anchored.removeprefix('minecraft:'):
            corrected[point] = anchored
            changes[point] = (blocks[point], anchored)

        # Avoid spreading through connected wood planks. Only contiguous logs
        # with the same dominant bark tile can inherit a known end-grain species.
        observed = side_tiles.get(point, {})
        if not observed:
            continue
        tile = max(observed, key=observed.get)
        for step in (-1, 1):
            for offset in range(1, maximum_trunk_length + 1):
                neighbor = (point[0], point[1] + step * offset, point[2])
                if neighbor not in blocks:
                    break
                current = blocks[neighbor].removeprefix('minecraft:').split('[', 1)[0]
                if not (current.endswith(('_log', '_wood')) or current.startswith('stripped_')):
                    break
                tiles = side_tiles.get(neighbor, {})
                if not tiles or max(tiles, key=tiles.get) != tile:
                    break
                neighbor_end = end_grain.get(neighbor, {})
                if any(other != species and strength >= .90
                       for other, strength in neighbor_end.items()):
                    break
                if current != anchored.removeprefix('minecraft:'):
                    corrected[neighbor] = anchored
                    changes[neighbor] = (blocks[neighbor], anchored)
    return corrected, {"corrected_positions": len(changes), "ambiguous_end_grain": ambiguous,
                       "changes_by_pair": dict(collections.Counter(
                           f"{old} -> {new}" for old, new in changes.values()))}
