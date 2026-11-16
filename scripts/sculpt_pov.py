"""First-person preview: a textured voxel raycaster from a standing player's eye.

Used by `sculpt.py render DIR --view pov`. The camera stands on the ground at `--stand x,z`
(eye 1.62 blocks above the block it stands on, like a player) or at an explicit `--eye x,y,z`,
and looks at `--look x,y,z`. `--figures` adds simple player-sized stand-ins (0.6 x 1.8 blocks)
so distances read at true scale. Sun shadows, distance fog and cross-shaped plants are drawn;
barriers are invisible, as in game.
"""
from __future__ import annotations

import math

import numpy as np
from PIL import Image

SKY_TOP = np.array([118, 160, 230], np.float64)
SKY_HORIZON = np.array([196, 218, 245], np.float64)
SUN = np.array([0.42, 0.82, 0.38]); SUN /= np.linalg.norm(SUN)
FACE_LIGHT = {"top": 1.0, "bottom": 0.5, "x": 0.82, "z": 0.7}


def build_tables(vol, faces_for, tex):
    """kind per palette id (0 pass-through, 1 opaque, 2 cross plant) and textures [id, face, 16, 16, 4]."""
    n = len(vol.palette)
    kind = np.zeros(n, np.int8)
    table = np.zeros((n, 3, 16, 16, 4), np.float64)
    for i, entry in enumerate(vol.palette):
        top, side, bottom, k = faces_for(entry, tex)
        if k in ("none", "barrier", "glass", "thin"):
            continue
        kind[i] = 2 if k == "cross" else 1
        for f, img in enumerate((top, side, bottom)):
            if img is not None:
                table[i, f] = np.asarray(img.convert("RGBA").resize((16, 16), Image.NEAREST), np.float64)
    return kind, table


def trace(a, kind, table, O, D, max_t, plants=True, max_steps=900):
    """Vectorised Amanatides-Woo walk. Returns hit mask, t, face axis, step sign, palette id, rgb."""
    N = len(D)
    dims = np.array([a.shape[2], a.shape[1], a.shape[0]])  # x, y, z
    ix = np.floor(O).astype(np.int64)
    if ix.ndim == 1:
        ix = np.broadcast_to(ix, (N, 3)).copy()
    O = np.broadcast_to(O, (N, 3))
    step = np.where(D > 0, 1, -1).astype(np.int64)
    with np.errstate(divide="ignore", invalid="ignore"):
        inv = np.where(D != 0, 1.0 / D, np.inf)
        tdelta = np.abs(inv)
        tmax = np.where(D != 0, ((ix + (step > 0)) - O) * inv, np.inf)
    tenter = np.zeros(N)
    axis = np.full(N, 1, np.int64)
    hit = np.zeros(N, bool)
    t_hit = np.full(N, np.inf)
    hit_id = np.zeros(N, np.int64)
    rgb = np.zeros((N, 3))
    act = np.arange(N)
    for _ in range(max_steps):
        if act.size == 0:
            break
        v = ix[act]
        inb = np.all((v >= 0) & (v < dims), axis=1)
        bid = np.zeros(act.size, np.int64)
        bid[inb] = a[v[inb, 2], v[inb, 1], v[inb, 0]]
        k = kind[bid]
        done = np.zeros(act.size, bool)
        solid = k == 1
        if solid.any():
            r = act[solid]
            hit[r] = True; t_hit[r] = tenter[r]; hit_id[r] = bid[solid]
            done |= solid
        if plants and (k == 2).any():
            sel = np.nonzero(k == 2)[0]
            r = act[sel]
            o, d, vv = O[r], D[r], ix[r].astype(np.float64)
            t0 = tenter[r]; t1 = np.min(tmax[r], axis=1)
            best_t = np.full(len(r), np.inf); best_c = np.zeros((len(r), 3))
            for plane in (0, 1):
                # plane 0: lx - lz = 0 ; plane 1: lx + lz = 1 (local voxel coords)
                if plane == 0:
                    num = (vv[:, 0] - o[:, 0]) - (vv[:, 2] - o[:, 2]); den = d[:, 0] - d[:, 2]
                else:
                    num = 1 + vv[:, 0] + vv[:, 2] - o[:, 0] - o[:, 2]; den = d[:, 0] + d[:, 2]
                with np.errstate(divide="ignore", invalid="ignore"):
                    tp = num / den
                ok = (tp >= t0 - 1e-6) & (tp <= t1 + 1e-6) & np.isfinite(tp)
                p = o + d * tp[:, None]
                lx = np.clip(p[:, 0] - vv[:, 0], 0, 0.999)
                ly = np.clip(p[:, 1] - vv[:, 1], 0, 0.999)
                u = (lx * 16).astype(np.int64); w = ((1 - ly) * 16).astype(np.int64).clip(0, 15)
                texel = table[bid[sel], 1, w, u]
                ok &= texel[:, 3] > 100
                better = ok & (tp < best_t)
                best_t[better] = tp[better]; best_c[better] = texel[better, :3] * 0.92
            got = np.isfinite(best_t)
            if got.any():
                rr = r[got]
                hit[rr] = True; t_hit[rr] = best_t[got]; hit_id[rr] = bid[sel][got]; rgb[rr] = best_c[got]
                axis[rr] = -1
                done[sel[got]] = True
        # advance
        rem = act[~done]
        tm = tmax[rem]
        ax = np.argmin(tm, axis=1)
        rows = np.arange(len(rem))
        tenter[rem] = tm[rows, ax]
        axis[rem] = ax
        ix[rem, ax] += step[rem, ax]
        tmax[rem, ax] += tdelta[rem, ax]
        v = ix[rem]
        away = np.any(((v < 0) & (step[rem] < 0)) | ((v >= dims) & (step[rem] > 0)), axis=1)
        act = rem[(~away) & (tenter[rem] < max_t)]
    return hit, t_hit, axis, step, hit_id, rgb


