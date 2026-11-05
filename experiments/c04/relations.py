"""Reglas exactas entre columnas y puntos; implementacion propia, uint32 modular.

RFS1 conserva el esquema, las posiciones de cada columna y un DAG autosuficiente.
La busqueda es voraz entre candidatos concretos, no una prueba de minimalidad.
"""
from collections import Counter
from pathlib import Path
import struct
import sys

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'c02'))
from filters import MASK, Reader, encode_column, words

LITERAL, AFFINE, DELTA, XOR_DELTA, GATHER, ADD, XOR = range(7)
OP_NAMES = ['literal', 'affine_relation', 'delta_relation', 'xor_relation',
            'indexed_relation', 'sum_relation', 'binary_xor_relation']


class Column:
    """Valida el formato C02 y consulta un elemento sin expandir la columna."""
    def __init__(self, blob, n):
        self.blob = blob
        r = Reader(blob)
        self.tag, self.n = r.unpack('<BI')
        if self.n != n or not n:
            raise ValueError('Dominio de columna incorrecto')
        tag = self.tag
        self.extra = ()
        if tag == 0:
            r.take(4*n)
        elif tag == 1:
            self.extra = r.unpack('<I')
        elif tag == 2:
            self.extra = r.unpack('<II')
        elif tag in (3, 4):
            self.extra = r.unpack('<I' if tag == 3 else '<II')
            p = self.extra[-1]
            if not 1 <= p <= n - (tag == 4):
                raise ValueError('Periodo incorrecto')
            r.take(4*p)
        elif tag == 5:
            self.extra = r.unpack('<III')
            if self.extra[2] > n:
                raise ValueError('Demasiados detalles')
            last = -1
            for _ in range(self.extra[2]):
                at, _ = r.unpack('<II')
                if not last < at < n:
                    raise ValueError('Detalle fuera de orden o dominio')
                last = at
        else:
            raise ValueError('Filtro desconocido')
        r.end()

    def at(self, i):
        if not 0 <= i < self.n:
            raise IndexError('Identidad fuera del dominio')
        tag, data = self.tag, self.blob
        u32 = lambda at: struct.unpack_from('<I', data, at)[0]
        if tag == 0:
            return u32(5 + 4*i)
        if tag == 1:
            return self.extra[0]
        if tag == 2:
            base, step = self.extra
            return (base + i*step) & MASK
        if tag == 3:
            return u32(9 + 4*(i % self.extra[0]))
        if tag == 4:
            base, p = self.extra
            cycles, tail = divmod(i, p)
            total = sum(u32(13 + 4*j) for j in range(p)) if cycles else 0
            return (base + cycles*total + sum(u32(13 + 4*j) for j in range(tail))) & MASK
        base, step, count = self.extra
        low, high = 0, count
        while low < high:
            middle = (low + high)//2
            if u32(17 + 8*middle) < i:
                low = middle + 1
            else:
                high = middle
        if low < count and u32(17 + 8*low) == i:
            return u32(21 + 8*low)
        return (base + i*step) & MASK


def pack_node(node):
    tag, *args = node
    if tag == LITERAL:
        return struct.pack('<BI', tag, len(args[0])) + args[0]
    if tag == AFFINE:
        return struct.pack('<BIII', tag, *args)
    if tag in (DELTA, XOR_DELTA):
        ref, blob = args
        return struct.pack('<BII', tag, ref, len(blob)) + blob
    return struct.pack('<BII', tag, *args)


