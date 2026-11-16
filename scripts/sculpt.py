#!/usr/bin/env python3
"""Sculpt: world-edit style terrain tools that build a block volume ChunkDaddy can stamp into a world.

A volume is the same on-disk format `terrain_volume` dumps and `stamp_volume` reads:
volume.json (bounds, z,y,x order, palette of Chunker block names) beside blocks.u16.gz
(gzip big-endian uint16). Sculpt adds markers.json for named points such as spawns.

    sculpt.py build SPEC.json --out DIR          run a spec (size + ops) into a fresh volume
    sculpt.py apply DIR OPS.json                 run more ops on an existing volume, in place
    sculpt.py op DIR OPNAME key=value ...        one op from the shell (values parsed as JSON)
    sculpt.py info DIR                           bounds, palette counts and markers
    sculpt.py render DIR --out PNG [options]     textured preview (see sculpt_render.py)

Ops are listed in docs/Sculpting.md. Coordinates are local to the volume (0..width-1 etc.);
volume.json's minX/minY/minZ place it in the world, and stamp_volume can offset it again.
"""
from __future__ import annotations

import argparse
import gzip
import json
import math
import sys
from pathlib import Path

import numpy as np

# --------------------------------------------------------------------------------------------
# Blocks and palettes

def parse_block(spec) -> dict:
    """'oak_log[axis=x]' or {'type': 'OAK_LOG', 'states': {...}} -> palette entry."""
    if isinstance(spec, dict):
        entry = {"type": spec["type"].upper().replace("MINECRAFT:", "")}
        if spec.get("states"):
            entry["states"] = {k: str(v) for k, v in spec["states"].items()}
        return entry
    text = str(spec).strip()
    states = {}
    if "[" in text:
        text, rest = text.split("[", 1)
        for pair in rest.rstrip("]").split(","):
            if pair.strip():
                key, value = pair.split("=", 1)
                states[key.strip()] = value.strip()
    entry = {"type": text.strip().upper().replace("MINECRAFT:", "")}
    if states:
        entry["states"] = states
    return entry


def block_key(entry: dict) -> str:
    states = entry.get("states") or {}
    if not states:
        return entry["type"]
    return entry["type"] + "[" + ",".join(f"{k}={states[k]}" for k in sorted(states)) + "]"


class Volume:
    def __init__(self, width, height, depth, minX=0, minY=0, minZ=0):
        self.width, self.height, self.depth = int(width), int(height), int(depth)
        self.minX, self.minY, self.minZ = int(minX), int(minY), int(minZ)
        self.a = np.zeros((self.depth, self.height, self.width), dtype=np.uint16)  # z, y, x
        self.palette = [{"type": "AIR"}]
        self._ids = {"AIR": 0}
        self.markers: list[dict] = []
        self.regions: list[dict] = []

    # palette ---------------------------------------------------------------
    def id(self, spec) -> int:
        entry = parse_block(spec)
        key = block_key(entry)
        if key not in self._ids:
            if len(self.palette) >= 65535:
                raise ValueError("palette full")
            self._ids[key] = len(self.palette)
            self.palette.append(entry)
        return self._ids[key]

    def ids_of_types(self, types) -> np.ndarray:
        wanted = {parse_block(t)["type"] for t in types}
        return np.array([i for i, e in enumerate(self.palette) if e["type"] in wanted], dtype=np.uint16)

    def type_of(self, i: int) -> str:
        return self.palette[i]["type"]

    # io --------------------------------------------------------------------
    def save(self, directory: Path):
        directory.mkdir(parents=True, exist_ok=True)
        # Drop unused palette entries so stamp_volume and renders stay tidy.
        used = np.unique(self.a)
        remap = np.zeros(len(self.palette), dtype=np.uint16)
        palette = []
        for new, old in enumerate(sorted(set(used.tolist()) | {0})):
            remap[old] = new
            palette.append(self.palette[old])
        data = remap[self.a]
        with gzip.open(directory / "blocks.u16.gz", "wb", compresslevel=6) as out:
            out.write(data.astype(">u2").tobytes(order="C"))
        meta = {"schemaVersion": 1, "minX": self.minX, "minY": self.minY, "minZ": self.minZ,
                "width": self.width, "height": self.height, "depth": self.depth,
                "order": "z,y,x", "encoding": "gzip-big-endian-uint16", "palette": palette,
                "generator": "sculpt"}
        (directory / "volume.json").write_text(json.dumps(meta, indent=1))
        (directory / "markers.json").write_text(json.dumps(
            {"markers": self.markers, "regions": self.regions}, indent=1))

    @classmethod
    def load(cls, directory: Path) -> "Volume":
        meta = json.loads((directory / "volume.json").read_text())
        v = cls(meta["width"], meta["height"], meta["depth"], meta["minX"], meta["minY"], meta["minZ"])
        raw = gzip.open(directory / meta.get("blocksFile", "blocks.u16.gz")).read()
        v.a = np.frombuffer(raw, dtype=">u2").astype(np.uint16).reshape(v.depth, v.height, v.width).copy()
        v.palette = [parse_block(e) for e in meta["palette"]]
        v._ids = {block_key(e): i for i, e in enumerate(v.palette)}
        mk = directory / "markers.json"
        if mk.exists():
            data = json.loads(mk.read_text())
            v.markers, v.regions = data.get("markers", []), data.get("regions", [])
        return v

    # helpers ---------------------------------------------------------------
    def grid_xz(self):
        z, x = np.mgrid[0:self.depth, 0:self.width]
        return x.astype(np.float64), z.astype(np.float64)

    def top_y(self, ignore=()) -> np.ndarray:
        """Highest non-air (and not-ignored) y per column, -1 where empty. Shape (depth, width)."""
        solid = self.a != 0
        if len(ignore):
            solid &= ~np.isin(self.a, self.ids_of_types(ignore))
        any_ = solid.any(axis=1)
        top = self.height - 1 - np.argmax(solid[:, ::-1, :], axis=1)
        return np.where(any_, top, -1)


# --------------------------------------------------------------------------------------------
# Noise (seeded gradient noise, vectorised)

