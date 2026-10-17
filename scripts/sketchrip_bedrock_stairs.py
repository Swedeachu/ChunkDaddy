#!/usr/bin/env python3
"""Constrained inverse of the pre-1.26.50 Bedrock stair neighbor algorithm.

This module deliberately does not guess individual corners. It solves for a set of
horizontal facings whose *derived* Bedrock stair shapes agree with the horizontal
face footprints measured from the source mesh. It uses the same topology as
Tungsten's Detail/StairShape.h and Chunker's pre-1.26.50 state mapping.

When no consistent arrangement is found inside a bounded search, the complete
component is left untouched. Every accepted edit belongs to a fully validated
solution of the component's observed geometry, not a pixel-similarity tie-break.
"""

from __future__ import annotations

import collections
import itertools
import re
from pathlib import Path

from sketchrip_reference import VanillaAssets
from sketchrip_stair_roof import roof_fingerprint


# Weirdo-direction values: 0=East, 1=West, 2=South, 3=North.
# Matches Tungsten's StairFacingOffsets and Chunker's BedrockStateTypes.
DIRECTIONS = ('east', 'west', 'south', 'north')
OFFSETS = ((1, 0, 0), (-1, 0, 0), (0, 0, 1), (0, 0, -1))
CLOCKWISE = (2, 3, 1, 0)
SHAPES = ('straight', 'outer_left', 'outer_right', 'inner_left', 'inner_right')
PROPERTY = re.compile(r'([a-z_]+)=([^,\]]+)')


def properties(state: str) -> dict[str, str]:
    if '[' not in state:
        return {}
    return dict(PROPERTY.findall(state.split('[', 1)[1]))


def change_state(state: str, facing: int, shape: str) -> str:
    name = state.split('[', 1)[0]
    props = properties(state)
    props['facing'] = DIRECTIONS[facing]
    props['shape'] = shape
    return name + '[' + ','.join(f'{key}={props[key]}' for key in sorted(props)) + ']'


def add(point: tuple, offset: tuple) -> tuple:
    return tuple(point[i] + offset[i] for i in range(3))


def predict_shape(point: tuple, facing: int, assignments: dict, half: dict) -> str:
    """Tungsten StairShape.h precedence, byte-for-byte equivalent in behavior."""
    clockwise = CLOCKWISE[facing]

    def neighbor(direction: int):
        position = add(point, OFFSETS[direction])
        other = assignments.get(position)
        return other if other is not None and half.get(position) == half[point] else None

    right = neighbor(clockwise)
    continued_on_right = right == facing

    def perpendicular(other):
        return other is not None and other // 2 != facing // 2

    front = neighbor(facing)
    if perpendicular(front):
        if front == clockwise:
            return 'outer_right'
        return 'straight' if continued_on_right else 'outer_left'

    back = neighbor(facing ^ 1)
    if perpendicular(back):
        if back != clockwise:
            return 'inner_left'
        if not continued_on_right:
            return 'inner_right'
    return 'straight'


def reference_masks(pack: Path):
    """Derive exact occupied quarter masks from vanilla models, not hardcoded rotations."""
    import numpy as np
    assets = VanillaAssets(pack)
    masks = {}
    for state, _ in assets.enumerate_states('oak_stairs'):
        prop = properties(state)
        if prop.get('shape') not in SHAPES:
            continue
        plane = 1.0 if prop['half'] == 'bottom' else 0.0
        triangles = np.asarray([tri for tri, _, _ in assets.faces(state)])
        mask, area = roof_fingerprint(triangles, plane)
        if mask and abs(area - mask.bit_count() * .25) < .055:
            masks[(prop['half'], DIRECTIONS.index(prop['facing']), prop['shape'])] = mask
    if len(masks) != 40:
        raise ValueError(f'Expected 40 reference stair shapes, received {len(masks)}')
    return masks


def supported(observation: tuple, scope: tuple, domains: dict, half: dict,
              masks: dict, support: dict):
    """Generalized arc consistency over the at-most-five-stair neighborhood."""
    point, mask = observation
    other = tuple(item for item in scope if item != point)
    legal = [set() for _ in scope]
    # Favor tight domains but consider *every* allowed facing; no greedy selection.
    for values in itertools.product(*(domains[p] for p in scope)):
        assigned = dict(zip(scope, values))
        facing = assigned[point]
        shape = predict_shape(point, facing, assigned, half)
        if masks[(half[point], facing, shape)] != mask:
            continue
        for index, value in enumerate(values):
            legal[index].add(value)
    return {position: legal[index] for index, position in enumerate(scope)}


def propagate(domains: dict, observations: dict, scopes: dict, half: dict, masks: dict):
    """Enforce local shape constraints to a fixed point; False means unsatisfiable."""
    changed = True
    while changed:
        changed = False
        for point, mask in observations.items():
            scope = scopes[point]
            allowed = supported((point, mask), scope, domains, half, masks, {})
            for other in scope:
                new_domain = domains[other] & allowed[other]
                if not new_domain:
                    return False
                if new_domain != domains[other]:
                    domains[other] = new_domain
                    changed = True
    return True


