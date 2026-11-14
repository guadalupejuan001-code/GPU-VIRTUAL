# C12 — relaciones ejecutables, memoria versionada y binary32

C11 ya conservaba una ejecucion coherente, pero los indices locales, muchas
operaciones de bits y los accesos indirectos desarrollaban arreglos de detalles
por invocacion. C12 incorpora esas relaciones a la autoridad, las comparte y
continua sobre ellas. Reutiliza el nucleo original C01 para los casos que este
ya representa bien, sin incorporar un motor o simulador externo.

## Ampliacion concreta

| Trabajo | Representacion C12 |
| --- | --- |
| Indice local y de grupo | Expresiones de modulo/division con dominio y tamano de grupo explicitos. |
| Aritmetica y bits | Campos C01 cuando caben; nodos de operaciones exactas cuando no conviene o no cabe desarrollar regiones/detalles. |
| Repeticiones | Comparticion de nodos/valores con igualdad estructural exacta. Los hashes solo buscan candidatos; nunca autorizan igualdad. |
| Control | Mascaras originales; constantes en las ejecuciones activas, periodos demostrados y cortes por indice evitan enumerar todo el dominio. |
| Lectura global | Retiene la version inmutable de memoria y su expresion de direccion. Cambiar la memoria despues no cambia el valor leido antes. |
| Escritura demostrable | Identidad y permutaciones XOR invertibles conservan una descripcion exacta. Las escrituras completas eliminan la dependencia de la memoria sustituida. |
| Memoria de grupo | Localidad demostrada y mapeo grupo*palabras+direccion; el caso local con una palabra por invocacion conserva la descripcion. |
| Atomico global uniforme | Direccion y sumando uniformes sobre la mascara activa: prefijo ordenado de identidades activas y una actualizacion modular de memoria. |
| Salida | Consultas puntuales y lector reutilizable; materializacion completa explicita cuando el consumidor la solicita. |

El prefijo atomico conserva exactamente el orden ascendente declarado en C11:
cada invocacion recibe el valor anterior que le corresponde. Tambien admite
mascaras separadas en varios intervalos. Si no se demuestra esa estructura, se
conserva la ruta individual acotada y sus comprobaciones de colision/direccion.

Las reglas incluyen identidades como grupo(i)*L+local(i)=i y constantes demostradas
solo dentro de la mascara que ejecuta la instruccion. No se usa una muestra de
valores para suponer que todo el dominio tiene el mismo patron. Los datos
irregulares conservan todos sus detalles y su coste.

## Consultar sin multiplicar trabajo repetido

El lector construye una secuencia de dependencias para grafos puntuales y calcula
cada nodo una vez por identidad solicitada. No expande recursivamente dos veces
el mismo operando compartido. Las lecturas permutadas utilizan memoizacion por
nodo e indice, porque una dependencia puede consultarse en direcciones distintas.
Los campos C01 tienen una ruta directa para conservar su lectura economica.

Los planes de consulta y las tablas de busqueda son derivados. No contienen
informacion necesaria para continuar la ejecucion: se reconstruyen desde las
raices persistidas. La comparticion entre instrucciones se reconstruye desde
los nodos alcanzables de la autoridad, no desde vistas externas ni desde una
cache oculta. Por ello los contadores y los futuros checkpoints coinciden al
recuperar en otro proceso.

## Flotantes con politica propia explicita

`float32_gpu.hpp` implementa sobre enteros, desde cero:

- Conversion uint32 a binary32.
- Suma y producto binary32.
- Comparacion menor, con NaN no ordenado y ceros con signo.

El perfil fija round-to-nearest/ties-to-even, subnormales graduales e infinitos.
La aritmetica produce NaN canonico `0x7fc00000`; almacenar/copiar palabras crudas
conserva sus 32 bits, incluidos otros NaN. No se representan flags de excepcion,
trampas, otros modos de redondeo ni operaciones no implementadas como division.

Las operaciones siguen el orden del programa. Compartir dos expresiones exige
los mismos operandos ordenados y la misma operacion; no se reasocian sumas ni
se fusionan suma/producto. La prueba conserva el contraejemplo con 2^24, 1 y
-2^24, donde cambiar parentesis cambia el resultado.

La referencia de pruebas usa operaciones float del anfitrion con redondeo
nearest y almacenamiento volatile, independientemente del ejecutor entero.
Canoniza NaN segun el contrato antes de comparar. Comprueba un millon de pares
aleatorios y casos de frontera, incluidos subnormales, ceros con signo e infinitos.

