# GPU Virtual — autoridad de trabajo gráfico

Esta capa añade una descripción compacta y verificable de trabajo gráfico repetido sin reemplazar la GPU física.

## Contrato

`GraphicsWorkRelation` conserva conteo lógico, relación afín, periodo completo de identidad visible, revisión de dependencias y alcance de sombra. Un consumidor puede mantener esta relación hasta la GPU física en vez de materializar N transformaciones.

`exact_identity_period` sólo autoriza colapsar instancias cuando repiten **fase y la identidad visible completa**. Geometría igual con material distinto no se elimina.

`GraphicsWorkDelta` hace explícitas las dependencias que invalidan reutilización.

`pwr()` calcula PWR por una categoría concreta y rechaza un denominador cero. No define un porcentaje global mezclando objetos, bytes, fragmentos y tiempo.

## Alcance

Esto no prueba FPS ni descarga física por sí solo. XGamingLab debe medir A/B con la misma escena y reportar por separado objetos, triángulos, bytes, draw work y tiempos GPU físicos.