def _hash2(ix, iz, seed):
    h = (ix.astype(np.int64) * 374761393 + iz.astype(np.int64) * 668265263 + seed * 2147483647) & 0xFFFFFFFF
    h = ((h ^ (h >> 13)) * 1274126177) & 0xFFFFFFFF
    return (h ^ (h >> 16)) & 0xFFFFFFFF


def perlin(x, z, scale=16.0, seed=0):
    """Gradient noise in roughly [-1, 1]."""
    px, pz = np.asarray(x, np.float64) / scale, np.asarray(z, np.float64) / scale
    ix, iz = np.floor(px).astype(np.int64), np.floor(pz).astype(np.int64)
    fx, fz = px - ix, pz - iz

    def grad(gx, gz, dx, dz):
        a = (_hash2(gx, gz, seed) & 1023) / 1023.0 * 2 * math.pi
        return np.cos(a) * dx + np.sin(a) * dz

    n00 = grad(ix, iz, fx, fz)
    n10 = grad(ix + 1, iz, fx - 1, fz)
    n01 = grad(ix, iz + 1, fx, fz - 1)
    n11 = grad(ix + 1, iz + 1, fx - 1, fz - 1)
    ux, uz = fx * fx * fx * (fx * (fx * 6 - 15) + 10), fz * fz * fz * (fz * (fz * 6 - 15) + 10)
    return ((n00 * (1 - ux) + n10 * ux) * (1 - uz) + (n01 * (1 - ux) + n11 * ux) * uz) * 1.41


def fbm(x, z, scale=32.0, octaves=4, seed=0, ridged=False, persistence=0.5):
    total, amp, norm = 0.0, 1.0, 0.0
    for o in range(octaves):
        n = perlin(x, z, scale / (2 ** o), seed + o * 1013)
        if ridged:
            n = 1.0 - np.abs(n)
            n = n * n * 2 - 1
        total = total + n * amp
        norm += amp
        amp *= persistence
    return total / norm


def hash01(x, y, z, seed=0):
    m = np.uint64(0xFFFFFFFF)
    with np.errstate(over="ignore"):
        h = ((np.asarray(x).astype(np.int64).astype(np.uint64) * np.uint64(73856093))
             ^ (np.asarray(y).astype(np.int64).astype(np.uint64) * np.uint64(19349663))
             ^ (np.asarray(z).astype(np.int64).astype(np.uint64) * np.uint64(83492791))
             ^ np.uint64((int(seed) * 2654435761) & 0xFFFFFFFF)) & m
        h = ((h ^ (h >> np.uint64(15))) * np.uint64(2246822519)) & m
        h = ((h ^ (h >> np.uint64(13))) * np.uint64(3266489917)) & m
        h = (h ^ (h >> np.uint64(16))) & np.uint64(0xFFFFFF)
    return h.astype(np.float64) / float(0xFFFFFF)


def smoothstep(e0, e1, x):
    t = np.clip((x - e0) / (e1 - e0), 0.0, 1.0)
    return t * t * (3 - 2 * t)


class Palette:
    """Weighted block choice. `noise` scale > 0 makes patches; 0 makes per-block speckle."""

    def __init__(self, vol: Volume, spec, scale=0.0, seed=0):
        if isinstance(spec, (str, dict)):
            spec = [spec]
        items = []
        for item in spec:
            if isinstance(item, list):
                items.append((item[0], float(item[1])))
            elif isinstance(item, dict) and "block" in item:
                items.append((item["block"], float(item.get("weight", 1))))
            else:
                items.append((item, 1.0))
        self.ids = np.array([vol.id(b) for b, _ in items], dtype=np.uint16)
        w = np.array([max(1e-9, w) for _, w in items])
        self.cum = np.cumsum(w) / w.sum()
        self.scale, self.seed = float(scale), int(seed)

    def pick(self, x, y, z):
        if len(self.ids) == 1:
            return np.full(np.broadcast(x, y, z).shape, self.ids[0], dtype=np.uint16)
        if self.scale > 0:
            # Smooth noise mapped through its rough CDF so weights stay close to proportions.
            n = fbm(x, z, self.scale, 3, self.seed) + 0.15 * perlin(y * 0 + x, y * 3.1 + z, self.scale * 0.5, self.seed + 7)
            t = np.clip(0.5 + n * 0.85, 0, 0.999999)
        else:
            t = hash01(x, y, z, self.seed)
        return self.ids[np.searchsorted(self.cum, t, side="right").clip(0, len(self.ids) - 1)]


def palette_from(vol, op, key="block", default=None, scale_key="noise", seed=0):
    spec = op.get(key, op.get("palette", default))
    if spec is None:
        raise ValueError(f"op {op.get('op')} needs '{key}' or 'palette'")
    return Palette(vol, spec, op.get(scale_key, 0), op.get("seed", seed))


# --------------------------------------------------------------------------------------------
# Writing helpers

def write_mask(vol: Volume, mask_zyx, pal: Palette, z0, y0, x0, op):
    """Write a palette into vol.a[z0:, y0:, x0:] where mask is set, honouring replace/keep rules."""
    dz, dy, dx = mask_zyx.shape
    view = vol.a[z0:z0 + dz, y0:y0 + dy, x0:x0 + dx]
    mask = mask_zyx.copy()
    if op.get("replace"):
        mask &= np.isin(view, vol.ids_of_types(op["replace"]))
    if op.get("airOnly"):
        mask &= view == 0
    if op.get("keep"):
        mask &= ~np.isin(view, vol.ids_of_types(op["keep"]))
    if not mask.any():
        return 0
    zz, yy, xx = np.nonzero(mask)
    view[zz, yy, xx] = pal.pick(xx + x0 + vol.minX, yy + y0 + vol.minY, zz + z0 + vol.minZ)
    return int(mask.sum())


def clip_box(vol, mn, mx):
    x0, y0, z0 = (max(0, int(mn[0])), max(0, int(mn[1])), max(0, int(mn[2])))
    x1 = min(vol.width - 1, int(mx[0])); y1 = min(vol.height - 1, int(mx[1])); z1 = min(vol.depth - 1, int(mx[2]))
    return x0, y0, z0, x1, y1, z1


