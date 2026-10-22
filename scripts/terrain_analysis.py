"""Semantic terrain-volume helpers and deterministic inspection renders."""
from __future__ import annotations

import gzip
import json
from collections import Counter
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw, ImageFont


def load_volume(directory):
    directory = Path(directory)
    metadata = json.loads((directory / 'volume.json').read_text())
    with gzip.open(directory / 'blocks.u16.gz', 'rb') as source:
        blocks = np.frombuffer(source.read(), dtype='>u2').astype(np.uint16)
    return metadata, blocks.reshape(metadata['depth'], metadata['height'], metadata['width'])


def colors(metadata):
    return np.array([[(p['rgb'] >> 16) & 255, (p['rgb'] >> 8) & 255, p['rgb'] & 255]
                     for p in metadata['palette']], dtype=np.uint8)


def surface(blocks, metadata, cap=None):
    limit = blocks.shape[1] if cap is None else min(blocks.shape[1], cap - metadata['minY'] + 1)
    visible = blocks[:, :limit, :] != 0
    any_block = visible.any(axis=1)
    heights = limit - 1 - visible[:, ::-1, :].argmax(axis=1)
    z, x = np.indices(heights.shape)
    ids = blocks[z, heights, x]
    heights = heights.astype(np.int16) + metadata['minY']
    heights[~any_block] = metadata['minY'] - 1
    return heights, ids, any_block


def topdown(blocks, metadata, output, cap=None, scale=3):
    heights, ids, occupied = surface(blocks, metadata, cap)
    rgb = colors(metadata)[ids].astype(float)
    dz, dx = np.gradient(heights.astype(float))
    shade = np.clip(0.9 + (-dx - dz) * 0.09, 0.38, 1.18)
    rgb = np.clip(rgb * shade[..., None], 0, 255).astype(np.uint8)
    rgb[~occupied] = (80, 140, 190)
    image = Image.fromarray(rgb).resize((rgb.shape[1] * scale, rgb.shape[0] * scale), Image.Resampling.NEAREST)
    draw = ImageDraw.Draw(image)
    for wx in range((metadata['minX'] // 32) * 32, metadata['minX'] + metadata['width'], 32):
        px = (wx - metadata['minX']) * scale
        draw.text((px + 2, 2), str(wx), fill='black', stroke_width=1, stroke_fill='white')
    for wz in range((metadata['minZ'] // 32) * 32, metadata['minZ'] + metadata['depth'], 32):
        py = (wz - metadata['minZ']) * scale
        draw.text((2, py + 2), str(wz), fill='black', stroke_width=1, stroke_fill='white')
    image.save(output)


def inspect(directory):
    metadata, blocks = load_volume(directory)
    counts = np.count_nonzero(blocks, axis=(0, 2))
    print('Bounds', {key:metadata[key] for key in ('minX','minY','minZ','width','height','depth')})
    print('Dense heights', [(int(i + metadata['minY']), int(counts[i])) for i in np.argsort(counts)[-24:][::-1]])
    ids, frequency = np.unique(blocks, return_counts=True)
    print('Materials', [(metadata['palette'][i]['type'],int(n)) for i,n in sorted(zip(ids,frequency),key=lambda p:p[1],reverse=True)[:50]])
    topdown(blocks, metadata, Path(directory) / 'overview.png')
    for cap in (64,80,96,112,128):
        topdown(blocks, metadata, Path(directory) / f'cap-{cap}.png', cap=cap)


def isometric(blocks, metadata, bounds, rotation=0, tile=5):
    x0,z0,x1,z1,y0,y1=bounds
    sample=blocks[z0-metadata['minZ']:z1-metadata['minZ']+1,
                  y0-metadata['minY']:y1-metadata['minY']+1,
                  x0-metadata['minX']:x1-metadata['minX']+1]
    sample=np.rot90(sample,rotation,axes=(0,2))
    depth,height,width=sample.shape
    canvas=Image.new('RGB',((width+depth)*tile+24,(width+depth)*tile//2+height*tile+30),(67,88,105))
    draw=ImageDraw.Draw(canvas)
    solid=sample!=0
    exposed=solid.copy()
    nx=np.zeros_like(solid); nx[:,:,:-1]=solid[:,:,1:]
    nz=np.zeros_like(solid); nz[:-1]=solid[1:]
    ny=np.zeros_like(solid); ny[:,:-1]=solid[:,1:]
    exposed &= ~nx|~ny|~nz
    zs,ys,xs=np.where(exposed)
    order=np.argsort(xs+zs+ys,kind='stable')
    palette=colors(metadata)
    for index in order:
        x,y,z=int(xs[index]),int(ys[index]),int(zs[index])
        px=(x-z+depth)*tile+12
        py=(x+z)*tile/2+(height-y)*tile+12
        rgb=palette[sample[z,y,x]].astype(float)
        if not nx[z,y,x]:
            draw.polygon([(px+tile,py-tile/2),(px,py),(px,py+tile),(px+tile,py+tile/2)],
                         fill=tuple((rgb*.72).astype(int)))
        if not nz[z,y,x]:
            draw.polygon([(px,py),(px-tile,py-tile/2),(px-tile,py+tile/2),(px,py+tile)],
                         fill=tuple((rgb*.56).astype(int)))
        if not ny[z,y,x]:
            draw.polygon([(px,py-tile),(px+tile,py-tile/2),(px,py),(px-tile,py-tile/2)],
                         fill=tuple(rgb.astype(int)))
    return canvas


def comparison(before_directory,after_directory,output,regions=None):
    metadata,before=load_volume(before_directory)
    after_meta,after_raw=load_volume(after_directory)
    assert all(metadata[key]==after_meta[key] for key in ('minX','minY','minZ','width','height','depth'))
    # Each panel uses its own semantic palette, including newly introduced materials.
    panels=[]
    regions=regions or [('Green terrain',(10,68,66,124,48,82),2),
             ('Ice terrain',(-137,1,-74,64,48,100),0),
             ('End-stone terrain',(-137,-111,-74,-48,48,85),0)]
    font=ImageFont.truetype('C:/Windows/Fonts/arial.ttf',22)
    for name,bounds,rotation in regions:
        images=[isometric(before,metadata,bounds,rotation),isometric(after_raw,after_meta,bounds,rotation)]
        width=max(im.width for im in images); height=max(im.height for im in images)
        row=Image.new('RGB',(width*2,height+48),'#18232d');d=ImageDraw.Draw(row)
        for col,(caption,image) in enumerate(zip(('Before','After'),images)):
            row.paste(image,(col*width,48));d.text((col*width+16,12),name+' — '+caption,font=font,fill='white')
        panels.append(row)
    sheet=Image.new('RGB',(max(p.width for p in panels),sum(p.height for p in panels)),'#18232d')
    top=0
    for panel in panels:
        sheet.paste(panel,(0,top));top+=panel.height
    sheet.save(output)
    return after_raw


if __name__ == '__main__':
    import sys
    inspect(Path(sys.argv[1]))
