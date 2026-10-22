"""Recognize terrain skins and rigid attached objects, then author a reviewable displacement field."""
from __future__ import annotations

import argparse
import gzip
import hashlib
import json
from pathlib import Path

import numpy as np
from scipy import ndimage as ndi
from PIL import Image, ImageDraw

from terrain_analysis import load_volume, topdown


def plan(directory, configuration, output):
    output.mkdir(parents=True, exist_ok=True)
    metadata, blocks = load_volume(directory)
    names = np.array([entry['type'] for entry in metadata['palette']])
    names_upper = np.char.upper(names)
    groups = configuration['terrainGroups']
    family = np.zeros(len(names), dtype=np.uint8)
    for number, group in enumerate(groups, 1):
        family[np.isin(names_upper, group['materials'])] = number
    plants = np.isin(names_upper, configuration['transparentGroundOverlays'])
    plants[0] = True
    minimum, maximum = configuration['floorBand']
    lo, hi = minimum - metadata['minY'], maximum - metadata['minY']
    candidate = (family[blocks[:, lo:hi+1, :]] != 0)
    candidate &= plants[blocks[:, lo+1:hi+2, :]] & plants[blocks[:, lo+2:hi+3, :]]
    known = candidate.any(axis=1)
    heights = maximum - candidate[:, ::-1, :].argmax(axis=1)
    z, x = np.indices(known.shape)
    ids = blocks[z, heights - metadata['minY'], x]
    families = family[ids]
    foundation = names_upper[blocks[:, configuration['foundationY']-metadata['minY'], :]] == configuration['foundationMaterial']
    known &= foundation
    distance, nearest = ndi.distance_transform_edt(~known, return_indices=True)
    heights = heights[tuple(nearest)].astype(np.int16)
    families = families[tuple(nearest)]
    editable = foundation & (ndi.distance_transform_edt(foundation) > configuration.get('border', 5))
    editable &= np.isin(families, [i for i,g in enumerate(groups,1) if g.get('edit',True)])
    editable &= distance <= configuration.get('maximumInferenceDistance', 10)
    for region in configuration.get('protectedRegions',[]):
        editable &= ~((x+metadata['minX']>=region['minX'])&(x+metadata['minX']<=region['maxX'])
                      &(z+metadata['minZ']>=region['minZ'])&(z+metadata['minZ']<=region['maxZ']))
    if configuration.get('groupOffsets') and configuration.get('sourceFingerprint') != metadata['fingerprint']:
        raise ValueError('Explicit group selections require their exact sourceFingerprint')
    liquid = np.isin(names_upper, configuration.get('liquids', ['WATER','LAVA']))
    liquids = liquid[blocks].any(axis=1)
    desired = np.minimum(0, configuration['targetY'] - heights).astype(np.int8)
    desired[~editable | liquids] = 0

    # Keep attached geometry rigid. A ground sample sees through flowers, but never
    # uses a roof or canopy as the terrain. Unknown ground is inferred from nearby paths.
    ys = np.arange(blocks.shape[1],dtype=np.int16) + metadata['minY']
    foreground = (blocks != 0) & (ys[None,:,None] > heights[:,None,:])
    structural_types = (family == 0) & ~plants & ~liquid
    structural_types[names_upper == configuration['foundationMaterial']] = False
    # Buried colored planks are part of this map's substrate, not a connected
    # building floor. Only exposed non-terrain blocks extend a group below the
    # inferred surface; buried roots follow the group's support-column offset.
    band=blocks[:,lo:hi+1,:]
    air=band==0
    exposed=(blocks[:,lo-1:hi,:]==0)|(blocks[:,lo+1:hi+2,:]==0)
    exposed[1:]|=air[:-1]; exposed[:-1]|=air[1:]
    exposed[:,:,1:]|=air[:,:,:-1]; exposed[:,:,:-1]|=air[:,:,1:]
    foreground[:,lo:hi+1,:] |= structural_types[band] & exposed
    foreground &= foundation[:,None,:]
    labels, count = ndi.label(foreground, structure=np.ones((3,3,3), dtype=np.uint8))
    del foreground
    sizes = np.bincount(labels.ravel(), minlength=count+1)
    boxes = ndi.find_objects(labels)
    root_labels = labels[z, np.clip(heights-metadata['minY']+1,0,blocks.shape[1]-1), x]
    shifts = np.zeros(count+1, dtype=np.int8)
    records = []
    supports = np.zeros_like(root_labels)
    parents = np.arange(count+1)
    def representative(identity):
        while parents[identity] != identity:
            parents[identity] = parents[parents[identity]]
            identity = parents[identity]
        return identity
    for identity, box in enumerate(boxes,1):
        if box is None:
            continue
        roots = root_labels == identity
        span = [sl.stop-sl.start for sl in box]
        movable = bool(roots.any() and sizes[identity] <= configuration.get('maximumGroupBlocks',30000)
                       and max(span[0],span[2]) <= configuration.get('maximumGroupWidth',80)
                       and span[1] <= configuration.get('maximumGroupHeight',100)
                       and editable[roots].all() and not liquids[roots].any())
        dy = int(np.rint(np.median(desired[roots]))) if movable else 0
        if movable:
            # A multi-foot object can span several original terrace heights. Do
            # not dig a new pit under its lowest foot to lower the other feet.
            dy = min(0, max(dy, configuration.get('minimumFloorY', configuration['targetY']-1)
                            - int(heights[roots].min())))
        override=configuration.get('groupOffsets',{}).get(str(identity))
        if override is not None:
            if int(override)!=0 and not movable:
                raise ValueError(f'Group {identity} is protected and cannot be moved')
            if int(override)>0 or int(override)<-16:
                raise ValueError('Planner group offsets must be -16..0')
            dy=int(override)
        local = labels[box] == identity
        low = local & (ys[box[1]][None,:,None] <= heights[box[0],box[2]][:,None,:] + 3)
        footprint = low.any(axis=1)
        if sizes[identity] >= 12 and span[1] >= 3:
            # The base of a same-palette sculpture can sit below the inferred
            # terrain. Its low silhouette and a one-block seam move together.
            footprint = ndi.binary_dilation(np.pad(footprint,1), iterations=1)
            fz, fx = np.where(footprint)
            fz += box[0].start-1; fx += box[2].start-1
            valid = (fz>=0)&(fz<blocks.shape[0])&(fx>=0)&(fx<blocks.shape[2])
            fz,fx=fz[valid],fx[valid]
        else:
            fz,fx=np.where(roots)
        if len(fz):
            if not editable[fz,fx].all() or liquids[fz,fx].any():
                dy=0
            dy=min(0,max(dy,configuration.get('minimumFloorY',configuration['targetY']-1)-int(heights[fz,fx].min())))
            for other in np.unique(supports[fz,fx]):
                if other:
                    parents[representative(int(other))]=representative(identity)
            supports[fz,fx]=identity
        shifts[identity] = dy
        indices, amounts = np.unique(blocks[box][local], return_counts=True)
        types = sorted(zip(indices,amounts),key=lambda pair:-pair[1])[:5]
        record = {'id':identity,'blocks':int(sizes[identity]),'anchors':int(roots.sum()),'movable':movable,'dy':dy,
                  'bounds':{'minX':box[2].start+metadata['minX'],'maxX':box[2].stop-1+metadata['minX'],
                            'minY':box[1].start+metadata['minY'],'maxY':box[1].stop-1+metadata['minY'],
                            'minZ':box[0].start+metadata['minZ'],'maxZ':box[0].stop-1+metadata['minZ']},
                  'materials':[(str(names[i]),int(n)) for i,n in types]}
        records.append(record)

    # Overlapping support footprints form one rigid selection. Pick the least
    # aggressive offset among its members instead of splitting their bases.
    cluster_shift={}
    for identity in range(1,count+1):
        root=representative(identity)
        cluster_shift[root]=max(cluster_shift.get(root,-128),int(shifts[identity]))
    for record in records:
        identity=record['id']; shifts[identity]=cluster_shift[representative(identity)]; record['dy']=int(shifts[identity])
        record['rigidSelection']=int(representative(identity))
    anchors=supports!=0
    initial_desired=desired.copy()
    frozen=np.zeros_like(editable)
    collision_freezes=[]
    for attempt in range(16):
        desired=initial_desired.copy()
        desired[anchors]=shifts[supports[anchors]]
        desired[frozen]=0
        target = heights.astype(np.int16) + desired
        # Feather toward fixed feet and original low ground, without raising any surface.
        locked = anchors | ~editable | liquids | frozen
        original_target = target.copy()
        for _ in range(16):
            next_target = np.maximum(target, ndi.maximum_filter(target,size=3)-1)
            next_target = np.minimum(next_target, heights)
            next_target[locked] = original_target[locked]
            if np.array_equal(next_target,target):
                break
            target = next_target
        ground_shift = (target-heights).astype(np.int8)
        ground_shift[~editable | liquids | frozen] = 0
        field = np.where(ys[None,:,None] <= heights[:,None,:], ground_shift[:,None,:], 0).astype(np.int8)
        field[labels != 0] = shifts[labels[labels != 0]]
        source_indices=np.flatnonzero((blocks.ravel()!=0)&(field.ravel()!=0))
        targets=source_indices+field.ravel()[source_indices].astype(np.int64)*blocks.shape[2]
        invalid=(blocks.ravel()[targets]!=0)&(field.ravel()[targets]==0)
        unique,counts=np.unique(targets,return_counts=True)
        invalid |= np.isin(targets,unique[counts>1])
        offenders=source_indices[invalid]
        if not len(offenders):
            break
        # A collision is evidence of ambiguous segmentation, never permission to
        # overwrite a build. Freeze the involved rigid selection and support cell.
        rz,ry,rx=np.unravel_index(offenders,blocks.shape)
        identities=np.unique(labels[rz,ry,rx])
        roots={representative(int(identity)) for identity in identities if identity}
        for identity in range(1,count+1):
            if representative(identity) in roots:
                shifts[identity]=0
        frozen[rz,rx]=True
        collision_freezes.append({'pass':attempt+1,'ambiguousVoxels':int(len(offenders)),
                                  'frozenSelections':[int(root) for root in sorted(roots)]})
    else:
        raise ValueError('Could not resolve terrain collisions conservatively')
    for record in records:
        record['dy']=int(shifts[record['id']])
    moved = (blocks != 0) & (field != 0)
    maximum_shift = int(np.abs(field).max())
    field_bytes=field.tobytes()
    result = {key:metadata[key] for key in ('minX','minY','minZ','width','height','depth','fingerprint')}
    result.update({'schemaVersion':1,'maximumShift':max(1,maximum_shift),'displacements':'displacements.i8.gz',
                   'displacementSha256':hashlib.sha256(field_bytes).hexdigest(),
                   'configuration':configuration, 'rigidGroups':records,'collisionFreezes':collision_freezes})
    serialized=json.dumps(result,indent=2)+'\n'
    with gzip.open(output / 'displacements.i8.gz.pending','wb',compresslevel=6) as stream:
        stream.write(field_bytes)
    (output/'plan.json.pending').write_text(serialized)
    (output/'displacements.i8.gz.pending').replace(output/'displacements.i8.gz')
    (output/'plan.json.pending').replace(output/'plan.json')
    np.savez_compressed(output/'surfaces.npz', height=heights, target=target, editable=editable,
                        groundShift=ground_shift, rootLabels=root_labels, liquid=liquids, family=families)
    report = {'groups':count,'movedGroups':int(np.count_nonzero(shifts)), 'movedBlocks':int(moved.sum()),
              'movedGroundCells':int(np.count_nonzero(ground_shift)), 'maximumLowering':maximum_shift,
              'conservativelyFrozenColumns':int(frozen.sum()),'collisionFreezes':collision_freezes,
              'heightBefore':height_statistics(heights,editable),'heightAfter':height_statistics(target,editable),
              'editedByFamily':{g['name']:int(np.count_nonzero(ground_shift[families==i])) for i,g in enumerate(groups,1)}}
    (output/'report.json').write_text(json.dumps(report,indent=2)+'\n')
    rgb = np.zeros((*heights.shape,3),dtype=np.uint8) + 225
    rgb[~foundation]=(80,140,190)
    rgb[anchors & foundation]=(125,125,125)
    intensity = np.clip(-ground_shift.astype(float)/max(1,maximum_shift),0,1)
    rgb[ground_shift<0]=np.stack([np.full_like(intensity,35),190-intensity*85,250-intensity*20],axis=-1)[ground_shift<0]
    Image.fromarray(rgb).resize((rgb.shape[1]*3,rgb.shape[0]*3),Image.Resampling.NEAREST).save(output/'displacement-map.png')
    print(json.dumps(report,indent=2),flush=True)
    print('Largest groups are recorded in plan.json; rigid selections:',len(cluster_shift),flush=True)
    return result


def height_statistics(height, mask):
    values=height[mask]
    keys,counts=np.unique(values,return_counts=True)
    edge_x=mask[:,1:]&mask[:,:-1]; edge_z=mask[1:]&mask[:-1]
    edges=np.concatenate([np.abs(np.diff(height,axis=1))[edge_x],np.abs(np.diff(height,axis=0))[edge_z]])
    return {'histogram':{int(k):int(n) for k,n in zip(keys,counts)},'stddev':float(values.std()),
            'stepsOverOneBlock':int((edges>1).sum()),'unevenEdges':int((edges>0).sum()),'edges':int(edges.size)}


if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--volume',type=Path,required=True)
    parser.add_argument('--config',type=Path,required=True)
    parser.add_argument('--output',type=Path,required=True)
    parser.add_argument('--group-offset',action='append',default=[],metavar='ID:DY',help='Override a recognized group; 0 protects it')
    args=parser.parse_args()
    config=json.loads(args.config.read_text())
    if args.group_offset:
        config['sourceFingerprint']=json.loads((args.volume/'volume.json').read_text())['fingerprint']
        config.setdefault('groupOffsets',{}).update(dict(value.split(':',1) for value in args.group_offset))
    plan(args.volume,config,args.output)
