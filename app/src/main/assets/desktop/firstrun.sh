#!/data/data/com.termux/files/usr/bin/bash
# Termux Desktop: one-time automatic setup, runs on first app start.
# Installs XFCE (native, no proot), GPU (Turnip + Zink) and the Breeze theme, plus the dev tools
# and .NET, and Debian for Linux apps when asked for.
# Re-run manually any time with: desktop-setup [--dev] [--distro]

LOG="$HOME/.termux/desktop-setup.log"
MARKER="$HOME/.termux/desktop-ready"
mkdir -p "$HOME/.termux" "$HOME/.local/bin" "$PREFIX/tmp"
rm -f "$HOME/.termux/desktop-setup.exit"
: > "$LOG"
exec > >(tee -a "$LOG") 2>&1
trap 'echo $? > "$HOME/.termux/desktop-setup.exit"' EXIT

# Optional packs: the boot screen switches or `desktop-setup --dev --distro` set these flags.
for arg in "$@"; do
  case "$arg" in
    --dev) touch "$HOME/.termux/dev-tools" ;;
    --distro) touch "$HOME/.termux/distro" ;;
  esac
done

# Progress for the boot screen: "<step> <steps> <title>" in desktop-setup.progress. The number of
# steps depends on the packs and on whether this runs on the pre-installed system.
PROGRESS="$HOME/.termux/desktop-setup.progress"
STEPS=5
[ -e "$PREFIX/etc/pocket-linux-system" ] || STEPS=$((STEPS + 1))
[ -e "$HOME/.termux/distro" ] && STEPS=$((STEPS + 1))
[ -e "$HOME/.termux/dev-tools" ] && STEPS=$((STEPS + 2))
STEP=0
step() {
  STEP=$((STEP + 1))
  echo "$STEP $STEPS $*" > "$PROGRESS"
  printf '\n\033[1;36m==> %s\033[0m\n' "$*"
}
warn() { printf '\033[1;33m!! %s\033[0m\n' "$*"; }

