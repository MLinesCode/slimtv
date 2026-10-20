# Hisense HiSmart 2K ATV4

| Dato | Valor |
|---|---|
| Sistema | Android TV 11 (API 30) |
| Plataforma | MediaTek / CVTE · armeabi-v7a |
| RAM | ~903 MB |
| Pantalla | 1280×720 |
| Launcher de fábrica | `com.google.android.tvlauncher` |

## Resultados medidos

| | Antes | Con SlimTV |
|---|---|---|
| RAM disponible | ~265 MB | **~393 MB** (+48 %) |
| Launcher | Google: 115 MB (+31 MB de recomendaciones y setupwraith) | SlimTV: **23 MB** |
| Apps que arrancan solas al encender | Netflix (123 MB), Mercado Play (47 MB), HBO Max (26 MB)… | Ninguna |

## Lo que se aplicó

```powershell
pwsh scripts/connect.ps1 -Ip 192.168.x.x
pwsh scripts/diagnose.ps1
pwsh scripts/debloat.ps1 -List examples/hisense-2k-atv4/debloat.txt   # + YouTube Music y TalkBack
pwsh scripts/tweaks.ps1
pwsh scripts/build.ps1 -Install
pwsh scripts/restrict-background.ps1
```

## Notas del modelo
- **Entradas (HDMI):** al desactivar el launcher de Google, el panel de entradas lo atiende
  `com.mediatek.wwtv.tvcenter`, así que el botón "Entradas" de SlimTV sigue funcionando.
- **`setupwraith`** tiene que desactivarse: si no, se queda con el botón Home.
- **Cuidado:** al reiniciar, Netflix, Mercado Play y HBO Max se cargaban solas. `restrict-background.ps1`
  fue el cambio que más RAM liberó.
