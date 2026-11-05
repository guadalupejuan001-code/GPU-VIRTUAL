# C02: evidencia observada

Backend observado: Mesa llvmpipe, sobre CPU; no hay GPU fisica verificada.
Las capturas son variables de shaders compilados y ejecutados por OpenGL,
no datos generados por C01 ni una captura simultanea del dispositivo completo.

Se ejecutaron 24 despachos: doce casos con dominio 1024/grupo 64 y doce con
257/grupo 16. Se compararon 596.680 valores capturados y de salida con una
referencia independiente. Todos coincidieron y todos los estados filtrados
reconstruyeron exactamente sus capturas.

## Diferencias que se deben conservar

- Los mismos valores en otro orden cambiaron 1.023 de 1.024 salidas.
- Conservar la misma suma por grupo no basto: el acceso a vecinos produjo
  1.009 salidas diferentes tras permutar valores dentro de cada grupo.
- Todas las invocaciones empezaban con el mismo campo c y la misma salida
  inicial; sus otros datos produjeron 368 valores finales distintos.
- Cambiar la asociacion de sumas float32 cambio 768 de 1.024 resultados.
- Publicar la salida cambio memoria aunque los otros siete campos observados
  conservaran su valor. El punto de ejecucion y la memoria tambien cuentan.

## Filtrado exacto de las capturas del ensayo 1024

Los tamanos suman todos los puntos de captura de cada caso. Son bytes de las
variables observadas, no RAM total, estado completo de GPU ni una medida de
rendimiento. El formato filtrado cuenta su cabecera, roles y puntos de captura.
Se elige entre seis filtros concretos; no se demuestra un minimo global.

| Caso | Valores capturados, bytes | Capturas filtradas, bytes |
| --- | ---: | ---: |
| uniform | 196,608 | 10,932 |
| ascending | 196,608 | 33,052 |
| shuffled_ascending | 196,608 | 104,952 |
| seeded_words | 196,608 | 104,840 |

La entrada seeded_words se conserva explicitamente en los campos donde los
filtros probados no encuentran una descripcion menor. No se declara aleatoria
irreducible: el analizador desconoce su generador y puede haber otros modelos.

## Reproducir y auditar

- `reference-1024/report.json` y `reference-257/report.json`: parametros, renderer,
  validaciones, filtros elegidos por campo y alcance.
- El paquete entregado `GPU-VIRTUAL-C02-CAPTURAS.zip` contiene los dos
  `captures.zip`: entradas, shader GLSL, traza completa, salida y estados
  filtrados, con inventario y hashes. Esos archivos binarios no se incorporan
  al historial Git. Cada ejecucion del workflow C02 tambien los adjunta
  junto con sus informes.
- `summary.json`: resumen y hashes de las herramientas usadas.

```sh
python3 -m zipfile -e GPU-VIRTUAL-C02-CAPTURAS.zip evidence/current
python3 experiments/c02/observe.py --replay evidence/current/C02/reference-1024/captures.zip
python3 experiments/c02/observe.py --replay evidence/current/C02/reference-257/captures.zip
```

Las pruebas en hardware, el analisis de ISA/registros fisicos y la ejecucion
sobre una nueva representacion siguen pendientes. Esta evidencia sirve para
investigar que relaciones aparecen y refutar filtros que pierden informacion.