def setb(vol, x, y, z, bid, air_only=False):
    if 0 <= x < vol.width and 0 <= y < vol.height and 0 <= z < vol.depth:
        if air_only and vol.a[z, y, x] != 0:
            return
        vol.a[z, y, x] = bid


# --------------------------------------------------------------------------------------------
# Ops

OPS = {}


def op(name):
    def register(fn):
        OPS[name] = fn
        return fn
    return register


@op("box")
def op_box(vol, o):
    """Cuboid min..max inclusive. hollow: shell only; walls: four sides only (no floor/ceiling)."""
    mn, mx = o["min"], o["max"]
    lo = [min(mn[i], mx[i]) for i in range(3)]; hi = [max(mn[i], mx[i]) for i in range(3)]
    x0, y0, z0, x1, y1, z1 = clip_box(vol, lo, hi)
    if x1 < x0 or y1 < y0 or z1 < z0:
        return 0
    shape = (z1 - z0 + 1, y1 - y0 + 1, x1 - x0 + 1)
    mask = np.ones(shape, bool)
    t = int(o.get("thickness", 1))
    if o.get("hollow") or o.get("walls"):
        zz, yy, xx = np.indices(shape)
        X, Y, Z = xx + x0, yy + y0, zz + z0
        side = (X < lo[0] + t) | (X > hi[0] - t) | (Z < lo[2] + t) | (Z > hi[2] - t)
        mask = side if o.get("walls") else side | (Y < lo[1] + t) | (Y > hi[1] - t)
    return write_mask(vol, mask, palette_from(vol, o), z0, y0, x0, o)


@op("ellipsoid")
def op_ellipsoid(vol, o):
    cx, cy, cz = o["center"]
    r = o["radius"]
    rx, ry, rz = (r, r, r) if not isinstance(r, list) else r
    x0, y0, z0, x1, y1, z1 = clip_box(vol, (cx - rx, cy - ry, cz - rz), (cx + rx, cy + ry, cz + rz))
    if x1 < x0 or y1 < y0 or z1 < z0:
        return 0
    zz, yy, xx = np.mgrid[z0:z1 + 1, y0:y1 + 1, x0:x1 + 1].astype(np.float64)
    d = ((xx - cx) / max(rx, .5)) ** 2 + ((yy - cy) / max(ry, .5)) ** 2 + ((zz - cz) / max(rz, .5)) ** 2
    if o.get("roughness"):
        d += o["roughness"] * perlin(xx * 1.7 + yy, zz * 1.7 - yy, 4.0, o.get("seed", 0))
    return write_mask(vol, d <= 1.0, palette_from(vol, o), z0, y0, x0, o)


@op("cylinder")
def op_cylinder(vol, o):
    cx, cz = o["center"][0], o["center"][-1]
    y0w = o.get("y", o["center"][1] if len(o["center"]) == 3 else 0)
    r, h = o["radius"], o["height"]
    x0, y0, z0, x1, y1, z1 = clip_box(vol, (cx - r, y0w, cz - r), (cx + r, y0w + h - 1, cz + r))
    zz, yy, xx = np.mgrid[z0:z1 + 1, y0:y1 + 1, x0:x1 + 1]
    mask = (xx - cx) ** 2 + (zz - cz) ** 2 <= r * r + 0.5
    return write_mask(vol, mask, palette_from(vol, o), z0, y0, x0, o)


@op("replace")
def op_replace(vol, o):
    """Replace matching block types (optionally inside min/max) with a palette."""
    mn = o.get("min", [0, 0, 0]); mx = o.get("max", [vol.width - 1, vol.height - 1, vol.depth - 1])
    x0, y0, z0, x1, y1, z1 = clip_box(vol, mn, mx)
    view = vol.a[z0:z1 + 1, y0:y1 + 1, x0:x1 + 1]
    mask = np.isin(view, vol.ids_of_types(o["match"]))
    return write_mask(vol, mask, palette_from(vol, o), z0, y0, x0, {k: v for k, v in o.items() if k != "replace"})


@op("surface_paint")
def op_surface_paint(vol, o):
    """Repaint the exposed top block of each column (and `depth` blocks below it) in a rect.
    match limits it to columns whose top is one of these types; maxSlope skips steep columns."""
    x0, z0, x1, z1 = o.get("rect", [0, 0, vol.width - 1, vol.depth - 1])
    x0, z0 = max(0, x0), max(0, z0); x1, z1 = min(vol.width - 1, x1), min(vol.depth - 1, z1)
    top = vol.top_y(ignore=o.get("ignore", PLANTS))
    pal = palette_from(vol, o)
    match = vol.ids_of_types(o["match"]) if o.get("match") else None
    depth = int(o.get("depth", 1))
    slope = _slope(top) if o.get("maxSlope") is not None or o.get("minSlope") is not None else None
    count = 0
    for z in range(z0, z1 + 1):
        row = top[z]
        xs = np.arange(x0, x1 + 1)
        ys = row[x0:x1 + 1]
        ok = ys >= 0
        if match is not None:
            ok &= np.isin(vol.a[z, ys.clip(0), xs], match)
        if o.get("yMin") is not None:
            ok &= ys >= o["yMin"]
        if o.get("yMax") is not None:
            ok &= ys <= o["yMax"]
        if slope is not None:
            s = slope[z, x0:x1 + 1]
            if o.get("maxSlope") is not None:
                ok &= s <= o["maxSlope"]
            if o.get("minSlope") is not None:
                ok &= s >= o["minSlope"]
        if o.get("chance") is not None:
            ok &= hash01(xs, ys, z, o.get("seed", 0) + 5) < o["chance"]
        for d in range(depth):
            yy = ys - d
            good = ok & (yy >= 0)
            if not good.any():
                continue
            gx, gy = xs[good], yy[good]
            solid = vol.a[z, gy, gx] != 0
            gx, gy = gx[solid], gy[solid]
            vol.a[z, gy, gx] = pal.pick(gx + vol.minX, gy + vol.minY, np.full_like(gx, z) + vol.minZ)
            count += len(gx)
    return count


