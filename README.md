# GPU VIRTUAL

Proyecto propio de representacion compacta del **estado de ejecucion** de una
GPU virtual. C01 conserva el nucleo original; [C11](docs/C11-ejecucion-estados.md)
lo reutiliza para evolucionar juntos registros, control por invocacion, memoria,
barreras y una cola de despachos dependientes. El codigo del nucleo se escribio
desde cero con C++17 y su biblioteca estandar. El ejecutor corre sobre la CPU
anfitriona.

C11 guarda y recupera el estado logico completo de su perfil uint32, incluidos
programas, mascaras, memoria y planificador. No es una captura de todos los
estados internos de un chip fisico, ni una integracion automatica en el juego.
Los ciclos graficos C02-C10 siguen teniendo el alcance descrito mas abajo.

El principio recuperado de Ether es mantener una autoridad de estado descrita
por reglas, relaciones y diferencias; transformar esa descripcion y obtener
valores concretos mediante consultas. C01 prueba ese principio en registros
de computo paralelo. La integracion con la sesion real de Ether/XI sigue
pendiente: este paquete no modifica los nucleos anteriores.

## El instrumento y su estado

La analogia de un instrumento guia la construccion: una estructura admite
modos; una entrada modifica su configuracion; una observacion produce una
salida. C01 concreta esa idea con modos **aritmeticos**, seleccionados para
conservar exactamente los bits de las operaciones admitidas. No simula un
instrumento acustico ni usa oscilaciones fisicas como procesador.

| Elemento del instrumento | Definicion ejecutable en C01 |
| --- | --- |
| Dominio | Identidades `i` de las ejecuciones logicas. |
| Modos | Funciones `1, i, i^2, ...` dentro de una region de identidades. |
| Configuracion | Coeficientes de esos modos, regiones y detalles individuales. |
| Accion | Instruccion que transforma esa representacion. |
| Lectura | Consulta del registro/salida en una identidad o intervalo. |
| Continuidad | Programa, posicion de ejecucion y estado se guardan juntos. |

Un registro dentro de una region se define por:

`R(i) = c0 + c1*i + ... + cd*i^d (mod 2^32)`.

Un detalle sustituye el valor de una identidad concreta. Diferentes regiones
pueden tener modos distintos. La representacion es suficiente para continuar
el programa dentro del perfil admitido; no se afirma que sea el minimo global
entre todas las representaciones posibles.

## Operar directamente sobre el estado

Para `R(i)=3*i+7`, sumar cinco transforma los coeficientes a `[12,3]`.
Multiplicar ese registro por si mismo produce `[144,72,9]`.
El ejecutor efectua aritmetica sobre coeficientes y detalles; no recorre todas
las identidades para realizar esas dos actualizaciones.

Cuando una identidad tiene un detalle, su valor real participa en la operacion.
Las diferencias se conservan. Solo se elimina un detalle que vuelve a ser
exactamente igual al valor de la regla base. Las regiones adyacentes se unen
solo cuando sus coeficientes son iguales. No se descartan terminos no nulos.

Una seleccion `i < corte` conserva regiones de las dos entradas y sus detalles.
Esto define una seleccion de valores; no implementa todavia saltos generales
con contadores de programa diferentes por invocacion.

## Perfil propio `.xgpu`, version 1

El parser y sus instrucciones son parte de este desarrollo. `.xgpu` no declara
compatibilidad automatica con los programas XI previos.

| Instruccion | Efecto |
| --- | --- |
| `xgpu 1` | Cabecera del perfil. |
| `dominio N` | Define las identidades `0..N-1`. |
| `indice r0` | `r0(i)=i`. |
| `constante r1 V` | `r1(i)=V`. |
| `suma r2 r0 r1` | Suma exacta uint32 de ambos registros. |
| `producto r2 r0 r1` | Producto exacto uint32 de ambos registros. |
| `detalle r2 I V` | Sustituye el valor de `r2` en la identidad `I`. |
| `elige r2 C r0 r1` | Elige `r0` para `i<C`; `r1` para el resto. |
| `salida r2` | Publica el valor actual del registro como salida inmutable. |
| `fin` | Termina el programa. |

