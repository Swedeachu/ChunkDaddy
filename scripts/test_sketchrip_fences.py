"""Known-answer narrow fence models must not be silently classified as planks."""
from pathlib import Path
import sys
import numpy as np
import trimesh
sys.path.insert(0,str(Path(__file__).parent))
from sketchrip_reference import VanillaAssets
from sketchrip_fence_precision import infer

PACK=Path(__file__).resolve().parent.parent/'assets/sketchrip/vanilla-26.2.zip'


def test_fence_post_geometry_promotes_planks_but_not_cubes():
    a=VanillaAssets(PACK)
    values=['minecraft:oak_planks','minecraft:oak_fence[east=false,north=false,south=false,west=false]',
            'minecraft:oak_fence[east=true,north=false,south=false,west=false]']
    positions=[(i*4,0,0) for i in range(len(values))]
    verts,inds=[],[]
    for position,state in zip(positions,values):
        for t,_,_ in a.faces(state):
            j=len(verts)
            verts.extend(np.asarray(t)+position)
            inds.append((j,j+1,j+2))
    m=trimesh.Trimesh(vertices=np.asarray(verts),faces=inds,process=False)
    original={point:'minecraft:oak_planks' for point in positions}
    changes,report=infer([m],original,PACK,1.,'y')
    assert positions[0] not in changes
    assert len(changes)>=1,report
    assert all(val.split('[')[0]=='minecraft:oak_fence' for val in changes.values())