export DEBIAN_FRONTEND=noninteractive
# Keep the default mirror (packages-cf.termux.dev, behind Cloudflare, fast everywhere). Otherwise
# pkg tests ~40 mirrors and picks a random one, often on another continent.
export TERMUX_PKG_NO_MIRROR_SELECT=1
# A readable log: no progress bars (they end up as one long line of redraws in a file) and no
# "apt does not have a stable CLI interface" warning on every pkg call.
printf '%s\n' 'quiet "1";' 'Apt::Cmd::Disable-Script-Warning "true";' 'Dpkg::Use-Pty "0";' > "$PREFIX/tmp/apt-setup.conf"
export APT_CONFIG="$PREFIX/tmp/apt-setup.conf"
APT_OPTS='-y -o Dpkg::Options::=--force-confnew -o Dpkg::Options::=--force-confdef'
# Installs only what is missing: on the pre-installed system (see scripts/build-system-image.sh)
# most calls then have nothing to do.
pkgi() {
  local missing=() p
  for p in "$@"; do dpkg -s "$p" >/dev/null 2>&1 || missing+=("$p"); done
  [ ${#missing[@]} -eq 0 ] || yes 2>/dev/null | pkg install $APT_OPTS "${missing[@]}"
}

step "Pocket Linux setup (keep the app open)"

# The pre-installed system is at most a week old and brings its package lists, so its
# packages are not updated now (pkg upgrade does that later).
if [ ! -e "$PREFIX/etc/pocket-linux-system" ]; then
  step "Updating packages"
  yes 2>/dev/null | pkg update $APT_OPTS || { warn "pkg update failed, retrying once"; sleep 3; yes 2>/dev/null | pkg update $APT_OPTS; }
  yes 2>/dev/null | pkg upgrade $APT_OPTS
  pkgi x11-repo tur-repo glibc-repo
  yes 2>/dev/null | pkg update $APT_OPTS
fi

step "Desktop: XFCE + audio (the display is built into the app)"
pkgi xkeyboard-config xfce4 xfce4-terminal pulseaudio dbus firefox synaptic || warn "some desktop packages failed"
step "Plasma-like look: Breeze theme, Whisker Menu, Docklike taskbar"
pkgi breeze-gtk kf6-breeze-icons xfce4-whiskermenu-plugin xfce4-docklike-plugin || warn "theme packages failed"

step "GPU acceleration: Turnip (Adreno Vulkan) + Zink (OpenGL on Vulkan)"
pkgi mesa-vulkan-icd-freedreno vulkan-tools mesa-demos 2>/dev/null || warn "GPU packages failed, desktop falls back to CPU"
pkgi mesa-zink 2>/dev/null || true

if [ -e "$HOME/.termux/distro" ]; then
step "Linux apps: Debian 13 with its app store"
# Termux packages only what it builds for Android. Debian (through proot, see bin/debian-run) runs
# nearly everything else for arm64 Linux, and its apps show up in the start menu (bin/debian-apps).
# Slower than Termux's own packages, so it is an opt-in pack.
pkgi proot-distro
DEBIAN_ROOTFS="$PREFIX/var/lib/proot-distro/containers/debian/rootfs"   # also in bin/debian-run
[ -d "$DEBIAN_ROOTFS" ] || proot-distro install debian:13 || warn "Debian could not be installed"
if [ -d "$DEBIAN_ROOTFS" ]; then
  # Done once, in the pre-installed system already.
  proot-distro login debian -- bash -s <<'DEBIAN' || warn "Debian setup failed, run desktop-setup to retry"
set -e
[ -e /etc/pocket-linux-debian ] && exit 0
export DEBIAN_FRONTEND=noninteractive
apt-get -qq update
apt-get -y -qq -o Dpkg::Use-Pty=0 install --no-install-recommends synaptic gdebi librsvg2-common breeze-gtk-theme \
  breeze-icon-theme breeze-cursor-theme fonts-noto-core xdg-utils dbus-x11 ca-certificates sudo >/dev/null
id user >/dev/null 2>&1 || useradd -m -s /bin/bash user
echo 'user ALL=(ALL) NOPASSWD:ALL' > /etc/sudoers.d/user
# Tells bin/desktop to update the start menu after installs and removals.
echo 'DPkg::Post-Invoke { "touch /tmp/.pocket-apps-changed 2>/dev/null || true"; };' > /etc/apt/apt.conf.d/99pocket-apps
mkdir -p /root/.synaptic
echo 'Synaptic { showWelcomeDialog "0"; };' > /root/.synaptic/synaptic.conf
apt-get clean
touch /etc/pocket-linux-debian
DEBIAN
  # The Debian user gets this app's user ID (fast mode runs as the real ID) and its home.
  proot-distro login debian -- usermod -o -u "$(id -u)" -d "$HOME" user 2>/dev/null ||
    warn "Debian user could not be updated"
fi
fi

step "Basic tools"
pkgi android-tools git openssh neovim ripgrep curl wget unzip || warn "some basic tools failed"

if [ -e "$HOME/.termux/dev-tools" ]; then
  step "Dev tools (native)"
  pkgi nodejs-lts python clang make cmake rust || warn "some dev tools failed"
  npm install -g @angular/cli pnpm || warn "npm globals failed"
  pkgi code-server 2>/dev/null || warn "code-server not available right now"
  pkgi code-oss 2>/dev/null || warn "VS Code (code-oss) not available right now"

  step ".NET SDK via glibc-runner (experimental)"
  if pkgi glibc glibc-runner libicu-glibc; then   # ICU: .NET aborts without it
    curl -fsSL https://dot.net/v1/dotnet-install.sh -o "$PREFIX/tmp/dotnet-install.sh" && \
    env -u LD_PRELOAD bash "$PREFIX/tmp/dotnet-install.sh" --channel LTS --os linux --architecture arm64 \
        --install-dir "$HOME/.dotnet" && \
    grun -c "$HOME/.dotnet/dotnet" || warn ".NET install failed, run desktop-setup later to retry"
  fi
fi

if ! command -v startxfce4 >/dev/null; then
  warn "Desktop packages missing, setup incomplete. Check your connection and reopen the app."
  exit 1
fi

touch "$MARKER"
echo "$STEPS $STEPS Done" > "$PROGRESS"

echo
echo "Setup finished. Starting the desktop..."
