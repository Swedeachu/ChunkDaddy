#!/usr/bin/env python3
"""view3d: quick 3D views of a sculpt/terrain volume with gameplay markers, for eyeballing placements.

    view3d.py orbit DIR --out PREFIX [--points points.json --map ID] [--markers M.json]
                        [--focus x,y,z] [--views 4] [--pitch 35] [--distance D] [--size 960x540]
    view3d.py html  DIR --out FILE.html [--points points.json --map ID] [--markers M.json] [--title T]
    add --barriers to either mode to draw invisible barrier walls in red (play-area boundaries).

DIR is a volume directory (volume.json + blocks.u16.gz, as dumped by `terrain_volume` or written by
sculpt.py). Marker and focus coordinates are WORLD block coordinates; volume.json's minX/minY/minZ
map them into the volume.

`orbit` renders N perspective images (PREFIX_0.png ...) circling the focus (default: the map
center from --points, else the volume center) with the sculpt_pov raycaster. Markers are drawn as
coloured pillars standing on their y with a label above, so an agent can Read the PNGs and judge
placements. `html` writes one self-contained page (three.js r128 from cdnjs + inline orbit
controls) with every surface-visible block as an instanced cube (hidden blocks culled) and the
markers as labelled pillars plus the hill ring; drag to orbit, right-drag/shift-drag to pan,
wheel to zoom.

Markers JSON: a list of {"label", "x", "y", "z", "colour": "#rrggbb", "radius"?}. A marker with
"radius" is drawn as a ring (hill). `--points` reads a party/event points.json (schema 1: maps[]
with teams[4] spawn/base/shop/upgrades, hill, ffaSpawns) and builds the markers for `--map`.
Blocks are coloured by an approximate average-texture table (no texture pack needed).
"""
from __future__ import annotations

import argparse
import base64
import json
import math
import re
import sys
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw, ImageFont

sys.path.insert(0, str(Path(__file__).resolve().parent))
import sculpt  # noqa: E402
import sculpt_pov  # noqa: E402

# --------------------------------------------------------------------------------------------
# Colours: (pattern, rgb), first match wins. Approximate average texture colours.
COLOURS = [
    (r"WATER", (52, 90, 200)), (r"LAVA", (210, 90, 20)), (r"GRASS_BLOCK", (105, 160, 70)),
    (r"LEAVES", (62, 118, 48)), (r"(SHORT|TALL)_GRASS|FERN|VINE|WATERLILY|SAPLING|REEDS", (80, 140, 55)),
    (r"^WHITE_", (225, 228, 228)), (r"^ORANGE_", (226, 120, 30)), (r"^MAGENTA_", (180, 70, 170)),
    (r"^LIGHT_BLUE_", (90, 160, 210)), (r"^YELLOW_", (230, 195, 50)), (r"^LIME_", (110, 180, 40)),
    (r"^PINK_", (220, 130, 160)), (r"^GRAY_", (70, 75, 80)), (r"^LIGHT_GRAY_", (140, 140, 135)),
    (r"^CYAN_", (30, 130, 140)), (r"^PURPLE_", (115, 45, 165)), (r"^BLUE_", (50, 60, 155)),
    (r"^BROWN_", (110, 75, 45)), (r"^GREEN_", (80, 105, 40)), (r"^RED_SAND", (185, 100, 40)),
    (r"^RED_", (155, 40, 35)), (r"^BLACK_", (25, 25, 30)),
    (r"DARK_OAK", (66, 43, 20)), (r"SPRUCE", (115, 85, 50)), (r"BIRCH", (195, 178, 120)),
    (r"JUNGLE", (160, 115, 80)), (r"ACACIA", (170, 92, 50)), (r"OAK|PLANKS|WOODEN|TRAPDOOR|FENCE|BOOKSHELF|CHEST|CRAFTING", (162, 130, 78)),
    (r"SANDSTONE|^SAND$|SMOOTH_SANDSTONE|CUT_SANDSTONE", (218, 205, 155)), (r"GRAVEL", (130, 125, 122)),
    (r"DIRT|FARMLAND|PODZOL|MYCELIUM", (134, 96, 67)), (r"QUARTZ|SNOW|DIORITE", (235, 230, 222)),
    (r"BRICK_BLOCK|^BRICK", (150, 85, 70)), (r"NETHER", (100, 40, 40)), (r"PRISMARINE", (80, 155, 140)),
    (r"MOSSY", (100, 120, 90)), (r"COBBLE", (120, 120, 120)), (r"ANDESITE", (135, 135, 136)),
    (r"GRANITE", (150, 105, 85)), (r"STONE_BRICK|STONE|BEDROCK|ORE", (122, 122, 122)),
    (r"TERRACOTTA|HARDENED_CLAY", (150, 92, 66)), (r"CLAY", (160, 165, 178)), (r"IRON", (215, 215, 215)),
    (r"GOLD", (245, 205, 60)), (r"DIAMOND", (100, 220, 210)), (r"EMERALD", (40, 190, 90)),
    (r"LAPIS", (35, 65, 140)), (r"REDSTONE", (175, 25, 20)), (r"COAL|OBSIDIAN", (30, 25, 40)),
    (r"GLOWSTONE|SEA_LANTERN|LAMP|TORCH", (240, 220, 150)), (r"ICE", (150, 185, 240)),
    (r"HAY", (200, 165, 40)), (r"PUMPKIN|MELON", (200, 120, 30)), (r"GLASS", (190, 215, 225)),
    (r"BARRIER", (235, 55, 55)),
]
PASS = re.compile(r"^(AIR|BARRIER|.*(SHORT_GRASS|TALL_GRASS|FERN|FLOWER|POPPY|DANDELION|TULIP|ORCHID|ALLIUM|BLUET|DAISY|"
                  r"TORCH|SIGN|BANNER|RAIL|REDSTONE_WIRE|TRIP_WIRE|TRIPWIRE|BUTTON|LEVER|SAPLING|DEADBUSH|VINE|"
                  r"REEDS|LILAC|PEONY|ROSE_BUSH|SUNFLOWER|MUSHROOM$|WHEAT|CARROTS|POTATOES|STEM|FRAME|LADDER|FIRE|WEB|"
                  r"LARGE_FERN|COCOA|PRESSURE_PLATE|CARPET|SNOW_LAYER|WATERLILY))$")


