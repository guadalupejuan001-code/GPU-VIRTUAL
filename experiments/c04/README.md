# C04: relaciones exactas y continuacion compacta

Implementacion propia en Python y biblioteca estandar. Conserva C01, C02 y
C03. No incorpora codigo de motores externos ni cambia el nucleo C++ de C01.

El ensayo busca redundancia entre campos y entre puntos de una misma captura:

- Una columna identica referencia una descripcion ya almacenada.
- Una relacion afin guarda `y = a*x+b (mod 2^32)`; se resuelven tambien
  diferencias pares, sin asumir una division modular que no exista.
- Una diferencia o XOR conserva un residual exacto con los filtros C02.
- Una lectura indexada guarda sus referencias a los valores y direcciones.
- Una suma o XOR de dos columnas anteriores guarda ambos operandos.

Las columnas se exploran por tamano dentro de cada punto, para que una
direccion compacta pueda describir una lectura del mismo punto. La busqueda
no conoce nombres de registros ni formulas del shader. Cada candidato se
comprueba contra todos los valores. Si no ahorra, conserva el literal exacto.
La seleccion es voraz entre estos candidatos, sin promesa de minimo global.

## Formato y coste

Cada archivo RFS1 es autosuficiente: incluye dominio, puntos, nombres,
posiciones de todas las columnas y nodos con operandos y datos. Los nodos solo
referencian otros anteriores, evitando ciclos. No hay referencias entre casos,
datos secretos del shader ni necesidad de volver a ejecutar el programa para
recuperar la captura. Las referencias conservan indices y orden originales.

Los tamanos comparados son los bytes RFS1 completos frente a los bytes TFS2
completos; no se usa la compresion ZIP para atribuir ahorro al nuevo formato.
Se incluyen dos ablaciones: el mismo contenedor con columnas independientes,
y el contenedor que solo reutiliza columnas identicas. Esto separa el ahorro
del esquema y la reutilizacion del ahorro de las relaciones nuevas.

## Consultar y continuar

`Graph.at(nodo, identidad)` evalua las dependencias necesarias para un valor,
sin construir las columnas completas. Los literales tambien permiten acceso
individual: un residual periodico puede requerir recorrer parte de su periodo,
y el trabajo depende del grafo. No se afirma que toda consulta sea O(1).
`materialize()` y `states()` expanden explicitamente para verificar o exportar.

`affine`, `binary` y `gather` agregan operaciones sobre las descripciones sin
recorrer el dominio al construirlas. La prueba continua cada salida capturada
con `gather((3*salida+5) XOR id, direccion)`; usa `id` cuando no hay columna de
direcciones. Se conservan todos los bits uint32, incluidos los patrones float
tratados como enteros. Esto no implementa nuevas operaciones de punto flotante.
Cada continuacion agrega 31 bytes de nodos. Su historial y datos previos siguen
incluidos; no se declaran 31 bytes como tamano de todo el estado.

La comprobacion enumera todas las salidas nuevas y expande las trazas para
compararlas. Esa expansion es un coste real de la verificacion. Las consultas
individuales y la construccion de las tres operaciones son rutas separadas.

## Ejecutar

Desde la raiz del repositorio:

```sh
python3 -m unittest discover -s experiments/c04 -p 'test_*.py' -v
python3 experiments/c04/explore.py --capture /ruta/GPU-FISICA-C03-RESULTADO.zip
```

El informe, las capturas RFS1 y el paquete autocontenido quedan en
`evidence/current/c04/`. El paquete conserva el ZIP C03 original, los datos
RFS1, las fuentes necesarias y el informe para repetir la comprobacion.
El modo `--synthetic` genera vectores CPU para CI y declara hardware=false.

Los resultados de hardware pertenecen a la ejecucion C03 del telefono. El
analisis C04, los codecs y la continuacion nueva se ejecutan en CPU anfitriona;
este ciclo no realiza nuevos despachos fisicos ni mide una aceleracion frente
a GPU convencional. No captura registros fisicos ni el estado completo de
un dispositivo. La integracion del nuevo formato con Ether/XI y el nucleo C01
queda fuera de este experimento.

## Resultado sobre la captura recibida de Mali-G68

Archivo C03 SHA-256:
`eb3712cb4fba4f843cc879b894319ca50f29f092cdc6af9883fd5d240c2b473c`.
Se verificaron los 581.322 valores de traza, sus 118 puntos/estados y 908
columnas. Los 15.358 valores publicados coinciden tambien con la referencia
escalar. La evidencia fisica original identifica SM-S166V, ARM/Mali-G68,
26 despachos y 596.680 valores totales comprobados.

| Representacion de las mismas capturas | Bytes |
| --- | ---: |
| Valores crudos | 2.325.288 |
| TFS2 C02/C03 | 615.986 |
| RFS1 con columnas independientes | 615.238 |
| RFS1 que solo reutiliza columnas identicas | 202.642 |
| RFS1 con relaciones adicionales | 142.273 |

El resultado equivale a 93,8815 % menos que los valores crudos y 76,9032 %
menos que TFS2. De las 908 columnas, 692 reutilizan otra descripcion y las
216 restantes usan 134 literales, 56 relaciones afines, 9 residuales XOR,
4 residuales por diferencia, 7 lecturas indexadas y 6 sumas de columnas.
Los literales incluyen sus propios filtros C02. Los datos irregulares
necesarios permanecen almacenados; el ahorro procede de las relaciones.

Las continuaciones verifican 15.358 resultados adicionales, agregando 744
bytes en total a los archivos anteriores: 31 por cada uno de los 24 casos.
Los archivos continuados ocupan por tanto 143.017 bytes. Se comprobaron ademas
consultas individuales de las columnas originales; todas conservaron sus bits.

El informe completo se genera en `evidence/current/c04/report.json`. El
paquete conserva el origen C03 y las fuentes para reproducir estas cifras.