Hay ocho registros, `r0..r7`. La suma y el producto conservan los 32 bits
inferiores del resultado; el desbordamiento tiene semantica modular explicita.
La lectura no modifica la revision del estado. Una instruccion que supera un
presupuesto deja intactos los registros, el contador y la revision anteriores.
La ejecucion completa no es una transaccion: los pasos anteriores validos se
conservan aunque uno posterior falle.

## Construir y reproducir

Requisitos: compilador C++17; Python 3 solo para orquestar las medidas.

```sh
bash build.sh
python3 measure.py
./build/gpu_c01 run examples/primer-calculo.xgpu evidence/current/resultado-demo.xgs
./build/gpu_c01 resume evidence/current/checkpoint-demo.xgs
```

El ejemplo obtiene una salida para 1.048.576 identidades. Antes del ultimo
detalle, la regla es `3*i+12` para la primera mitad y `(3*i+12)^2` para la
segunda, siempre modulo `2^32`. La ultima identidad tiene resultado 16 porque
su valor anterior se sustituyo explicitamente. Esta diferencia se conserva.

`evidence/current/checkpoint-demo.xgs` se guarda a mitad del programa; reanudarlo
reproduce el mismo estado y salida finales que ejecutar el programa completo.
Los snapshots contienen programa, dominio, posicion, revision, campos,
asignacion de registros y salida. Se preservan los campos compartidos por
referencias internas, sin depender de direcciones de memoria del proceso.

## Evidencia y limites de las medidas

`evidence/current/correctness.json`: comprobaciones de equivalencia elemento a elemento,
160 programas aleatorios, desbordamiento, seleccion, alias de salida,
reanudacion y rechazos por presupuestos.

`evidence/current/measurements.json`: cinco repeticiones por backend y tamano, con orden
alternado, informacion de compilacion, hashes del codigo y salida completa.
Se separan preparacion, ejecucion y materializacion. El checksum y la
serializacion del snapshot quedan fuera del tiempo total declarado.

La referencia es otro interprete **sobre CPU**, escrito para este experimento,
que ejecuta cada identidad explicitamente y mantiene arreglos de registros.
No es una comparacion con una GPU fisica ni con un compilador GPU optimizado.
Los resultados del ejemplo no acreditan una mejora general en juegos o FPS.

Se distinguen tres cantidades:

1. Bytes del snapshot completo: tamano serializado del estado logico.
2. Bytes de salida completa: valores concretos solicitados por el consumidor.
3. Pico RSS nativo: `VmHWM` de `/proc/self/status`, incluyendo ejecutor,
   estructuras, temporales y salida. Se conserva tambien `ru_maxrss` bruto,
   que puede incluir un maximo heredado del lanzador. Los bytes de snapshot
   no equivalen a RAM fisica total.

Un caso con 1.024 valores independientes mantiene 1.024 detalles y aumenta el
estado. La prueba exige conservarlos y comprueba el crecimiento. No se asume
que un estado arbitrario resulte compacto.

| Presupuesto C01 | Valor |
| --- | --- |
| Dominio logico | 1 a `2^32` identidades |
| Registros | 8 |
| Grado por region | 32 |
| Regiones por registro | 256 |
| Coeficientes por registro | 4.096 |
| Detalles por registro | 65.536 |
| Materializacion por consulta | 16.777.216 valores uint32 |

Pendientes del nucleo **C01**: operaciones de bits, comparaciones
generales, punto flotante con redondeo definido, memoria compartida, atomicos,
sincronizacion entre grupos, multiples despachos y graficos/texturas.
Las capacidades no admitidas se rechazan; no se sustituyen silenciosamente
por un simulador externo.

