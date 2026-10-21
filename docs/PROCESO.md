# Proceso técnico

Qué se hizo, en qué orden y con qué comando, tal como se aplicó en la TV de referencia
(Hisense HiSmart 2K ATV4). Sirve para entender los scripts o para hacerlo a mano.

## Árbol del repositorio

```
slimtv/
├── README.md · README.en.md        ← portada (español / inglés)
├── PROMPT.md                       ← prompt para replicarlo con un agente de IA
├── CONTRIBUTING.md                 ← cómo agregar tu modelo de TV
├── LICENSE                         ← MIT
├── docs/
│   ├── PROCESO.md                  ← este archivo
│   ├── IA.md                       ← cómo se hizo con IA
│   └── img/                        ← capturas
├── examples/
│   └── hisense-2k-atv4/
│       ├── debloat.txt             ← lista de limpieza comentada
│       └── README.md               ← resultados medidos y notas del modelo
├── scripts/                        ← PowerShell (Windows 5.1/7, y pwsh en Mac/Linux)
│   ├── common.ps1                  ← adb/SDK, IP guardada, lista de paquetes protegidos
│   ├── connect.ps1                 ← conectar por Wi-Fi
│   ├── diagnose.ps1                ← RAM por proceso + respaldo del estado original
│   ├── debloat.ps1                 ← desactivar paquetes de una lista (-DryRun)
│   ├── restrict-background.ps1     ← quitar el arranque en segundo plano
│   ├── tweaks.ps1                  ← velocidad de animaciones
│   ├── build.ps1                   ← compilar el launcher sin Gradle
│   ├── install.ps1                 ← instalar y poner como inicio
│   └── restore.ps1                 ← deshacer todo
├── launcher/                       ← código del launcher (io.github.mlinescode.slimtv)
│   ├── AndroidManifest.xml         ← actividad HOME + <queries> para Android 11
│   ├── res/values/config.xml       ← favoritas, ocultas, apps de música, columnas, minutos
│   ├── res/drawable/               ← íconos vectoriales y banner
│   └── src/io/github/mlinescode/slimtv/
│       ├── MainActivity.java       ← UI en código: reloj, barra de vidrio, tarjeta, mini reproductor, menú
│       ├── WallpaperView.java      ← paisajes por código + copia desenfocada de 160×90 para el vidrio
│       ├── GlassDrawable.java      ← vidrio esmerilado sin desenfoque en vivo (recorta esa copia)
│       ├── MediaWatcher.java       ← observa las sesiones de reproducción ("lector de notificaciones")
│       ├── NowPlaying.java         ← guarda lo último que reprodujo cada app y sus portadas
│       └── Screensaver.java        ← protector de pantalla (DreamService)
├── state/   (ignorado por git)     ← respaldo y registro de cambios por TV
└── dist/    (ignorado por git)     ← slimtv.apk compilado
```

## Árbol del proceso

