# Pocket Linux

A full Linux desktop in one Android app. Open it and it boots straight into XFCE, with
the display built in, no terminal UI, no second app. Made for the Galaxy S25 Ultra
(Snapdragon 8 Elite, arm64), where a locked bootloader rules out real dual boot.

It is a fork of [Termux](https://github.com/termux/termux-app) with
[Termux:X11](https://github.com/termux/termux-x11) built in, and it uses the regular
Termux package repositories.

## What it does

- **Boot screen only**: first start downloads the base system, installs XFCE, Firefox,
  dev tools (Node, Angular CLI, Python, clang, Rust, .NET via glibc-runner) and a
  Plasma-like Breeze theme, then opens the desktop. Later starts open the desktop directly.
- **Built-in display**: the X server runs from the app's own APK and the display is an
  activity of this app. Resolution follows the window and the scale follows the screen
  density, so it fits the phone, split screen and external monitors.
- **Survives Android's process limit**: Android 12+ kills an app's background processes
  beyond 32. The desktop is trimmed to about 12 and Firefox to about 6, and a watchdog
  restarts whatever still dies, so the "Disable child process restrictions" developer
  option is not needed.
- **Own app ID `ch.pocketl`**: installs next to Termux. See below for how.

## How the own app ID works

Termux packages have `/data/data/com.termux/files/usr` compiled into their programs,
libraries and scripts. `ch.pocketl` has exactly as many characters as `com.termux`, so
the path can be swapped byte for byte, even inside compiled programs, without shifting
anything:

- the base system is rewritten while it is unpacked (`Relocator.java`)
- every package installed later goes through an apt hook that rewrites and repacks it
  right before dpkg runs (`assets/desktop/relocate-debs`)

The app ID must therefore stay 10 characters long.

## Build

The X server needs a Linux host to build, so the build runs in WSL (or any Linux).

One time, in WSL: JDK 17+, `bison`, `rsync`, and an Android SDK with platform 36,
build-tools 36, NDK 29.0.14206865 and CMake 3.22.1 in `~/android-sdk`.

```bash
git clone --recurse-submodules https://github.com/PianoNic/PocketLinux
# from Windows:
wsl -d Debian -- bash /mnt/c/Coding/PocketLinux/build-in-wsl.sh
# -> dist/pocket-linux-v<version>.apk
```

`external/termux-x11` is a git submodule and has submodules of its own, so clone
recursively. Keep LF line endings (the repo's `.gitattributes` does that); the X server's
patches do not apply to CRLF files.

## Layout

| Path | What |
|---|---|
| `app/src/main/java/com/termux/app/BootActivity.java` | the boot screen (install, setup, logs) |
| `app/src/main/java/com/termux/app/DesktopService.java` | keeps the desktop alive, watchdog |
| `app/src/main/java/com/termux/app/PocketDisplay.java` | display defaults and automatic scale |
| `app/src/main/java/com/termux/app/Relocator.java` | moves Termux paths to this app's ID |
| `app/src/main/assets/desktop/` | first-run setup, `desktop`, `desktop-theme`, `gpu`, apt hook |
| `external/termux-x11` | Termux:X11 (X server + display), built in as a library |
| `art/pocket-linux-icon.svg` | icon source; `art/render-legacy-icons.sh` renders the PNGs |

## Commands inside the desktop

| Command | What |
|---|---|
| `desktop` | start or repair the desktop |
| `desktop-theme dark` / `light` | Breeze look, bottom panel with Whisker Menu and Docklike |
| `gpu <program>` | run one program with GPU acceleration (Turnip/Zink, experimental on Adreno 830) |
| `desktop-setup` | run the first-start setup again |

## Credits and license

Based on [Termux](https://github.com/termux/termux-app) and
[Termux:X11](https://github.com/termux/termux-x11), both GPLv3. Pocket Linux is GPLv3
as well, see [LICENSE.md](LICENSE.md). Packages come from the Termux repositories.