C11 incorpora operaciones de bits, comparaciones, saltos, memoria y atomicos en
un perfil ampliado explicito. No cambia la semantica ni el formato de C01.

Las pruebas adicionales de AddressSanitizer y UndefinedBehaviorSanitizer
pasaron. LeakSanitizer no pudo ejecutarse en el entorno trazado; esa comprobacion
no se declara superada. Su alcance figura en `evidence/current/sanitizers.json`.

## Procedencia

Los archivos de codigo de este paquete fueron escritos para la peticion de X.
No incorporan fuente de CUDA, Qiskit, motores graficos, bibliotecas de sintesis
ni simuladores externos. El compilador y la biblioteca estandar son las
herramientas anfitrionas. Se utiliza algebra convencional; la prioridad
historica de estas ideas no ha sido objeto de este ensayo.

Referencias conceptuales consultadas, sin copiar sus implementaciones:

- Contrato propio de Ether: https://github.com/guadalupejuan001-code/mundo-X/blob/main/docs/ether-architecture.md
- Julius O. Smith, *Physical Audio Signal Processing*, expansion modal:
  https://www.dsprelated.com/freebooks/pasp/Modal_Expansion.html
- Semantica de tipos y aritmetica entera de shaders, Khronos:
  https://docs.vulkan.org/glsl/latest/chapters/variables.html

La correspondencia con Ether es por ahora de diseno. La siguiente integracion
necesita una autoridad de sesion explicita y demostrar que sus modificaciones
producen los cambios de salida previstos, conservando resultados y recursos.

## Repositorio

Proyecto: https://github.com/guadalupejuan001-code/GPU-VIRTUAL

La evidencia original del primer ensayo se conserva sin alterar en
`evidence/baseline-c01/`. Las ejecuciones nuevas escriben en
`evidence/current/`, ignorado por Git. El workflow C01 compila, prueba,
reanuda el checkpoint, ejecuta sanitizadores y adjunta su evidencia.

El nucleo C01 conserva su perfil original. Los ciclos posteriores exploran
capturas y operaciones adicionales por separado, antes de integrarlas con Ether.

## C02: observar antes de elegir otra representacion

[Experimento C02](experiments/c02/README.md): shaders propios ejecutados mediante
el driver OpenGL del sistema, capturas de sus variables y memoria seleccionada,
referencia escalar independiente y filtros con reconstruccion exacta. Examina
datos, elecciones de control, direcciones, memoria compartida y orden float32.

La [evidencia inicial de C02](evidence/c02/README.md) conserva las capturas completas
y los contraejemplos. El anfitrion disponible usa llvmpipe sobre CPU. Las pruebas
en GPU fisica se realizaron despues mediante C03. El estado completo de
dispositivo y la minimalidad general siguen pendientes. C02 no modifica C01.

## C03: llevar la prueba al dispositivo fisico

[Prueba Android](android-probe/README.md): APK que ejecuta los vectores de C02
mediante compute shaders OpenGL ES en el telefono, identifica su driver,
rechaza software/emuladores y exporta capturas e informe. El workflow C03
construye y valida el APK; el resultado fisico requiere ejecutarlo en el
dispositivo y revisar el archivo exportado.

La captura recibida del Samsung SM-S166V identifica Mali-G68/ARM y paso la
verificacion independiente de 24 casos, 26 despachos y 596.680 valores.
Se reconstruyeron exactamente 118 estados de variables y memoria seleccionada.
No se capturaron registros fisicos ni el estado completo del dispositivo.

## C04: explotar relaciones y continuar sobre descripciones

[Experimento C04](experiments/c04/README.md): comparte columnas repetidas,
descubre relaciones afines, residuales, sumas y lecturas indexadas exactas.
El formato RFS1 conserva todas las identidades y cada dato necesario para
reconstruir las trazas, sin referencias externas a otros casos.

