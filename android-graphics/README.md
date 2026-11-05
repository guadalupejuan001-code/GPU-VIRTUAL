# C05: Campo de luz, una demostracion grafica utilizable

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

1. Instalar `GPU-VIRTUAL-C05-CAMPO-DE-LUZ.apk` y abrir **Campo de luz**.
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
