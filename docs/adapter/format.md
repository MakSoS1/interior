# Portable Interior AR package v1

ZIP с manifest.json в корне и встроенными GLB. Реальный пример создаётся Blender в CI (`two-groups.interior.zip`).

```json
{
  "format": "interior-ar",
  "version": 1,
  "units": "m",
  "name": "Dining set",
  "assets": [
    {"id": "chair", "name": "Chair", "file": "models/chair.glb", "origin": "floor"},
    {"id": "table", "name": "Table", "file": "models/table.glb", "origin": "floor"}
  ]
}
```

Необязательное поле sha256 — 64 hex-символа. Наши экспортёры всегда добавляют вычисленный хеш. Уникальные id и пути обязательны. origin=floor переносит bbox-min в точку опоры, authored сохраняет исходное начало. glTF: метры, +Y вверх, предполагаемая лицевая сторона +Z. Импорт не меняет единицы молча.