def _slope(top):
    t = top.astype(np.float64)
    gx = np.abs(np.diff(t, axis=1, append=t[:, -1:]))
    gx2 = np.abs(np.diff(t[:, ::-1], axis=1, append=t[:, :1])[:, ::-1])
    gz = np.abs(np.diff(t, axis=0, append=t[-1:, :]))
    gz2 = np.abs(np.diff(t[::-1], axis=0, append=t[:1, :])[::-1])
    return np.maximum(np.maximum(gx, gx2), np.maximum(gz, gz2))


@op("heightfield")
def op_heightfield(vol, o):
    """Fill columns from `floor` up to a noise height. The height is
         base + amplitude * shape(distance) * noise
    where distance is measured outward from `avoidRect` (so terrain rises around an arena),
    or the whole rect when no avoidRect is given. Layers: top, soil (soilDepth), stone below.
    Steep columns use `cliff`, columns above `snowLine` use `snow`."""
    x0, z0, x1, z1 = o.get("rect", [0, 0, vol.width - 1, vol.depth - 1])
    X, Z = vol.grid_xz()
    sl = (slice(z0, z1 + 1), slice(x0, x1 + 1))
    X, Z = X[sl], Z[sl]
    wx, wz = X + vol.minX, Z + vol.minZ
    seed = int(o.get("seed", 0))
    base = float(o.get("base", 0))
    amp = float(o.get("amplitude", 20))
    n = fbm(wx, wz, o.get("scale", 48), o.get("octaves", 5), seed, ridged=o.get("ridged", False))
    n = 0.5 + 0.5 * n if not o.get("ridged") else 0.5 + 0.5 * n
    if o.get("detail"):
        n = n + o["detail"] * fbm(wx, wz, o.get("detailScale", 9), 2, seed + 99)
    shape = np.ones_like(X)
    if o.get("avoidRect"):
        ax0, az0, ax1, az1 = o["avoidRect"]
        dx = np.maximum(np.maximum(ax0 - X, X - ax1), 0)
        dz = np.maximum(np.maximum(az0 - Z, Z - az1), 0)
        dist = np.sqrt(dx * dx + dz * dz)
        # Warp the distance so the foot of the slope isn't a ruler-straight line.
        dist = dist + o.get("footWobble", 3) * perlin(wx, wz, 11, seed + 3)
        inside = (X >= ax0) & (X <= ax1) & (Z >= az0) & (Z <= az1)
        start, full = o.get("rampStart", 2), o.get("rampEnd", 30)
        shape = smoothstep(start, full, dist) ** o.get("rampPower", 1.4)
        shape[inside] = 0
    if o.get("edgeFalloff"):
        # Fade toward the rect's outer edge so the range ends instead of being cut off.
        edge = np.minimum(np.minimum(X - x0, x1 - X), np.minimum(Z - z0, z1 - Z))
        edge = edge + 4 * perlin(wx, wz, 13, seed + 5)
        shape = shape * smoothstep(0, o["edgeFalloff"], edge) ** 0.8
    h = base + amp * shape * np.clip(n, 0, 1.5)
    if o.get("edgeDrop"):
        # and let the ground itself sink away at the very edge
        edge = np.minimum(np.minimum(X - x0, x1 - X), np.minimum(Z - z0, z1 - Z))
        h = h - o["edgeDrop"] * (1 - smoothstep(0, 10, edge + 3 * perlin(wx, wz, 7, seed + 6)))
    h = np.round(h).astype(np.int64)
    floor = int(o.get("floor", 0))
    h = np.clip(h, floor, vol.height - 2)
    top_id_pal = palette_from(vol, o, "top", "grass_block")
    soil_pal = palette_from(vol, o, "soil", "dirt")
    stone_pal = Palette(vol, o.get("stone", "stone"), o.get("stoneNoise", 0), seed + 11)
    cliff_pal = Palette(vol, o.get("cliff", o.get("stone", "stone")), o.get("cliffNoise", 7), seed + 13)
    snow_pal = Palette(vol, o["snow"], 0, seed + 17) if o.get("snow") else None
    snow_line = o.get("snowLine", 10 ** 9)
    soil_depth = int(o.get("soilDepth", 3))
    slope = _slope(h)
    cliff_slope = o.get("cliffSlope", 3)
    count = 0
    for zi in range(h.shape[0]):
        z = z0 + zi
        for xi in range(h.shape[1]):
            x = x0 + xi
            top = int(h[zi, xi])
            if top < floor:
                continue
            col = vol.a[z, :, x]
            ys = np.arange(floor, top + 1)
            jitter = int(hash01(x, 0, z, seed + 21) * 3) - 1
            steep = slope[zi, xi] >= cliff_slope + (hash01(x, 1, z, seed) < 0.3)
            ids = stone_pal.pick(np.full_like(ys, x) + vol.minX, ys + vol.minY, np.full_like(ys, z) + vol.minZ)
            if steep:
                band = ys > top - 3
                ids[band] = cliff_pal.pick(np.full(band.sum(), x) + vol.minX, ys[band] + vol.minY,
                                           np.full(band.sum(), z) + vol.minZ)
            else:
                sd = max(1, soil_depth + jitter)
                band = (ys < top) & (ys >= top - sd)
                ids[band] = soil_pal.pick(np.full(band.sum(), x) + vol.minX, ys[band] + vol.minY,
                                          np.full(band.sum(), z) + vol.minZ)
                if snow_pal is not None and top + jitter * 2 >= snow_line:
                    ids[-1] = snow_pal.ids[0]
                else:
                    ids[-1] = top_id_pal.pick(x + vol.minX, top + vol.minY, z + vol.minZ)
            if snow_pal is not None and steep and top + jitter * 2 >= snow_line + 4:
                ids[-1] = snow_pal.ids[0]
            existing = col[floor:top + 1]
            write = existing == 0 if o.get("airOnly") else np.ones_like(existing, bool)
            col[floor:top + 1][write] = ids[write]
            count += int(write.sum())
    return count


