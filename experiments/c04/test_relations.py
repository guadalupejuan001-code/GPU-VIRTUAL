import random
import struct
import unittest

from relations import (AFFINE, LITERAL, MASK, Column, Graph, affine_relation,
                       encode, pack_node)
from filters import encode_column


class ExactRelations(unittest.TestCase):
    def test_scalar_access_to_every_column_format(self):
        n = 19
        examples = [
            (struct.pack('<BI', 0, n) + struct.pack('<19I', *range(n)), list(range(n))),
            (struct.pack('<BII', 1, n, MASK), [MASK]*n),
            (struct.pack('<BIII', 2, n, MASK-1, 7), [(MASK-1+7*i)&MASK for i in range(n)]),
            (struct.pack('<BII3I', 3, n, 3, 2, 4, 9), [[2,4,9][i%3] for i in range(n)]),
        ]
        differences = [MASK, 7, 3]
        values = [11]
        for i in range(1, n):
            values.append((values[-1]+differences[(i-1)%3])&MASK)
        examples.append((struct.pack('<BIII3I', 4, n, 11, 3, *differences), values))
        values = [(3+5*i)&MASK for i in range(n)]
        values[2], values[17] = MASK, 0
        examples.append((struct.pack('<BIIII4I', 5, n, 3, 5, 2, 2, MASK, 17, 0), values))
        for blob, expected in examples:
            col = Column(blob, n)
            self.assertEqual([col.at(i) for i in range(n)], expected)
            with self.assertRaises(IndexError):
                col.at(n)

    def test_modular_affine_solver_handles_even_differences_and_wrap(self):
        rng = random.Random(0xA44)
        for shift in [0, 1, 2, 7, 19, 31]:
            for _ in range(40):
                source = [(rng.getrandbits(32)<<shift)&MASK for _ in range(19)]
                a, b = rng.getrandbits(32), rng.getrandbits(32)
                target = [(a*x+b)&MASK for x in source]
                result = affine_relation(source, target)
                self.assertIsNotNone(result)
                aa, bb = result
                self.assertEqual([(aa*x+bb)&MASK for x in source], target)
        self.assertIsNone(affine_relation([0,2,4], [1,7,14]))
        self.assertIsNone(affine_relation([7,7,7], [1,2,1]))

    def test_unseen_shapes_data_and_relations_preserve_every_value(self):
        rng = random.Random(0xC042026)
        for n in [1, 2, 3, 19, 63, 263]:
            for trial in range(4):
                a = [rng.getrandbits(32) for _ in range(n)]
                address = [rng.randrange(n) for _ in range(n)]
                names = ['ordinal', 'source', 'derived', 'address', 'read', 'mixed']
                first = [[i,a[i],(a[i]*7+13)&MASK,address[i],a[address[i]],a[i]^(i%5)] for i in range(n)]
                second = [row.copy() for row in first]
                second[rng.randrange(n)][-1] ^= 1
                phases = [first, second]
                for mode in ['independent','reuse','relations']:
                    with self.subTest(n=n,trial=trial,mode=mode):
                        graph,_ = encode(names,phases,mode)
                        recovered=Graph.from_bytes(graph.to_bytes())
                        self.assertEqual(recovered.states(),phases)
                        for p,phase in enumerate(phases):
                            for c,name in enumerate(names):
                                ref=recovered.field(p,name)
                                self.assertEqual([recovered.at(ref,i) for i in range(n)], [row[c] for row in phase])

    def test_compact_continuation_reads_the_preserved_exceptions(self):
        n=257
        values=[(7+3*i)&MASK for i in range(n)]
        values[17]=MASK
        names=['id','source','address']
        rows=[[i,values[i],(5*i+3)%n] for i in range(n)]
        graph,_=encode(names,[rows])
        scaled=graph.affine(graph.field(0,'source'),MASK,5)
        mixed=graph.binary(scaled,graph.field(0,'id'),'xor')
        result=graph.gather(mixed,graph.field(0,'address'))
        expected=[(((MASK*values[(5*i+3)%n]+5)&MASK)^((5*i+3)%n)) for i in range(n)]
        saved=Graph.from_bytes(graph.to_bytes())
        self.assertEqual([saved.at(result,i) for i in range(n)],expected)
        self.assertEqual(saved.states(),[rows])

    def test_queries_and_updates_do_not_expand_a_large_domain(self):
        n=10_000_000
        literal=struct.pack('<BII',1,n,42)
        graph=Graph(n,1,['value'],[0],[(LITERAL,literal)])
        ref=0;expected=42
        for _ in range(1000):
            ref=graph.affine(ref,3,5)
            expected=(3*expected+5)&MASK
        stats={}
        self.assertEqual(graph.at(ref,n-1,stats),expected)
        self.assertEqual(stats['full_columns_materialized'],0)
        self.assertEqual(stats['node_identity_evaluations'],2)
        # La historia de 1000 operaciones ocupa nodos; no se oculta ese coste.
        self.assertLess(len(graph.to_bytes()),14_000)

    def test_same_histogram_different_order_and_single_bit_are_preserved(self):
        first=[[i,x] for i,x in enumerate([4,8,16,32])]
        second=[[i,x] for i,x in enumerate([32,16,8,4])]
        graph,_=encode(['id','v'],[first,second])
        self.assertEqual(Graph.from_bytes(graph.to_bytes()).states(),[first,second])
        second[3][1]^=1
        other,_=encode(['id','v'],[first,second])
        self.assertNotEqual(other.to_bytes(),graph.to_bytes())

    def test_forward_references_truncation_unknown_ops_and_bad_indices(self):
        graph,_=encode(['v'],[[[3],[7],[9]]])
        blob=graph.to_bytes()
        for bad in [blob[:-1],blob+b'\0',b'BAD!'+blob[4:]]:
            with self.assertRaises(ValueError):Graph.from_bytes(bad)
        with self.assertRaises(ValueError):Graph(3,1,['v'],[0],[(AFFINE,0,1,2)])
        with self.assertRaises(ValueError):Column(struct.pack('<BII',1,9,7),3)
        with self.assertRaises(ValueError):graph.binary(0,999,'add')
        addresses=graph._append((LITERAL,encode_column([0,1,3])))
        invalid=graph.gather(0,addresses)
        with self.assertRaises(ValueError):graph.at(invalid,2)
        with self.assertRaises(ValueError):graph.materialize()


if __name__=='__main__':
    unittest.main()
