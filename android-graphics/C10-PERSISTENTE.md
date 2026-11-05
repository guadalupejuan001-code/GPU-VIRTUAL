# C10 — componentes de superficies persistentes

Objetivo: reducir memoria y trabajo repetido manteniendo ID, color y profundidad
de las tres rutas GPU. Es un componente propio para el futuro renderizador,
todavia sin integrar en Reverse Velocity y sin afirmar FPS nuevos en Mali.
Los shaders C09/C08 permanecen reproducibles en sus archivos originales.

## Listas compactas de candidatos

C09 reservaba un bit por objeto y bloque y recorria todas las palabras de esa
mascara por pixel. Con 4.096 objetos en HD, el mapa ocupaba 7.430.404 B.
C10 proyecta cada objeto una vez y dispersa solo sus referencias a bloques:

1. Limpiar el espacio de trabajo GPU.
2. Proyectar esfera envolvente conservadora, guardar limites y contar candidatos
   mediante atomicos. Se mantiene el margen de proyeccion de dos pixeles.
3. Prefijo paralelo por grupos de 128 hilos y prefijo de sus sumas en GPU.
4. Crear registros de bloques y el indice inverso de paginas activas.
5. Rellenar las listas de candidatos, sin reservar palabras para objetos ausentes.
6. Restaurar los offsets que actuaron como cursores atomicos de escritura.

La CPU lee un encabezado de **16 bytes** para dimensionar reservas y comprobar
que ambos recuentos de referencias concuerdan. La GPU construye el contenido.
El mapa residente contiene cuatro palabras de cabecera, tres palabras por bloque,
el indice inverso y los ID de candidatos. El espacio de trabajo reutilizable
incluye proyecciones y prefijos; se contabiliza aunque no se utilice en ese cuadro.

En el caso host de 4.096 objetos a 1280x720: mapa residente 295.936 B y espacio
de trabajo 297.760 B; **593.696 B combinados**, frente a 7.430.404 B de C09.
No es ahorro total de RAM ni de toda la memoria GPU. Para cargas pequenas la
lista y su espacio de trabajo pueden ocupar mas que una mascara pequena.

La ruta densa tambien usa las listas y salta inmediatamente los bloques vacios.
No conserva la desventaja de recorrer 128 palabras nulas por pixel que tenia
la referencia C09 de 4.096 objetos.

## Visibilidad reutilizable

Tres rutas:

- 0: **Densa rapida GPU**, ID+RGBA en todos los bloques y nueva visibilidad cada cuadro.
- 1: **Compacta GPU**, mismo resultado en paginas activas y nueva visibilidad cada cuadro.
- 2: **Persistente GPU**, conserva ID de la superficie cuando camara/geometria no cambian.

En una reutilizacion solo se intersecta la superficie ya identificada para
reconstruir profundidad, normal y color con la luz actual. No se vuelve a buscar
entre todos los candidatos. Cambiar camara, geometria, resolucion o asignacion
del pool invalida la visibilidad antes de volver a usarla. El ID sigue siendo
derivado de la escena; no se sustituye el estado autoritativo.

Los modos compactos despachan grupos solo para paginas activas, utilizando el
indice inverso; no lanzan grupos de captura para todo el fondo. La presentacion
final todavia genera cada pixel y mantiene la textura RGBA8 completa.

Hay un solo pool ID+RGBA, 384 B por pagina de 8x8. Los cambios de ruta terminan
el trabajo previo y reasignan. Los movimientos de camara reutilizan capacidades
si caben y crecen cuando hace falta. Se distinguen bytes logicos y capacidad
retenida. El mapa, el espacio de trabajo y los objetos permanecen contabilizados.
La camara no vuelve a crear o subir todos los objetos cada cuadro.

## Intersecciones y observables

La nueva prueba HD de 4.096 objetos encontro cancelacion numerica en C09:
`r*r - (dot(offset,offset) - along*along)` perdia precision en esferas pequenas.
C10 usa el producto cruzado y el cuadrado de longitud real del rayo; para
elipsoides usa el discriminante equivalente `dot(d,d)-dot(cross(o,d),cross(o,d))`.
Evita restar cuadrados grandes casi iguales.

