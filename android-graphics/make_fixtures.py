#!/usr/bin/env python3
"""Referencia escalar independiente, expresiones uint32 explicitas."""
from pathlib import Path
import random
import struct

ROOT=Path(__file__).resolve().parent
MASK=(1<<32)-1

def mix(x):
    x=(x^(x>>16))*0x7feb352d&MASK
    x=(x^(x>>15))*0x846ca68b&MASK
    return x^(x>>16)

def tri(x):
    p=x%512
    return min(p,511-p)

def details(n,tick):
    base=(tick*17&MASK)%n
    return [( (base+(n//4)*k)%n,51+13*k,87+7*k,0xf0b060^(k*0x132517)) for k in range(4)]

def value(n,tick,seed,id):
    h=mix(id^seed)
    x=(16*(id%64)+tri(tick+h%256)//4)%1024
    y=(16*((id//64)%64)+tri(tick*3+(h//256)%256)//4)%1024
    z=(32*((id//4096)%32)+tri(tick+(h//65536)%256)*4)%1024
    color=((h&0xffffff)|((tick+id)<<24))&MASK
    for at,dx,dy,other in details(n,tick):
        if at==id:x=(x+dx)%1024;y=(y+dy)%1024;color^=other
    return x,y,z,color

def generate():
    rng=random.Random(0xc051326)
    cases=[]
    for n in [1,17,257,4096,65536]:
        for tick in [0,1,255,511,123456789,0x7fffffff,0xffffffff]:
            seed=rng.getrandbits(32)
            ids=sorted(set([0,n//3,n//2,n-1]+[d[0] for d in details(n,tick)]+[rng.randrange(n) for _ in range(min(n,64))]))
            cases.append((n,tick,seed,ids))
    out=bytearray(struct.pack('>I',len(cases)))
    for n,tick,seed,ids in cases:
        out.extend(struct.pack('>4I',n,tick,seed,len(ids)))
        for id in ids:out.extend(struct.pack('>5I',id,*value(n,tick,seed,id)))
    target=ROOT/'build/scene-fixtures.dat';target.parent.mkdir(parents=True,exist_ok=True);target.write_bytes(out)
    return cases

if __name__=='__main__':generate()