@op("island")
def op_island(vol, o):
    """A floating island: a mostly flat, gently undulating top over a tapering, spiky underside.
    Thickness at normalised radius t is rim + depth*(1-t)^taper + spikes*(1-t)^0.8*noise.
    Soil sits on top of the stone with ragged fingers so there is no straight seam."""
    cx, cz = o["center"]
    R = float(o["radius"])
    sy = int(o["surfaceY"])
    seed = int(o.get("seed", 0))
    depth = float(o.get("depth", R * 0.9))
    rim = float(o.get("rim", 3))
    relief = float(o.get("relief", 1.2))
    wobble = float(o.get("wobble", 0.18))
    spikes = float(o.get("spikes", depth * 0.45))
    taper = float(o.get("taper", 1.5))
    stretch = o.get("stretch", [1.0, 1.0])
    reach = int(R * (1 + wobble) * max(stretch) + 3)
    x0, y0, z0, x1, y1, z1 = clip_box(vol, (cx - reach, 0, cz - reach), (cx + reach, vol.height - 1, cz + reach))
    Z, X = np.mgrid[z0:z1 + 1, x0:x1 + 1].astype(np.float64)
    wx, wz = X + vol.minX, Z + vol.minZ
    dx, dz = (X - cx) / stretch[0], (Z - cz) / stretch[1]
    ang = np.arctan2(dz, dx)
    dist = np.sqrt(dx * dx + dz * dz)
    # Edge wobble sampled around the circle so the outline is lumpy but closed.
    edge = R * (1 + wobble * fbm(np.cos(ang) * R * 0.9 + cx * 3, np.sin(ang) * R * 0.9 + cz * 3, R * 0.55, 3, seed))
    mask = dist <= edge
    t = np.clip(dist / np.maximum(edge, 1e-6), 0, 1)
    plateau = o.get("plateau", 0.0)  # raise the middle a little (blocks), fading to the rim
    surf = sy + np.round(relief * fbm(wx, wz, o.get("reliefScale", 18), 3, seed + 1)
                         + plateau * (1 - smoothstep(0.15, 0.85, t))).astype(np.int64)
    # The outermost ring drops by one so the edge reads as rounded rather than a cut disc.
    surf = surf - (t > 0.93 + 0.04 * perlin(wx, wz, 5, seed + 9)).astype(np.int64)
    # Low-frequency lobes give several hanging masses instead of one cone; ridged
    # high-frequency spikes hang off those, strongest toward the middle.
    lobe = np.clip(0.5 + 0.7 * fbm(wx, wz, o.get("lobeScale", max(6.0, R * 0.45)), 2, seed + 3), 0, 1)
    spike = np.clip(0.5 + 0.5 * fbm(wx, wz, o.get("spikeScale", 4.5), 2, seed + 2, ridged=True), 0, 1)
    thick = (rim + depth * (1 - t) ** taper * (0.45 + 0.9 * lobe)
             + spikes * spike ** 2 * (1 - t) ** 0.6 * (0.4 + lobe))
    thick = thick + 1.5 * perlin(wx, wz, 3.5, seed + 4) * (1 - t)
    bottom = np.round(surf - np.maximum(thick, rim)).astype(np.float64)
    # No column may hang more than `drip` blocks below its neighbours: spikes stay at least
    # two or three blocks wide instead of one-block needles.
    drip = float(o.get("drip", 2))
    big = np.where(mask, bottom, -1e9)
    for _ in range(3):
        p = np.pad(big, 1, mode="constant", constant_values=-1e9)
        neighbour_low = np.minimum(np.minimum(p[:-2, 1:-1], p[2:, 1:-1]), np.minimum(p[1:-1, :-2], p[1:-1, 2:]))
        nb = np.maximum(np.maximum(p[:-2, 1:-1], p[2:, 1:-1]), np.maximum(p[1:-1, :-2], p[1:-1, 2:]))
        second = np.sort(np.stack([p[:-2, 1:-1], p[2:, 1:-1], p[1:-1, :-2], p[1:-1, 2:]]), axis=0)[1]
        big = np.where(mask, np.maximum(big, second - drip), big)
    bottom = np.round(np.where(mask, big, surf)).astype(np.int64)
    top_pal = Palette(vol, o.get("top", "grass_block"), o.get("topNoise", 9), seed + 5)
    soil_pal = Palette(vol, o.get("soil", "dirt"), o.get("soilNoise", 5), seed + 6)
    stone_pal = Palette(vol, o.get("stone", "stone"), o.get("stoneNoise", 6), seed + 7)
    under_pal = Palette(vol, o.get("underside", o.get("stone", "stone")), o.get("undersideNoise", 4), seed + 8)
    soil_depth = o.get("soilDepth", 3)
    strata = Palette(vol, o["strata"], 0, seed + 18).ids if o.get("strata") else None
    strata_h = float(o.get("strataHeight", 3))
    count = 0
    zz, xx = np.nonzero(mask)
    for i in range(len(zz)):
        z, x = int(zz[i] + z0), int(xx[i] + x0)
        s, b = int(surf[zz[i], xx[i]]), int(bottom[zz[i], xx[i]])
        s = min(s, vol.height - 1); b = max(b, 0)
        if s < b:
            continue
        ys = np.arange(b, s + 1)
        X1, Z1 = np.full_like(ys, x) + vol.minX, np.full_like(ys, z) + vol.minZ
        if strata is not None:
            # horizontal rock bands, gently warped, read as geology from the side
            warp = 2.5 * perlin(x + vol.minX, z + vol.minZ, 14, seed + 14)
            band = np.floor((ys + warp) / strata_h).astype(np.int64)
            ids = strata[(hash01(band, band * 7, band * 13, seed + 15) * len(strata)).astype(np.int64)]
            speck = hash01(X1, ys, Z1, seed + 16) < 0.18
            ids[speck] = stone_pal.pick(X1[speck], ys[speck] + vol.minY, Z1[speck])
        else:
            ids = stone_pal.pick(X1, ys + vol.minY, Z1)
        low = ys < b + max(2, int((s - b) * 0.3))
        ids[low] = under_pal.pick(X1[low], ys[low] + vol.minY, Z1[low])
        sd = soil_depth + int(3 * (0.5 + 0.5 * perlin(x + vol.minX, z + vol.minZ, 7, seed + 12)))
        if t[zz[i], xx[i]] > 0.9:
            sd = max(1, sd - 2)
        soil = (ys < s) & (ys >= s - sd)
        # ragged lower edge of the soil cap
        soil &= ~((ys == s - sd) & (hash01(x, ys, z, seed) < 0.5))
        ids[soil] = soil_pal.pick(X1[soil], ys[soil] + vol.minY, Z1[soil])
        ids[-1] = top_pal.pick(x + vol.minX, s + vol.minY, z + vol.minZ)
        col = vol.a[z, b:s + 1, x]
        col[:] = ids
        count += len(ys)
    vol.regions.append({"name": o.get("name", "island"), "kind": "island", "center": [cx, sy, cz],
                        "radius": R})
    return count


