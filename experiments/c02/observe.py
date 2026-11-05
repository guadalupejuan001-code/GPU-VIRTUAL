#!/usr/bin/env python3
"""Ejecutar, capturar, contrastar y filtrar estados observados de shaders."""
import argparse
from collections import Counter
import hashlib
import json
from pathlib import Path
import random
import struct
import time
import zipfile

from filters import MASK, decode_state, encode_column, encode_state, words
from gl_capture import Capture, float_shader, integer_shader, shared_shader

ROOT = Path(__file__).resolve().parents[2]
SENTINEL = 0x5A5A5A5A
INT_NAMES = ['id', 'route', 'a', 'b', 'c', 'd', 'address', 'published_output']
SHARED_NAMES = ['id', 'a', 'neighbor', 'group_sum', 'value', 'published_output']
FLOAT_NAMES = ['id', 'a_bits', 'b_bits', 'c_bits', 'left_bits', 'right_bits', 'same_bits', 'published_output']


def to_words(blob):
    return list(struct.unpack('<' + 'I' * (len(blob) // 4), blob))


def matrices(blob, n, phases, columns):
    values = to_words(blob)
    if len(values) != n * phases * columns:
        raise AssertionError('Tamano de captura inesperado')
    return [[values[(p*n+i)*columns:(p*n+i+1)*columns] for i in range(n)] for p in range(phases)]


def integer_reference(source):
    n = len(source)
    stages = [[] for _ in range(6)]
    output = []
    for i, a in enumerate(source):
        stages[0].append([i, 0, a, 0, 0, 0, i, SENTINEL])
        b = (3*a + 5) & MASK
        stages[1].append([i, 0, a, b, 0, 0, i, SENTINEL])
        route = a & 1
        c = (b + 11 if route else b - 7) & MASK
        stages[2].append([i, route, a, b, c, 0, i, SENTINEL])
        address = (5*i + 3) % n
        d = source[address]
        stages[3].append([i, route, a, b, c, d, address, SENTINEL])
        c = ((c ^ d) + (i & 7)*17) & MASK
        stages[4].append([i, route, a, b, c, d, address, SENTINEL])
        output.append(c)
        stages[5].append([i, route, a, b, c, d, address, c])
    return stages, output


def shared_reference(source, local_size):
    n = len(source)
    stages = [[] for _ in range(3)]
    output = []
    for i, a in enumerate(source):
        base = (i // local_size) * local_size
        other = base + ((i % local_size + 1) % local_size)
        neighbor = source[other] if other < n else 0
        total = sum(source[base:base + local_size]) & MASK
        value = (neighbor + total) & MASK
        stages[0].append([i, a, 0, 0, 0, SENTINEL])
        stages[1].append([i, a, neighbor, 0, 0, SENTINEL])
        stages[2].append([i, a, neighbor, total, value, value])
        output.append(value)
    return stages, output


def f32(value):
    return struct.unpack('<f', struct.pack('<f', value))[0]


def float_bits(value):
    return struct.unpack('<I', struct.pack('<f', value))[0]


def float_reference(source):
    stages = [[], []]
    output = []
    for i in range(len(source) // 3):
        ab, bb, cb = source[3*i:3*i+3]
        a, b, c = struct.unpack('<fff', words([ab, bb, cb]))
        p = float_bits(f32(f32(a + b) + c))
        q = float_bits(f32(a + f32(b + c)))
        stages[0].append([i, ab, bb, cb, 0, 0, 1, SENTINEL])
        stages[1].append([i, ab, bb, cb, p, q, int(p == q), p])
        output.append(p)
    return stages, output


def make_cases(n, local_size):
    rng = random.Random(20261104)
    ascending = list(range(n))
    shuffled = ascending.copy()
    rng.shuffle(shuffled)
    irregular = [rng.getrandbits(32) for _ in range(n)]
    sparse = [7] * n
    sparse[n // 3] = MASK
    sparse[-1] = 8
    tile = [0, 1, 0, MASK, 4, 2, 4]
    data = [
        ('uniform', [7] * n), ('ascending', ascending),
        ('wrapped_progression', [(MASK - 15 + 3*i) & MASK for i in range(n)]),
        ('checkerboard', [17 * (((i % 32) // 4 + (i // 32) // 4) & 1) for i in range(n)]),
        ('sparse_changes', sparse), ('shuffled_ascending', shuffled),
        ('seeded_words', irregular), ('repeated_tile', [tile[i % len(tile)] for i in range(n)]),
    ]
    cases = []
    for name, source in data:
        cases.append({'name': name, 'kind': 'integer', 'source': source, 'n': n,
                      'shader': integer_shader(local_size), 'names': INT_NAMES,
                      'reference': integer_reference(source)})
    within_groups = irregular.copy()
    for begin in range(0, n, local_size):
        section = within_groups[begin:begin+local_size]
        rng.shuffle(section)
        within_groups[begin:begin+local_size] = section
    for name, source in [('shared_full_groups', irregular),
                         ('shared_permuted_within_groups', within_groups),
                         ('shared_partial_group', irregular[:max(1, n-7)])]:
        cases.append({'name': name, 'kind': 'shared', 'source': source, 'n': len(source),
                      'shader': shared_shader(local_size), 'names': SHARED_NAMES,
                      'reference': shared_reference(source, local_size)})
    triples = [(1e20, -1e20, 3.25), (16777216.0, 1.0, -16777216.0),
               (1.0, 2.0, 3.0), (1e-20, -1e-20, 1e-30)]
    source = [float_bits(x) for i in range(n) for x in triples[i % len(triples)]]
    cases.append({'name': 'float_order', 'kind': 'float', 'source': source, 'n': n,
                  'shader': float_shader(local_size), 'names': FLOAT_NAMES,
                  'reference': float_reference(source)})
    return cases


def validate_values(actual, expected, label):
    if len(actual) != len(expected):
        raise AssertionError('Longitud diferente: ' + label)
    for i, (left, right) in enumerate(zip(actual, expected)):
        if left != right:
            raise AssertionError(f'{label}, elemento {i}: {left} != {right}')
    return len(actual)


def archive_replay(path):
    checks = 0
    with zipfile.ZipFile(path) as archive:
        inventory = json.loads(archive.read('inventory.json'))
        for item in inventory['cases']:
            prefix = item['name'] + '/'
            source = to_words(archive.read(prefix + 'input.u32'))
            n = item['n']
            if item['kind'] == 'integer':
                expected, out = integer_reference(source)
            elif item['kind'] == 'shared':
                expected, out = shared_reference(source, inventory['local_size'])
            else:
                expected, out = float_reference(source)
            checks += validate_values(to_words(archive.read(prefix + 'output.u32')), out, item['name'])
            raw = archive.read(prefix + 'trace.u32')
            actual = matrices(raw, n, len(expected), len(item['names']))
            for p, rows in enumerate(actual):
                checks += validate_values([v for row in rows for v in row],
                                          [v for row in expected[p] for v in row], item['name'])
                point, names, decoded = decode_state(archive.read(prefix + f'point-{p}.tfs'))
                if point != p or names != item['names'] or decoded != rows:
                    raise AssertionError('El estado filtrado no reconstruye la captura')
            shader = archive.read(prefix + 'program.glsl')
            if hashlib.sha256(shader).hexdigest() != item['shader_sha256']:
                raise AssertionError('Programa capturado diferente')
    return {'checked_values': checks, 'cases': len(inventory['cases']), 'all_filtered_captures_exact': True}


def run(args):
    destination = Path(args.output)
    destination.mkdir(parents=True, exist_ok=True)
    capture = Capture(software=args.software)
    archive_path = destination / 'captures.zip'
    results, seen = [], {}
    checks = 0
    began = time.perf_counter()
    inventory = {'format': 'C02-source-checkpoints-v1', 'local_size': args.local_size, 'cases': []}
    try:
        if args.require_hardware and capture.identity['backend_kind'] == 'software':
            raise RuntimeError('Se solicito hardware y el driver observado es software')
        identity, limits = capture.identity, capture.limits
        with zipfile.ZipFile(archive_path, 'w', zipfile.ZIP_DEFLATED, compresslevel=9) as archive:
            for item in make_cases(args.count, args.local_size):
                name, source, n = item['name'], item['source'], item['n']
                expected, expected_output = item['reference']
                phases, columns = len(expected), len(item['names'])
                trace, output, driver_ms = capture.execute(item['shader'], source, n, phases, columns, args.local_size)
                observed = matrices(trace, n, phases, columns)
                observed_output = to_words(output)
                checks += validate_values(observed_output, expected_output, name + '/output')
                point_results = []
                prefix = name + '/'
                archive.writestr(prefix + 'input.u32', words(source))
                archive.writestr(prefix + 'trace.u32', trace)
                archive.writestr(prefix + 'output.u32', output)
                archive.writestr(prefix + 'program.glsl', item['shader'])
                for p, rows in enumerate(observed):
                    checks += validate_values([v for row in rows for v in row],
                                              [v for row in expected[p] for v in row], f'{name}/point-{p}')
                    encoded, fields = encode_state(item['names'], rows, point=p)
                    archive.writestr(prefix + f'point-{p}.tfs', encoded)
                    point_results.append({'point': p, 'raw_values_bytes': n * columns * 4,
                                          'filtered_state_bytes': len(encoded), 'fields': fields,
                                          'exact_reconstruction': True})
                result = {'name': name, 'kind': item['kind'], 'n': n,
                          'source_checkpoints': phases, 'captured_words': n * columns * phases,
                          'raw_values_bytes': len(trace),
                          'filtered_state_bytes': sum(p['filtered_state_bytes'] for p in point_results),
                          'driver_dispatch_and_finish_ms': driver_ms,
                          'dispatch_time_is_not_a_performance_comparison': True,
                          'trace_sha256': hashlib.sha256(trace).hexdigest(),
                          'output_sha256': hashlib.sha256(output).hexdigest(), 'points': point_results}
                results.append(result)
                seen[name] = {'states': observed, 'output': observed_output, 'input': source}
                inventory['cases'].append({'name': name, 'kind': item['kind'], 'n': n, 'names': item['names'],
                                           'shader_sha256': hashlib.sha256(item['shader'].encode()).hexdigest()})
            archive.writestr('inventory.json', json.dumps(inventory, indent=2))
    finally:
        capture.close()
    first = seen['ascending']
    other = seen['shuffled_ascending']
    same_histogram = Counter(first['input']) == Counter(other['input'])
    different_outputs = sum(a != b for a, b in zip(first['output'], other['output']))
    if not same_histogram or different_outputs == 0:
        raise AssertionError('El contraejemplo de orden no fue observado')
    distinct_future = len(set(first['output']))
    if not all(row[4] == 0 and row[7] == SENTINEL for row in first['states'][0]) or distinct_future <= 1:
        raise AssertionError('El contraejemplo de estado oculto no fue observado')
    float_rows = seen['float_order']['states'][1]
    non_associative = sum(row[4] != row[5] for row in float_rows)
    if not non_associative:
        raise AssertionError('No se observo diferencia por orden float32')
    replay = archive_replay(archive_path)
    shared_before = seen['shared_full_groups']
    shared_after = seen['shared_permuted_within_groups']
    same_sums = all(a[3] == b[3] for a, b in zip(shared_before['states'][2], shared_after['states'][2]))
    changed_neighbors = sum(a != b for a, b in zip(shared_before['output'], shared_after['output']))
    if not same_sums or changed_neighbors == 0:
        raise AssertionError('El contraejemplo de agregado compartido no fue observado')
    findings = {
        'same_histogram_different_order': {'same_histogram': same_histogram, 'different_final_outputs': different_outputs,
                                         'n': args.count, 'first_input_prefix': first['input'][:8],
                                         'second_input_prefix': other['input'][:8]},
        'same_present_output_different_future': {'same_initial_c': 0, 'same_initial_output': SENTINEL,
                                                'distinct_future_outputs': distinct_future,
                                                'example_outputs': first['output'][:8]},
        'float32_order_changes_bits': {'different_results': non_associative, 'n': len(float_rows),
                                      'example': {'left_bits': float_rows[0][4], 'right_bits': float_rows[0][5]}},
        'same_group_sum_different_neighbor_result': {'same_group_sums': same_sums,
                                                    'different_final_outputs': changed_neighbors, 'n': args.count},
        'output_store_changes_memory_without_changing_other_captured_variables': {
            'same_first_seven_fields': first['states'][4][0][:7] == first['states'][5][0][:7],
            'output_before': first['states'][4][0][7], 'output_after': first['states'][5][0][7]},
    }
    result = {
        'experiment': 'C02-observed-shader-states', 'backend': identity, 'driver_limits': limits,
        'count': args.count, 'local_size': args.local_size, 'cases': results,
        'checked_values': checks, 'archive_replay': replay, 'findings': findings,
        'captured_words': sum(r['captured_words'] for r in results),
        'all_reference_values_equal': True, 'all_filtered_captures_exact': True,
        'scope': {'source_variables_and_selected_memory': True, 'physical_register_trace': False,
                  'physical_program_counter_or_warp_mask': False, 'simultaneous_device_snapshot': False,
                  'complete_gpu_state': False, 'live_execution_on_filtered_state': False,
                  'gpu_minimum_proven': False, 'c01_core_modified': False},
        'archive_sha256': hashlib.sha256(archive_path.read_bytes()).hexdigest(),
        'archive_bytes': archive_path.stat().st_size, 'elapsed_seconds': time.perf_counter() - began,
    }
    (destination / 'report.json').write_text(json.dumps(result, indent=2) + '\n')
    summary = {'backend': identity, 'cases': len(results), 'checked_values': checks,
               'captured_words': result['captured_words'], 'findings': findings,
               'sizes': [{'case': r['name'], 'raw_values_bytes': r['raw_values_bytes'],
                          'filtered_state_bytes': r['filtered_state_bytes']} for r in results],
               'archive_replay': replay, 'report': str(destination / 'report.json')}
    print(json.dumps(summary, indent=2))


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--software', action='store_true', help='Seleccionar explicitamente el driver CPU')
    parser.add_argument('--require-hardware', action='store_true', help='Rechazar un renderer software conocido')
    parser.add_argument('--count', type=int, default=1024)
    parser.add_argument('--local-size', type=int, default=64)
    parser.add_argument('--output', default=str(ROOT / 'evidence/current/c02'))
    parser.add_argument('--replay', type=Path)
    args = parser.parse_args()
    if args.replay:
        print(json.dumps(archive_replay(args.replay), indent=2))
    else:
        if args.count < 8 or args.count > MASK:
            parser.error('El ensayo requiere al menos ocho identidades y un dominio uint32')
        if args.local_size < 1:
            parser.error('El grupo debe ser positivo')
        run(args)