def colour_of(t: str):
    for pat, rgb in COLOURS:
        if re.search(pat, t):
            return rgb
    h = abs(hash(t)) % 997
    return (90 + h % 120, 90 + (h * 7) % 120, 90 + (h * 13) % 120)


SHOW_BARRIERS = False  # --barriers: draw invisible barrier blocks (red, never culling) to see play-area walls


def tables(vol):
    """Per palette id: rgb, opaque-for-culling flag, drawn flag."""
    n = len(vol.palette)
    rgb = np.zeros((n, 3), np.uint8); drawn = np.zeros(n, bool); opaque = np.zeros(n, bool)
    for i, e in enumerate(vol.palette):
        t = e["type"]
        if t == "AIR" or (t == "BARRIER" and not SHOW_BARRIERS):
            continue
        rgb[i] = colour_of(t)
        drawn[i] = not PASS.match(t) or "CARPET" in t or "SNOW_LAYER" in t
        opaque[i] = drawn[i] and not re.search(r"GLASS|LEAVES|FENCE|SLAB|STAIRS|PANE|BARS|WALL|DOOR|CARPET|SNOW_LAYER|WATER|LAVA", t)
        if t == "BARRIER":
            drawn[i], opaque[i] = True, False
    return rgb, opaque, drawn


def faces_flat(vol):
    rgb, _, drawn = tables(vol)

    def faces_for(entry, _tex):
        i = vol._ids.get(sculpt.block_key(entry))
        if i is None or not drawn[i]:
            return None, None, None, "none"
        img = Image.new("RGBA", (16, 16), tuple(int(c) for c in rgb[i]) + (255,))
        # faint texel grid so block edges read
        d = ImageDraw.Draw(img)
        d.rectangle((0, 0, 15, 15), outline=tuple(int(c * 0.8) for c in rgb[i]) + (255,))
        return img, img, img, "cube"
    return faces_for


# --------------------------------------------------------------------------------------------
# Markers

TEAM_RGB = ["#e53935", "#1e88e5", "#43a047", "#fdd835"]


