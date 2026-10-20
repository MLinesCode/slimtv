# Prompt para un agente de IA

Abre [Claude Code](https://claude.com/claude-code) (o cualquier agente de IA con acceso a la terminal)
**dentro de la carpeta de este repositorio** y pega el siguiente texto. Cambia la IP por la de tu TV.
Antes de empezar, activa la *Depuración USB* en la TV (ver el [README](README.md#1-prepara-la-tv-una-sola-vez)).

---

```text
Quiero acelerar mi Android TV, que tiene poca RAM, usando el proyecto SlimTV de esta carpeta.
La IP de mi TV es 192.168.X.X. Lee README.md, docs/PROCESO.md y scripts/ antes de empezar.

Sigue estas fases y detente a preguntarme en cada punto marcado con [PREGUNTA]:

1. PREPARACIÓN
   - Verifica que tengo adb (y Android SDK + JDK 11+ si hay que compilar). Si falta algo,
     dime cómo instalarlo; no descargues nada sin preguntarme.
   - Corre scripts/connect.ps1. Si sale "unauthorized", pídeme aceptar el aviso en la TV.

2. DIAGNÓSTICO
   - Corre scripts/diagnose.ps1 (esto guarda el respaldo del estado original).
   - Explícame en lenguaje simple: modelo, RAM total y disponible, y qué procesos consumen más.

3. LISTA DE LIMPIEZA
   - Busca en examples/ una lista para mi modelo o uno parecido. Si no existe, crea
     examples/<marca-modelo>/debloat.txt con este formato (paquete + comentario):
       · Grupo seguro: publicidad, recomendaciones, demos, registro de producto, telemetría.
       · Grupo opcional: apps que quizá uso (asistente, Chromecast, YouTube Music...).
       · Sección "NO TOCAR": sintonizador, HDMI, control remoto, Play Services, menús de fábrica.
   - Nunca propongas paquetes de la lista $Protected de scripts/common.ps1.
   - [PREGUNTA] Muéstrame la lista y pregúntame qué apps uso antes de aplicar nada.
   - Corre debloat.ps1 primero con -DryRun y luego de verdad.

4. LAUNCHER
   - [PREGUNTA] Pregúntame qué 5 apps quiero en la barra y ponlas en
     launcher/res/values/config.xml (usa los nombres de paquete que mostró diagnose.ps1).
   - Corre scripts/build.ps1 y luego scripts/install.ps1, que comprueba que SlimTV abre
     antes de desactivar el launcher de fábrica.
   - Toma capturas (adb shell screencap) y revísalas: barra, cuadrícula (DPAD_DOWN) y botón Entradas.

5. SEGUNDO PLANO Y AJUSTES
   - Reinicia la TV (adb reboot), espera a que arranque y revisa qué apps se cargaron solas.
   - [PREGUNTA] Propón restringirlas con scripts/restrict-background.ps1.
   - Corre scripts/tweaks.ps1 para las animaciones.

6. VERIFICACIÓN Y REPORTE
   - Comprueba que tras reiniciar arranca en SlimTV, que las apps de la barra abren y que Home regresa.
   - Muéstrame una tabla de RAM disponible antes/después y cuánto usaba cada launcher.
   - Escribe examples/<marca-modelo>/README.md con los resultados, igual que el de Hisense.
   - Recuérdame que scripts/restore.ps1 deshace todo.

Reglas: nada de root ni ROMs; solo cambios reversibles (pm disable-user, appops, settings).
No desinstales apps del sistema. Si un paso falla, explícame qué pasó antes de intentar otra cosa.
```

---

### Consejos
- **Ten la TV encendida y a la vista:** tienes que aceptar el aviso de depuración y el agente
  puede pedirte que confirmes lo que se ve en pantalla.
- Si tu modelo funcionó, comparte la carpeta `examples/<tu-modelo>/` con un Pull Request
  (ver [CONTRIBUTING.md](CONTRIBUTING.md)).
