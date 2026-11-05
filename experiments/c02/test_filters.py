import random
import struct
import unittest

from filters import MASK, decode_column, decode_state, encode_column, encode_state, period


class ExactFilters(unittest.TestCase):
    def test_observed_candidates_and_independent_patterns(self):
        rng = random.Random(0xC02026)
        for n in [1, 2, 3, 7, 19, 64, 257]:
            samples = [[0] * n, [MASK] * n, [(MASK - 8 + 7*i) & MASK for i in range(n)],
                       [rng.getrandbits(32) for _ in range(n)],
                       [[7, 2, MASK][i % 3] for i in range(n)]]
            for trial in range(12):
                sample = [(trial * 29 + i * 3) & MASK for i in range(n)]
                for _ in range(min(n, 4)):
                    sample[rng.randrange(n)] = rng.getrandbits(32)
                samples.append(sample)
            for sample in samples:
                with self.subTest(n=n, sample=sample[:4]):
                    packed = encode_column(sample)
                    self.assertEqual(decode_column(packed), sample)
                    self.assertLessEqual(len(packed), 5 + 4*n)

    def test_period_matches_brute_force_on_short_sequences(self):
        for n in range(1, 9):
            for bits in range(1 << n):
                sample = [(bits >> i) & 1 for i in range(n)]
                expected = next(p for p in range(1, n+1) if all(sample[i] == sample[i % p] for i in range(n)))
                self.assertEqual(period(sample), expected)

    def test_raw_values_remain_when_candidates_do_not_save(self):
        rng = random.Random(9932)
        sample = [rng.getrandbits(32) for _ in range(512)]
        self.assertEqual(encode_column(sample)[0], 0)

    def test_state_keeps_source_point_roles_and_identities(self):
        rows = [[i, 0, (4+3*i) & MASK] for i in range(257)]
        names = ['id', 'branch', 'register_a']
        encoded, _ = encode_state(names, rows, point=19)
        self.assertEqual(decode_state(encoded), (19, names, rows))
        other, _ = encode_state(names, rows, point=20)
        self.assertNotEqual(encoded, other)

    def test_equal_histogram_is_not_equal_state(self):
        first, _ = encode_state(['id', 'value'], [[0, 1], [1, 2], [2, 3]])
        other, _ = encode_state(['id', 'value'], [[0, 3], [1, 2], [2, 1]])
        self.assertNotEqual(decode_state(first), decode_state(other))

    def test_incomplete_and_invalid_encodings_rejected(self):
        encoded = encode_column([1, 2, 3, 4])
        for candidate in [encoded[:-1], encoded+b'\0', b'\xff'+encoded[1:], struct.pack('<BI', 0, 0)]:
            with self.assertRaises(ValueError):
                decode_column(candidate)
        with self.assertRaises(ValueError):
            decode_state(b'bad')


if __name__ == '__main__':
    unittest.main()