Sobre el ZIP fisico C03, los bytes de captura bajan de 2.325.288 crudos y
615.986 TFS2 a **142.273 RFS1**: 93,88 % menos que los datos crudos y 76,90 %
menos que los filtros anteriores. Estas cifras incluyen todos los esquemas,
mapas y operandos RFS1; excluyen la compresion ZIP y no son medidas de VRAM.

La continuacion propia agrega tres operaciones mediante 31 bytes de nodos
por caso y se comprueba contra 15.358 resultados escalares. Las consultas
individuales no expanden columnas completas; la verificacion si las expande.
El analisis y la continuacion C04 corren en CPU. No se declara aceleracion
frente a GPU convencional, nueva ejecucion fisica, ni integracion con C01/XI.

## C05: escena grafica utilizable en Android

[Campo de luz](android-graphics/README.md) convierte el principio en una escena
animada propia, con dos rutas sobre un mismo programa grafico: materializacion
CPU y envio de datos completos, o reglas y cambios evaluados por GPU. Permite
cambiar la carga, observar la cadencia de la app y comparar tiempos de cuadros
terminados a resolucion fija. Exporta imagenes, valores, paquetes y muestras.

Es un perfil especializado nuevo, sin puerto automatico de RFS1 ni integracion
con el nucleo C01 o Ether. Su correccion se verifica con referencia escalar,
Java y shaders en driver software; sus tiempos fisicos requieren ejecutar el
APK en el dispositivo. El ahorro de almacenamiento C04 no implica un aumento
proporcional ni automatico de FPS.

## C06: cuadros terminados por segundo, sin presentar cada uno

La [prueba sostenida Android](android-graphics/README.md) ejecuta un bucle de
render fuera de pantalla durante doce bloques de al menos cinco segundos,
seis por metodo. Un fence GPU confirma cada cuadro antes de contarlo.
Los FPS se calculan como cuadros terminados entre tiempo real acumulado,
sin derivarlos del contador del visor ni de la duracion de una sola muestra.

El APK distingue FPS visibles, Hz informados por Android, tasa sostenida de
calculo y mejor bloque. Conserva capturas de correccion antes y despues,
todos los bloques y ventanas de tiempo, con cancelacion y resultado parcial.
La compilacion y el control llvmpipe son pruebas CPU; los FPS del telefono
siguen requiriendo que se ejecute el nuevo APK fisicamente.

Una captura posterior del usuario muestra C06 completado en la Mali-G68:
197,7 FPS sostenidos con reglas y 162,9 con datos para 65.536 luces a 512 por
512 (+21,36 % de cuadros/s). Son valores de esa captura. El ZIP recibido
despues contiene otra medicion, verificada y detallada en C08.

## C07: progreso visible y arranque con contexto listo

La [medicion activa Android](android-graphics/README.md) inicia el ensayo desde
`onDrawFrame` y muestra un panel de progreso con reloj independiente del hilo
GL, etapa, ultima operacion y cuadros confirmados por fences. Un reloj que
avanza no se cuenta como avance GPU. Una espera prolongada se hace visible
sin fabricar FPS; se puede preparar un diagnostico y solicitar la detencion.

Conserva el protocolo sostenido de C06 y los mismos shaders. Se etiqueta la
comparacion corta por separado y se limpia el resultado anterior al iniciar.
El APK C07 puede coexistir con las versiones previas. Las pruebas anfitrionas
comprueban los contadores; el panel nuevo requiere ejecutarse en el telefono.
C06 ya completo el ensayo segun la nueva captura; C07 aclara su espera.

## C08: graficos compactos frente a expansion en CPU y GPU

La [campana grafica Android](android-graphics/README.md) agrega una ruta de
reglas con cache derivada de 4 bytes por identidad y una referencia que
materializa el estado en un anillo GPU antes de dibujar. Las cuatro rutas
comparten escena y salida. Permite 512 x 512 o HD 1280 x 720, lotes de 8/16
y cargas de hasta 262.144 luces, como perfiles de este instrumento.

