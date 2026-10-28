<div align="center">

# SlimTV

**Bring your 1 GB RAM Android TV back to life.**
Reversible debloat + a 60 KB glass-style launcher that runs at 60 fps on 1 GB of RAM.

[Español](README.md) · [Full process (ES)](docs/PROCESO.md) · [How it was built with AI (ES)](docs/IA.md) · [Add your model](CONTRIBUTING.md)

![MIT](https://img.shields.io/badge/license-MIT-blue)
![Android TV 8+](https://img.shields.io/badge/Android%20TV-8%2B-3ddc84)
![RAM ≤ 1 GB](https://img.shields.io/badge/RAM-%E2%89%A4%201%20GB-orange)
![No root](https://img.shields.io/badge/root-not%20required-lightgrey)

</div>

| Before: stock launcher | After: SlimTV |
|---|---|
| ![Before](docs/img/antes.png) | ![After](docs/img/inicio.png) |

## Why

Many budget Android TVs (Hisense, TCL, Onn, generic brands…) ship with **1 GB of RAM or less**. After the
Google launcher, recommendations, ads and apps that start themselves at boot, only ~250 MB are left free,
so menus stutter and apps take ages to open.

SlimTV needs no custom ROM and no root. It uses **ADB over Wi-Fi** to do four things, **all reversible**:

1. **Disable bloatware:** demos, ads, telemetry, OEM apps. They are disabled, never uninstalled.
2. **Block background auto-start**, so Netflix, HBO Max & co. don't load themselves at boot.
   *On 1 GB TVs this frees the most RAM.*
3. **Replace the launcher** with an ultralight one: clock, Inputs/Wi-Fi/Settings, a glass row of apps
   sorted by recent use, "Continue watching", a Spotify mini player and a screensaver with
   code-generated landscapes.
4. **Speed up system animations.**

## Results (Hisense HiSmart 2K ATV4, 903 MB RAM)

| | Before | With SlimTV |
|---|---|---|
| Available RAM | ~265 MB | **~393 MB** (+48 %) |
| Launcher memory | 115 MB (+31 MB in related services) | **23 MB** |
| Launcher size | — | **61 KB** (29 KB in v1.0, without glass or extras) |
| Apps auto-started at boot | Netflix, HBO Max, Mercado Play… | **None** |

## The launcher

- **Glass row** with up to 10 apps sorted by recent use (your `config.xml` favorites at first); it takes
  the color of the focused app's brand.
- **Continue watching:** a card with what you last played, progress and time left, for apps that publish
  it in their media session (tested: Crunchyroll, YouTube, Spotify; Netflix and Prime Video don't share it).
- **Mini player** for Spotify playing in the background (⏮ ⏯ ⏭).
- **Hold OK on an app:** open, pin to the front, remove from the row, forget last watched, free memory,
  app info or uninstall.
- **Screensaver:** full-screen landscapes, a big clock and, only when useful, what's playing, what you
  were watching or a "No internet" notice.
- **No AndroidX, never connects to the internet** (it only listens on your home network for the remote). The glass doesn't blur live (Android 11 can't): it crops a 160×90 blurred
  copy of the wallpaper, so it costs nothing per frame. Navigating: 5 ms per frame, same as v1.0.

## Phone remote

The TV serves a web app on your home network: open it on your iPhone (or any phone) and add it to the
home screen. No PC, nothing from the App Store.

![Phone button on the home screen](docs/img/control-tv-boton.png)

1. On the TV home screen pick **Celular** (top right). A QR code and a 6-digit code appear.
2. Point the iPhone camera at the QR. The panel opens already paired. No camera: open the address the
   TV shows (e.g. `192.168.1.50:8765`) and type the digits.
3. The first time, the TV asks **"Allow debugging?"**: tick **Always allow** and accept. That enables
   arrows, OK, Back and the keyboard.
4. In Safari: **Share → Add to Home Screen**.

| On the TV: pairing code | On the iPhone, without camera |
|---|---|
| ![QR and 6 digits on the TV](docs/img/control-tv-vincular.png) | <img src="docs/img/control-celular-vincular.png" alt="Typing the code on the iPhone" width="220"> |

*Sample screenshots with a fictitious IP (`192.168.1.50`); your TV shows its own.*

![iPhone panel: remote, apps, typing and settings](docs/img/control-celular.png)

- **Touchpad** (swipe to move, tap for OK, hold for the app menu) or classic arrows; Back, Home, Pause,
  volume with the TV's real level, and power off.
- **Now playing** with artwork, progress and controls; **type** on the iPhone and it shows up in the TV's search box.
- **Apps:** open any app, Inputs or Settings; pin, reorder or hide apps in the row.
- **Settings:** your name in the greeting, the TV name, free memory and paired phones.

**Arrows and keyboard.** Android doesn't let a normal app press keys inside other apps, so SlimTV connects to
the TV's own ADB (`127.0.0.1:5555`) and starts a tiny process that injects keys in milliseconds. It needs USB/network
debugging on; if it's off, volume, music, launching apps and settings still work.

**Performance cost** (Hisense 2K ATV4, 903 MB RAM):

| | Result |
|---|---|
| Launcher memory (PSS) | **24 MB** fresh, **~38 MB** after use (cached logos/artwork): within v1.1's range |
| Key-injector process | **~1–2 MB** real (10 MB RSS, almost all shared; 0.6 MB its own) |
| CPU, remote idle | **~0 %** (launcher 0.02 s in 30 s, injector none) |
| CPU, panel open on the iPhone | **~0.6 % of one core** (polls once per second, only while visible) |
| Smoothness navigating with the remote | **5 ms** median frame, 99 % under 9 ms, **0.3 %** janky |
| Key round trip (signed) | median **19 ms**, 95 % under 131 ms |
| APK size | 61 KB → **105 KB** |

## Quick start

1. On the TV: **Settings → System → About**, press **Build** 7 times, then enable **USB debugging** in Developer options.
   Note the TV's IP address and use the same Wi-Fi network on your computer.
2. **With an AI agent (easiest):** open [Claude Code](https://claude.com/claude-code) in this folder and paste
   [PROMPT.md](PROMPT.md). The prompt is in Spanish; ask the agent to talk to you in English.
3. **Manually** (requires `adb`; building also needs the Android SDK and JDK 11+; scripts run on PowerShell 5.1/7 or `pwsh` on macOS/Linux):

```powershell
pwsh scripts/connect.ps1 -Ip 192.168.1.50
pwsh scripts/diagnose.ps1
pwsh scripts/debloat.ps1 -List examples/hisense-2k-atv4/debloat.txt -DryRun
pwsh scripts/debloat.ps1 -List examples/hisense-2k-atv4/debloat.txt
pwsh scripts/tweaks.ps1
pwsh scripts/build.ps1 -Install
pwsh scripts/restrict-background.ps1
```

**Undo everything:** `pwsh scripts/restore.ps1` (a factory reset also reverts it all).

**Customize:** edit [`launcher/res/values/config.xml`](launcher/res/values/config.xml) (favorites, music apps, row size, minutes) and run `pwsh scripts/build.ps1 -Install`. Pinning or removing apps needs no rebuild: hold OK on the app.

## Safety
- Packages are disabled with `pm disable-user` (reverted with `pm enable`). Nothing is uninstalled.
- The scripts refuse to touch Play Services, Play Store, SystemUI, Settings, the tuner, HDMI inputs and the remote
  (`$Protected` in [`scripts/common.ps1`](scripts/common.ps1)).
- Every change is logged in `state/<tv>/`, which is what `restore.ps1` uses to undo it (including the original screensaver).
- `restrict-background.ps1` skips the music apps in `config.xml`: Spotify must keep playing after you leave it.
- **Remote:** answers only devices on your local network and only when addressed by IP (blocks DNS rebinding);
  pairing requires the code shown on the TV (3 minutes, 5 tries); afterwards every request is signed with
  HMAC-SHA256 plus time and a one-time number, so the key never travels again and captured requests can't be
  replayed or altered. The API runs no shell commands. Limit: it's plain `http://` at home, so the key travels
  once over your Wi-Fi when pairing.
- The launcher's special permissions are usage access (to sort the row) and media sessions (for "Continue
  watching" and the mini player). Android requires registering as a "notification listener" to see sessions;
  SlimTV reads no notifications.

## Built with AI
SlimTV was designed, coded and tested **in a single conversation with [Claude Code](https://claude.com/claude-code)** (Anthropic),
with the agent connected to the real TV over ADB. The AI did the research, diagnosis, launcher, scripts and measurements.
The human made the calls: which apps they use, what to disable and when to apply each change.
See [docs/IA.md](docs/IA.md).

## Credits & license
Inspired by [tv-home](https://github.com/mihirgupta0900/tv-home) by Mihir Gupta (MIT), which targets 2 GB TVs.
SlimTV is an independent implementation focused on devices with 1 GB or less.
Icons: [Material Icons](https://fonts.google.com/icons) (Apache 2.0).

[MIT](LICENSE) © 2026 Miguel Garcia. Not affiliated with Hisense, Google or any manufacturer. Use at your own risk.
