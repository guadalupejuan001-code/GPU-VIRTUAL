# C08: graficos compactos contra materializacion CPU y GPU

Instalar **GPU-VIRTUAL-C08-GRAFICOS-COMPACTOS.apk** y abrir **Graficos C08**.
El paquete es `x.gpu.campaign`, separado de C05/C06/C07. No requiere red,
motores externos ni permisos generales de almacenamiento.

## Repetir y buscar trabajo evitable

1. Desactivar el ahorro de energia en Android. Si sigue activo, **Medir**
   ofrece abrir sus ajustes; la app comprueba de nuevo antes del ensayo.
   No lo desactiva por su cuenta ni cambia relojes o politicas termicas.
2. Para repetir la carga de C06: **65.536 luces**, **512 x 512**, **lote 8**.
   Pulsar **Medir 4 metodos** y mantener la app abierta unos 2,5 minutos.
3. Guardar el ZIP al terminar. Cada ejecucion tiene nombre e identificador
   unicos con carga, resolucion, lote y hora de inicio para no sobrescribirla.
4. Para explorar graficos mas exigentes, cambiar una variable cada vez:
   **HD 1280 x 720**, lote **16**, o hasta **262.144 luces**.
   Comparar los cuatro metodos dentro de cada perfil, sin mezclar sus tasas.

El resultado C06 recibido y verificado contiene 174,8483 FPS con reglas y
153,5365 con datos CPU (+13,8806 %), con ahorro activo. Fueron doce bloques,
9.896 cuadros declarados terminados, 524.288 valores comparados con Python y
dos pares de imagenes byte a byte sin diferencias. El ZIP tiene SHA-256
`e1c878d6738b67d844f8960cb6b89b131930dfdfc062527a0ec21a0cc6fdef9f`.
Es una medicion diferente de la captura anterior de 197,7 / 162,9 FPS.
C08 cambia el protocolo y agrega metodos; repetir la carga no convierte
el contraste C06/C08 en una ablacion que aisle exclusivamente el ahorro.

## Cuatro rutas propias

| Ruta | Trabajo por cuadro | Datos de escena enviados a la API |
| --- | --- | ---: |
| Datos CPU | CPU expande 4 uint32 por luz y sube el buffer | 16*N + 16 B |
| Reglas | El vertex shader evalua la descripcion | 80 B |
| Reglas + cache | El mismo shader reutiliza el hash estable por identidad | 80 B, mas cache inicial |
| Datos GPU | Compute genera el estado completo, barrera y dibujo instanciado | 96 B |

La nueva ruta compacta separa `mixWord(id XOR seed)` de los valores que
dependen del tick y de las excepciones. Conserva un hash de 4 bytes por
identidad; no conserva sus posiciones y color completos en cada cuadro.
Su coste es **4*N bytes de cache activa** (262.144 B para 65.536 luces), mas
el coste de construirla y subirla al crear contexto o ampliar la carga.
La semilla es fija en este perfil; cambiarla requeriria invalidar la cache.
Esta cache es derivada: la autoridad XSC1 sigue siendo de 84 bytes.

La optimizacion evita recalcular esa mezcla estable en cada vertice, pero
introduce lecturas de memoria. Su ganancia depende del perfil y del chip:
se mide, no se presupone. Un resultado mas lento se conserva como tal.

**Datos GPU** evita la expansion por CPU y las subidas por luz. Despacha
compute a un anillo de buffers, aplica una barrera de visibilidad para
atributos y despues dibuja. Cada cuadro del lote tiene su buffer de 16*N bytes;
se reutilizan solamente cuando todos los fences del lote anterior han terminado.
El anillo ocupa 16*N*lote bytes: 8 MiB con 65.536 luces y lote 8. Evita la
dependencia de sobrescribir estado que aun lee el dibujo. Es una referencia mas exigente
para averiguar si las reglas compactas aportan frente a materializar en GPU.
Sigue siendo nuestra escena y nuestra implementacion, no una comparacion
contra la mejor alternativa de todos los motores.

Las cuatro rutas comparten vertex shader, triangulos, camara, fragment shader,
resolucion, estados ciclicos y excepciones exactas. Los shaders nuevos
conservan los mismos valores enteros y la salida del perfil C05.

