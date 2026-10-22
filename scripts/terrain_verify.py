"""Verify a terrain edit's exact semantic blocks, rigid translations and positional records after export."""
from __future__ import annotations

import argparse
from collections import Counter
import gzip
import hashlib
import json
from pathlib import Path

import numpy as np

from terrain_analysis import load_volume


def aligned_blocks(metadata, directory):
    other, values=load_volume(directory)
    assert all(metadata[key]==other[key] for key in ('minX','minY','minZ','width','height','depth')), 'Volume bounds changed'
    def key(entry):
        states=dict(entry['states'])
        # Bedrock serialization makes this implicit vanilla default explicit.
        # Distance, persistence, waterlogging and all other properties stay exact.
        if entry['type'].endswith('_LEAVES'):
            states.setdefault('update','FALSE')
        return json.dumps({'type':entry['type'],'states':states},sort_keys=True)
    lookup={key(entry):i for i,entry in enumerate(metadata['palette'])}
    mapping=np.array([lookup[key(entry)] for entry in other['palette']],dtype=np.uint16)
    return other,mapping[values]


def verify(before_directory,after_directory,plan_path,reopened_directory=None):
    metadata,before=load_volume(before_directory)
    plan=json.loads(plan_path.read_text())
    assert plan['fingerprint']==metadata['fingerprint'], 'Wrong source volume'
    raw=gzip.open(plan_path.parent/plan['displacements'],'rb').read()
    assert hashlib.sha256(raw).hexdigest()==plan['displacementSha256'], 'Wrong displacement field'
    shifts=np.frombuffer(raw,dtype=np.int8).reshape(before.shape)
    assert np.array_equal(np.where(before!=0,shifts,0),np.minimum(np.where(before!=0,shifts,0),0)), 'Unexpected upward move'
    source=np.flatnonzero((before.ravel()!=0)&(shifts.ravel()!=0))
    target=source+shifts.ravel()[source].astype(np.int64)*metadata['width']
    assert len(np.unique(target))==len(target), 'Duplicate target voxels'
    assert not ((before.ravel()[target]!=0)&(shifts.ravel()[target]==0)).any(), 'Overwrite of stationary blocks'
    expected=before.copy();expected.ravel()[source]=0;expected.ravel()[target]=before.ravel()[source]
    after_meta,after=aligned_blocks(metadata,after_directory)
    assert np.array_equal(expected,after), 'Edited world does not exactly match the plan'
    assert np.array_equal(np.bincount(before.ravel()),np.bincount(after.ravel())), 'Block state counts changed'
    liquid=np.array([entry['type'] in ('WATER','LAVA') for entry in metadata['palette']])
    assert np.array_equal(np.where(liquid[before],before,0),np.where(liquid[after],after,0)), 'Liquid positions changed'
    expected_records=[]
    for record in metadata['blockEntities']:
        shifted=dict(record)
        z,y,x=record['z']-metadata['minZ'],record['y']-metadata['minY'],record['x']-metadata['minX']
        shifted['y']+=int(shifts[z,y,x])
        expected_records.append(shifted)
    def records(values):
        return Counter((entry['type'],entry['x'],entry['y'],entry['z']) for entry in values)
    assert records(expected_records)==records(after_meta['blockEntities']), 'Block-entity position mismatch'
    if reopened_directory:
        reopened_meta,reopened=aligned_blocks(metadata,reopened_directory)
        assert np.array_equal(expected,reopened), 'Serialized world does not match the edited document'
        assert records(expected_records)==records(reopened_meta['blockEntities']), 'Serialized block-entity position mismatch'
    return {'verified':True,'sourceFingerprint':metadata['fingerprint'],'blockStates':len(metadata['palette'])-1,
            'nonAirBlocks':int(np.count_nonzero(before)),'translatedBlocks':int(len(source)),
            'blockEntityRecords':len(expected_records),'maximumLowering':int(-shifts.min()),
            'exactBlockStateCountsPreserved':True,'exactPlannedPositions':True,'liquidPositionsUnchanged':True,
            'reopenedExportVerified':bool(reopened_directory),'normalizedVanillaDefaults':['leaves.update=FALSE']}


if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--before',type=Path,required=True)
    parser.add_argument('--after',type=Path,required=True)
    parser.add_argument('--plan',type=Path,required=True)
    parser.add_argument('--reopened',type=Path)
    parser.add_argument('--output',type=Path,required=True)
    args=parser.parse_args()
    result=verify(args.before,args.after,args.plan,args.reopened)
    args.output.write_text(json.dumps(result,indent=2)+'\n')
    print(json.dumps(result,indent=2))
