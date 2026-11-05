# C03: prueba en una GPU fisica Android

Esta app ejecuta los vectores de C02 en el driver OpenGL ES del dispositivo.
Exige ES 3.1, rechaza renderizadores software conocidos y perfiles de emulador,
y exige identificar un driver de hardware conocido. No sustituye un resultado
fisico por la referencia CPU ni marca una compilacion como ejecucion fisica.

El usuario pulsa **Ejecutar prueba en GPU**, espera el resultado y pulsa
**Guardar resultado**. El ZIP contiene report.json, entradas, shaders ES exactos,
trazas leidas de los buffers, salidas y estados filtrados. Se puede compartir
ese ZIP para verificar la ejecucion. No requiere red ni permisos de archivos
amplios; el usuario elige el destino mediante el selector del sistema.

Se comparan todos los bits de las capturas y salidas con los vectores de
referencia. Los filtros Java implementan el mismo formato C02 y se verifican
contra sus columnas y estados Python antes de generar el APK. Los tamaños y
grupos del ensayo son datos del experimento, no limites de una GPU virtual.

La version movil fuerza las dos asociaciones de suma float32 con **dos
despachos** e intermedios almacenados, separados por una barrera de memoria.
Esto mantiene el redondeo de cada suma sin exigir la extension `precise` en
GLSL ES 3.1. El informe declara esa diferencia respecto al programa desktop.

La evidencia de hardware se basa en el perfil Android, vendor/renderer del
driver, ejecucion de compute y lectura exacta de buffers. No es una captura de
registros fisicos ni de contadores de utilizacion del silicio. Un driver sin
clasificar produce un resultado no verificado.

## Construir

El workflow C03 usa el Android SDK del runner: plataforma 35 y build-tools
35.0.0. Se utilizan aapt2, javac, d8, zipalign y apksigner; no hay motor externo
ni dependencias de aplicaciones de terceros.

```sh
bash android-probe/build.sh
```

El APK lleva una firma de prueba. `validation.json` informa construccion,
firma, integridad y controles del codec; mantiene `physical_gpu_executed=false`
porque la ejecucion del telefono es un paso separado.