Los bytes declarados son payload logico de escena a la API; no son trafico
fisico del bus, VRAM total ni consumo energetico. La app comparativa conserva
ambos buffers densos y el cache en todos los metodos: reserva 16*MAX bytes
directos en CPU, 32*MAX bytes de buffers densos GPU y 4*MAX de cache GPU,
ademas de dos framebuffers y costes del driver. MAX=262.144 es una carga
elegida para este instrumento, no un limite de la representacion o del chip.
Se agrega el anillo de 16*N*lote bytes, tambien retenido para todas las rutas
durante la campana. Se declaran por separado cache, buffers de comparacion y anillo.

## Protocolo sostenido

- Dos segundos de calentamiento por cada uno de los cuatro metodos.
- Treinta y dos bloques de al menos cuatro segundos, ocho por metodo:
  al menos **32 segundos medidos por metodo**, 128 segundos en conjunto.
- Dos rondas de dieciseis bloques. La segunda invierte el orden de la primera.
  Cada grupo de cuatro contiene todos los metodos y cada metodo tiene la
  misma posicion media en el orden de bloques. Se usa un orden Williams y su espejo.
- Cada cuadro tiene un fence y solo se cuenta cuando senala finalizacion.
  El lote 8 o 16 es comun a todos los metodos del ensayo.
- Los FPS agregados se recalculan como cuadros / suma de tiempo real.
  Se conservan bloques, ventanas completas y la cola parcial de cada bloque.
- Se guardan FPS agregados por ronda, mejor bloque y ultimos tres bloques.
  Elegir la mejor compacta dentro del ensayo no acredita un ganador universal.

El ensayo inicia desde un `onDrawFrame` con contexto listo y mantiene el
bucle completo dentro de ese callback, sin presentar entre cuadros ni dormir
a una tasa objetivo. El tiempo incluye preparacion CPU, envio, compute si
corresponde, instrumentacion y espera de fences. Lecturas de validacion,
cache inicial y ZIP quedan fuera de los bloques medidos y se informan.

El panel de UI lee snapshots cada 500 ms, con tiempo, etapa, operacion y
cuadros enviados/confirmados. El reloj solo muestra tiempo: no inventa avance
GPU ni FPS. Una espera prolongada queda visible. **Detener** guarda lo parcial;
**Guardar diagnostico** prepara un JSON, solicita detener y abre el selector
cuando el controlador devuelve el control. No termina llamadas nativas colgadas.

Antes y despues se compara la imagen de las otras tres rutas con Datos CPU:
**seis pares completos**. Tambien se contrastan todos los valores de compute
directo, compute con cache y el buffer real de Datos GPU con la referencia
CPU: **24*N palabras**, mas N hashes de la cache. El ZIP guarda cada imagen,
paquete XSC1, estado GPU, cache, shaders exactos y sus hashes.
Al finalizar se comprueban ademas todos los buffers reales del ultimo lote de
Datos GPU: **4*N*lote palabras**. Se guardan sus ticks y hashes, mas el estado
crudo y XSC1 de su ultima ranura. Esta lectura queda fuera de los tiempos.

Se registran ahorro, estado termico de Android, bateria, alimentacion y
**temperatura de bateria**, que no se etiqueta como temperatura GPU.
Si aparece ahorro o falta su estado en algun control, el resultado conserva
la evidencia pero no queda marcado como comparacion de rendimiento elegible.
El orden compensado reduce sesgos de deriva temporal; no garantiza condiciones
termicas constantes ni significacion estadistica con solo una campana.

Al terminar se selecciona para el visor la ruta compacta mas rapida **en ese
perfil**. El visor sigue la cadencia de pantalla y usa su propia resolucion;
esa seleccion no predice sus FPS visibles, los de otros perfiles o juegos.
El boton **Latencia (2 rutas)** conserva la referencia corta CPU/reglas a
512 x 512; se etiqueta en ms para distinguirla de la campana.

## Verificaciones y compilacion de C08

- Referencia escalar Python frente a Java: 42 estados, 8.184 palabras,
  dominios hasta 262.144, semillas, overflow y excepciones.
- `SustainedTest`: 18 controles de tasas ponderadas y acumulados.
- `MonitorTest`: 11 controles de tiempo, confirmaciones, espera y reinicio.
- `PlanTest`: 183 controles de cuatro metodos, dos rondas reflejadas,
  posiciones compensadas, cargas de 512/HD y lotes 8/16.
