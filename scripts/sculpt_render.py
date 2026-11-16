#!/usr/bin/env python3
"""Textured previews of a sculpt volume, for reviewing a map before it goes into a world.

    sculpt.py render DIR --textures TEXDIR --out OUT.png [--view iso|top|side|sheet]
                     [--yaw 0-3] [--block 8] [--below] [--crop x0,z0,x1,z1] [--title TEXT]

TEXDIR is a folder of 16x16 block textures named like the game's own (grass_block_top.png,
oak_log.png ...), for example extracted from a local game client's assets. Missing textures
fall back to a flat colour. `sheet` renders iso from two sides, the top view and the side
view into one image. Barriers draw as a faint red glass so walls can be checked.
"""
from __future__ import annotations

import argparse
import hashlib
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw, ImageFont, ImageEnhance

import sculpt

GRASS_TINT = (124, 189, 80)
FOLIAGE = {"OAK": (92, 160, 52), "BIRCH": (128, 167, 85), "SPRUCE": (97, 135, 97)}
CROSS = {"SHORT_GRASS", "TALL_GRASS", "FERN", "POPPY", "DANDELION", "AZURE_BLUET", "OXEYE_DAISY",
         "CORNFLOWER", "BUSH", "SWEET_BERRY_BUSH", "FIREFLY_BUSH", "HANGING_ROOTS", "WILDFLOWERS"}
THIN = {"SNOW": 2, "MOSS_CARPET": 1, "LEAF_LITTER": 1, "PINK_PETALS": 1}
TINTED_CROSS = {"SHORT_GRASS", "TALL_GRASS", "FERN", "BUSH"}


class Textures:
    def __init__(self, folder: Path | None):
        self.folder = folder
        self.cache = {}

    def load(self, name) -> Image.Image | None:
        if name in self.cache:
            return self.cache[name]
        img = None
        if self.folder is not None:
            p = self.folder / f"{name}.png"
            if p.exists():
                img = Image.open(p).convert("RGBA")
                if img.height > img.width:  # animated strip
                    img = img.crop((0, 0, img.width, img.width))
                img = img.resize((16, 16), Image.NEAREST)
        self.cache[name] = img
        return img

    def first(self, *names):
        for n in names:
            img = self.load(n)
            if img is not None:
                return img
        return None


def _tint(img, rgb):
    a = np.asarray(img).astype(np.float64)
    a[..., :3] = a[..., :3] * (np.array(rgb) / 255.0)
    return Image.fromarray(a.clip(0, 255).astype(np.uint8), "RGBA")


def _flat(rgb):
    return Image.new("RGBA", (16, 16), tuple(rgb) + (255,))


def _hash_colour(name):
    h = hashlib.md5(name.encode()).digest()
    return (80 + h[0] % 140, 80 + h[1] % 140, 80 + h[2] % 140)