def solve_component(points: list, blocks: dict, observations: dict, masks: dict,
                    limit: int = 20000):
    half = {p: properties(blocks[p]).get('half', 'bottom') for p in points}
    current = {p: DIRECTIONS.index(properties(blocks[p]).get('facing', 'north')) for p in points}
    domains = {p: set(range(4)) for p in points}
    scopes = {}
    for p in observations:
        scopes[p] = tuple([p] + [add(p, offset) for offset in OFFSETS
                                if add(p, offset) in half and half[add(p, offset)] == half[p]])
    # Every observed shape is physically constrained. Pre-restrict the central
    # facing to those capable of producing its quadrant mask in any topology.
    for p, mask in observations.items():
        domains[p] = {face for face in range(4)
                      if any(masks[(half[p], face, shape)] == mask for shape in SHAPES)}
        if not domains[p]:
            return None, 'unrepresentable_geometry', 0
    if not propagate(domains, observations, scopes, half, masks):
        return None, 'inconsistent_observations', 0

    count = 0
    found = None
    exhausted = False

    def search(candidate):
        nonlocal count, found, exhausted
        if found is not None or exhausted:
            return
        count += 1
        if count > limit:
            exhausted = True
            return
        free = [p for p in points if len(candidate[p]) > 1]
        if not free:
            assignment = {p: next(iter(candidate[p])) for p in points}
            # Final global forward validation: every source-mesh roof mask
            # must agree with the Bedrock topology after converting facings.
            if all(masks[(half[p], assignment[p], predict_shape(p, assignment[p], assignment, half))] == mask
                   for p, mask in observations.items()):
                found = assignment
            return
        p = min(free, key=lambda v: (len(candidate[v]), 0 if v in observations else 1, v))
        for value in sorted(candidate[p], key=lambda v: (v != current[p], v)):
            child = {key: set(values) for key, values in candidate.items()}
            child[p] = {value}
            if propagate(child, observations, scopes, half, masks):
                search(child)
            if found is not None or exhausted:
                break

    search(domains)
    if found is None:
        return None, 'search_limit' if exhausted else 'inconsistent_observations', count
    return found, 'solved', count


def solve(blocks: dict, roof_report: dict, pack: Path, max_component: int = 36,
          search_limit: int = 20000):
    """Return exact Bedrock-compatible stair states for observed geometry.

    Unknown stair roofs impose no target constraints. When the exact solver
    cannot prove a consistent solution for an entire connected component, it
    refuses to change it. No arbitrary string-sorted corner selection.
    """
    staircase = {p: s for p, s in blocks.items() if s.split('[', 1)[0].endswith('_stairs')
                 and 'facing=' in s and 'half=' in s}
    observations = {}
    for row in roof_report.get('observations', []):
        point = tuple(row['voxel'])
        mask = int(row['mask'])
        if point in staircase and mask in range(1, 15):
            observations[point] = mask
    masks = reference_masks(pack)
    halves = {p: properties(s)['half'] for p, s in staircase.items()}
    unseen = set(staircase)
    changes = {}
    counts = collections.Counter()
    samples = []
    failures = []
    for start in sorted(staircase):
        if start not in unseen:
            continue
        queue = [start]
        unseen.remove(start)
        component = []
        while queue:
            p = queue.pop()
            component.append(p)
            for offset in OFFSETS:
                neighbor = add(p, offset)
                if neighbor in unseen and halves[neighbor] == halves[p]:
                    unseen.remove(neighbor)
                    queue.append(neighbor)
        constraints = {p: observations[p] for p in component if p in observations}
        counts['components'] += 1
        if not constraints:
            counts['no_visible_constraints'] += 1
            continue
        if len(component) > max_component:
            counts['oversized_components'] += 1
            counts['oversized_stairs'] += len(component)
            continue
        assignment, status, attempts = solve_component(component, staircase, constraints, masks, search_limit)
        counts[status] += 1
        counts['search_nodes'] += attempts
        if assignment is None:
            counts['unresolved_footprints'] += len(constraints)
            if len(failures) < 50:
                failures.append({'reason': status, 'component_size': len(component),
                                 'observations': len(constraints),
                                 'voxels': [list(point) for point in component[:20]],
                                 'observed_masks': {str(point): observations[point] for point in component if point in observations}})
            continue
        current_half = {p: halves[p] for p in component}
        for p in component:
            shape = predict_shape(p, assignment[p], assignment, current_half)
            new = change_state(staircase[p], assignment[p], shape)
            if new != staircase[p]:
                # A different property order is not an actual stair change.
                old = properties(staircase[p])
                if (old.get('facing'), old.get('shape')) == (DIRECTIONS[assignment[p]], shape):
                    continue
                changes[p] = new
                counts['updated_stairs'] += 1
                if len(samples) < 60:
                    samples.append({'voxel': list(p), 'old': staircase[p], 'new': new,
                                    'mesh_roof': constraints.get(p), 'bedrock_derived_shape': shape})
        counts['verified_roof_constraints'] += len(constraints)
    return changes, {
        'source_version_constraint': 'Bedrock 1.26.0/1.26.40 do not serialize minecraft:corner',
        'policy': 'Exact connected-stair constraint solve against pre-1.26.50 Bedrock/Tungsten corner topology',
        'observed_stairs': len(observations), 'examined_stairs': len(staircase),
        'proposed_changes': len(changes), 'counts': dict(counts), 'samples': samples, 'failures': failures,
        'limits': {'component_stairs': max_component, 'search_nodes': search_limit},
        'notes': 'Skipped oversized or unsatisfiable groups are left intact. Only observed, validated geometry constrains facings.'
    }