def shade_hits(O, D, hit, t, axis, step, hid, rgb, table):
    """Texture-sample block faces (plants already carry their colour)."""
    P = O + D * np.where(hit, t, 0)[:, None]
    faces = hit & (axis >= 0)
    idx = np.nonzero(faces)[0]
    ax = axis[idx]
    p = P[idx]
    # Nudge into the hit voxel so frac() is taken inside it.
    fx, fy, fz = p[:, 0] - np.floor(p[:, 0] + 1e-7 * -step[idx, 0]), p[:, 1] - np.floor(p[:, 1]), p[:, 2] - np.floor(p[:, 2])
    fx = p[:, 0] % 1.0; fy = p[:, 1] % 1.0; fz = p[:, 2] % 1.0
    u = np.where(ax == 0, fz, fx)
    v = np.where(ax == 1, fz, 1 - fy)
    face = np.where(ax == 1, np.where(step[idx, 1] < 0, 0, 2), 1)
    ui = (u * 16).astype(np.int64).clip(0, 15); vi = (v * 16).astype(np.int64).clip(0, 15)
    col = table[hid[idx], face, vi, ui, :3]
    light = np.where(ax == 1, np.where(step[idx, 1] < 0, FACE_LIGHT["top"], FACE_LIGHT["bottom"]),
                     np.where(ax == 0, FACE_LIGHT["x"], FACE_LIGHT["z"]))
    rgb[idx] = col * light[:, None]
    normal = np.zeros((len(idx), 3))
    normal[np.arange(len(idx)), ax] = -step[idx, ax]
    return P, idx, normal


