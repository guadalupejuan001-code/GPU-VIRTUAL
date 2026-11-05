# GPU VIRTUAL — C01

Primer experimento de una representacion compacta del **estado de ejecucion**
de una GPU virtual. El codigo se escribio para este proyecto, desde cero, con
C++17 y su biblioteca estandar. El ejecutor corre sobre la CPU anfitriona.

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

Pendientes para ampliar la GPU virtual: operaciones de bits, comparaciones
generales, punto flotante con redondeo definido, memoria compartida, atomicos,
sincronizacion entre grupos, multiples despachos y graficos/texturas.
Las capacidades no admitidas se rechazan; no se sustituyen silenciosamente
por un simulador externo.

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

El siguiente ciclo debe ampliar las operaciones admitidas y verificar sus
resultados sobre el estado compacto antes de integrar Ether o la GPU fisica.

## C02: observar antes de elegir otra representacion

[Experimento C02](experiments/c02/README.md): shaders propios ejecutados mediante
el driver OpenGL del sistema, capturas de sus variables y memoria seleccionada,
referencia escalar independiente y filtros con reconstruccion exacta. Examina
datos, elecciones de control, direcciones, memoria compartida y orden float32.

La [evidencia inicial de C02](evidence/c02/README.md) conserva las capturas completas
y los contraejemplos. El anfitrion disponible usa llvmpipe sobre CPU. Las pruebas
en GPU fisica, el estado completo de dispositivo y la minimalidad general
siguen pendientes. C02 no modifica el nucleo C01.

## C03: llevar la prueba al dispositivo fisico

[Prueba Android](android-probe/README.md): APK que ejecuta los vectores de C02
mediante compute shaders OpenGL ES en el telefono, identifica su driver,
rechaza software/emuladores y exporta capturas e informe. El workflow C03
construye y valida el APK; el resultado fisico requiere ejecutarlo en el
dispositivo y revisar el archivo exportado.
