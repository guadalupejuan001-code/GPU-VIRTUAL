# GPU VIRTUAL

Este repositorio contiene el proyecto propio de X para representar y ejecutar
el estado logico de una GPU mediante reglas compactas, relaciones y detalles.

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