class Graph:
    def __init__(self, n, points, names, slots, nodes):
        if n < 1 or points < 1 or not names or any(not name for name in names) or len(set(names)) != len(names):
            raise ValueError('Esquema incorrecto')
        if len(slots) != points*len(names) or any(not 0 <= x < len(nodes) for x in slots):
            raise ValueError('Mapa de columnas incorrecto')
        self.n, self.points, self.names = n, points, list(names)
        self.slots, self.nodes = list(slots), []
        self.columns = {}
        for node in nodes:
            self._append(node)

    def _append(self, node):
        tag, *args = node
        if tag not in range(7):
            raise ValueError('Operacion desconocida')
        refs = [] if tag == LITERAL else args[:1] if tag in (AFFINE, DELTA, XOR_DELTA) else args
        if any(not isinstance(ref, int) or not 0 <= ref < len(self.nodes) for ref in refs):
            raise ValueError('Referencia adelantada o inexistente')
        if tag == LITERAL:
            self.columns[len(self.nodes)] = Column(args[0], self.n)
        elif tag in (DELTA, XOR_DELTA):
            self.columns[len(self.nodes)] = Column(args[1], self.n)
        self.nodes.append(node)
        return len(self.nodes)-1

    def field(self, point, name):
        if not 0 <= point < self.points:
            raise IndexError('Punto fuera del esquema')
        return self.slots[point*len(self.names) + self.names.index(name)]

    def affine(self, ref, a, b):
        if not 0 <= ref < len(self.nodes):
            raise ValueError('Referencia inexistente')
        a, b = a & MASK, b & MASK
        node = self.nodes[ref]
        if node[0] == AFFINE:
            _, ref, previous_a, previous_b = node
            a, b = a*previous_a & MASK, (a*previous_b + b) & MASK
        return self._append((AFFINE, ref, a, b))

    def binary(self, left, right, op):
        if op not in ('add', 'xor'):
            raise ValueError('Operacion binaria no admitida')
        return self._append((ADD if op == 'add' else XOR, left, right))

    def gather(self, values, indices):
        return self._append((GATHER, values, indices))

    def at(self, node, i, stats=None):
        """Evalua solo las identidades necesarias, incluso en una consulta indexada."""
        if not 0 <= node < len(self.nodes) or not 0 <= i < self.n:
            raise IndexError('Consulta fuera del grafo')
        memo, pending = {}, [(node, i)]
        while pending:
            ref, at = key = pending[-1]
            if key in memo:
                pending.pop()
                continue
            tag, *args = self.nodes[ref]
            if tag == LITERAL:
                result = self.columns[ref].at(at)
                dependencies = []
            elif tag == GATHER:
                index_key = (args[1], at)
                if index_key not in memo:
                    pending.append(index_key)
                    continue
                other = memo[index_key]
                if not 0 <= other < self.n:
                    raise ValueError('Direccion fuera del dominio')
                dependencies = [(args[0], other)]
            else:
                dependencies = [(args[0], at)]
                if tag in (ADD, XOR):
                    dependencies.append((args[1], at))
            missing = [dep for dep in dependencies if dep not in memo]
            if missing:
                pending.extend(missing)
                continue
            if tag != LITERAL:
                value = memo[dependencies[0]]
                if tag == AFFINE:
                    result = (value*args[1] + args[2]) & MASK
                elif tag in (DELTA, XOR_DELTA):
                    detail = self.columns[ref].at(at)
                    result = (value + detail) & MASK if tag == DELTA else value ^ detail
                elif tag == GATHER:
                    result = value
                elif tag == ADD:
                    result = (value + memo[dependencies[1]]) & MASK
                else:
                    result = value ^ memo[dependencies[1]]
            memo[key] = result
            pending.pop()
        if stats is not None:
            stats['queries'] = stats.get('queries', 0) + 1
            stats['node_identity_evaluations'] = stats.get('node_identity_evaluations', 0) + len(memo)
            stats['full_columns_materialized'] = 0
        return memo[(node, i)]

    def materialize(self):
        """Expansion explicita para verificar/exportar; no es la ruta de consulta."""
        columns = []
        for ref, node in enumerate(self.nodes):
            tag, *args = node
            if tag == LITERAL:
                col = [self.columns[ref].at(i) for i in range(self.n)]
            elif tag == AFFINE:
                col = [(value*args[1] + args[2]) & MASK for value in columns[args[0]]]
            elif tag in (DELTA, XOR_DELTA):
                base = columns[args[0]]
                residual = [self.columns[ref].at(i) for i in range(self.n)]
                col = [(v+d) & MASK for v, d in zip(base, residual)] if tag == DELTA else [v^d for v, d in zip(base, residual)]
            elif tag == GATHER:
                if any(i >= self.n for i in columns[args[1]]):
                    raise ValueError('Direccion fuera del dominio')
                col = [columns[args[0]][i] for i in columns[args[1]]]
            elif tag == ADD:
                col = [(a+b) & MASK for a, b in zip(columns[args[0]], columns[args[1]])]
            else:
                col = [a^b for a, b in zip(columns[args[0]], columns[args[1]])]
            columns.append(col)
        return columns

    def states(self):
        columns = self.materialize()
        width = len(self.names)
        return [[list(row) for row in zip(*(columns[self.slots[p*width+c]] for c in range(width)))]
                for p in range(self.points)]

    def to_bytes(self):
        out = bytearray(struct.pack('<4sIIH', b'RFS1', self.n, self.points, len(self.names)))
        for name in self.names:
            label = name.encode()
            if not 0 < len(label) <= 255:
                raise ValueError('Nombre demasiado largo o vacio')
            out.extend(struct.pack('<B', len(label)) + label)
        out.extend(words(self.slots))
        out.extend(struct.pack('<I', len(self.nodes)))
        for node in self.nodes:
            out.extend(pack_node(node))
        return bytes(out)

    @classmethod
    def from_bytes(cls, data):
        r = Reader(data)
        magic, n, points, width = r.unpack('<4sIIH')
        if magic != b'RFS1' or not n or not points or not width:
            raise ValueError('Cabecera RFS1 incorrecta')
        names = [r.take(r.unpack('<B')[0]).decode() for _ in range(width)]
        count = points*width
        slot_data = r.take(count*4)
        slots = list(struct.unpack('<' + 'I'*count, slot_data))
        number, = r.unpack('<I')
        if number > (len(data)-r.offset)//5:
            raise ValueError('Cantidad de nodos imposible')
        nodes = []
        for _ in range(number):
            tag, = r.unpack('<B')
            if tag == LITERAL:
                node = (tag, r.take(r.unpack('<I')[0]))
            elif tag == AFFINE:
                node = (tag, *r.unpack('<III'))
            elif tag in (DELTA, XOR_DELTA):
                ref, size = r.unpack('<II')
                node = (tag, ref, r.take(size))
            elif tag in (GATHER, ADD, XOR):
                node = (tag, *r.unpack('<II'))
            else:
                raise ValueError('Operacion RFS1 desconocida')
            nodes.append(node)
        r.end()
        return cls(n, points, names, slots, nodes)