def figure_hits(O, D, figures):
    """Ray vs simple player boxes. Returns t (inf where missed) and colour."""
    N = len(D)
    best = np.full(N, np.inf); col = np.zeros((N, 3))
    parts = [((-0.25, 0.0, -0.13), (0.25, 0.75, 0.13), (52, 62, 140)),     # legs
             ((-0.25, 0.75, -0.13), (0.25, 1.4, 0.13), (40, 160, 170)),    # torso
             ((-0.37, 0.75, -0.11), (-0.25, 1.4, 0.11), (205, 160, 120)),  # arms
             ((0.25, 0.75, -0.11), (0.37, 1.4, 0.11), (205, 160, 120)),
             ((-0.25, 1.4, -0.25), (0.25, 1.9, 0.25), (210, 165, 125)),    # head
             ((-0.26, 1.72, -0.26), (0.26, 1.92, 0.26), (70, 45, 30))]     # hair
    with np.errstate(divide="ignore", invalid="ignore"):
        inv = 1.0 / D
    for fx, fy, fz in figures:
        for lo, hi, c in parts:
            lo3 = np.array([fx + lo[0], fy + lo[1], fz + lo[2]]); hi3 = np.array([fx + hi[0], fy + hi[1], fz + hi[2]])
            with np.errstate(invalid="ignore"):
                t1 = (lo3 - O) * inv; t2 = (hi3 - O) * inv
            tn = np.nanmax(np.minimum(t1, t2), axis=1); tf = np.nanmin(np.maximum(t1, t2), axis=1)
            ok = (tf >= tn) & (tf > 0) & (tn < best)
            best[ok] = tn[ok]
            # crude face shading by which slab was entered
            ent = np.argmax(np.minimum(t1, t2), axis=1)
            k = np.where(ent == 1, 1.0, np.where(ent == 0, 0.85, 0.72))
            col[ok] = np.array(c) * k[ok, None]
    return best, col


def render_pov(vol, faces_for, tex, eye, look, size=(1280, 720), fov=70.0, figures=(), shadows=True,
               fog=320.0, max_t=300.0):
    W, H = size
    kind, table = build_tables(vol, faces_for, tex)
    a = vol.a
    eye = np.asarray(eye, np.float64)
    fwd = np.asarray(look, np.float64) - eye; fwd /= np.linalg.norm(fwd)
    right = np.cross(fwd, [0, 1, 0]); right /= np.linalg.norm(right)
    up = np.cross(right, fwd)
    th = math.tan(math.radians(fov) / 2)
    js, is_ = np.mgrid[0:H, 0:W].astype(np.float64)
    sx = ((is_ + 0.5) / W * 2 - 1) * th * W / H
    sy = (1 - (js + 0.5) / H * 2) * th
    D = fwd[None] + sx.reshape(-1, 1) * right[None] + sy.reshape(-1, 1) * up[None]
    D /= np.linalg.norm(D, axis=1, keepdims=True)
    hit, t, axis, step, hid, rgb = trace(a, kind, table, eye, D, max_t)
    P, idx, normal = shade_hits(eye, D, hit, t, axis, step, hid, rgb, table)
    if shadows and len(idx):
        so = P[idx] + normal * 1e-3 + SUN[None] * 1e-3
        sh, *_ = trace(a, kind, table, so, np.broadcast_to(SUN, so.shape).copy(), 120.0, plants=False)
        rgb[idx[sh]] *= 0.6
    ft, fcol = figure_hits(eye, D, figures)
    fig = ft < np.where(hit, t, np.inf)
    rgb[fig] = fcol[fig]; t = np.where(fig, ft, t); hit = hit | fig
    # sky + fog
    sky = SKY_HORIZON[None] + (SKY_TOP - SKY_HORIZON)[None] * np.clip(D[:, 1], 0, 1)[:, None] ** 0.6
    below = D[:, 1] < 0
    sky[below] = SKY_HORIZON * (1 - 0.25 * np.clip(-D[below, 1], 0, 1))[:, None]
    f = np.where(hit, 1 - np.exp(-np.where(hit, t, 0) / fog), 1.0)[:, None]
    out = rgb * (1 - f) + sky * f
    img = out.reshape(H, W, 3).clip(0, 255).astype(np.uint8)
    return Image.fromarray(img, "RGB")
