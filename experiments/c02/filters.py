"""Filtros exactos sobre valores capturados; no presume un minimo universal."""
from collections import Counter
import struct

MASK = (1 << 32) - 1
LABELS = ['raw', 'constant', 'affine', 'periodic', 'periodic_differences', 'affine_with_details']


def words(values):
    return struct.pack('<' + 'I' * len(values), *values)


def period(values):
    if not values:
        return 0
    # Longitud del periodo que reproduce exactamente el prefijo observado.
    prefix = [0] * len(values)
    for i in range(1, len(values)):
        j = prefix[i - 1]
        while j and values[i] != values[j]:
            j = prefix[j - 1]
        if values[i] == values[j]:
            j += 1
        prefix[i] = j
    return len(values) - prefix[-1]


def encode_column(values):
    n = len(values)
    if not n:
        raise ValueError('Campo vacio')
    if any(not 0 <= value <= MASK for value in values):
        raise ValueError('Valor fuera de uint32')
    candidates = [struct.pack('<BI', 0, n) + words(values)]
    if all(value == values[0] for value in values):
        candidates.append(struct.pack('<BII', 1, n, values[0]))
    step = (values[1] - values[0]) & MASK if n > 1 else 0
    if all(value == (values[0] + i * step) & MASK for i, value in enumerate(values)):
        candidates.append(struct.pack('<BIII', 2, n, values[0], step))
    p = period(values)
    candidates.append(struct.pack('<BII', 3, n, p) + words(values[:p]))
    if n > 1:
        differences = [(values[i] - values[i - 1]) & MASK for i in range(1, n)]
        p = period(differences)
        candidates.append(struct.pack('<BIII', 4, n, values[0], p) + words(differences[:p]))
        slope = Counter(differences).most_common(1)[0][0]
    else:
        slope = 0
    base = Counter((value - i * slope) & MASK for i, value in enumerate(values)).most_common(1)[0][0]
    details = [(i, value) for i, value in enumerate(values) if value != (base + i * slope) & MASK]
    candidates.append(struct.pack('<BIIII', 5, n, base, slope, len(details)) + b''.join(struct.pack('<II', *item) for item in details))
    selected = min(candidates, key=lambda value: (len(value), value[0]))
    # Es una seleccion entre estos filtros concretos, no entre todos los modelos.
    if decode_column(selected) != values:
        raise AssertionError('El filtro perdio informacion')
    return selected


class Reader:
    def __init__(self, data):
        self.data = data
        self.offset = 0

    def take(self, size):
        if size < 0 or self.offset + size > len(self.data):
            raise ValueError('Estado truncado')
        result = self.data[self.offset:self.offset + size]
        self.offset += size
        return result

    def unpack(self, fmt):
        return struct.unpack(fmt, self.take(struct.calcsize(fmt)))

    def end(self):
        if self.offset != len(self.data):
            raise ValueError('Datos sobrantes')


def decode_column(data):
    reader = Reader(data)
    tag, n = reader.unpack('<BI')
    if n == 0:
        raise ValueError('Campo vacio')
    if tag == 0:
        result = list(reader.unpack('<' + 'I' * n))
    elif tag == 1:
        value, = reader.unpack('<I')
        reader.end()
        result = [value] * n
    elif tag == 2:
        base, step = reader.unpack('<II')
        reader.end()
        result = [(base + i * step) & MASK for i in range(n)]
    elif tag == 3:
        p, = reader.unpack('<I')
        if not 1 <= p <= n:
            raise ValueError('Periodo invalido')
        pattern = reader.unpack('<' + 'I' * p)
        reader.end()
        result = [pattern[i % p] for i in range(n)]
    elif tag == 4:
        base, p = reader.unpack('<II')
        if not 1 <= p < n:
            raise ValueError('Periodo de diferencias invalido')
        pattern = reader.unpack('<' + 'I' * p)
        reader.end()
        result = [base]
        for i in range(1, n):
            result.append((result[-1] + pattern[(i - 1) % p]) & MASK)
    elif tag == 5:
        base, step, count = reader.unpack('<III')
        if count > n:
            raise ValueError('Cantidad de detalles invalida')
        # Validar todos los detalles antes de reservar el resultado.
        details = [reader.unpack('<II') for _ in range(count)]
        if any(at >= n for at, _ in details) or any(details[i][0] >= details[i+1][0] for i in range(len(details)-1)):
            raise ValueError('Indices de detalles invalidos')
        reader.end()
        result = [(base + i * step) & MASK for i in range(n)]
        for at, value in details:
            result[at] = value
    else:
        raise ValueError('Filtro desconocido')
    reader.end()
    return result


def encode_state(names, rows, point=0):
    if not rows or not names or any(len(row) != len(names) for row in rows):
        raise ValueError('Matriz de captura invalida')
    encoded = bytearray(struct.pack('<4sIIH', b'TFS2', point, len(rows), len(names)))
    stats = []
    for column, name in enumerate(names):
        label = name.encode()
        if len(label) > 255:
            raise ValueError('Nombre demasiado largo')
        values = [row[column] for row in rows]
        payload = encode_column(values)
        encoded.extend(struct.pack('<B', len(label)) + label + struct.pack('<I', len(payload)) + payload)
        stats.append({'field': name, 'filter': LABELS[payload[0]], 'payload_bytes': len(payload),
                      'raw_value_bytes': len(values) * 4, 'distinct_values': len(set(values)), 'first_values': values[:8]})
    if decode_state(bytes(encoded)) != (point, names, rows):
        raise AssertionError('Reconstruccion de estado diferente')
    return bytes(encoded), stats


def decode_state(data):
    reader = Reader(data)
    magic, point, n, columns = reader.unpack('<4sIIH')
    if magic != b'TFS2' or not n or not columns:
        raise ValueError('Cabecera de estado invalida')
    names, fields = [], []
    for _ in range(columns):
        length, = reader.unpack('<B')
        names.append(reader.take(length).decode())
        size, = reader.unpack('<I')
        field = decode_column(reader.take(size))
        if len(field) != n:
            raise ValueError('Dominios diferentes')
        fields.append(field)
    reader.end()
    if len(set(names)) != len(names):
        raise ValueError('Nombres duplicados')
    return point, names, [list(row) for row in zip(*fields)]
