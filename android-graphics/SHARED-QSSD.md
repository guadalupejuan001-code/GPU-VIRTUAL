# Entrada compartida qSSD

`CompactTriangleStream.update(ByteBuffer, categories, passes)` consume una vista
de geometría XFG4 sin convertirla primero a un arreglo Java. La sobrecarga
`byte[]` se conserva. La vista debe comenzar en posición cero y contener la
longitud exacta del paquete; la lectura usa little endian explícito.

El módulo conserva posiciones enteras, UV, iluminación binary32, clasificación,
orden alfa —incluidos empates—, vértices de 24 bytes y regiones sucias. La entrada
no se modifica. El adaptador sigue produciendo su buffer de vértices para la API
GPU; esa proyección y sus subidas no desaparecen por compartir la entrada.

La prueba independiente de fuente comprueba igualdad de vértices, posiciones de
lectura, retención, invalidación por pérdida de contexto y rechazos. La
integración de REVERSE Velocity añade trazas con toda su escena y compara cada
atributo con el adaptador original. La prueba Java se ejecuta en CPU anfitriona;
la ejecución del juego en GPU/emulador se informa por separado.
