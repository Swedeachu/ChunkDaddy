"""Conservative texture-to-block selection for Minecraft mesh reconstruction.

Pixel similarity identifies a sprite candidate, not a block. Modern vanilla packs
contain shelves, beds and shulker-box sprites whose RGB values closely match
ordinary construction materials. An approximate match to a special block must
clear a substantially higher evidence bar than an approximate cube material.

This policy never overrides an exact vanilla sprite fingerprint or a user atlas
mapping; genuinely special blocks remain possible with strong texture evidence
and are refined by the separate voxel geometry/state pass.
"""

from __future__ import annotations

from dataclasses import dataclass

import numpy as np


@dataclass(frozen=True)
class PreferredTexture:
    index: int
    original_index: int
    reason: str | None


def is_complex(block: str) -> bool:
    name = block.removeprefix("minecraft:").split("[", 1)[0]
    return (name.endswith(("_shelf", "_bed", "_shulker_box", "_stairs",
                           "_slab", "_fence", "_fence_gate", "_pane",
                           "_door", "_trapdoor", "_wall", "_button",
                           "_pressure_plate", "_hanging_sign", "_sign"))
            or name in {"shulker_box", "dried_ghast", "ladder",
                        "barrel", "chest", "trapped_chest", "ender_chest"})


def penalty(block: str, texture: str) -> float:
    """Cost multiplier for an *approximate* image match, not an existence rule.

    Penalties are intentionally strongest where full-cube neighborhood materials
    are routinely classified as new decorative blocks. Do not penalize an
    unambiguous exact texture match; that is handled by the caller.
    """
    name = block.removeprefix("minecraft:").split("[", 1)[0]
    texture = texture.lower()
    if name.endswith("_shelf"):
        return 1.35
    if name.endswith("_bed"):
        return 2.30
    if name.endswith("_shulker_box") or name == "shulker_box":
        return 1.40
    if name == "dried_ghast":
        return 1.85
    if name.endswith(("_hanging_sign", "_sign")):
        return 1.50
    if name.endswith(("_stairs", "_slab", "_fence", "_fence_gate", "_wall", "_pane")):
        return 1.22
    if name.endswith(("_door", "_trapdoor", "_button", "_pressure_plate")):
        return 1.26
    if name in {"barrel", "chest", "trapped_chest", "ender_chest"}:
        return 1.24
    # A direct texture of a commonplace building block needs no statistical
    # popularity prior. That avoids turning stone bricks into generic stone.
    return 1.0


def choose(distances: np.ndarray, candidate_indices: np.ndarray,
           names: list[str], blocks: list[str],
           max_relative_error: float = 2.4) -> PreferredTexture:
    """Choose the nearest plausible sprite among candidates by pixel error+prior.

    `distances` is squared full RGBA difference in candidate order. Only
    alternate sprites within a bounded raw-error ratio may replace the nearest
    pixel match. Otherwise the material is too unlike any familiar solid block
    to justify a substitution. This bounded approach is deterministic and O(k)
    for the fixed-size nearest-neighbor candidate set, not O(all blockstates).
    """
    if not len(distances):
        raise ValueError("No texture candidates")
    raw_error = np.sqrt(np.maximum(distances, 0.0))
    original = int(np.argmin(raw_error))
    if raw_error[original] < 1e-5:
        return PreferredTexture(int(candidate_indices[original]),
                                int(candidate_indices[original]), None)
    eligible = raw_error <= max(raw_error[original] * max_relative_error, raw_error[original] + 1.0)
    cost = np.full(raw_error.shape, float("inf"), dtype=np.float64)
    nearest_block = blocks[int(candidate_indices[original])]
    shulker_confusion = nearest_block.endswith("_shulker_box") or nearest_block == "minecraft:shulker_box"
    for i in np.flatnonzero(eligible):
        idx = int(candidate_indices[i])
        weight = penalty(blocks[idx], names[idx])
        # A shulker-box facade often resembles dyed fabric. Among similarly
        # colored regular materials favor the fabric sprite over loose powder,
        # but do not bias powder in other contexts where it may be genuine.
        if shulker_confusion and blocks[idx].endswith("_concrete_powder"):
            weight *= 1.12
        cost[i] = raw_error[i] * weight
    selected = int(np.argmin(cost))
    before = int(candidate_indices[original])
    after = int(candidate_indices[selected])
    reason = None
    if before != after:
        reason = "approximate special-block candidate deprioritized"
    return PreferredTexture(after, before, reason)