Dos rondas reflejadas compensan el orden de los metodos: 32 bloques y al
menos 32 s medidos por metodo, con ventanas completas, controles de energia,
progreso y archivos de resultado unicos. Antes y despues se comprueban seis
pares de imagenes, 24*N palabras de estado GPU y N hashes de cache.
La mejor ruta compacta del perfil se selecciona al terminar para el visor.

El ZIP C06 recibido tiene SHA-256
`e1c878d6738b67d844f8960cb6b89b131930dfdfc062527a0ec21a0cc6fdef9f`.
Su revision independiente recalculo 174,8483 FPS con reglas y 153,5365 con
datos CPU (+13,8806 %), con ahorro activo, 524.288 valores correctos y dos
pares de imagenes exactos. Es otra medicion distinta a la captura previa.

En C08 los controles software verifican 12.032.024 palabras, 827.906 hashes y
38 pares de imagenes, con 96 fences. El APK nuevo requiere ejecutarse en el telefono para
medir si la cache ayuda y como se compara con materializar el estado en GPU.
No se declara un maximo universal, una mejora de RAM total o una integracion
automatica con otros proyectos. El coste de cache y expansion queda explicito.

## C08R: reparar el arranque antes de cargar funciones nuevas

La revision [C08R](android-graphics/README.md) responde al reporte de C08
detenido en «Abriendo la escena». Inicia con los shaders propios C06 ya
probados en Mali, prepara las cuatro rutas al pedirlas y da altura fija al
viewport con controles desplazables. La UI muestra etapa, error y tiempo;
el diagnostico se puede copiar sin esperar al hilo GL. El log recibido
posteriormente confirma el primer cuadro y localiza el fallo de las funciones
nuevas en compute, como se detalla en C08R2.

El primer cuadro se confirma antes de habilitar las pruebas. Trece controles
anfitriones verifican ese estado y el regreso al visor si falla una funcion
nueva. El control software conserva los 12.032.024 valores, contrasta 39
pares de imagenes e incluye 97 fences, ahora con un primer render C06 sin
crear buffers de estado ni compute. El log fisico posterior de C08R confirma
ese primer cuadro; las funciones nuevas requieren la correccion C08R2.

## C08R2: corregir la llamada desde un buffer readonly

El diagnostico del Samsung confirma que C08R abre el visor en unos 0,192 s.
Las funciones nuevas fallan al compilar compute: el shader pasaba un miembro
de buffer readonly directamente a una funcion, rechazado por el compilador
Mali en la linea 28. C08R2 lo copia a un uint local antes de la llamada,
manteniendo el buffer readonly, las cuatro rutas y su salida exacta.

Se conservan los controles de valores e imagenes y se agrega compilacion
independiente de los GLSL ES 3.10 de produccion con glslangValidator. La
correccion de compilacion y los FPS nuevos requieren verificar el APK en Mali.

## C11: continuar desde el estado de ejecucion conjunto

[Estado de ejecucion C11](docs/C11-ejecucion-estados.md) integra directamente
`Field` y `Mode` del nucleo C01 con PCs por invocacion, mascaras de ejecucion,
memoria global y compartida, atomicos, barreras con epocas y dependencias entre
despachos. El formato propio `.xgpu 2` admite saltos con etiquetas. Los programas
`.xgpu 1` se ejecutan tambien mediante la nueva autoridad.

`gpu_c11` puede avanzar un numero de pasos, inspeccionar el estado sin modificarlo
y reanudarlo desde otro proceso. Sus checkpoints conservan todos los campos del
perfil, el programa y los cursores necesarios; el guardado POSIX reemplaza el
archivo anterior solo despues de escribir y sincronizar el candidato.

La referencia escalar mantiene PCs y arreglos por invocacion, sin usar los campos
compactos para evolucionar. Contrasta todos los registros, memorias, salidas,
esperas, epocas y cursores en cada paso de las cargas pequenas. C01 conserva sus
pruebas originales. Ver comandos, presupuestos y limites en la documentacion C11.

