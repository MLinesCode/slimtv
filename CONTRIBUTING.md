# Contribuir a SlimTV

La forma más valiosa de ayudar es **agregar la lista de tu modelo de TV**. Cada modelo probado
le ahorra horas a la siguiente persona con la misma TV.

## Agregar un modelo

1. Sigue el [README](README.md) o usa el [prompt de IA](PROMPT.md) en tu TV.
2. Crea `examples/<marca>-<modelo>/` (en minúsculas y con guiones, por ejemplo `tcl-32s5400a`) con:
   - **`debloat.txt`:** un paquete por línea con un comentario de qué es. Separa *seguros*,
     *opcionales* (comentados con `#`) y una sección **NO TOCAR** con lo que rompe la TV.
   - **`README.md`:** ficha (Android, RAM, resolución, launcher de fábrica), una tabla de RAM disponible
     antes/después y notas raras del modelo. Usa el de [Hisense](examples/hisense-2k-atv4/README.md) como plantilla.
3. Agrega tu modelo a la tabla de **Compatibilidad** del README.
4. Abre un Pull Request.

**Antes de enviar:** no incluyas tu carpeta `state/`, la IP de tu casa ni capturas con tu foto de perfil o tus datos.

## Reglas para las listas
- Solo cambios **reversibles** (`pm disable-user`). Nada de `pm uninstall` en apps del sistema.
- Si no sabes qué hace un paquete, déjalo fuera o márcalo como opcional.
- Nunca agregues paquetes de la lista `$Protected` de [`scripts/common.ps1`](scripts/common.ps1).

## Código
- **Launcher:** Java puro, sin dependencias. Antes de agregar algo, piensa en cuánta RAM cuesta:
  el objetivo es seguir por debajo de ~25 MB en uso y ~50 KB de APK.
- **Scripts:** deben funcionar en Windows PowerShell 5.1 y en `pwsh` 7 (Windows/Mac/Linux).
  Guárdalos en UTF-8 con BOM para que los acentos se vean bien en 5.1.
- Prueba en una TV real antes de abrir el PR y cuenta en él qué probaste.

## Reportar problemas
Abre un *issue* con el modelo de TV, la versión de Android, el comando que corriste y la salida completa.
