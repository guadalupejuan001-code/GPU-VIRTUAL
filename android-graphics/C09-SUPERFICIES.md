# C09 — Ether Surface Buffer en GPU

Adaptacion propia del `EtherSurfaceBuffer` y las intersecciones de
`EtherRenderSpatial` de X en `guadalupejuan001-code/mundo-X`, commit
`6f36ba5701efcedd5173fb8107db1994328c5810`. No incluye otro motor grafico.
El backend OpenGL ES 3.1 ejecuta nuestros programas sobre la GPU disponible.
C01 y la escena C08 mantienen su semantica y sus pruebas.

## Recorrido real

1. La CPU envia los objetos (96 bytes por esfera, elipsoide o triangulo),
   la camara y los parametros de iluminacion. No genera pixeles ni profundidad.
2. Un compute shader proyecta las esferas envolventes conservadoras, construye
   mascaras de candidatos por bloque de 8x8 y asigna paginas mediante un contador
   atomico GPU. Dos pixeles de margen protegen los bordes de proyeccion.
3. Al cambiar geometria, camara o resolucion, se confirma el mapa con GLsync.
   La CPU lee **4 bytes** (numero de paginas) y reserva el pool al tamano necesario.
   Este ajuste no se oculta: `layout_prepare_ns` incluye proyeccion, espera,
   lectura del contador y reservas. No se requiere leer la imagen para dibujar.
4. Otro compute shader calcula la superficie opaca mas cercana, el ID y RGBA8.
   Cada pagina contiene 64 colores de 32 bits y 64 ID de 16 bits: **384 bytes**.
   Los ID vacios son 65535. Las paginas conservadoras pueden contener pixeles
   vacios: no equivale al empaquetado exacto por tramos de la version CPU.
5. El fragment shader consulta el pool, genera el fondo por regla y presenta
   directamente sobre una textura RGBA8. El visor Android recibe un blit GPU;
   no se construye un `int[]` o `Bitmap` de presentacion en CPU.
6. Profundidad: se reconstruye por interseccion con el objeto cuyo ID se guardo.
   Hay un visor de profundidad y consultas compute de ID, color y distancia.

Las barreras SSBO hacen visibles las escrituras y terminan lecturas previas
antes de reutilizar el pool. Los cuadros de prueba cuentan solo al confirmarse
su GLsync. El mapa es una cache derivada, invalidada por cambios de escena/camara.
La iluminacion animada no cambia la visibilidad y no obliga a reconstruirlo.

## Dos rutas comparables

- **Densa GPU**: un pool ID+RGBA para todos los bloques de pantalla.
- **Compacta GPU**: el mismo formato ID+RGBA para bloques potencialmente visibles.

Ambas usan el mismo shader compute, geometria, mascaras conservadoras, luz,
camara, precision y shader de presentacion. Hay **un solo pool**: al cambiar
de ruta termina el trabajo previo y se reasigna. No se retiene un pool denso
para comparar mientras se muestra el compacto. El mapa y la geometria son
comunes y se contabilizan en ambas rutas.

Comparar 6 bytes de ID+RGBA por pixel con 4 bytes de RGBA solamente omite
semantica. El informe separa el pool, las mascaras, la escena y la salida final.
La textura final RGBA8 sigue ocupando `4*ancho*alto` bytes en ambas rutas.
Android y el driver mantienen otros buffers y pueden retener reservas internas.
Los bytes pedidos a la API no son una medicion de VRAM fisica ni de RAM total.

En HD con 64 objetos dispersos y la camara inicial, el driver software produjo
2.570 paginas de 14.400: pool denso 5.529.600 B y compacto 986.880 B,
mas 172.804 B de mapa, 6.144 B de objetos y 3.686.400 B de salida RGBA8.
La escena ocupada requiere todas las paginas y elimina el ahorro del pool.
No son resultados de rendimiento de un telefono.

## Alcance y precision

Este primer observador GPU soporta superficies **opacas**, el ID cercano,
RGBA8 y profundidad reconstruida. No implementa composicion de transparencias,
texturas, sombras secundarias, exportacion perezosa sin pool ni invalidacion
parcial por regiones. Soporta los 4.096 objetos/ID del contrato CPU; no supone
que todas sus cargas alcancen el mismo rendimiento.

Los calculos geometricos GPU son `highp float` de 32 bits. El motor CPU original
usa `double`: **no son bit-exactos entre precisiones**. C09 declara esa diferencia
como contrato separado; no reemplaza silenciosamente el nucleo float64 de Mundo X.
El ID y RGBA de ambas rutas GPU deben coincidir exactamente. La referencia
exhaustiva independiente float64 verifica los ID y permite error relativo de
profundidad de 3e-4 y dos niveles de canal de 8 bits en la prueba software.
La consulta GPU de profundidad permite max(1e-4, abs(t)*2e-5); las imagenes
de profundidad de las dos rutas GPU siguen comparandose byte a byte.

## Prueba en Android

Instalar `GPU-VIRTUAL-C09-SUPERFICIES-GPU.apk` (paquete nuevo `x.gpu.surfacegpu`).
Usar primero 64 objetos, escena dispersa y HD. Arrastrar cambia la camara;
`Ver profundidad` cambia el observable. `Buffer` alterna las reservas reales.
Se conservan las pruebas de luces C08 en otra actividad.

`Verificar y medir` valida imagen, profundidad, ID y consultas antes/despues.
Mide ocho bloques de tres segundos en orden 0,1,1,0,0,1,1,0, con cuatro bloques
por ruta. Los FPS son cuadros terminados **en serie**, con un cuadro en vuelo,
sin swaps de ventana durante la medicion: no son el maximo throughput con
pipeline ni los FPS del visor. La geometria/camara quedan fijas y se anima la luz.
Calentamiento, cambios de ruta, reconstruccion del mapa y validacion estan fuera
del tiempo de cada bloque y se identifican en el informe.

La interfaz sigue indicando estado/confirmaciones y permite detener. Guardar
exporta entradas de escena, camara, mapa, pools, imagenes RGBA y profundidad,
consultas crudas, shaders, tiempos y muestras de bateria/estado termico/ahorro.
Un informe parcial o un driver software no se presenta como prueba fisica pasada.
`Copiar diagnostico` funciona sin esperar al hilo grafico.

## Verificacion del host

`python3 android-graphics/verify_surface.py` compila y ejecuta los shaders
reales en llvmpipe CPU. Usa entradas binarias del productor Java de Android,
compara imagenes, estados, consultas y reutilizacion del pool. Un observador
Python escalar exhaustivo consulta toda la geometria, ignorando las mascaras
GPU. Incluye vacio, dispersion, solapamiento, ocupacion completa, ID 4095,
HD y dimensiones no divisibles por ocho. No demuestra ejecucion Mali ni FPS.

`validate_shaders.py` añade cinco shaders C09 al compilador independiente
GLSL ES 3.10. El APK tambien conserva los seis shaders y las pruebas C08.
