<div align="center">

# SlimTV

**Bring your 1 GB RAM Android TV back to life.**
Reversible debloat + a 30 KB launcher that uses 20 MB of RAM.

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
3. **Replace the launcher** with an ultralight one: clock, Inputs/Wi-Fi/Settings, a dock of favorite apps,
   and code-generated landscape wallpapers.
4. **Speed up system animations.**

## Results (Hisense HiSmart 2K ATV4, 903 MB RAM)

| | Before | With SlimTV |
|---|---|---|
| Available RAM | ~265 MB | **~393 MB** (+48 %) |
| Launcher memory | 115 MB (+31 MB in related services) | **23 MB** |
| Launcher size | — | **29 KB** |
| Apps auto-started at boot | Netflix, HBO Max, Mercado Play… | **None** |

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

**Customize the dock:** edit [`launcher/res/values/config.xml`](launcher/res/values/config.xml) and run `pwsh scripts/build.ps1 -Install`.

## Safety
- Packages are disabled with `pm disable-user` (reverted with `pm enable`). Nothing is uninstalled.
- The scripts refuse to touch Play Services, Play Store, SystemUI, Settings, the tuner, HDMI inputs and the remote
  (`$Protected` in [`scripts/common.ps1`](scripts/common.ps1)).
- Every change is logged in `state/<tv>/`, which is what `restore.ps1` uses to undo it.

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