```
SlimTV
│
├── FASE 0 · Enfoque
│   ├── ¿ROM propia? No: no existen ROMs para estas TVs y el riesgo de inutilizarla es alto
│   └── ADB sin root: pm disable-user, appops y settings → todo reversible
│
├── FASE 1 · Preparar la PC
│   ├── adb (platform-tools)
│   ├── Para compilar: Android SDK (build-tools + platform ≥ 30) y JDK 11+
│   └── Llave de firma: ~/.android/debug.keystore (build.ps1 la crea si no existe)
│
├── FASE 2 · Conectar                                    → scripts/connect.ps1
│   ├── TV: Acerca de → "Compilación" ×7 → Opciones de desarrollador → Depuración USB
│   ├── adb connect <ip>:5555 → "unauthorized"
│   └── TV: "Permitir siempre" → adb get-state = device
│
├── FASE 3 · Diagnóstico y respaldo                      → scripts/diagnose.ps1
│   ├── getprop · /proc/meminfo · pm list packages (-f -3 -d)
│   ├── resolve-activity HOME · settings get global *_animation_scale
│   ├── Respaldo en state/<tv>/original/
│   └── dumpsys meminfo → en la TV de referencia (903 MB, solo ~265 MB libres):
│       ├── com.google.android.tvlauncher ............ 115 MB  ← el principal culpable
│       ├── com.google.android.gms .................... 64 MB  (protegido)
│       ├── com.android.vending ....................... 54 MB  (protegido)
│       ├── com.google.android.katniss ................ 41 MB  (opcional: asistente)
│       ├── com.google.android.tvrecommendations ...... 19 MB
│       └── com.google.android.tungsten.setupwraith ... 12 MB
│
├── FASE 4 · Limpieza                                    → scripts/debloat.ps1
│   ├── Clasificar: seguro / opcional / no tocar          (examples/<modelo>/debloat.txt)
│   ├── pm disable-user --user 0 <paquete>                (desactivar, no desinstalar)
│   └── Registro en state/<tv>/disabled.txt
│
├── FASE 5 · Launcher propio                             → scripts/build.ps1
│   ├── Restricciones de diseño para ≤1 GB
│   │   ├── UI construida en código: sin layouts XML, sin AndroidX, sin librerías
│   │   ├── Fondos generados por código (640×360 escalado ≈ 1 MB) en vez de fotos
│   │   └── Sin internet ni servicios; solo un receptor PACKAGE_* para detectar apps nuevas
│   ├── Compilación sin Gradle
│   │   ├── aapt2 compile + link      → recursos, R.java, APK base
│   │   ├── javac -source 11          → .class
│   │   ├── d8 --min-api 26           → classes.dex (se agrega al APK con System.IO.Compression)
│   │   ├── zipalign -p 4
│   │   └── apksigner                 → dist/slimtv.apk (~60 KB)
│   └── Revisión visual con adb shell screencap + input keyevent DPAD_*
│
├── FASE 6 · Reemplazar el launcher                      → scripts/install.ps1
│   ├── adb install -r → am start → comprobar que SlimTV queda en primer plano
│   ├── pm disable-user: tvlauncher / launcherx (Google TV), partnercustomizer, setupwraith
│   │   └── setupwraith tiene prioridad en HOME: si no se desactiva, se queda con el botón
│   ├── cmd package set-home-activity io.github.mlinescode.slimtv/.MainActivity
│   └── El botón "Entradas" lanza com.android.tv.action.VIEW_INPUTS
│       └── sin el launcher de Google lo atiende el panel del fabricante (MediaTek en Hisense)
│
├── FASE 7 · Segundo plano                               → scripts/restrict-background.ps1
│   ├── adb reboot → al arrancar: Netflix 123 MB, Mercado Play 47 MB y HBO Max 26 MB, solas
│   ├── cmd appops set <paquete> RUN_ANY_IN_BACKGROUND ignore
│   └── am force-stop <paquete>    · registro en state/<tv>/background.txt
│
├── FASE 8 · Ajustes                                     → scripts/tweaks.ps1
│   └── settings put global {window,transition,animator}_*_scale 0.5
│
└── FASE 9 · Verificación
    ├── adb reboot → arranca en SlimTV
    ├── Las apps de la barra abren y Home regresa al inicio
    └── RAM disponible: ~265 MB → ~393 MB (+48 %)
```

## Comandos útiles

```powershell
adb shell dumpsys meminfo                         # RAM por proceso ("Total PSS by process")
adb shell pm list packages -s                     # paquetes del sistema
adb shell pm disable-user --user 0 <paquete>      # desactivar
adb shell pm enable --user 0 <paquete>            # reactivar
adb shell cmd appops set <paquete> RUN_ANY_IN_BACKGROUND allow   # devolver el segundo plano
adb shell screencap -p /sdcard/s.png; adb pull /sdcard/s.png    # captura
adb shell input keyevent DPAD_DOWN                # simular el control (DPAD_CENTER, HOME, BACK)
adb logcat -d -s AndroidRuntime:E                 # ver por qué falla una app
```

## Problemas comunes

| Síntoma | Solución |
|---|---|
| `unauthorized` | Acepta el aviso en la TV. Si no aparece, apaga y prende la Depuración USB |
| `failed to connect` | La IP cambió o la PC está en otra red. Revisa Ajustes → Red |
| Pantalla negra al pulsar Home | `adb shell pm enable --user 0 com.google.android.tvlauncher` o `restore.ps1` |
| Una app no actualiza en segundo plano | `appops set <paquete> RUN_ANY_IN_BACKGROUND allow` |
| Después de actualizar la TV volvió el bloatware | Vuelve a correr `debloat.ps1` con tu lista |
| Nada funciona | Restablecimiento de fábrica desde Ajustes: deshace todo |
