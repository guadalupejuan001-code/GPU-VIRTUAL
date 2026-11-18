# C13 · Residencia de páginas gráficas

[`GraphicsPageResidency`](../include/graphics_page_residency.hpp) conserva las
páginas de texturas o geometría en un atlas de capacidad fija. La identidad y la
revisión de una página son valores de 64 bits. Cada par identifica bytes
inmutables. El tamaño de página lo define el consumidor que crea el atlas.

## Contrato con el renderizador

Una instancia administra un atlas en una cola GPU ordenada. El renderizador
serializa las llamadas y pasa la lista completa de las entradas de tabla que el
cuadro va a leer. Varias entradas pueden compartir una página y su ranura.

1. `prepare_frame` recibe las entradas activas, el último fence confirmado y el
   fence previsto para el cuadro. El plan enumera las cargas y los cambios de
   tabla, además de los enlaces activos con sus ranuras. Las entradas inactivas
   quedan fuera del contrato de lectura del cuadro.
2. El renderizador encola las cargas, los cambios de tabla, el dibujo y su fence
   en ese orden. Los cambios de tabla también se encolan para que un cuadro
   anterior siga leyendo sus entradas correctas. `submit_frame` confirma la
   publicación y retiene las ranuras hasta que se confirme el fence.
3. Si falta espacio por trabajo en curso, `prepare_frame` devuelve `nullptr` y
   permite reintentar tras confirmar un fence posterior. Un cuadro cuyas páginas
   superan la capacidad del atlas produce `std::length_error`.
4. Si falla una publicación preparada, `discard_frame` invalida las ranuras que
   pudieron recibir datos y fuerza la republicación de los enlaces del próximo
   cuadro. Si quedaron comandos en cola, el renderizador encola el fence previsto
   después de ellos y llama a `discard_frame(true)`. Con la cola libre de esos
   comandos puede usar `discard_frame(false)`.

Una versión nueva obtiene otra ranura si la anterior todavía está en uso.
El plan puede compartir ranuras ya encoladas cuando el cuadro solicita la misma
versión. Un cambio de cámara conserva las páginas que siguen en uso y permite
recuperar páginas aún residentes al volver a una zona.

## Verificación

```sh
bash build-c13.sh
```

El ensayo usa el recorrido del prototipo de residencia. Dibuja cada cuadro desde
la tabla incremental y un atlas de 6 MiB en CPU. Un oráculo por coordenadas y
revisión comprueba todos los píxeles a 1280 × 720 en una escena de 256 MiB lógicos.

| Escenario | Cuadros exactos | Escrituras de tabla de referencia | Escrituras de tabla C13 |
| --- | ---: | ---: | ---: |
| Cámara en movimiento con ediciones | 320 | 24.410 | 1.718 |
| Cortes de cámara sin solapamiento | 48 | 12.096 | 12.096 |

La referencia vuelve a publicar la vista cuando cambia su cobertura. El informe
`evidence/current/c13/residency.json` registra las escrituras de tabla y los bytes
de contenido preparados para el atlas. La prueba también cubre las revisiones
en vuelo, los reintentos y las cargas canceladas. El enlace con el atlas de
REgraphics y la medición física en Mali quedan por verificar.