# ---------- vegetation and objects

PLANTS = ["SHORT_GRASS", "TALL_GRASS", "FERN", "POPPY", "DANDELION", "AZURE_BLUET", "OXEYE_DAISY",
          "CORNFLOWER", "BUSH", "LEAF_LITTER", "PINK_PETALS", "WILDFLOWERS", "MOSS_CARPET", "SNOW",
          "SWEET_BERRY_BUSH", "FIREFLY_BUSH", "HANGING_ROOTS", "GLOW_LICHEN", "VINE"]

TREE_KINDS = {
    "oak": ("oak_log", "oak_leaves[persistent=true]"),
    "birch": ("birch_log", "birch_leaves[persistent=true]"),
    "spruce": ("spruce_log", "spruce_leaves[persistent=true]"),
}


def _leaves(kind):
    return TREE_KINDS[kind][1]


def tree(vol, x, y, z, kind="oak", height=None, rng=None, seed=0):
    """Hand-shaped trees. y is the first air block above the ground."""
    rng = rng or np.random.default_rng(seed)
    log = vol.id(TREE_KINDS["spruce" if kind == "spruce" else kind if kind in TREE_KINDS else "oak"][0] + "[axis=y]")
    if kind == "bush":
        leaf = vol.id(_leaves("oak"))
        setb(vol, x, y, z, log)
        for dx in range(-1, 2):
            for dz in range(-1, 2):
                for dy in range(0, 2):
                    if abs(dx) + abs(dz) + dy <= 2 and rng.random() < 0.9:
                        setb(vol, x + dx, y + dy, z + dz, leaf, air_only=True)
        return
    leaf = vol.id(_leaves(kind))
    if kind == "spruce":
        h = height or int(rng.integers(8, 12))
        for i in range(h):
            setb(vol, x, y + i, z, log)
        top = y + h
        r = 0
        for ly in range(top, y + 2, -1):
            layer = top - ly
            r = [0, 1, 1, 2, 1, 2, 3, 2, 3, 3, 2][min(layer, 10)]
            for dx in range(-r, r + 1):
                for dz in range(-r, r + 1):
                    if abs(dx) + abs(dz) <= r + (r > 1) and not (abs(dx) == r and abs(dz) == r and r > 0):
                        setb(vol, x + dx, ly, z + dz, leaf, air_only=True)
        setb(vol, x, top, z, leaf, air_only=True)
        setb(vol, x, top + 1, z, leaf, air_only=True)
        return
    h = height or int(rng.integers(5, 8) if kind == "oak" else rng.integers(6, 9))
    for i in range(h):
        setb(vol, x, y + i, z, log)
    top = y + h - 1
    canopy = [(top - 2, 2), (top - 1, 2), (top, 1), (top + 1, 1)]
    if kind == "oak" and h >= 6:
        canopy = [(top - 3, 2), (top - 2, 3), (top - 1, 2), (top, 2), (top + 1, 1)]
    for ly, r in canopy:
        for dx in range(-r, r + 1):
            for dz in range(-r, r + 1):
                corner = abs(dx) == r and abs(dz) == r
                if corner and (r == 1 or rng.random() < 0.6):
                    continue
                if dx * dx + dz * dz > r * r + 1.5:
                    continue
                setb(vol, x + dx, ly, z + dz, leaf, air_only=True)
    setb(vol, x, top + 1, z, leaf, air_only=True)


def boulder(vol, x, y, z, radius, palette, seed):
    op_ellipsoid(vol, {"center": [x, y, z], "radius": [radius, radius * 0.75, radius * 0.9],
                       "palette": palette, "noise": 3, "roughness": 0.35, "seed": seed})


@op("tree")
def op_tree(vol, o):
    x, y, z = o["at"]
    tree(vol, x, y, z, o.get("kind", "oak"), o.get("height"), seed=o.get("seed", 0))
    return 1


def _clearance(ob, o):
    """Free space an object needs around its base: [radius, height]. Trees and boulders need room
    so neither lands in or on another (from this op or an earlier one); blocks need none.
    Override with "clear": [r, h] on the object or the op, or "clear": 0 to skip."""
    given = ob.get("clear", o.get("clear"))
    if given is not None:
        return (0, 0) if given == 0 else (int(given[0]), int(given[1]))
    if "tree" in ob:
        return (3, 12) if ob["tree"] == "spruce" else (2, 10)
    if "boulder" in ob:
        return (int(math.ceil(float(ob["boulder"]) * 1.2)) + 1, int(math.ceil(float(ob["boulder"]))) + 2)
    return (0, 0)


def _clear_around(vol, x, ty, z, clearance):
    """True when nothing an object would collide with is near its base: no log or leaves in the box
    above the ground, and no earlier tree or boulder footprint (kept on the volume) overlapping."""
    r, h = clearance
    if r <= 0 and h <= 0:
        return True
    for px, pz, pr in getattr(vol, "footprints", []):
        if (x - px) ** 2 + (z - pz) ** 2 < (r + pr) ** 2:
            return False
    z0, z1 = max(0, z - r), min(vol.depth, z + r + 1)
    x0, x1 = max(0, x - r), min(vol.width, x + r + 1)
    y0, y1 = max(0, ty + 1), min(vol.height, ty + 1 + h)
    for i in np.unique(vol.a[z0:z1, y0:y1, x0:x1]):
        name = vol.type_of(int(i))
        if name.endswith("_LOG") or name.endswith("_LEAVES") or name.endswith("_WOOD"):
            return False
    return True


