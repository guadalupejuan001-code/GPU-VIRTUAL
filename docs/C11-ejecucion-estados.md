# C11 — registros, control, memoria y sincronizacion en una autoridad

El objetivo original es describir las diferencias que importan para continuar
el calculo, incluyendo el control y la memoria; comparar rutas densa/compacta
de una escena no cubria ese objetivo. C11 agrega una autoridad de ejecucion
continuable que reutiliza realmente `Field`, `Mode` y sus operaciones C01.

## Estado que evoluciona junto

| Parte | Representacion y semantica C11 |
| --- | --- |
| Programa | Instrucciones propias, dominio, tamano de grupo y memoria por grupo. Los ocho primeros opcodes preservan C01. |
| Registros | Ocho campos uint32 por despacho, con reglas, regiones y diferencias exactas del nucleo original. |
| Control | Mascaras de intervalos por PC, ejecuciones terminadas y saltos generales. Los bucles pueden dar distintas vueltas por invocacion. |
| Memoria global | Un campo comun a todos los despachos; direcciones y valores se leen de las versiones de operandos anteriores al paso. |
| Memoria compartida | Espacio separado por grupo, incluida la ultima agrupacion parcialmente ocupada. Las direcciones locales invalidas se rechazan. |
| Atomicos | Suma uint32 con devolucion del valor anterior; dentro de un paso, el orden es la identidad ascendente de las invocaciones activas. |
| Barreras | Sitio de programa, epoca del grupo y mascara de invocaciones que llegaron. Solo se libera un grupo completo en la misma barrera. |
| Dependencias | Cola con dependencias explicitas de trabajos anteriores. Las dependencias futuras, repetidas o ciclicas se rechazan. |
| Continuidad | Cursores del planificador, salidas y validez, programa, limites, contadores y alias de campos se serializan junto al resto del estado. |

Cada `step()` prepara una autoridad candidata. Cualquier fallo de direccion,
presupuesto, operacion o sincronizacion deja intacto el estado publicado,
incluidos los contadores. Un bloqueo por barrera incompleta se puede guardar;
recuperarlo conserva el bloqueo. Copiar `Execution` abre una bifurcacion
independiente que comparte los campos inmutables iniciales.

El planificador es propio y determinista: ronda entre despachos elegibles y
entre PCs listos. Un paso ejecuta las invocaciones listas del PC elegido. El
checkpoint se toma entre instrucciones logicas completas, nunca a mitad de una
suma atomica. No reproduce el orden interno de un driver o de un warp fisico.
Los accesos a memoria de distintos pasos se observan en ese orden serial; no se
declara el modelo completo de memoria GLSL/Vulkan. Dos escrituras no atomicas a
la misma direccion dentro de un paso se rechazan, incluso con valores iguales.

## Operar sobre la descripcion y declarar las expansiones

`indice`, `constante`, `mueve`, `suma`, `producto`, `detalle` y `elige` usan los
campos originales. Una escritura bajo mascara conserva las regiones y detalles
de las invocaciones no seleccionadas. Los saltos por corte de indice operan
directamente sobre intervalos, sin crear un arreglo de PCs por invocacion.

Las lecturas y escrituras globales con direccion identidad y dominio coincidente
comparten o combinan campos. Una lectura de direccion constante se resuelve una
vez. Comparaciones y operaciones de bits constantes se pliegan sin recorrer el
dominio; algunos predicados constantes por region se resuelven por intervalos.

Las direcciones arbitrarias, atomicos, indices locales/de grupo y operaciones
puntuales no reducibles evaluan las invocaciones activas una a una. Es una
expansion explicita, limitada y registrada en `scalar_lane_evaluations`; sus
resultados conservan cada diferencia necesaria. Las barreras tambien pueden
expandir metadatos por grupo. Los presupuestos de grado y regiones pueden
rechazar una expresion aunque otra representacion pudiera ser mas pequena.

| Presupuesto | Limite |
| --- | ---: |
| Invocaciones y palabras por campo | 1 a 2^32 |
| Registros por despacho | 8 |
| Instrucciones por kernel | 100.000 |
| Despachos por autoridad | 256, configurable hacia abajo |
| Tamano de grupo | 1 a 1.024 |
| Palabras compartidas por grupo | 0 a 65.536; dominio total hasta 2^32 |
| Invocaciones evaluadas individualmente por paso | 65.536, configurable hacia abajo |
| Grupos con metadatos de barrera | 65.536, configurable hacia abajo |
| Regiones, grado y detalles por campo | Limites C01: 256, 32 y 65.536 |
| Checkpoint completo | 64 MiB |

Las invocaciones logicas no equivalen a hilos fisicos ni certifican todos los
estados arbitrarios de ese dominio. Los datos irregulares ocupan memoria;
campos, metadatos, temporales y salida materializada son costes diferentes.

## Programa y ejecucion

```sh
bash build.sh
bash build-c11.sh
./build/gpu_c11 run examples/estado-compartido.xgpu
./build/gpu_c11 advance examples/estado-divergente.xgpu 14 evidence/current/c11/esperando.xgs
./build/gpu_c11 inspect evidence/current/c11/esperando.xgs
./build/gpu_c11 resume evidence/current/c11/esperando.xgs evidence/current/c11/recuperado.xgs
./build/gpu_c11 run examples/primer-calculo.xgpu
```