def faces_for(entry, tex: Textures):
    """(top, side, bottom) RGBA 16x16 images, and a kind: cube, cross, thin, glass, none."""
    t = entry["type"]
    low = t.lower()
    if t == "AIR":
        return None, None, None, "none"
    if t == "BARRIER":
        g = Image.new("RGBA", (16, 16), (235, 55, 55, 255))
        return g, g, g, "barrier"
    if t == "GRASS_BLOCK":
        top = _tint(tex.first("grass_block_top") or _flat((200, 200, 200)), GRASS_TINT)
        side = (tex.first("grass_block_side") or _flat((134, 96, 67))).copy()
        ov = tex.first("grass_block_side_overlay")
        if ov is not None:
            side.alpha_composite(_tint(ov, GRASS_TINT))
        return top, side, tex.first("dirt") or _flat((134, 96, 67)), "cube"
    if t.endswith("_LOG") or t.endswith("_WOOD"):
        side = tex.first(low) or _flat((100, 80, 50))
        top = tex.first(low + "_top") or side
        axis = (entry.get("states") or {}).get("axis", "y").lower()
        if axis != "y":
            return side, top, side, "cube"
        return top, side, top, "cube"
    if t.endswith("_LEAVES"):
        wood = t[:-7]
        img = tex.first(low) or _flat((60, 120, 40))
        if wood in FOLIAGE:
            img = _tint(img, FOLIAGE[wood])
        # "fast" leaves: fill the holes with a darker shade so canopies read as solid masses
        back = Image.new("RGBA", (16, 16), tuple(int(c * 0.45) for c in (FOLIAGE.get(wood, (60, 120, 40)))) + (255,))
        back.alpha_composite(img)
        return back, back, back, "cube"
    if t in CROSS:
        names = {"SHORT_GRASS": "short_grass", "TALL_GRASS": "tall_grass_top", "BUSH": "bush",
                 "SWEET_BERRY_BUSH": "sweet_berry_bush_stage3", "FIREFLY_BUSH": "firefly_bush"}
        img = tex.first(names.get(t, low), low) or _flat(_hash_colour(t))
        if t in TINTED_CROSS:
            img = _tint(img, GRASS_TINT)
        return img, img, img, "cross"
    if t in THIN:
        name = {"SNOW": "snow", "MOSS_CARPET": "moss_block", "LEAF_LITTER": "leaf_litter", "PINK_PETALS": "pink_petals"}[t]
        img = tex.first(name) or _flat(_hash_colour(t))
        return img, img, img, "thin"
    if t == "SNOW_BLOCK":
        img = tex.first("snow")
        return img, img, img, "cube"
    if t == "PODZOL":
        return tex.first("podzol_top"), tex.first("podzol_side"), tex.first("dirt"), "cube"
    if t == "DIRT_PATH":
        return tex.first("dirt_path_top"), tex.first("dirt_path_side"), tex.first("dirt"), "cube"
    if t in ("MYCELIUM",):
        return tex.first("mycelium_top"), tex.first("mycelium_side"), tex.first("dirt"), "cube"
    top = tex.first(low + "_top", low)
    side = tex.first(low + "_side", low)
    bottom = tex.first(low + "_bottom", low)
    if side is None:
        c = _flat(_hash_colour(t))
        return c, c, c, "cube"
    return top or side, side, bottom or side, "cube"


# --------------------------------------------------------------------------------------------
# Isometric sprites

def _affine_face(img, size, origin, u, v):
    """Map a 16x16 texture onto the parallelogram origin + s*u + t*v (s,t in 0..1)."""
    # PIL affine maps output pixel -> input pixel: in = M * out
    M = np.array([[u[0], v[0]], [u[1], v[1]]], dtype=np.float64) / 16.0
    inv = np.linalg.inv(M)
    ox, oy = origin
    a, b = inv[0]
    d, e = inv[1]
    c = -(a * ox + b * oy)
    f = -(d * ox + e * oy)
    face = img.transform(size, Image.AFFINE, (a, b, c, d, e, f), resample=Image.NEAREST)
    mask = Image.new("L", size, 0)
    pts = [origin, (ox + u[0], oy + u[1]), (ox + u[0] + v[0], oy + u[1] + v[1]), (ox + v[0], oy + v[1])]
    ImageDraw.Draw(mask).polygon(pts, fill=255)
    alpha = np.minimum(np.asarray(face)[..., 3], np.asarray(mask))
    arr = np.asarray(face).copy()
    arr[..., 3] = alpha
    return Image.fromarray(arr, "RGBA")


def _shade(img, k):
    a = np.asarray(img).astype(np.float64)
    a[..., :3] *= k
    return Image.fromarray(a.clip(0, 255).astype(np.uint8), "RGBA")