def markers_from_points(path, map_id):
    data = json.loads(Path(path).read_text())
    m = next((m for m in data["maps"] if m["id"] == map_id), None)
    if m is None:
        raise SystemExit(f"map {map_id} not in {path}")
    out = []
    for i, t in enumerate(m.get("teams", [])):
        c = TEAM_RGB[i % 4]
        for key, lab in (("spawn", "T"), ("base", "B"), ("shop", "S"), ("upgrades", "U")):
            p = t.get(key)
            if p:
                out.append({"label": f"{lab}{i + 1}", "x": p["x"], "y": p["y"], "z": p["z"], "colour": c,
                            "kind": key, "yaw": p.get("yaw")})
    h = m.get("hill")
    if h:
        out.append({"label": "H", "x": h["x"], "y": h["y"], "z": h["z"], "colour": "#ffffff", "radius": h.get("radius", 4)})
    for i, f in enumerate(m.get("ffaSpawns", [])):
        out.append({"label": f"F{i + 1}", "x": f["x"], "y": f["y"], "z": f["z"], "colour": "#ab47bc", "kind": "ffa", "yaw": f.get("yaw")})
    return out, m


def hexrgb(s):
    s = s.lstrip("#")
    return tuple(int(s[i:i + 2], 16) for i in (0, 2, 4))


def local(vol, x, y, z):
    return x - vol.minX, y - vol.minY, z - vol.minZ


def load_markers(args, vol):
    markers, meta = [], None
    if args.points:
        markers, meta = markers_from_points(args.points, args.map)
    if args.markers:
        markers += json.loads(Path(args.markers).read_text())
    return markers, meta


# --------------------------------------------------------------------------------------------
# Orbit renders

def pillar_hits(O, D, markers, vol):
    N = len(D)
    best = np.full(N, np.inf); col = np.zeros((N, 3))
    with np.errstate(divide="ignore", invalid="ignore"):
        inv = 1.0 / D
    boxes = []
    for mk in markers:
        x, y, z = local(vol, mk["x"], mk["y"], mk["z"])
        c = np.array(hexrgb(mk.get("colour", "#ff00ff")), np.float64)
        if "radius" in mk:  # ring of small posts
            r = float(mk["radius"])
            for k in range(24):
                a = 2 * math.pi * k / 24
                px, pz = x + r * math.cos(a), z + r * math.sin(a)
                boxes.append(((px - 0.2, y, pz - 0.2), (px + 0.2, y + 0.6, pz + 0.2), c))
            boxes.append(((x - 0.15, y, z - 0.15), (x + 0.15, y + 4, z + 0.15), c))
        else:
            h = 1.8 if mk.get("kind") in ("shop", "upgrades", "ffa") else 4.0
            w = 0.3
            boxes.append(((x - w, y, z - w), (x + w, y + h, z + w), c))
    for lo, hi, c in boxes:
        lo3, hi3 = np.array(lo), np.array(hi)
        with np.errstate(invalid="ignore"):
            t1 = (lo3 - O) * inv; t2 = (hi3 - O) * inv
        tn = np.nanmax(np.minimum(t1, t2), axis=1); tf = np.nanmin(np.maximum(t1, t2), axis=1)
        ok = (tf >= tn) & (tf > 0) & (tn < best)
        best[ok] = tn[ok]
        ent = np.argmax(np.minimum(t1, t2), axis=1)
        k = np.where(ent == 1, 1.0, np.where(ent == 0, 0.85, 0.7))
        col[ok] = c * k[ok, None]
    return best, col


