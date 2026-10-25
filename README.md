# DonghuaFlix para CloudStream

Extensión híbrida (arquitectura C): usa los índices ligeros publicados por
DonghuaFlix (`public/data` del repositorio) como catálogo principal y consulta
las páginas de origen como red de seguridad cuando el detalle no está publicado.

## Catálogos
Donghua Life, DonghuaSub, Doramas, Dramas & Series YouTube, YouTube Films y
PelisPedia (los 6 definidos en `build-master-index.js`).

## Origen de datos
La web desplegada (donghuaflix.pages.dev) no sirve `/data` (la SPA responde
HTML). El proveedor lee los JSON en este orden:
1. raw.githubusercontent.com/Aneitor33/donghuaflix/main/public/data
2. cdn.jsdelivr.net/gh/Aneitor33/donghuaflix@main/public/data
3. donghuaflix.pages.dev/data (por si el despliegue los sirve en el futuro)

## Publicación
1. Crea el repositorio `donghuaflix-cloudstream` y sube estos archivos a `main`.
2. Settings → Actions → General → Workflow permissions: **Read and write**.
3. Ejecuta el workflow `Build CloudStream plugin`.
4. En CloudStream añade:
   https://raw.githubusercontent.com/TU_USUARIO/donghuaflix-cloudstream/builds/repo.json

## Compilación local
Requiere JDK 17 y Gradle 8.9+ instalados:

    gradle DonghuaFlix:make makePluginsJson

El `.cs3` se genera en `DonghuaFlix/build/outputs/`.

## Notas y límites
- Los detalles se leen de `catalog-<id>-details/shard-N.json` escaneando shards
  en lotes de 4 (máx. 120). Si no aparecen, se recurre a la fuente original.
- El esquema interno de los shards se parsea de forma defensiva (objeto por
  slug o array con campos `i`/`s`/`id`). Si DonghuaFlix cambia el formato,
  actualiza `detailIn()` en `DonghuaFlixProvider.kt`.
- Si la compilación falla por la firna de `getMainPage`, cambia
  `request: MainPageRequest` por `request: MainPageRequest?` y usa `request?.data`.
- No elude DRM, paywalls ni restricciones; úsala con contenido autorizado.
