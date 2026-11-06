# GPU-VIRTUAL: exact compact triangles for REgraphics

This shared Java component extends GPU-VIRTUAL's exact representation and
resident-resource work to the real XFG4 triangle stream used by Reverse
Velocity. It is not the C10 sphere/ellipsoid renderer and imports no third-party
renderer. XI remains the producer and authority for geometry and physics;
REgraphics supplies material categories and draw passes.

Each GPU vertex occupies 24 bytes instead of the original 44:

| Offset | Storage | Meaning |
| --- | --- | --- |
| 0 | two signed int32 | exact screen X/Y, converted to binary32 by the GPU |
| 8 | uint32 | exact 20-bit Z and flat 4-bit category |
| 12 | two signed int16 | original UV coordinates |
| 16 | binary32 | original light value, with the same division as the adapter |
| 20 | four uint8 | original material base/count/tile/strength |

All declared XFG4 positions and all original binary32 shader attributes remain
representable. The separate depth and color vertex shaders must use the same
position reconstruction. Transparent triangles keep the original quicksort
ordering, including ties. Metadata-only faces are omitted by the producer.

Staging and VBO capacity grow together, with a finite 8,192-face limit. Exact
word comparisons identify dirty 256-vertex pages; adjacent pages merge into
upload ranges. Hash collisions cannot cause reuse. Context loss invalidates
all resident data. The caller retains control over synchronization and GPU
resource lifetime. No GPU counter readback or per-frame spatial-list rebuild
is required for this transport.

Run the portable component tests with Java 17:

```
mkdir -p build/compact-triangles
javac -d build/compact-triangles android-graphics/shared/x/gpu/virtual/CompactTriangleStream.java android-graphics/shared-tests/CompactTriangleStreamTest.java
java -cp build/compact-triangles x.gpu.virtual.CompactTriangleStreamTest
```

These are host transport tests, not physical GPU performance results. The game
integration separately verifies legacy/compact attributes, pixel equivalence,
installed Android gameplay, resource counters and same-device APK timing.
