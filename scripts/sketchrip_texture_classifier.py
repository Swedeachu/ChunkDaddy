"""Calibrated texture identity classifier. Geometry/blockstate selection is separate.

Every candidate carries its original sprite identity and optional authoring-model
block references. Similar-looking textures never become 'certain' based on the
nearest neighbor alone.
"""

from __future__ import annotations

import collections
from dataclasses import dataclass

import numpy as np
from PIL import Image


@dataclass(frozen=True)
class TexturePrediction:
    texture: str
    block: str
    confidence: float
    margin: float
    candidates: tuple[str, ...]
    ambiguous: bool
    rotation: int


class CalibratedTextureMatcher:
    def __init__(self, index):
        from scipy.spatial import cKDTree
        self.index = index
        self.names = index.names
        self.blocks = index.blocks
        raw = index.descriptors16.reshape(-1, 16, 16, 4)
        rotated = np.concatenate([np.rot90(raw, k=k, axes=(1, 2)) for k in range(4)], axis=0)
        self.raw = rotated
        self.rotation = np.repeat(np.arange(4), len(raw))
        self.original_index = np.tile(np.arange(len(raw)), 4)
        self.full_rgb = rotated[:, :, :, :3].astype(np.float32).reshape(-1, 768)
        self.alpha = rotated[:, :, :, 3].astype(np.float32).reshape(-1, 256)
        self.coarse = self._coarse(rotated)
        self.mean = self.coarse.mean(axis=1, keepdims=True)
        self.normed = (self.coarse - self.mean) / np.maximum(
            np.std(self.coarse, axis=1, keepdims=True), 12.0)
        # High-dimensional (192D) KD-trees are effectively linear scans on
        # atlas-sized workloads. PCA to 28D keeps the discriminating structure
        # while enabling fast tree pruning; final scoring still reads all texels.
        self.raw_center, self.raw_basis = self._basis(self.coarse, 28)
        self.shape_center, self.shape_basis = self._basis(self.normed, 28)
        self.raw_tree = cKDTree((self.coarse - self.raw_center) @ self.raw_basis)
        self.shape_tree = cKDTree((self.normed - self.shape_center) @ self.shape_basis)
        self.exact = collections.defaultdict(list)
        self.rotated_exact = collections.defaultdict(list)
        for i, pixels in enumerate(raw):
            self.exact[pixels.tobytes()].append(i)
        for j, pixels in enumerate(rotated):
            self.rotated_exact[pixels.tobytes()].append(int(self.original_index[j]))
        self.cache = {}

    @staticmethod
    def _coarse(images):
        # 8x8 average blurring suppresses JPEG DCT ringing while retaining the
        # pixel structure needed to distinguish stones, planks and vegetation.
        result = images[:, :, :, :3].reshape(-1, 8, 2, 8, 2, 3).mean(axis=(2, 4))
        return result.reshape(-1, 192).astype(np.float32)

    @staticmethod
    def _basis(samples, components):
        center = samples.mean(axis=0)
        shifted = samples - center
        covariance = shifted.T @ shifted
        values, vectors = np.linalg.eigh(covariance)
        basis = vectors[:, np.argsort(values)[-components:][::-1]]
        return center, basis

    def identify(self, image: Image.Image, allow_rotation: bool = True) -> TexturePrediction:
        img = image.convert("RGBA").resize((16, 16), Image.Resampling.BOX)
        pixel = np.asarray(img)
        key = (pixel.tobytes(), allow_rotation)
        cached = self.cache.get(key)
        if cached:
            return cached
        exact = self.exact.get(pixel.tobytes())
        if exact:
            variants = sorted(set(exact))
            i = variants[0]
            result = TexturePrediction(self.names[i], self.blocks[i],
                                       1.0 if len(variants) == 1 else 0.0, 1.0,
                                       tuple(self.names[k] for k in variants),
                                       len(variants) != 1, 0)
            self.cache[key] = result
            return result

        if allow_rotation:
            exact_rotations = sorted(set(self.rotated_exact.get(pixel.tobytes(), ())))
            if exact_rotations:
                # Rotations can make *different* vanilla sprites pixel-identical.
                # Do not claim their IDs were recovered uniquely from those pixels.
                i = exact_rotations[0]
                names = tuple(self.names[j] for j in exact_rotations)
                result = TexturePrediction(self.names[i], self.blocks[i],
                                           1.0 if len(names) == 1 else 0.0,
                                           1.0 if len(names) == 1 else 0.0,
                                           names, len(names) != 1, 0)
                self.cache[key] = result
                return result
        coarse = self._coarse(pixel[None, ...])[0]
        mu = coarse.mean()
        normalized = (coarse - mu) / max(coarse.std(), 12.0)
        k = min(96, len(self.full_rgb))
        raw_projected = (coarse - self.raw_center) @ self.raw_basis
        shape_projected = (normalized - self.shape_center) @ self.shape_basis
        raw_near = np.atleast_1d(self.raw_tree.query(raw_projected, k=k)[1])
        shape_near = np.atleast_1d(self.shape_tree.query(shape_projected, k=k)[1])
        candidates = np.unique(np.concatenate((raw_near, shape_near)))
        if not allow_rotation:
            candidates = candidates[self.rotation[candidates] == 0]
        if not len(candidates):
            raise ValueError("No texture candidates")
        query = pixel.reshape(256, 4).astype(np.float32)
        # Alpha is preserved by ordinary GLB exports. Never allow a JPEG that
        # erased alpha to become a confident opaque-block classification.
        query_rgb = query[:, :3].reshape(1, -1)
        rgb = self.full_rgb[candidates]
        alpha = self.alpha[candidates]
        diff = rgb - query_rgb
        raw_error = np.mean(diff * diff, axis=1)
        coarse_error = np.mean((self.coarse[candidates] - coarse) ** 2, axis=1)
        # Recover uniform lighting/biome color shifts with fitted gains. Use a
        # bounded correction and a penalty to avoid collapsing colored concrete
        # into a similarly patterned but different colored block.
        channel_q = query_rgb.reshape(1, 256, 3)
        channel_s = rgb.reshape(-1, 256, 3)
        gains = np.sum(channel_q * channel_s, axis=1) / np.maximum(
            np.sum(channel_s * channel_s, axis=1), 1.0)
        gains = np.clip(gains, 0.75, 1.25)
        adjusted = np.mean((channel_s * gains[:, None, :] - channel_q) ** 2, axis=(1, 2))
        gain_penalty = 85.0 * np.mean((gains - 1.0) ** 2, axis=1)
        adjusted_coarse = np.mean((self.coarse[candidates].reshape(-1, 64, 3) *
                                   gains[:, None, :] - coarse.reshape(1, 64, 3)) ** 2, axis=(1, 2))
        alpha_error = np.mean((alpha - query[:, 3]) ** 2, axis=1)
        # Structural + color evidence, with raw RGB on near-lossless sprites.
        rgb_score = np.minimum(raw_error * 0.42 + coarse_error * 0.58,
                               adjusted * 0.42 + adjusted_coarse * 0.58 + gain_penalty)
        score = rgb_score + alpha_error * 0.55
        order = np.argsort(score)
        selected = int(candidates[order[0]])
        if self.index.material_policy == "logical":
            from sketchrip_material_policy import choose
            # The rotated candidate rows refer back to one base sprite ID.
            # Choose that ID with the same material prior as the fast matcher,
            # then retain whichever rotation had its smallest image error.
            base_ids = self.original_index[candidates]
            decision = choose(score, base_ids, self.names, self.blocks)
            self.index._record_logical_selection(decision)
            alternatives = np.flatnonzero(base_ids == decision.index)
            if len(alternatives):
                selected = int(candidates[alternatives[np.argmin(score[alternatives])]])
        score0 = float(score[np.where(candidates == selected)[0][0]])
        runner = next((float(score[o]) for o in order[1:] if
                       self.original_index[candidates[o]] != self.original_index[selected]),
                      float('inf'))
        margin = max(0.0, (runner - score0) / max(runner, 1.0))
        # Score is RGB pixel MSE (~0 exact, 100 moderate corruption); a large
        # second-best gap is necessary for reliable acceptance.
        confidence = float(np.exp(-np.sqrt(max(score0, 0.0)) / 38.0))
        ambiguous = margin < 0.09 or confidence < 0.68
        tied = [int(candidates[o]) for o in order[:12]
                if float(score[o]) - score0 <= max(10.0, score0 * .09)]
        names = tuple(dict.fromkeys(self.names[int(self.original_index[j])] for j in tied))
        original = int(self.original_index[selected])
        prediction = TexturePrediction(self.names[original], self.blocks[original],
                                       confidence, margin, names, ambiguous,
                                       int(self.rotation[selected]) * 90)
        if len(self.cache) > 65536:
            self.cache.clear()
        self.cache[key] = prediction
        return prediction
