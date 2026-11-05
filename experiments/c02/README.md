# C02: observar y filtrar estados de shaders

La pregunta de este ciclo es que informacion cambia y que estructura comparten
las ejecuciones. Se ejecutan shaders propios en el driver OpenGL del sistema y
se leen variables instrumentadas antes y despues de operaciones concretas.
El nucleo C01 no participa como generador ni como referencia de estas capturas.

## Que se observa

- Cargas desde memoria, aritmetica uint32, eleccion condicional, direccionamiento,
  lectura por indice permutado y publicacion de salida.
- Memoria compartida, barreras y suma atomica por grupo, incluidos grupos finales
  parcialmente ocupados.
- Sumas float32 con orden explicito y la diferencia entre `(a+b)+c` y `a+(b+c)`.

Cada shader escribe variables y partes seleccionadas de memoria en un buffer
de traza. El buffer de traza empieza con un centinela y el de salida con otro;
la comparacion exige que cada posicion tenga el valor esperado. Despues de
`glDispatchCompute`, se aplica una barrera de memoria y se espera con
`glFinish` antes de leer los buffers.

Los puntos son puntos del **codigo fuente**. Las invocaciones pueden alcanzarlos
en momentos distintos. No son una captura simultanea de la GPU, ni registros
fisicos, ni una traza de instrucciones ISA, ni una mascara fisica de un warp.
El indicador `route` representa la condicion del shader; el compilador puede
implementar esa seleccion de distintas maneras.

## Como se contrasta

Una referencia escalar independiente, escrita en Python para este ensayo,
calcula cada variable y cada salida esperada. Se exige igualdad de los bits
en todas las posiciones, incluidos los resultados float32 registrados como
uint32. Los ejemplos de flotantes evitan NaN y resultados subnormales.

Los filtros reciben solo los valores capturados. No reciben el nombre del caso,
la semilla ni el programa para decidir su descripcion. Se prueban seis formas:
valores explicitos, constante, relacion afin, periodo, diferencias periodicas
y relacion afin con detalles. Se elige la que ocupa menos bytes **entre esas
candidatas** y se reconstruye todo para comprobar igualdad exacta.

TFS2 conserva el punto de captura, dominio, nombres/roles de los campos,
identidades y valores. El filtro no fusiona identidades ni elimina diferencias.
Estos son estados **observados**: el archivo no declara contener por si solo
todo lo necesario para continuar la ejecucion de cualquier GPU.

El inventario comprimido incluye entradas, programa GLSL exacto, traza sin
filtrar, salida y cada estado filtrado. `--replay` vuelve a contrastar el archivo
persistido, sin ejecutar OpenGL. Los bytes comparados se miden antes de ZIP y
cuentan las cabeceras y nombres del formato filtrado.

## Reproducir

```sh
python3 -m unittest discover -s experiments/c02 -p 'test_*.py' -v
python3 experiments/c02/observe.py --software --count 1024 --local-size 64
python3 experiments/c02/observe.py --software --count 257 --local-size 16 \
  --output evidence/current/c02-257
python3 experiments/c02/observe.py --replay evidence/current/c02/captures.zip
```

Python usa su biblioteca estandar. Se necesita el driver EGL/OpenGL del sistema
con compute shaders OpenGL 4.3 o posterior. En Ubuntu se obtiene la referencia
software con `libegl1` y `libgl1-mesa-dri`. Son herramientas de observacion,
externas al nucleo propio del proyecto; no se copia su codigo ni se enlazan en
el nucleo C01.

`--software` selecciona explicitamente el backend CPU. El informe identifica
vendor, renderer, version y nodos de dispositivo. `--require-hardware` rechaza
un renderer software conocido; no certifica por si mismo la naturaleza fisica
de un driver desconocido.

## Alcance del resultado

La evidencia de referencia de este ciclo fue obtenida con Mesa/llvmpipe,
ejecutado sobre CPU. No habia nodos de GPU fisica accesibles. Esto comprueba
comportamiento de los programas a traves de una implementacion OpenGL externa
a C01. La prueba en hardware GPU queda pendiente.

No se declara un estado minimo universal, aceleracion en GPU ni ejecucion
directa del shader sobre el estado filtrado. Este ciclo aporta observaciones
y contraejemplos para decidir que informacion puede compartirse y que
distinciones se deben conservar en un diseño posterior.

Referencia de la API: https://registry.khronos.org/OpenGL/specs/gl/glspec43.core.pdf