Referencia conceptual de formatos, sin copiar implementaciones:
[Oracle Numerical Computation Guide, IEEE Formats](https://docs.oracle.com/cd/E77782_01/html/E77791/z4000ac019878.html).

## Perfil, archivos y presupuestos

`.xgpu 3` selecciona C12. Conserva las instrucciones anteriores y agrega
`flotante_u32`, `suma_f32`, `producto_f32` y `menor_f32`. Los literales siguen
siendo palabras uint32; no dependen del parser decimal float ni del FPU del
proceso. Los ejemplos muestran los patrones de bits de 1.0 y 2.0.

`XGPU-C12 1` guarda el DAG en orden de dependencias, las hojas C01, mascaras,
parches, raices de registros/memoria, programas, control, esperas, planificador,
limites y contadores. El lector rechaza referencias futuras/ciclicas, dominios
incompatibles y accesos que no puede validar. El formato C11 sigue teniendo
su escritor/lector: la continuacion de archivos antiguos se contrasta byte a byte
con el ejecutor C11 publicado.

| Presupuesto adicional | Limite |
| --- | ---: |
| Nodos alcanzables | 65.536 |
| Profundidad | 128 |
| Unidades de contenido de campos, regiones y pares indice/valor | 1.048.576 |
| Intervalos en mascaras de valores | 1.048.576, con maximo 65.536 por mascara |
| Periodo usado para demostrar un predicado | Hasta 65.536 |
| Evaluaciones individuales de una instruccion no reducible | Limite C11, hasta 65.536 |
| Checkpoint | 64 MiB |
| Exportacion completa solicitada | Hasta 16.777.216 palabras, 64 MiB |

Si la fragmentacion supera los presupuestos se rechaza el paso sin publicacion
parcial. C12 no declara una representacion minima universal. El archivo de estado,
el DAG en memoria, el plan de consulta, los temporales y la salida son cantidades
distintas. `scalar_lane_evaluations` cuenta las rutas individuales de ejecucion;
no oculta que exportar N palabras calcula y escribe N valores. Las comprobaciones
individuales adicionales de direcciones/desplazamientos tienen su contador propio.

## Reproducir y conservar checkpoints

```sh
bash build.sh
bash build-c11.sh
bash build-c12.sh
./build/gpu_c12 advance examples/flotantes-ordenados.xgpu 5 evidence/current/c12/medio.xgs
./build/gpu_c12 resume evidence/current/c12/medio.xgs evidence/current/c12/final.xgs
./build/gpu_c12 export evidence/current/c12/final.xgs 0 evidence/current/c12/salida.bin
python3 scripts/verify-c12-process.py --baseline-root /ruta/al/checkout/C11
python3 scripts/measure-c12.py --baseline-root /ruta/al/checkout/C11 --rounds 5
```

La base debe tener exactamente el arbol C11 publicado en
`567b97a5ccd59f4a25a4df351bd6e48d0e0f8665`. La CI obtiene ese commit por separado.
Conserva los checkpoints intermedios, salidas binarias, reportes y ejecutables
en el artefacto `GPU-VIRTUAL-C12`.

## Verificacion y medicion

La campana nativa C12 pasa 6.259.630 comprobaciones, 120 kernels aleatorios,
5.229 pasos contrastados con el interprete escalar y 5.354 estados recuperados.
Incluye lecturas contra versiones anteriores, atomicos con mascaras separadas,
grupos parciales, alias de operandos, bucles, limites, corrupcion y grafos ciclicos.
Prueba un DAG flotante compartido de 120 sumas que no puede evaluarse mediante
expansion recursiva exponencial. C01 y C11 mantienen sus campañas originales.

El CLI recupera 14 checkpoints C12 en procesos distintos y exporta 131.072
palabras contrastadas completas. Tambien verifica 19 continuaciones antiguas
C11 con los mismos bytes futuros, incluyendo el caso de una sola invocacion.
ASan/UBSan comprueban el nuevo ejecutor y la CI habilita tambien deteccion de fugas.

La medicion compila la misma carga contra el C11 inmutable y contra C12. Alterna
cinco repeticiones por version y tamano, despues de calentamientos separados.
Mide preparacion, ejecucion y materializacion de toda la salida; verifica cada
palabra con una formula independiente y conserva el hash completo. La escritura
del checkpoint se mide por separado, no se atribuye su ahorro al tiempo de dibujo.

Se publican cargas periodicas, memoria permutada y el ejemplo original ya
compacto. Este ultimo permite ver el coste fijo adicional de construir relaciones
cuando ya hay una descripcion C01 suficiente. No se elimina de los resultados si
resulta mas lento. `measurements.json` conserva todas las rondas, medianas, bytes
de checkpoint y pico RSS nativo. Son mediciones CPU del interprete con salidas
completas, no una comparacion con un rasterizador convencional optimizado.

## C12.1 · Exportación periódica, sumas exactas y circuitos XI reversibles

`export` conserva todos los bytes de salida y escribe el formato little-endian
existente. Cuando el ejecutor conoce un período, evalúa una vez ese patrón y
copia sus repeticiones. Las salidas irregulares se evalúan en el orden original.

`sum-u32` suma un rango válido de un despacho con resultado exacto de 64 bits.
Los campos periódicos necesitan a lo sumo un período de evaluaciones. Los
campos afines módulo 2^32 suman cada intervalo mediante una recurrencia euclídea
y después corrigen sus excepciones. Los otros campos siguen usando el lector
exacto. `sum_u64` se devuelve como decimal en una cadena JSON para que lo puedan
leer programas cuyo tipo numérico no representa todos los enteros de 64 bits.

`reversible-xi` compone las puertas `x objetivo` y `cx control objetivo` de XI
sobre entradas en la base computacional. Acepta lotes `begin` y `end` y reutiliza
la transformación con todos los operandos hexadecimales del comando. Empaqueta
en palabras de 64 bits las dependencias que conservan su posición relativa y
evalúa las demás salidas por paridad. La ejecución usa enteros del procesador
anfitrión. El subconjunto cubre transformaciones afines binarias. Las sumas
con acarreo, la superposición y las otras puertas siguen fuera de esta ruta.

```sh
bash build-c12.sh
./build/gpu_c12 run examples/relaciones-compartidas.xgpu estado.xgs
./build/gpu_c12 sum-u32 estado.xgs 0
./build/gpu_c12 sum-u32 estado.xgs 0 61 997
printf 'begin\nx 128\ncx 0 64\ncx 64 128\nend\n' > mascara.xi
./build/gpu_c12 reversible-xi 129 mascara.xi 0 1 10000000000000000
```

## Continuidad del proyecto

C12 amplia la GPU de software con mas estados y relaciones realmente ejecutables.
La adaptacion de trazas C02/RFS1, el bus qSSD/XI y los trabajos del renderizador
siguen pendientes de integrar. Las ecuaciones y recursos originales del juego
no se han sustituido por los ejemplos de esta campaña.
