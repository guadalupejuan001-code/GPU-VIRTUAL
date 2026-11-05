# C06: FPS sostenidos fuera de pantalla

Instalar **GPU-VIRTUAL-C06-FPS-SOSTENIDOS.apk**, elegir la cantidad de luces y
pulsar **Medir FPS · 60 segundos**. La vista queda fija mientras la GPU sigue
dibujando en framebuffers de 512 por 512. No se presenta cada cuadro ni se
espera al refresco entre cuadros dentro del bucle de medicion. Al terminar,
**Guardar prueba** exporta `GPU-VIRTUAL-C06-RESULTADO.zip`.

La app C06 usa el paquete `x.gpu.sustained` y puede coexistir con la C05.
Conserva su escena y comparacion de latencia como referencia. La prueba nueva
usa el mismo perfil de reglas y las mismas excepciones; no integra un motor
ajeno, RFS1, el nucleo C01 ni Ether.

## Medicion sostenida

- Cuatro segundos nominales de calentamiento: dos por metodo.
- Doce bloques de al menos cinco segundos, en orden ABBA/BAAB/ABBA.
  Cada metodo recibe al menos treinta segundos de tiempo medido.
- Cada cuadro enviado tiene un `GLsync`. Solo se cuenta al confirmar que
  ese fence ha terminado. Hay hasta ocho cuadros en el lote de trabajo.
- La tasa sostenida es **cuadros confirmados / suma de tiempos reales de sus
  bloques**. No se usa `1000 / tiempo de un cuadro`, ni se promedian tasas
  de intervalos desiguales, ni se usa la frecuencia de la pantalla.
- Se guardan todos los bloques, ventanas de aproximadamente un segundo,
  mejor bloque de cinco segundos y tasa de los ultimos tres bloques por metodo.
- La duracion total incluye ademas calentamiento, verificacion y guardado.
  Se puede detener desde la app; salir tambien cancela y guarda lo parcial.

El bucle completo se ejecuta dentro de una sola tarea del hilo GL, fuera del
callback repetido de cuadros visibles. Entre sus cuadros no llama a
`eglSwapBuffers`, ni solicita otro cuadro de pantalla, ni duerme a un FPS
objetivo. La UI puede actualizar el progreso; ese coste de instrumentacion y
los controles del driver/sistema siguen formando parte del entorno medido.

Antes y despues de medir se verifican ambos framebuffers y todos los valores
de la escena GPU contra la proyeccion CPU. El ZIP conserva esos datos,
paquetes XSC1, shaders exactos, bloques y ventanas de tiempo. La lectura y el
guardado de capturas estan fuera de los intervalos medidos.

Los cuadros usan la misma secuencia ciclica de 64 estados por metodo. La
animacion del ensayo avanza por cuadro calculado, no por un reloj de pantalla
a 60 pasos. El numero de cuadros de cada metodo puede diferir porque el tiempo
de exposicion, la carga y la resolucion son los controles compartidos.

Los FPS de este ensayo representan **rendimiento fuera de pantalla del perfil
elegido bajo las condiciones actuales del equipo**. No son FPS visibles,
un maximo universal del chip o un resultado transferible a todos los juegos.
El mejor bloque tampoco sustituye la tasa sostenida de toda la prueba.
Se informa por separado de la tasa del visor y los Hz que comunica Android.
Tambien se registran ahorro de energia y estado termico cuando la API los ofrece.
No se fuerzan modos de pantalla, frecuencias de reloj ni politicas termicas.

La comparacion sigue siendo expansion CPU por cuadro frente a evaluacion
procedimental GPU. La carga es compartida, con bloques intercalados; no son
treinta segundos consecutivos aislados por metodo ni una estabilizacion
termica garantizada. Preparacion CPU, llamadas GL y espera de fences entran
en el tiempo real. El buffer denso se conserva en ambos metodos para comparar.

## Verificacion de C06

`SustainedTest` contrasta tasas ponderadas, bloques desiguales, ausencia de un
tope de 60, estados vacios y rechazos sin alterar los acumulados. Son 18
comprobaciones anfitrionas. `verify_host.py` verifica ademas 24 fences GL
reales sobre llvmpipe CPU, incluidos ocho dibujos en un lote sin presentacion.
Se mantienen las 690.184 palabras y ocho parejas de imagenes del perfil C05.
Estas verificaciones y la construccion Android no se declaran ejecucion fisica.

El control del driver usa `glFenceSync`, `glClientWaitSync` y `glDeleteSync` de
OpenGL ES. Cada cuadro confirmado sigue teniendo el trabajo de rasterizacion
de la escena. No se cuentan solo llamadas de envio ni cuadros descartados por
el propio programa. El driver conserva sus optimizaciones y costes internos.

La compilacion sigue usando `bash android-graphics/build.sh` y conserva sus
validaciones en `android-graphics/build/dist/`. El APK nuevo lleva firma de
prueba y requiere ejecutarse en el telefono para producir FPS fisicos.

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