- `verify_host.py`: ejecuta los GLSL de produccion en llvmpipe CPU y
  verifica **12.032.024 palabras de estado**, **827.906 hashes**, doce escenas
  con cuatro rutas equivalentes y dos imagenes finales de lotes (**38 pares
  de imagenes**) y 96 fences. Incluye HD y 262.144 luces; prueba dos lotes
  consecutivos con anillos de 8 y 16 buffers, sin swap.

Estos controles no son una medicion fisica ni demuestran aceleracion en Mali.
El APK debe ejecutarse en el Samsung para obtener esa evidencia.

```sh
python3 android-graphics/verify_host.py
bash android-graphics/build.sh
```

El build usa Android SDK 35 y build-tools 35.0.0, javac, aapt2, d8, zipalign y
apksigner. Conserva firma, paquete, APK y validaciones en build/dist, con
`hardware_gpu_executed=false` para sus controles anfitriones.

C08 no cambia el nucleo C01, no integra automaticamente Ether/XI o RFS1, ni
ejecuta shaders arbitrarios. Lleva el principio de estado compacto a una
comparacion grafica propia mas exigente y lista para probarse en el dispositivo.

Referencias de API, sin copiar implementaciones:
- https://developer.android.com/reference/android/provider/Settings#ACTION_BATTERY_SAVER_SETTINGS
- https://developer.android.com/reference/android/os/PowerManager#isPowerSaveMode()
- https://wikis.khronos.org/opengl/GLAPI/glMemoryBarrier
- https://registry.khronos.org/OpenGL/specs/es/3.1/es_spec_3.1.pdf (seccion 7.11)

## Antecedente C05: Campo de luz

APK nativo con una escena animada propia y dos proyecciones del mismo estado:

- **Datos completos:** CPU calcula cuatro uint32 por luz en cada cuadro y
  actualiza un buffer de atributos de instancias.
- **Reglas + cambios:** GPU obtiene esos mismos cuatro valores desde una
  descripcion pequena y cuatro excepciones exactas.

Ambas rutas dibujan las mismas instancias, triangulos y fragmentos con el
**mismo programa grafico**. Solo cambia de donde provienen los cuatro valores.
Se usa OpenGL ES 3.1 como backend del sistema, sin motor externo ni codigo de
otros renderizadores. La implementacion de escena, pruebas y shaders es propia.

Este perfil lleva al render el principio de reglas, relaciones y excepciones
que se investigo con Ether y C04. Define un nuevo perfil especializado de
escena entera; **no es un puerto del decoder RFS1 ni un ejecutor de shaders
arbitrarios**, y no modifica el nucleo C01 ni integra una sesion Ether/XI.

## Uso en el telefono

1. En la version C05, instalar `GPU-VIRTUAL-C05-CAMPO-DE-LUZ.apk` y abrir **Campo de luz**.
2. Cambiar entre ruta compacta/completa y 4.096, 16.384 o 65.536 luces.
3. Pulsar **Comparar ahora** y mantener la app abierta durante la prueba.
4. Pulsar **Guardar prueba** y compartir `GPU-VIRTUAL-C05-RESULTADO.zip`.

El paquete es `x.gpu.visual`, distinto al de la prueba C03. No requiere red ni
permisos amplios de almacenamiento. El archivo se guarda mediante el selector
del sistema. Al salir de la escena durante una comparacion, se conserva un
informe incompleto identificado como no verificado.

Los tres conteos son cargas elegidas para este instrumento; no constituyen
un limite nuevo impuesto a Ether, RFS1 o C01. El dominio logico del perfil de
escena lo define el paquete de entrada; la demo reserva buffers para su carga
maxima de comparacion.

## Estado y equivalencia

`SceneState` es la autoridad de escena: conteo, tick, semilla y cuatro cambios.
Su paquete XSC1 ocupa 84 bytes: cabecera de cuatro bytes y 80 bytes de datos.
El perfil define una mezcla de bits, ondas triangulares periodicas y cambios
locales con aritmetica uint32. Cada identidad conserva posiciones xyz de diez
bits y una palabra de color/tamano. Java y GLSL conservan los mismos bits.