def affine_relation(source, target):
    """Resuelve y=a*x+b en Z/(2^32), incluyendo diferencias pares."""
    a, modulus, x0, y0 = 0, 1, source[0], target[0]
    for x, y in zip(source, target):
        dx, dy = (x-x0) & MASK, (y-y0) & MASK
        if dx == 0:
            if dy:
                return None
            continue
        divisor = dx & -dx
        if dy % divisor:
            return None
        m = (1 << 32)//divisor
        candidate = (dy//divisor)*pow(dx//divisor, -1, m) % m
        if (candidate-a) % min(modulus, m):
            return None
        if m > modulus:
            a, modulus = candidate, m
    return a, (y0-a*x0) & MASK


def encode(names, phases, mode='relations'):
    if mode not in ('independent', 'reuse', 'relations'):
        raise ValueError('Modo desconocido')
    if not phases or not phases[0] or not names or any(len(phase) != len(phases[0]) for phase in phases):
        raise ValueError('Dominio inconsistente')
    if any(len(row) != len(names) or any(not isinstance(v, int) or not 0 <= v <= MASK for v in row)
           for phase in phases for row in phase):
        raise ValueError('Valores o esquema incorrectos')
    n = len(phases[0])
    nodes, slots, unique, observed = [], [-1]*(len(phases)*len(names)), {}, []
    stats = Counter()
    for point, rows in enumerate(phases):
        fields = [list(values) for values in zip(*rows)]
        payloads = [encode_column(values) for values in fields]
        # Campos pequenos primero: una direccion puede describir luego una lectura.
        for c in sorted(range(len(names)), key=lambda c: (len(payloads[c]), c)):
            values, payload = fields[c], payloads[c]
            key = words(values)
            if mode != 'independent' and key in unique:
                slots[point*len(names)+c] = unique[key]
                stats['reused_columns'] += 1
                continue
            best = (LITERAL, payload)
            size = len(pack_node(best))
            def consider(candidate):
                nonlocal best, size
                length = len(pack_node(candidate))
                if length < size:
                    best, size = candidate, length
            if mode == 'relations':
                for ref, prior in observed:
                    relation = affine_relation(prior, values)
                    if relation is not None:
                        consider((AFFINE, ref, *relation))
                    # Residuales exactos; los datos irregulares se mantienen si no ahorran.
                    for tag, residual in [(DELTA, [(v-x) & MASK for v, x in zip(values, prior)]),
                                          (XOR_DELTA, [v^x for v, x in zip(values, prior)])]:
                        consider((tag, ref, encode_column(residual)))
                sample = sorted(set([0, n//3, n//2, n-1]))
                for left, a in observed:
                    for right, b in observed:
                        if all(i < n for i in b) and all(values[i] == a[b[i]] for i in sample):
                            if all(v == a[j] for v, j in zip(values, b)):
                                consider((GATHER, left, right))
                        if left > right:
                            continue
                        if all(values[i] == (a[i]+b[i]) & MASK for i in sample) and all(v == (x+y) & MASK for v, x, y in zip(values, a, b)):
                            consider((ADD, left, right))
                        if all(values[i] == a[i]^b[i] for i in sample) and all(v == x^y for v, x, y in zip(values, a, b)):
                            consider((XOR, left, right))
            ref = len(nodes)
            nodes.append(best)
            slots[point*len(names)+c] = ref
            unique.setdefault(key, ref)
            observed.append((ref, values))
            stats[OP_NAMES[best[0]]] += 1
    graph = Graph(n, len(phases), names, slots, nodes)
    return graph, dict(stats)