def render_view(vol, faces_for, eye, look, size, markers, fov=60.0, max_t=600.0):
    W, H = size
    kind, table = sculpt_pov.build_tables(vol, faces_for, None)
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
    hit, t, axis, step, hid, rgb = sculpt_pov.trace(vol.a, kind, table, eye, D, max_t, plants=False, max_steps=2000)
    sculpt_pov.shade_hits(eye, D, hit, t, axis, step, hid, rgb, table)
    ft, fcol = pillar_hits(eye, D, markers, vol)
    fig = ft < np.where(hit, t, np.inf)
    rgb[fig] = fcol[fig]; hit = hit | fig
    sky = np.broadcast_to(sculpt_pov.SKY_HORIZON, rgb.shape)
    out = np.where(hit[:, None], rgb, sky)
    img = Image.fromarray(out.reshape(H, W, 3).clip(0, 255).astype(np.uint8), "RGB")
    # labels: project the pillar tops
    d = ImageDraw.Draw(img)
    try:
        font = ImageFont.truetype("arial.ttf", 13)
    except OSError:
        font = ImageFont.load_default()
    for mk in markers:
        x, y, z = local(vol, mk["x"], mk["y"], mk["z"])
        top = np.array([x, y + (4.6 if "radius" in mk else 2.4 if mk.get("kind") in ("shop", "upgrades", "ffa") else 4.6), z])
        v = top - eye
        depth = v @ fwd
        if depth <= 0.5:
            continue
        px = (v @ right) / depth / (th * W / H); py = (v @ up) / depth / th
        u = (px + 1) / 2 * W; w = (1 - py) / 2 * H
        if 0 <= u < W and 0 <= w < H:
            txt = mk["label"]
            tb = d.textbbox((u, w), txt, font=font)
            d.rectangle((tb[0] - 2, tb[1] - 1, tb[2] + 2, tb[3] + 1), fill=(0, 0, 0))
            d.text((u, w), txt, fill=hexrgb(mk.get("colour", "#ffffff")), font=font)
    return img