El estado compacto avanza al cambiar el tick y sus cuatro modificaciones. La
ruta completa materializa los resultados por identidad; la compacta los
calcula en el shader. La CPU no construye una tabla densa para enviar la ruta
compacta. El proceso de comparacion si conserva el buffer denso para alternar
ambas rutas, y lo materializa durante la verificacion.

Cada muestra de validacion conserva un paquete XSC1, ambas imagenes RGBA8
sin filtrar y todos los valores generados por un compute shader que comparte
las reglas del vertex shader. Se comparan los valores GPU con la proyeccion
CPU y todos los bytes de ambas imagenes. Asi, una diferencia oculta por
solapamiento de luces no puede pasar solo porque el pixel final coincida.

## Que miden los numeros

- **FPS app:** cadencia entre inicios de `onDrawFrame`, incluida la espera
  habitual entre cuadros. Es una aproximacion del ritmo de la aplicacion,
  sensible a VSync; no acredita cuando se ilumino fisicamente cada pixel.
- **Tiempo de comparacion:** preparacion CPU, envio GL y finalizacion con
  `glFinish`, a 512 por 512 pixeles. Se separan preparacion y envio/finalizacion.
  No es un contador de tiempo GPU puro ni incluye presentacion en pantalla.
- **Bytes de escena por cuadro:** datos suministrados a la API: `16*N+16`
  para la ruta completa y 80 para la compacta. Excluye el resto del estado de
  dibujo y el coste de las llamadas. No son contadores de trafico fisico ni
  medidas de RAM total del dispositivo.
- **Memoria:** los snapshots PSS son del proceso completo y no son un pico.
  El buffer denso y los dos framebuffers permanecen reservados en esta app
  comparativa, incluso al mostrar la ruta compacta.

Hay ocho pares de calentamiento y 48 pares medidos por conteo, alternando
que ruta se ejecuta primero y usando el mismo tick en cada pareja. Se guardan
todas las muestras, mediana y percentil 95 con interpolacion lineal entre
estadisticos de orden. Los conteos se recorren en orden
fijo; no hay estabilizacion termica ni promesa de significacion estadistica.
Lectura, verificacion y ZIP quedan fuera de los intervalos medidos.

La prueba completa valida nueve parejas de imagenes y 1.032.192 palabras de
estado GPU. Un perfil de dispositivo y driver conocido de hardware es
necesario para marcar la prueba fisica como superada. No se usan contadores
fisicos del silicio. Correctitud y rapidez son conclusiones distintas: una
ruta compacta mas lenta sigue siendo un resultado valido de la comparacion.

El punto de comparacion es la expansion CPU por cuadro frente a evaluacion
procedimental GPU. Los motores convencionales tambien pueden usar shaders
procedimentales e instancias. Esto no compara contra todos esos motores ni
demuestra una ventaja sobre la mejor alternativa existente.

El ahorro de 93,88 % de C04 describe sus capturas. No se usa como un factor
de FPS, ni como una medida de VRAM de esta escena, ni como una mejora
automatica de Wild Rift u otros juegos. Los tiempos fisicos requieren abrir
este APK en el telefono y revisar el resultado exportado.

## Verificaciones y construccion

```sh
python3 android-graphics/make_fixtures.py
mkdir -p android-graphics/build/host
java com.sun.tools.javac.Main -d android-graphics/build/host \
  android-graphics/src/x/gpu/visual/SceneState.java android-graphics/host/SceneTest.java
java -cp android-graphics/build/host SceneTest android-graphics/build/scene-fixtures.dat
python3 android-graphics/verify_host.py
bash android-graphics/build.sh
```

La referencia Python contrasta 35 estados con diferentes dominios, semillas,
ticks, desbordamientos e identidades modificadas: 6.176 palabras contra Java.
El control GL software ejecuta los GLSL de produccion y contrasta 690.184
palabras con Python y ocho parejas de imagenes completas a 384 por 384.
Ambos controles declaran `hardware_gpu_executed=false`.

El build usa Android SDK 35 y build-tools 35.0.0, aapt2, javac, d8, zipalign y
apksigner, con firma de prueba. La compilacion e integridad del APK no se
presentan como una ejecucion Android o una medicion de FPS del telefono.

Referencias de las interfaces anfitrionas:

- https://developer.android.com/reference/android/opengl/GLES30
- https://developer.android.com/reference/android/opengl/GLSurfaceView.Renderer