def _remember_footprint(vol, x, z, clearance):
    if clearance[0] > 0:
        if not hasattr(vol, "footprints"):
            vol.footprints = []
        vol.footprints.append((x, z, clearance[0]))


@op("scatter")
def op_scatter(vol, o):
    """Place objects on the surface. objects: [{"tree": "oak", "weight": 2}, {"block": "poppy"},
    {"boulder": 2, "palette": [...]}]. count or density (per column), minSpacing, on (surface
    types), rect, avoid ([x0,z0,x1,z1] list), maxSlope, insideRegion (island name)."""
    rng = np.random.default_rng(o.get("seed", 0))
    circle = o.get("circle")  # [cx, cz, r] limit
    default = [0, 0, vol.width - 1, vol.depth - 1]
    if circle and not o.get("rect"):
        # sample inside the circle's box, not the whole volume
        default = [int(circle[0] - circle[2]), int(circle[1] - circle[2]), int(circle[0] + circle[2]), int(circle[1] + circle[2])]
    x0, z0, x1, z1 = o.get("rect", default)
    top = vol.top_y(ignore=PLANTS)
    on = vol.ids_of_types(o["on"]) if o.get("on") else None
    slope = _slope(top)
    objs = o["objects"]
    weights = np.array([float(ob.get("weight", 1)) for ob in objs]); weights /= weights.sum()
    columns = (x1 - x0 + 1) * (z1 - z0 + 1)
    target = int(o.get("count", round(columns * o.get("density", 0.01))))
    spacing = float(o.get("minSpacing", 0))
    placed = []
    tries = 0
    avoid = o.get("avoid", [])
    ring_avoid = o.get("avoidCircles", [])
    while len(placed) < target and tries < target * 40 + 200:
        tries += 1
        x, z = int(rng.integers(x0, x1 + 1)), int(rng.integers(z0, z1 + 1))
        if not (0 <= x < vol.width and 0 <= z < vol.depth):
            continue
        if any(a[0] <= x <= a[2] and a[1] <= z <= a[3] for a in avoid):
            continue
        if circle and (x - circle[0]) ** 2 + (z - circle[1]) ** 2 > circle[2] ** 2:
            continue
        if any((x - c[0]) ** 2 + (z - c[1]) ** 2 < c[2] ** 2 for c in ring_avoid):
            continue
        ty = int(top[z, x])
        if ty < 0 or ty + 1 >= vol.height or vol.a[z, ty + 1, x] != 0:
            continue
        if on is not None and vol.a[z, ty, x] not in on:
            continue
        if o.get("maxSlope") is not None and slope[z, x] > o["maxSlope"]:
            continue
        if o.get("yMin") is not None and ty < o["yMin"]:
            continue
        if o.get("yMax") is not None and ty > o["yMax"]:
            continue
        ob = objs[int(rng.choice(len(objs), p=weights))]
        sp = float(ob.get("spacing", spacing))
        if sp > 0 and any((x - px) ** 2 + (z - pz) ** 2 < max(sp, ps) ** 2 for px, pz, ps in placed):
            continue
        clearance = _clearance(ob, o)
        if not _clear_around(vol, x, ty, z, clearance):
            continue
        _remember_footprint(vol, x, z, clearance)
        if "tree" in ob:
            tree(vol, x, ty + 1, z, ob["tree"], ob.get("height"), rng)
        elif "boulder" in ob:
            boulder(vol, x, ty + int(ob.get("sink", 0)), z, float(ob["boulder"]) * (0.8 + 0.4 * rng.random()),
                    ob.get("palette", ["stone", "andesite", "cobblestone"]), int(rng.integers(1 << 30)))
        elif "surface" in ob:
            # replace the ground block itself (ores set into the grass) and clear any plant on it
            vol.a[z, ty, x] = vol.id(ob["surface"])
            if vol.type_of(int(vol.a[z, ty + 1, x])) in PLANTS:
                vol.a[z, ty + 1, x] = 0
        elif "block" in ob:
            setb(vol, x, ty + 1, z, vol.id(ob["block"]))
            if ob.get("tall"):
                setb(vol, x, ty + 2, z, vol.id(ob["tall"]))
        placed.append((x, z, sp))
    return len(placed)


@op("hang")
def op_hang(vol, o):
    """Dangle blocks (roots, vines, lichen) under overhangs: each solid block with air below in
    rect gets a chance of a 1..length strand."""
    rng = np.random.default_rng(o.get("seed", 0))
    bid = vol.id(o["block"])
    on = vol.ids_of_types(o["on"]) if o.get("on") else None
    a = vol.a
    below_air = (a[:, 1:, :] != 0) & (a[:, :-1, :] == 0)
    zz, yy, xx = np.nonzero(below_air)
    yy = yy + 1
    n = 0
    for i in range(len(zz)):
        if rng.random() > o.get("chance", 0.05):
            continue
        z, y, x = zz[i], yy[i], xx[i]
        if on is not None and a[z, y, x] not in on:
            continue
        for d in range(1, int(rng.integers(1, o.get("length", 1) + 1)) + 1):
            if y - d < 0 or a[z, y - d, x] != 0:
                break
            a[z, y - d, x] = bid
            n += 1
    return n


@op("smooth")
def op_smooth(vol, o):
    """Blur the surface height inside rect and rebuild each column's top: raised columns copy
    their surface block upward over their soil, lowered ones are cut down."""
    x0, z0, x1, z1 = o.get("rect", [0, 0, vol.width - 1, vol.depth - 1])
    top = vol.top_y(ignore=PLANTS)
    t = top[z0:z1 + 1, x0:x1 + 1].astype(np.float64)
    valid = t >= 0
    for _ in range(int(o.get("iterations", 2))):
        p = np.pad(t, 1, mode="edge")
        t = np.where(valid, (p[1:-1, 1:-1] * 4 + p[:-2, 1:-1] + p[2:, 1:-1] + p[1:-1, :-2] + p[1:-1, 2:]) / 8, t)
    new = np.round(t).astype(np.int64)
    n = 0
    for zi, xi in zip(*np.nonzero(valid)):
        z, x = z0 + zi, x0 + xi
        old, nh = int(top[z, x]), int(new[zi, xi])
        if nh == old:
            continue
        col = vol.a[z, :, x]
        surface = col[old]
        if nh < old:
            col[nh + 1:old + 1] = 0
            col[nh] = surface
        else:
            under = col[old - 1] if old > 0 else surface
            col[old:nh] = under
            col[nh] = surface
        n += 1
    return n


