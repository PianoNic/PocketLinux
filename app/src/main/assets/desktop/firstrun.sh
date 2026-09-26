#!/data/data/com.termux/files/usr/bin/bash
# Termux Desktop: one-time automatic setup, runs on first app start.
# Installs XFCE (native, no proot), GPU (Turnip + Zink), dev tools, .NET via glibc-runner,
# and the Breeze theme. Re-run manually any time with: desktop-setup

LOG="$HOME/.termux/desktop-setup.log"
MARKER="$HOME/.termux/desktop-ready"
mkdir -p "$HOME/.termux" "$HOME/.local/bin" "$PREFIX/tmp"
rm -f "$HOME/.termux/desktop-setup.exit"
: > "$LOG"
exec > >(tee -a "$LOG") 2>&1
trap 'echo $? > "$HOME/.termux/desktop-setup.exit"' EXIT

step() { printf '\n\033[1;36m==> %s\033[0m\n' "$*"; }
warn() { printf '\033[1;33m!! %s\033[0m\n' "$*"; }

export DEBIAN_FRONTEND=noninteractive
APT_OPTS='-y -o Dpkg::Options::=--force-confnew -o Dpkg::Options::=--force-confdef'
pkgi() { yes | pkg install $APT_OPTS "$@"; }

step "Termux Desktop setup (takes ~10-20 min, keep the app open)"

step "Updating packages"
yes | pkg update $APT_OPTS || { warn "pkg update failed, retrying once"; sleep 3; yes | pkg update $APT_OPTS; }
yes | pkg upgrade $APT_OPTS
pkgi x11-repo tur-repo glibc-repo
yes | pkg update $APT_OPTS

step "Desktop: XFCE + audio (the display is built into the app)"
pkgi xkeyboard-config xfce4 xfce4-terminal pulseaudio dbus firefox || warn "some desktop packages failed"
step "Plasma-like look: Breeze theme, Whisker Menu, Docklike taskbar"
pkgi breeze-gtk kf6-breeze-icons xfce4-whiskermenu-plugin xfce4-docklike-plugin || warn "theme packages failed"

step "GPU acceleration: Turnip (Adreno Vulkan) + Zink (OpenGL on Vulkan)"
pkgi mesa-vulkan-icd-freedreno vulkan-tools mesa-demos 2>/dev/null || warn "GPU packages failed, desktop falls back to CPU"
pkgi mesa-zink 2>/dev/null || true

step "Dev tools (native)"
pkgi android-tools git openssh nodejs-lts python clang make cmake rust neovim ripgrep curl wget unzip || warn "some dev tools failed"
npm install -g @angular/cli pnpm || warn "npm globals failed"
pkgi code-server 2>/dev/null || warn "code-server not available right now"

step ".NET SDK via glibc-runner (experimental)"
if pkgi glibc glibc-runner libicu-glibc; then   # ICU: .NET aborts without it
  curl -fsSL https://dot.net/v1/dotnet-install.sh -o "$PREFIX/tmp/dotnet-install.sh" && \
  env -u LD_PRELOAD bash "$PREFIX/tmp/dotnet-install.sh" --channel LTS --os linux --architecture arm64 \
      --install-dir "$HOME/.dotnet" && \
  grun -c "$HOME/.dotnet/dotnet" || warn ".NET install failed, run desktop-setup later to retry"
fi

if ! command -v startxfce4 >/dev/null; then
  warn "Desktop packages missing, setup incomplete. Check your connection and reopen the app."
  exit 1
fi

touch "$MARKER"

echo
echo "Setup finished. Starting the desktop..."