def build_sprite(faces, W, H):
    top, side, bottom, kind = faces
    size = (W, W // 2 + H)
    out = Image.new("RGBA", size, (0, 0, 0, 0))
    if kind == "none":
        return out
    hw, qw = W / 2, W / 4
    if kind == "cross":
        plant = top.resize((max(2, int(W * 0.8)), H), Image.NEAREST)
        out.alpha_composite(plant, (int(W * 0.1), int(qw + 0)))
        return out
    h = H
    y_off = 0
    if kind in ("thin", "barrier"):
        h = max(1, int(H * 0.15))
        y_off = H - h
    # top diamond
    t = _affine_face(top, size, (hw, 0 + y_off), (hw, qw), (-hw, qw))
    left = _affine_face(side, size, (0, qw + y_off), (hw, qw), (0, h))
    right = _affine_face(side, size, (hw, hw + y_off), (hw, -qw), (0, h))
    out.alpha_composite(_shade(left, 0.78))
    out.alpha_composite(_shade(right, 0.62))
    out.alpha_composite(t)
    return out


def iso(vol: sculpt.Volume, tex: Textures, W=8, yaw=0, below=False, crop=None, background=(26, 30, 40),
        cut_below=None):
    a = vol.a.copy()
    pal = vol.palette
    # Barriers are invisible in game: draw only the bottom block of each barrier column, as a
    # thin red plate, so the wall's footprint shows without hiding what is behind it.
    bar = [i for i, e in enumerate(pal) if e["type"] == "BARRIER"]
    if bar:
        isb = np.isin(a, bar)
        below_b = np.zeros_like(isb); below_b[:, 1:, :] = isb[:, :-1, :]
        a[isb & below_b] = 0
    if cut_below is not None:
        a[:, :max(0, cut_below - vol.minY), :] = 0
    if crop:
        x0, z0, x1, z1 = crop
        a = a[z0:z1 + 1, :, x0:x1 + 1]
    if below:
        a = a[:, ::-1, :]
    a = np.rot90(a, k=yaw, axes=(0, 2))  # rotate in the x/z plane
    D, Hh, Wd = a.shape
    H = int(W * 0.62)
    faces = [faces_for(e, tex) for e in pal]
    if below:
        faces = [(f[2], f[1], f[0], f[3]) for f in faces]
    sprites = [build_sprite(f, W, H) for f in faces]
    glass = np.array([f[3] in ("glass", "cross", "thin", "none", "barrier") for f in faces])
    opaque = ~glass[a]
    # A voxel is drawn when it is non-air and at least one camera-facing neighbour is see-through.
    nz = a != 0
    pad = np.pad(opaque, ((0, 1), (0, 1), (0, 1)), constant_values=False)
    exposed = nz & (~pad[:-1, 1:, :-1] | ~pad[1:, :-1, :-1] | ~pad[:-1, :-1, 1:] | glass[a])
    zz, yy, xx = np.nonzero(exposed)
    order = np.lexsort((yy, xx + zz))
    hw, qw = W / 2, W / 4
    sx = ((xx - zz) * hw).astype(np.int64)
    sy = ((xx + zz) * qw - yy * H).astype(np.int64)
    minx, miny = sx.min(), sy.min()
    width = int(sx.max() - minx + W + 20)
    height = int(sy.max() - miny + W // 2 + H + 20)
    canvas = Image.new("RGBA", (width, height), background + (255,))
    ids = a[zz, yy, xx]
    # mild depth fog toward the back so layered terrain separates
    for k in order:
        canvas.alpha_composite(sprites[ids[k]], (int(sx[k] - minx + 10), int(sy[k] - miny + 10)))
    return canvas


def top_view(vol, tex, S=6, background=(26, 30, 40)):
    a = vol.a
    pal = vol.palette
    faces = [faces_for(e, tex) for e in pal]
    skip = np.array([f[3] in ("none", "glass", "cross", "thin", "barrier") for f in faces])
    solid = ~skip[a]
    has = solid.any(axis=1)
    topy = vol.height - 1 - np.argmax(solid[:, ::-1, :], axis=1)
    ids = np.take_along_axis(a, topy[:, None, :], axis=1)[:, 0, :]
    tiles = np.zeros((len(pal), S, S, 3), np.float64)
    for i, f in enumerate(faces):
        if f[0] is None:
            continue
        img = f[0]
        if f[3] == "cross":
            base = Image.new("RGBA", (16, 16), (0, 0, 0, 0)); base.alpha_composite(img); img = base
        t = img.resize((S, S), Image.BOX)
        arr = np.asarray(t).astype(np.float64)
        alpha = arr[..., 3:4] / 255.0
        tiles[i] = arr[..., :3] * alpha + np.array(background) * (1 - alpha)
    # hillshade from the height map
    h = np.where(has, topy, 0).astype(np.float64)
    gx = np.gradient(h, axis=1); gz = np.gradient(h, axis=0)
    shade = np.clip(1.0 - 0.18 * gx - 0.18 * gz, 0.55, 1.25)
    hmin, hmax = h[has].min() if has.any() else 0, h[has].max() if has.any() else 1
    shade *= 0.82 + 0.25 * (h - hmin) / max(1, hmax - hmin)
    img = tiles[ids] * shade[:, :, None, None, None]
    img[~has] = background
    D, Wd = ids.shape
    img = img.transpose(0, 2, 1, 3, 4).reshape(D * S, Wd * S, 3)
    # barriers as red outline
    bar = np.zeros((D, Wd), bool)
    bid = [i for i, e in enumerate(pal) if e["type"] == "BARRIER"]
    if bid:
        bar = np.isin(a, bid).any(axis=1)
        mask = np.kron(bar, np.ones((S, S), bool))
        img[mask] = img[mask] * 0.4 + np.array([230, 60, 60]) * 0.6
    return Image.fromarray(img.clip(0, 255).astype(np.uint8), "RGB").convert("RGBA")


def side_view(vol, tex, S=5, background=(26, 30, 40), from_south=True):
    """Orthographic elevation looking north (along -z): shows the underside profile."""
    a = vol.a if from_south else vol.a[::-1]
    pal = vol.palette
    faces = [faces_for(e, tex) for e in pal]
    skip = np.array([f[3] in ("none", "glass", "barrier") for f in faces])
    solid = ~skip[a]                         # z, y, x
    s2 = solid[::-1]                         # nearest to viewer (largest z) first
    has = s2.any(axis=0)                     # y, x
    first = np.argmax(s2, axis=0)
    zidx = a.shape[0] - 1 - first
    yy, xx = np.indices(has.shape)
    ids = a[zidx, yy, xx]
    tiles = np.zeros((len(pal), S, S, 3))
    for i, f in enumerate(faces):
        if f[1] is None:
            continue
        img = f[1]
        if f[3] == "cross":
            base = Image.new("RGBA", (16, 16), background + (255,)); base.alpha_composite(img); img = base
        arr = np.asarray(img.resize((S, S), Image.BOX)).astype(np.float64)
        al = arr[..., 3:4] / 255.0
        tiles[i] = arr[..., :3] * al + np.array(background) * (1 - al)
    depth = zidx / max(1, a.shape[0] - 1)
    shade = 0.55 + 0.45 * depth
    img = tiles[ids] * shade[:, :, None, None, None]
    img[~has] = background
    img = img[::-1]  # y up
    Hh, Wd = has.shape
    img = img.transpose(0, 2, 1, 3, 4).reshape(Hh * S, Wd * S, 3)
    return Image.fromarray(img.clip(0, 255).astype(np.uint8), "RGB").convert("RGBA")


def _label(img, text, background=(26, 30, 40)):
    pad = 34
    out = Image.new("RGBA", (img.width, img.height + pad), background + (255,))
    out.alpha_composite(img, (0, pad))
    d = ImageDraw.Draw(out)
    try:
        font = ImageFont.truetype("arial.ttf", 20)
    except OSError:
        font = ImageFont.load_default()
    d.text((10, 6), text, fill=(235, 235, 235, 255), font=font)
    return out


def sheet(vol, tex, title, W=8, cut_below=None):
    views = [
        _label(iso(vol, tex, W, yaw=0, cut_below=cut_below), f"{title} - isometric (from south-east)"),
        _label(iso(vol, tex, W, yaw=2, cut_below=cut_below), f"{title} - isometric (from north-west)"),
        _label(top_view(vol, tex, 4), f"{title} - top down"),
        _label(side_view(vol, tex, 4), f"{title} - side elevation"),
    ]
    cols = 2
    rows = (len(views) + 1) // 2
    cw = max(v.width for v in views); rh = [max(v.height for v in views[r * 2:r * 2 + 2]) for r in range(rows)]
    out = Image.new("RGBA", (cw * cols + 30, sum(rh) + 30), (18, 20, 28, 255))
    y = 10
    for r in range(rows):
        for c in range(cols):
            k = r * cols + c
            if k < len(views):
                out.alpha_composite(views[k], (10 + c * (cw + 10), y))
        y += rh[r] + 10
    return out


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("dir")
    ap.add_argument("--out", required=True)
    ap.add_argument("--textures")
    ap.add_argument("--view", default="iso", choices=["iso", "top", "side", "sheet", "pov"])
    ap.add_argument("--yaw", type=int, default=0)
    ap.add_argument("--block", type=int, default=8, help="pixels per block (iso width / top size)")
    ap.add_argument("--below", action="store_true", help="iso from underneath")
    ap.add_argument("--crop", help="x0,z0,x1,z1 in volume coordinates")
    ap.add_argument("--title", default="")
    ap.add_argument("--cut-below", type=int, help="hide blocks below this y (diorama base) in iso views")
    ap.add_argument("--stand", help="pov: x,z to stand on (eye 1.62 above the ground there)")
    ap.add_argument("--eye", help="pov: explicit eye x,y,z")
    ap.add_argument("--look", help="pov: point to look at, x,y,z")
    ap.add_argument("--figures", help="pov: player stand-ins 'x,z;x,z' placed on the ground")
    ap.add_argument("--fov", type=float, default=70.0, help="pov: vertical field of view")
    ap.add_argument("--size", default="1280x720", help="pov: image size WxH")
    ap.add_argument("--no-shadows", action="store_true")
    args = ap.parse_args(argv)
    vol = sculpt.Volume.load(Path(args.dir))
    tex = Textures(Path(args.textures) if args.textures else None)
    crop = [int(v) for v in args.crop.split(",")] if args.crop else None
    if args.view == "iso":
        img = iso(vol, tex, args.block, args.yaw % 4, args.below, crop, cut_below=args.cut_below)
    elif args.view == "top":
        img = top_view(vol, tex, args.block)
    elif args.view == "side":
        img = side_view(vol, tex, args.block)
    elif args.view == "pov":
        import sculpt_pov
        if args.stand:
            x, z = [float(v) for v in args.stand.split(",")]
            ground = int(vol.top_y(ignore=sculpt.PLANTS + ["BARRIER"])[int(z), int(x)])
            eye = (x, ground + 1 + 1.62, z)
        else:
            eye = tuple(float(v) for v in args.eye.split(","))
        look = [float(v) for v in args.look.split(",")]
        figs = []
        for f in (args.figures.split(";") if args.figures else []):
            fx, fz = [float(v) for v in f.split(",")]
            figs.append((fx, int(vol.top_y(ignore=sculpt.PLANTS + ["BARRIER"])[int(fz), int(fx)]) + 1, fz))
        w, h = [int(v) for v in args.size.split("x")]
        img = sculpt_pov.render_pov(vol, faces_for, tex, eye, look, (w, h), args.fov, figs,
                                    not args.no_shadows).convert("RGBA")
    else:
        img = sheet(vol, tex, args.title or Path(args.dir).name, args.block, args.cut_below)
    if args.title and args.view != "sheet":
        img = _label(img, args.title)
    img.convert("RGB").save(args.out)
    print(f"{args.out} {img.width}x{img.height}")


if __name__ == "__main__":
    main()
