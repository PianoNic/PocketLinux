# Linux Desktop (Termux fork)

Stripped-down Termux: no terminal UI, sessions, extra-keys bar, drawer, settings or plugin APIs.
The app has a single boot screen that installs the base system, runs a one-time setup
(XFCE, Turnip/Zink GPU, dev tools, .NET via glibc-runner) and boots straight into the desktop
through Termux:X11.

Package name stays `com.termux` because the Linux packages are compiled for
`/data/data/com.termux/files/usr`. Uninstall any other Termux build before installing.

What changed:
- New: `BootActivity` (the only screen), `DesktopService` (keeps Linux alive), `assets/desktop/*`
- Removed: `TermuxActivity`, `TermuxService`, `RunCommandService`, terminal/session/extra-keys UI,
  settings, file share receivers, documents provider, boot receiver
- Base system (bootstrap) downloaded on first start and SHA-256 verified, not embedded (APK ~8 MB)
- arm64-v8a only, no NDK build needed

Build: `./gradlew :app:assembleDebug` (Android SDK 36)