La cabecera `.xgpu 2` declara, en orden, `dominio`, `grupo`, `compartida` y
`global`. Las etiquetas se definen con `marca nombre`; `salta nombre`,
`si r0 nombre` y `si_indice_menor corte nombre` controlan el flujo. Los ejemplos
cubren divergencia, bucles, barreras, memoria y atomicos. El parser rechaza
instrucciones, operandos y etiquetas desconocidos. La API C++ permite encolar
mas kernels con `enqueue(kernel, n, grupo, palabras, dependencias)`.

`run` y `resume` usan un presupuesto de un millon de pasos; si se agota guardan
el checkpoint solicitado y devuelven 3 indicando ejecucion pendiente. `advance`
acepta de 0 a un millon de pasos y conserva deliberadamente el estado parcial.
Las consultas y `inspect` no avanzan el programa ni los contadores.

## Checkpoint propio XGPU-C11

`snapshot_gpu.hpp` escribe un archivo textual portable por valores. Un inventario
de campos conserva los alias compartidos; cada campo incluye su dominio, modos,
regiones y detalles. No depende de punteros, del programa original en disco ni de
un archivo externo de entradas. El programa y los cursores estan dentro.

El lector acota recuentos y verifica dominios, cobertura exacta y disjunta de las
mascaras de control, PCs, dependencias, epocas y referencias. Comprueba longitud
y checksum FNV-1a de 64 bits para detectar corrupcion accidental; ese checksum
no autentica entradas hostiles. Un archivo rechazado no sustituye la autoridad
existente. C01 mantiene su formato y lector originales.

El CLI POSIX escribe un temporal en el mismo directorio, sincroniza su contenido,
lo renombra atomicamente y sincroniza el directorio. Una escritura incompleta
conserva byte a byte el checkpoint anterior y elimina el temporal. El guardado
no ocurre en cada paso; serializarlo es un coste explicito.

## Verificacion reproducible

El ejecutor C11 corre sobre CPU anfitriona. `referencia_ejecucion.hpp` es un
interprete escalar separado con arreglos de registros, PCs y esperas por
invocacion y memoria explicita: no usa `Field`, `Mode`, `LaneMask`, `Execution`
ni su codec para evolucionar. Comparte el contrato de instrucciones, no sus
operaciones compactas.

La campana incluye 160 kernels aleatorios y compara tras cada paso todos los
registros, memorias, PCs, esperas, epocas, salidas, cursores y contadores semanticos
de las cargas pequenas. Cubre dependencias e intercalado de despachos, alias de
direccion/resultado, direccionamiento permutado, grupos parciales, bucles
divergentes, atomicos con desbordamiento, bloqueos, barreras incompatibles y
rechazos sin publicacion parcial.

Cada estado de esas ejecuciones se serializa y recupera. Tambien se compara la
continuacion futura en los escenarios de control/memoria. El CLI se verifica
en procesos independientes y conserva 42 checkpoints intermedios, incluidos
siete puntos con grupos esperando en barreras. Una prueba limita el tamano de
archivo para forzar una escritura fallida antes de reemplazar un checkpoint.

La ejecucion local produjo:

| Evidencia | Resultado |
| --- | ---: |
| Comprobaciones C01 originales | 6.343.189, sin fallos |
| Comprobaciones C11 | 14.306.466, sin fallos |
| Pasos contrastados con referencia escalar | 13.521 |
| Estados serializados y recuperados en la campana nativa | 13.691 |
| Snapshot del ejemplo original de 1.048.576 invocaciones | 641 bytes; salida completa contrastada con C01 |
| Caso estructurado de 2^32 invocaciones | 486 bytes; seis consultas de frontera, no comprobacion exhaustiva |
| Evaluaciones escalares de esos dos casos estructurados | 0 |

Estos tamanos son del archivo completo de cada caso, no de la RAM del proceso
ni de una GPU fisica. Los scripts de sanitizadores distinguen si LeakSanitizer
estaba habilitado. La CI conserva resultados, ejecutables y checkpoints en el
artefacto `GPU-VIRTUAL-C11`.

## Estado del objetivo y siguientes ciclos

Actualizacion: [C12](C12-relaciones-ejecutables.md) implementa relaciones
ejecutables, lecturas versionadas y operaciones binary32. La importacion de
trazas C02/RFS1 y los adaptadores de qSSD/XI continuan en los ciclos siguientes.

C11 ya usa conjuntamente datos, instrucciones, control, memoria, sincronizacion
y dependencias dentro de su perfil. Todavia no abarca todos los estados de una
GPU general: faltan float32 con redondeo explicito, texturas/rasterizacion dentro
de esta autoridad, subgrupos y adaptadores para los shaders existentes.

| Ciclo siguiente | Cierre que debe demostrar |
| --- | --- |
| C12: operaciones y relaciones | Ampliar estados numericos sin reordenar float32; conectar trazas C02/RFS1 con instrucciones y dependencias verificables. |
| C13: integracion de autoridad | Adaptar esta autoridad al bus qSSD/XI y a trabajos graficos reales con recursos originales coherentes. |
| C14: ejecucion GPU y juego | Contrastar ejecucion fisica y continuacion de cargas reales; medir costes completos antes de atribuir FPS o memoria al modelo. |

Esta entrega continua el nucleo GPU-VIRTUAL. Los resultados actuales no son una
medicion de Mali-G68 ni una captura del estado privado del driver. La GPU activa
de REVERSE VELOCITY y el software qSSD de otras ramas conservan sus versiones.
