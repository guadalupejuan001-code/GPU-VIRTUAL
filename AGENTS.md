# GPU VIRTUAL

Este repositorio contiene el proyecto propio de X para representar y ejecutar
el estado logico de una GPU mediante reglas compactas, relaciones y detalles.

## Base oficial para futuras actualizaciones

- La base oficial vigente parte de **C12**. Leer `VERSION` y `GPU_BASELINE.json`
  antes de modificar o integrar el proyecto.
- Crear las siguientes ramas desde `main` actualizado, conservando la ascendencia
  del commit `84d015c6897035d7408db32e3f7ee1f7cb51f4f2`. No empezar otra GPU desde
  una rama C01/C10/C11 anterior a esa base ni sustituir el nucleo C12 al integrar.
- `baseline/c12` es una referencia fija del punto de partida; no moverla ni
  reescribirla. Los nuevos ciclos avanzan en ramas derivadas de `main`.
- Mantener juntos el ejecutor C12 y el transporte compacto de REgraphics con
  ByteBuffer de solo lectura. Los consumidores deben fijar una revision concreta.
- Conservar la lectura y continuacion de checkpoints C11/C12 y los perfiles
  `.xgpu 1/2/3`. Los cambios de formato o semantica deben versionarse explicitamente.
- Al modificar el ejecutor, comprobar C01, C11 y C12 con las pruebas relevantes.
  Avanzar `VERSION` explicitamente al cerrar un nuevo ciclo; el origen C12 y su
  referencia historica se conservan. La CI comprueba la ascendencia de la rama.

## Reglas del nucleo

- Mantener la implementacion del nucleo propia. Las referencias conceptuales
  pueden documentarse; no incorporar codigo fuente de motores o simuladores
  externos. El compilador, la biblioteca estandar y las herramientas de CI son
  herramientas anfitrionas.
- Conservar cada diferencia que pueda alterar un resultado observable.
- Operar sobre la representacion compacta cuando la operacion lo permita.
  Informar cualquier expansion necesaria y su coste.
- C01 tiene semantica uint32 modulo 2^32, regiones por indice y detalles
  exactos. Ampliar la semantica explicitamente y preservar los ejemplos.
- Verificar equivalencia con una referencia independiente. Al cambiar el
  nucleo, ejecutar `bash build.sh` y los controles relevantes.
- La evidencia original permanece en `evidence/baseline-c01/`; los resultados
  nuevos se escriben en `evidence/current/`.
- Distinguir estado serializado, salida materializada y memoria del proceso.
- Declarar por separado ejecucion anfitriona CPU, integracion Ether/XI y
  ejecucion en GPU fisica. Mantener el alcance comprobado de cada resultado.