@op("marker")
def op_marker(vol, o):
    pos = list(o["pos"])
    if o.get("snap"):
        # stand on the highest solid block of that column
        x, z = int(pos[0]), int(pos[2])
        pos[1] = int(vol.top_y(ignore=PLANTS + ["BARRIER"])[z, x]) + 1
    m = {"name": o["name"], "pos": pos}
    for k in ("yaw", "tags"):
        if k in o:
            m[k] = o[k]
    vol.markers.append(m)
    return 1


@op("region")
def op_region(vol, o):
    vol.regions.append({k: v for k, v in o.items() if k != "op"})
    return 1


@op("clear")
def op_clear(vol, o):
    return op_box(vol, {**o, "block": "air"})


# ---------- composition

def _subst(value, env):
    if isinstance(value, str) and value.startswith("="):
        return eval(value[1:], {"__builtins__": {}, "math": math, "round": round, "int": int,
                                "abs": abs, "min": min, "max": max}, env)
    if isinstance(value, list):
        return [_subst(v, env) for v in value]
    if isinstance(value, dict):
        return {k: _subst(v, env) for k, v in value.items()}
    return value


@op("ring")
def op_ring(vol, o, env=None):
    """Repeat `ops` count times around center at radius (angleJitter degrees / radiusJitter
    blocks randomise each copy within its sector, seeded by jitterSeed). Inside, strings beginning with '='
    are expressions over i, n, angle (radians), x, z (this copy's centre) and anything in `vars`."""
    cx, cz = o["center"]
    n = int(o["count"])
    total = 0
    # Optional seeded jitter: each copy moves randomly inside its own slice of the circle, so a
    # ring can place things that look random but stay evenly spread (one per sector).
    rng = np.random.default_rng(o.get("jitterSeed", 0))
    aj, rj = float(o.get("angleJitter", 0)), float(o.get("radiusJitter", 0))
    for i in range(n):
        ang = math.radians(o.get("angleOffset", 0) + rng.uniform(-aj, aj)) + 2 * math.pi * i / n
        rad = o["radius"] + rng.uniform(-rj, rj)
        x = cx + math.cos(ang) * rad
        z = cz + math.sin(ang) * rad
        e = dict(env or {}, **o.get("vars", {}), i=i, n=n, angle=ang, x=round(x), z=round(z),
                 cx=cx, cz=cz, yaw=round((math.degrees(math.atan2(cx - x, z - cz))) % 360, 1))
        for child in o["ops"]:
            total += run_op(vol, _subst(child, e), e)
    return total


def run_op(vol, o, env=None):
    if o.get("skip"):
        return 0
    name = o["op"]
    fn = OPS.get(name)
    if fn is None:
        raise ValueError(f"unknown op '{name}' (known: {', '.join(sorted(OPS))})")
    if name == "ring":
        return fn(vol, o, env)
    return fn(vol, o)


def run_ops(vol, ops, log=True):
    for i, o in enumerate(ops):
        if o.get("skip"):
            continue
        n = run_op(vol, o)
        if log:
            print(f"[{i:02d}] {o['op']:<14} {o.get('note', ''):<40} {n}", file=sys.stderr)


# --------------------------------------------------------------------------------------------
# CLI

def _kv(pairs):
    out = {}
    for p in pairs:
        k, v = p.split("=", 1)
        try:
            out[k] = json.loads(v)
        except json.JSONDecodeError:
            out[k] = v
    return out


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = ap.add_subparsers(dest="cmd", required=True)
    b = sub.add_parser("build"); b.add_argument("spec"); b.add_argument("--out", required=True)
    a = sub.add_parser("apply"); a.add_argument("dir"); a.add_argument("ops")
    o = sub.add_parser("op"); o.add_argument("dir"); o.add_argument("name"); o.add_argument("args", nargs="*")
    i = sub.add_parser("info"); i.add_argument("dir")
    r = sub.add_parser("render", add_help=False); r.add_argument("rest", nargs=argparse.REMAINDER)
    args = ap.parse_args(argv)

    if args.cmd == "build":
        spec = json.loads(Path(args.spec).read_text())
        size = spec["size"]
        org = spec.get("origin", [0, 0, 0])
        vol = Volume(size[0], size[1], size[2], *org)
        run_ops(vol, spec["ops"])
        vol.save(Path(args.out))
        print(json.dumps({"out": str(Path(args.out).resolve()), "nonAir": int((vol.a != 0).sum()),
                          "markers": len(vol.markers)}))
    elif args.cmd == "apply":
        vol = Volume.load(Path(args.dir))
        data = json.loads(Path(args.ops).read_text())
        run_ops(vol, data["ops"] if isinstance(data, dict) else data)
        vol.save(Path(args.dir))
    elif args.cmd == "op":
        vol = Volume.load(Path(args.dir))
        print(run_op(vol, {"op": args.name, **_kv(args.args)}))
        vol.save(Path(args.dir))
    elif args.cmd == "info":
        vol = Volume.load(Path(args.dir))
        ids, counts = np.unique(vol.a, return_counts=True)
        print(json.dumps({"size": [vol.width, vol.height, vol.depth], "origin": [vol.minX, vol.minY, vol.minZ],
                          "blocks": {block_key(vol.palette[i]): int(c) for i, c in zip(ids, counts) if i},
                          "markers": vol.markers, "regions": vol.regions}, indent=1))
    elif args.cmd == "render":
        sys.path.insert(0, str(Path(__file__).parent))
        import sculpt_render
        sculpt_render.main(args.rest)


if __name__ == "__main__":
    main()