def cmd_orbit(args):
    vol = sculpt.Volume.load(Path(args.dir))
    markers, meta = load_markers(args, vol)
    if args.focus:
        fx, fy, fz = [float(v) for v in args.focus.split(",")]
    elif meta and meta.get("hill"):
        fx, fy, fz = meta["hill"]["x"], meta["hill"]["y"], meta["hill"]["z"]
    else:
        fx, fy, fz = vol.minX + vol.width / 2, vol.minY + vol.height / 2, vol.minZ + vol.depth / 2
    focus = np.array(local(vol, fx, fy, fz), np.float64)
    dist = args.distance or 0.75 * max(vol.width, vol.depth)
    w, h = [int(v) for v in args.size.split("x")]
    faces_for = faces_flat(vol)
    pitch = math.radians(args.pitch)
    for k in range(args.views):
        a = 2 * math.pi * k / args.views + math.radians(args.start)
        eye = focus + dist * np.array([math.cos(pitch) * math.sin(a), math.sin(pitch), -math.cos(pitch) * math.cos(a)])
        img = render_view(vol, faces_for, eye, focus, (w, h), markers)
        bearing = ["from N", "from E", "from S", "from W"][k * 4 // args.views] if args.views in (4, 8) else f"{k}"
        ImageDraw.Draw(img).text((6, 4), f"{args.title or Path(args.dir).name} view {k} ({bearing})", fill=(0, 0, 0))
        p = f"{args.out}_{k}.png"
        img.save(p)
        print(p)


# --------------------------------------------------------------------------------------------
# HTML export

def surface_blocks(vol, pad_markers=()):
    rgb, opaque, drawn = tables(vol)
    a = vol.a
    dr = drawn[a]
    op = opaque[a]
    hidden = np.ones_like(op)
    # a block is hidden when all 6 neighbours are opaque (volume edges count as open)
    for ax in range(3):
        for s in (1, -1):
            nb = np.zeros_like(op)
            sl_dst = [slice(None)] * 3; sl_src = [slice(None)] * 3
            if s == 1:
                sl_dst[ax] = slice(0, -1); sl_src[ax] = slice(1, None)
            else:
                sl_dst[ax] = slice(1, None); sl_src[ax] = slice(0, -1)
            nb[tuple(sl_dst)] = op[tuple(sl_src)]
            hidden &= nb
    vis = dr & ~hidden
    z, y, x = np.nonzero(vis)
    ids = a[z, y, x]
    return x, y, z, ids, rgb


HTML = """<!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>__TITLE__</title><style>html,body{margin:0;height:100%;background:#1a1e28;overflow:hidden;font:13px sans-serif;color:#ddd}
#hud{position:absolute;left:8px;top:8px;background:#000a;padding:6px 8px;border-radius:4px;max-width:46ch}
.lab{position:absolute;pointer-events:none;font:bold 12px sans-serif;padding:1px 3px;background:#000b;border-radius:3px;transform:translate(-50%,-100%)}
</style></head><body><div id="hud"><b>__TITLE__</b><br>drag: orbit, right/shift-drag: pan, wheel: zoom.
<label><input type="checkbox" id="lbl" checked> labels</label> <label><input type="checkbox" id="ffa" checked> FFA</label><br>__LEGEND__</div>
<script src="https://cdnjs.cloudflare.com/ajax/libs/three.js/r128/three.min.js"></script>
<script>
const D=__DATA__;
function dec(s,T){const b=atob(s),u=new Uint8Array(b.length);for(let i=0;i<b.length;i++)u[i]=b.charCodeAt(i);return new T(u.buffer);}
const P=dec(D.pos,Int16Array),C=dec(D.col,Uint16Array),pal=D.pal,n=C.length;
const scene=new THREE.Scene();scene.background=new THREE.Color(0x1a1e28);
const cam=new THREE.PerspectiveCamera(55,innerWidth/innerHeight,0.5,4000);
const R=new THREE.WebGLRenderer({antialias:true});R.setSize(innerWidth,innerHeight);R.setPixelRatio(Math.min(devicePixelRatio,2));document.body.appendChild(R.domElement);
scene.add(new THREE.HemisphereLight(0xffffff,0x445566,0.75));const sun=new THREE.DirectionalLight(0xffffff,0.55);sun.position.set(0.4,1,0.3);scene.add(sun);
const mesh=new THREE.InstancedMesh(new THREE.BoxGeometry(1,1,1),new THREE.MeshLambertMaterial(),n);
const m=new THREE.Matrix4(),c=new THREE.Color();
for(let i=0;i<n;i++){m.makeTranslation(P[3*i]+0.5,P[3*i+1]+0.5,P[3*i+2]+0.5);mesh.setMatrixAt(i,m);const q=pal[C[i]];c.setRGB(q[0]/255,q[1]/255,q[2]/255);mesh.setColorAt(i,c);}
scene.add(mesh);
const labels=[];
for(const k of D.markers){const col=new THREE.Color(k.colour);const x=k.x-D.origin[0],y=k.y-D.origin[1],z=k.z-D.origin[2];
 if(k.radius!==undefined){const g=new THREE.TorusGeometry(k.radius,0.12,6,64);const t=new THREE.Mesh(g,new THREE.MeshBasicMaterial({color:col}));t.rotation.x=Math.PI/2;t.position.set(x,y+0.1,z);scene.add(t);}
 const h=k.radius!==undefined?4:(['shop','upgrades','ffa'].includes(k.kind)?1.8:4);
 const p=new THREE.Mesh(new THREE.BoxGeometry(0.5,h,0.5),new THREE.MeshBasicMaterial({color:col}));p.position.set(x,y+h/2,z);scene.add(p);p.userData.ffa=k.kind==='ffa';
 if(k.yaw!==null&&k.yaw!==undefined){const a=k.yaw*Math.PI/180;const dir=new THREE.Vector3(-Math.sin(a),0,Math.cos(a));scene.add(new THREE.ArrowHelper(dir,new THREE.Vector3(x,y+1.2,z),2,col.getHex(),0.6,0.4));}
 const el=document.createElement('div');el.className='lab';el.textContent=k.label;el.style.color=k.colour;document.body.appendChild(el);labels.push({el,v:new THREE.Vector3(x,y+h+0.4,z),ffa:k.kind==='ffa',mesh:p});}
let tgt=new THREE.Vector3(D.focus[0],D.focus[1],D.focus[2]),rad=D.dist,th=0.6,ph=0.9;
function upd(){cam.position.set(tgt.x+rad*Math.sin(ph)*Math.sin(th),tgt.y+rad*Math.cos(ph),tgt.z+rad*Math.sin(ph)*Math.cos(th));cam.lookAt(tgt);}
let drag=null;R.domElement.oncontextmenu=e=>e.preventDefault();
R.domElement.onpointerdown=e=>{drag={x:e.clientX,y:e.clientY,pan:e.button===2||e.shiftKey};R.domElement.setPointerCapture(e.pointerId);};
R.domElement.onpointerup=()=>drag=null;
R.domElement.onpointermove=e=>{if(!drag)return;const dx=e.clientX-drag.x,dy=e.clientY-drag.y;drag.x=e.clientX;drag.y=e.clientY;
 if(drag.pan){const s=rad/600,r=new THREE.Vector3().setFromMatrixColumn(cam.matrix,0),f=new THREE.Vector3(-Math.sin(th),0,-Math.cos(th));tgt.addScaledVector(r,-dx*s).addScaledVector(f,dy*s);}
 else{th-=dx*0.006;ph=Math.min(Math.max(ph-dy*0.006,0.05),1.55);}};
R.domElement.onwheel=e=>{e.preventDefault();rad=Math.min(Math.max(rad*Math.exp(e.deltaY*0.001),5),2000);};
const lbl=document.getElementById('lbl'),ffa=document.getElementById('ffa');
onresize=()=>{cam.aspect=innerWidth/innerHeight;cam.updateProjectionMatrix();R.setSize(innerWidth,innerHeight);};
const v=new THREE.Vector3();
(function loop(){upd();R.render(scene,cam);
 for(const L of labels){L.mesh.visible=!L.ffa||ffa.checked;v.copy(L.v).project(cam);const ok=lbl.checked&&(!L.ffa||ffa.checked)&&v.z<1&&Math.abs(v.x)<1&&Math.abs(v.y)<1;
  L.el.style.display=ok?'block':'none';if(ok){L.el.style.left=((v.x+1)/2*innerWidth)+'px';L.el.style.top=((1-v.y)/2*innerHeight)+'px';}}
 requestAnimationFrame(loop);})();
</script></body></html>"""


def cmd_html(args):
    vol = sculpt.Volume.load(Path(args.dir))
    markers, meta = load_markers(args, vol)
    x, y, z, ids, rgb = surface_blocks(vol)
    used, inv = np.unique(ids, return_inverse=True)
    pos = np.stack([x, y, z], 1).astype("<i2").tobytes()
    col = inv.astype("<u2").tobytes()
    if meta and meta.get("hill"):
        h = meta["hill"]; focus = local(vol, h["x"], h["y"], h["z"])
    else:
        focus = (vol.width / 2, vol.height / 2, vol.depth / 2)
    data = {"pos": base64.b64encode(pos).decode(), "col": base64.b64encode(col).decode(),
            "pal": [[int(c) for c in rgb[i]] for i in used], "origin": [vol.minX, vol.minY, vol.minZ],
            "markers": markers, "focus": list(map(float, focus)), "dist": 0.6 * max(vol.width, vol.depth)}
    legend = ("<span style='color:#e53935'>team1</span> <span style='color:#1e88e5'>team2</span> "
              "<span style='color:#43a047'>team3</span> <span style='color:#fdd835'>team4</span>: "
              "T spawn, B base, S shop, U upgrades; <span style='color:#ab47bc'>F</span> FFA; H hill") if args.points else ""
    title = args.title or (meta.get("name") if meta else Path(args.dir).name)
    html = HTML.replace("__TITLE__", title).replace("__LEGEND__", legend).replace("__DATA__", json.dumps(data))
    Path(args.out).write_text(html, encoding="utf-8")
    print(f"{args.out} blocks={len(ids)} bytes={len(html)}")


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = ap.add_subparsers(dest="cmd", required=True)
    for name in ("orbit", "html"):
        p = sub.add_parser(name)
        p.add_argument("dir")
        p.add_argument("--out", required=True)
        p.add_argument("--points"); p.add_argument("--map"); p.add_argument("--markers")
        p.add_argument("--title", default="")
        if name == "orbit":
            p.add_argument("--focus"); p.add_argument("--views", type=int, default=4)
            p.add_argument("--pitch", type=float, default=35.0); p.add_argument("--distance", type=float)
            p.add_argument("--start", type=float, default=0.0, help="first view bearing in degrees")
            p.add_argument("--size", default="960x540")
        p.add_argument("--barriers", action="store_true", help="draw barrier blocks (red) to show invisible walls")
    args = ap.parse_args(argv)
    global SHOW_BARRIERS
    SHOW_BARRIERS = args.barriers
    (cmd_orbit if args.cmd == "orbit" else cmd_html)(args)


if __name__ == "__main__":
    main()