Por esa correccion, C10 **no es byte-exacto respecto a C09**: se cuentan cambios
de ID/color frente a C09, se consultan los ID modificados con la referencia
float64 y se identifican por separado los bordes tangentes inciertos en float32.
La comprobacion no presenta esos bordes como exactitud float64. En la prueba HD
host se modificaron 626 ID respecto a C09; uno cae en el intervalo de incertidumbre
de silueta float32, los demas coinciden con el observador de mayor precision.
Las tres rutas C10 deben conservar entre si exactamente los ID, RGBA y las
imagenes de profundidad. El desempate por igual profundidad favorece el ID menor,
independientemente del orden atomico de las listas.

El contrato sigue siendo superficies opacas y geometria float32. No hay
transparencias, texturas ni iluminacion secundaria; C01 no cambia de semantica.

## Carga y movimiento

Perfiles de 64 a **65.535 objetos**; el ID 65535 queda reservado para fondo y
por eso el indice maximo representable es 65534. Es un limite de este formato
uint16, no del chip. Superar ese numero requiere ampliar el formato y declarar
el coste. Por encima de 4.096, los objetos no siguen encogiendose: el radio
minimo conserva cobertura y genera mas superposicion.

Se agrega terreno de dos triangulos con volumenes encima. Sus triangulos son
geometria real, no un fondo de pantalla. Se conservan las escenas dispersa,
superpuesta, ocupada y vacia. La escena ocupada no permite ahorrar paginas.

`Camara: movil` activa una trayectoria de 64 poses. La medicion incluye en cada
cuadro la reconstruccion del mapa, las esperas, la lectura del encabezado y
cualquier crecimiento de capacidad. Los objetos no se vuelven a subir por un
cambio exclusivamente de camara. Se registran reconstrucciones, tiempo de mapa,
aciertos de visibilidad, capturas completas y subidas de geometria por bloque.
La ruta persistente debe invalidarse con cada pose: ese coste no se excluye
para aparentar FPS altos con una escena que en realidad esta quieta.

## APK y evidencia

`GPU-VIRTUAL-C10-PERSISTENTE-GPU.apk`, paquete `x.gpu.surface10`, conserva C09
instalado para repetir sus pruebas. El visor inicial usa terreno, 4.096 objetos,
HD y ruta persistente. La medicion realiza 12 bloques de tres segundos,
orden 0,1,2,2,1,0,0,1,2,2,1,0: cuatro bloques y al menos 12 segundos por ruta.
FPS de cuadros terminados en serie, un cuadro en vuelo, sin swaps de ventana;
no se presentan como throughput maximo con pipeline.

La validacion antes/despues guarda mapa, geometria, camaras, tres pools usados,
RGBA, profundidad y consultas crudas. Compara cada ruta con la densa sin retener
las capturas completas de las tres rutas a la vez. Se solicita GC de las
capturas temporales antes del calentamiento, fuera de los tiempos medidos.
La memoria PSS sigue siendo una medicion distinta de los bytes solicitados a GL.

Verificacion: `verify_surface2.py` ejecuta los 11 programas C10 en llvmpipe CPU,
con entradas del mismo productor Java que usa Android. Comprueba registros CSR,
offsets, ausencia de candidatos duplicados, tres rutas, cache con cambios de luz,
invalidacion por camara con pool retenido, observador escalar independiente,
terreno, HD, dimensiones no multiples de ocho y 65.535 objetos a 320x180.
No es ejecucion fisica ni benchmark de velocidad del telefono.

Para la prueba fisica, guardar primero terreno/4.096/HD con camara fija y luego
el mismo perfil con camara movil. Los ZIP permitiran decidir que ruta sirve
mejor en cada carga sin convertir el ensayo de una escena en una promesa de FPS
para todos los juegos.
