#!/bin/sh
# Ultimate File Manager Pro — Linux / FreeBSD companion one-line installer.
#
#   Linux (prebuilt binaries):
#     curl -fsSL https://raw.githubusercontent.com/Kilowatch/ultimate-file-manager-pro/main/UFM-Linux/scripts/install.sh | sh
#
#   FreeBSD (native source build, experimental):
#     curl -fsSL https://raw.githubusercontent.com/Kilowatch/ultimate-file-manager-pro/main/UFM-Linux/scripts/install.sh | sh
#
# Inspect before piping: fetch the URL in a browser or with curl and read it.
# Options (pass after `--` when piping, e.g. `... | sh -s -- --help`):
#   --tag v2.1.8        GitHub release tag to install (default: latest)
#   --method auto|deb|rpm|aur|appimage|source
#                       Install method (default: auto-detect)
#   --prefix DIR        User-install prefix for the AppImage fallback
#                       (default: $HOME/.local)
#   --system            Install the AppImage fallback system-wide
#                       (/opt/ufm-companion, needs root)
#   --yes               Assume yes for prompts
#   --dry-run           Print what would be done, change nothing
#   --uninstall         Remove a previous install.sh installation
#   --help              Show this help
#
# Env overrides (mostly for testing):
#   UFM_INSTALL_OS=Linux|FreeBSD   pretend to run on another OS
#   UFM_REPO=owner/repo            use a fork
#
# POSIX sh only — runs on Linux (dash/bash) and FreeBSD (/bin/sh).
set -eu

REPO="${UFM_REPO:-Kilowatch/ultimate-file-manager-pro}"
APP_ID="za.kilowatch.ufm-companion"
BIN_NAME="ufm-companion"
TAG="latest"
METHOD="auto"
PREFIX="${HOME:-/root}/.local"
SYSTEM=0
ASSUME_YES=0
DRY_RUN=0
UNINSTALL=0

log() { printf '%s\n' "ufm-install: $*"; }
warn() { printf '%s\n' "ufm-install: WARNING: $*" >&2; }
die() { printf '%s\n' "ufm-install: ERROR: $*" >&2; exit 1; }
run() {
  if [ "$DRY_RUN" -eq 1 ]; then
    printf '%s\n' "ufm-install: [dry-run] $*"
  else
    "$@"
  fi
}

usage() {
  if [ -f "$0" ]; then
    sed -n '2,/^$/p' "$0" | sed 's/^# \{0,1\}//'
  else
    echo "ufm-install: Ultimate File Manager Pro companion installer (Linux/FreeBSD)"
  fi
  echo "Usage: install.sh [--tag TAG] [--method auto|deb|rpm|aur|appimage|source]"
  echo "                   [--prefix DIR] [--system] [--yes] [--dry-run] [--uninstall]"
}

while [ $# -gt 0 ]; do
  case "$1" in
    --tag) TAG="${2:?--tag needs a value}"; shift 2 ;;
    --method) METHOD="${2:?--method needs a value}"; shift 2 ;;
    --prefix) PREFIX="${2:?--prefix needs a value}"; shift 2 ;;
    --system) SYSTEM=1; shift ;;
    --yes|-y) ASSUME_YES=1; shift ;;
    --dry-run|--dryrun|-n) DRY_RUN=1; shift ;;
    --uninstall) UNINSTALL=1; shift ;;
    --help|-h) usage; exit 0 ;;
    --) shift; break ;;
    *) die "unknown option: $1 (see --help)" ;;
  esac
done

KERN="$(uname -s 2>/dev/null || echo unknown)"
OS="${UFM_INSTALL_OS:-$KERN}"

# --- helpers ---------------------------------------------------------------
have() { command -v "$1" >/dev/null 2>&1; }

priv() {
  # Print the privilege wrapper: "" when root, else sudo/doas if available.
  if [ "$(id -u)" -eq 0 ]; then
    printf '%s' ""
  elif have sudo; then
    printf '%s' "sudo"
  elif have doas; then
    printf '%s' "doas"
  else
    printf '%s' ""
  fi
}

need_priv() {
  if [ "$(id -u)" -ne 0 ] && ! have sudo && ! have doas; then
    die "need root (or sudo/doas) for system-wide install. Re-run as root or drop --system for a user install."
  fi
}

confirm() {
  if [ "$ASSUME_YES" -eq 1 ] || [ "$DRY_RUN" -eq 1 ]; then return 0; fi
  printf '%s [y/N] ' "ufm-install: $1"
  read -r ans || ans=""
  case "$ans" in [Yy]*) return 0 ;; *) return 1 ;; esac
}

fetch() {
  # fetch URL DEST — curl preferred, wget fallback.
  _url="$1"; _dest="$2"
  if [ "$DRY_RUN" -eq 1 ]; then
    printf '%s\n' "ufm-install: [dry-run] download $_url -> $_dest"
    return 0
  fi
  if have curl; then
    curl -fsSL --retry 3 -o "$_dest" "$_url"
  elif have wget; then
    wget -q --tries=3 -O "$_dest" "$_url"
  else
    die "need curl or wget to download release assets"
  fi
}

rel_base() {
  # Base download URL for the requested tag.
  if [ "$TAG" = "latest" ]; then
    printf 'https://github.com/%s/releases/latest/download' "$REPO"
  else
    case "$TAG" in v*) _t="$TAG" ;; *) _t="v$TAG" ;; esac
    printf 'https://github.com/%s/releases/download/%s' "$REPO" "$_t"
  fi
}

raw_base() {
  # Raw file base for icon/desktop sources.
  if [ "$TAG" = "latest" ]; then
    printf 'https://raw.githubusercontent.com/%s/main' "$REPO"
  else
    case "$TAG" in v*) _t="$TAG" ;; *) _t="v$TAG" ;; esac
    printf 'https://raw.githubusercontent.com/%s/%s' "$REPO" "$_t"
  fi
}

write_desktop() {
  # write_desktop FILE EXEC_PATH ICON_NAME
  if [ "$DRY_RUN" -eq 1 ]; then
    printf '%s\n' "ufm-install: [dry-run] write desktop entry $1 (Exec=$2)"
    return 0
  fi
  cat > "$1" <<EOF
[Desktop Entry]
Type=Application
Version=1.5
Name=Ultimate File Manager Pro Companion
GenericName=File Manager
Comment=Native Wayland-first Linux companion for Ultimate File Manager Pro
Exec=$2
Icon=$3
Terminal=false
Categories=Utility;FileTools;
Keywords=file;manager;android;sideload;apk;wifi;transfer;
StartupWMClass=$APP_ID
StartupNotify=true
EOF
}

# --- Linux distro detection -------------------------------------------------
distro_id() {
  # Prints "<id> <id_like>", lowercased. Empty when unknown.
  if [ -f /etc/os-release ]; then
    _id="$(sed -n 's/^ID=//p' /etc/os-release | head -n1 | sed 's/"//g' | tr '[:upper:]' '[:lower:]')"
    _like="$(sed -n 's/^ID_LIKE=//p' /etc/os-release | head -n1 | sed 's/"//g' | tr '[:upper:]' '[:lower:]')"
    printf '%s %s' "$_id" "$_like"
  else
    printf ' '
  fi
}

aur_helper() {
  for h in yay paru pikaur trizen aurman; do
    if have "$h"; then printf '%s' "$h"; return 0; fi
  done
  return 1
}

# --- install methods (Linux prebuilt) ---------------------------------------
install_deb() {
  need_priv; _s="$(priv)"
  _tmp="$(mktemp -d)"; trap 'rm -rf "$_tmp"' EXIT INT TERM
  log "installing .deb (Debian/Ubuntu family)…"
  fetch "$(rel_base)/ufm-companion_amd64.deb" "$_tmp/ufm-companion_amd64.deb"
  run $_s apt update
  # shellcheck disable=SC2086
  run $_s apt install -y "$_tmp/ufm-companion_amd64.deb"
  trap - EXIT INT TERM; rm -rf "$_tmp"
  log "done — launch with: ufm-linux"
}

install_rpm() {
  need_priv; _s="$(priv)"
  _tmp="$(mktemp -d)"; trap 'rm -rf "$_tmp"' EXIT INT TERM
  log "installing .rpm…"
  fetch "$(rel_base)/ufm-companion-x86_64.rpm" "$_tmp/ufm-companion-x86_64.rpm"
  if have dnf; then
    # shellcheck disable=SC2086
    run $_s dnf install -y "$_tmp/ufm-companion-x86_64.rpm"
  elif have yum; then
    # shellcheck disable=SC2086
    run $_s yum install -y "$_tmp/ufm-companion-x86_64.rpm"
  elif have zypper; then
    # shellcheck disable=SC2086
    run $_s zypper --non-interactive install "$_tmp/ufm-companion-x86_64.rpm"
  else
    die "no supported rpm installer found (dnf/yum/zypper)"
  fi
  trap - EXIT INT TERM; rm -rf "$_tmp"
  log "done — launch with: ufm-linux"
}

install_aur() {
  _helper="$(aur_helper || true)"
  if [ -z "$_helper" ]; then
    warn "no AUR helper found (yay/paru/pikaur/trizen/aurman) — falling back to AppImage"
    install_appimage
    return 0
  fi
  log "installing ufm-companion-bin via $_helper…"
  run "$_helper" -S --noconfirm ufm-companion-bin
  log "done — launch with: ufm-companion"
}

install_appimage() {
  if [ "$SYSTEM" -eq 1 ]; then
    need_priv; _s="$(priv)"
    log "installing AppImage system-wide to /opt/ufm-companion…"
    _tmp="$(mktemp -d)"; trap 'rm -rf "$_tmp"' EXIT INT TERM
    fetch "$(rel_base)/ufm-companion-x86_64.AppImage" "$_tmp/app.AppImage"
    fetch "$(raw_base)/UFM-Linux/src-tauri/icons/icon.png" "$_tmp/icon.png"
    # shellcheck disable=SC2086
    run $_s install -Dm755 "$_tmp/app.AppImage" /opt/ufm-companion/ufm-companion.AppImage
    # shellcheck disable=SC2086
    run $_s install -Dm644 "$_tmp/icon.png" /usr/share/icons/hicolor/512x512/apps/$APP_ID.png
    # shellcheck disable=SC2086
    run $_s install -Dm644 "$_tmp/icon.png" /usr/share/pixmaps/$APP_ID.png
    write_desktop "$_tmp/$APP_ID.desktop" /opt/ufm-companion/ufm-companion.AppImage "$APP_ID"
    # shellcheck disable=SC2086
    run $_s install -Dm644 "$_tmp/$APP_ID.desktop" /usr/share/applications/$APP_ID.desktop
    if [ "$DRY_RUN" -eq 0 ]; then
      $_s update-desktop-database -q 2>/dev/null || true
      $_s gtk-update-icon-cache -q -t -f /usr/share/icons/hicolor 2>/dev/null || true
      $_s ln -sf /opt/ufm-companion/ufm-companion.AppImage /usr/bin/ufm-companion 2>/dev/null || true
    else
      printf '%s\n' "ufm-install: [dry-run] sudo ln -sf /opt/ufm-companion/ufm-companion.AppImage /usr/bin/ufm-companion"
    fi
    trap - EXIT INT TERM; rm -rf "$_tmp"
  else
    log "installing AppImage for the current user to $PREFIX…"
    _tmp="$(mktemp -d)"; trap 'rm -rf "$_tmp"' EXIT INT TERM
    fetch "$(rel_base)/ufm-companion-x86_64.AppImage" "$_tmp/app.AppImage"
    fetch "$(raw_base)/UFM-Linux/src-tauri/icons/icon.png" "$_tmp/icon.png"
    run mkdir -p "$PREFIX/bin" "$PREFIX/share/applications" \
      "$PREFIX/share/icons/hicolor/512x512/apps" "$PREFIX/share/pixmaps"
    run install -m755 "$_tmp/app.AppImage" "$PREFIX/bin/ufm-companion.AppImage"
    run install -m644 "$_tmp/icon.png" "$PREFIX/share/icons/hicolor/512x512/apps/$APP_ID.png"
    run install -m644 "$_tmp/icon.png" "$PREFIX/share/pixmaps/$APP_ID.png"
    write_desktop "$PREFIX/share/applications/$APP_ID.desktop" "$PREFIX/bin/ufm-companion.AppImage" "$APP_ID"
    if [ "$DRY_RUN" -eq 0 ]; then
      update-desktop-database "$PREFIX/share/applications" 2>/dev/null || true
      gtk-update-icon-cache -q -t -f "$PREFIX/share/icons/hicolor" 2>/dev/null || true
    fi
    trap - EXIT INT TERM; rm -rf "$_tmp"
    case ":$PATH:" in
      *":$PREFIX/bin:"*) ;;
      *) warn "$PREFIX/bin is not on PATH — add: export PATH=\"\$HOME/.local/bin:\$PATH\"" ;;
    esac
  fi
  log "done — launch with: ufm-companion.AppImage (or from your app menu)"
}

# --- source build (FreeBSD native, or Linux --method source) ------------------
install_source() {
  need_priv; _s="$(priv)"
  if [ "$OS" = "FreeBSD" ]; then
    log "FreeBSD detected — native source build (experimental)."
    log "This compiles the Rust backend; expect several minutes on first run."
    confirm "install build dependencies via pkg and compile?" || die "aborted"
    if ! have pkg; then
      if [ "$DRY_RUN" -eq 1 ]; then
        printf '%s\n' "ufm-install: [dry-run] require pkg(8)"
      else
        die "pkg(8) not found — bootstrap it first: https://docs.freebsd.org/en/books/handbook/ports/"
      fi
    fi
    # Install in tolerant groups: warn (don't abort) per package so one
    # renamed package name can't sink the whole run; required tools are
    # verified afterwards.
    # shellcheck disable=SC2086
    if [ "$DRY_RUN" -eq 1 ]; then
      printf '%s\n' "ufm-install: [dry-run] $_s pkg install -y git curl pkgconf rust node npm"
      printf '%s\n' "ufm-install: [dry-run] $_s pkg install -y webkit2-gtk_41 gtk3 libsoup3 librsvg2-rust libappindicator"
    else
      $_s pkg install -y git curl pkgconf || warn "base tools install had issues"
      $_s pkg install -y rust node npm || warn "toolchain install had issues (need: rustc/cargo, node, npm)"
      $_s pkg install -y webkit2-gtk_41 gtk3 libsoup3 librsvg2-rust libappindicator \
        || warn "GUI libs install had issues (need: webkit2-gtk_41 gtk3 libsoup3 librsvg2-rust libappindicator)"
    fi
    for t in git cargo node npm cc pkgconf; do
      if [ "$DRY_RUN" -eq 1 ]; then
        printf '%s\n' "ufm-install: [dry-run] require tool: $t"
      elif ! have "$t"; then
        die "required tool '$t' still missing — install it via pkg, e.g.: sudo pkg install -y rust node npm"
      fi
    done
  else
    log "source build on Linux — installing build dependencies…"
    _ids="$(distro_id)"
    case "$_ids" in
      *debian*|*ubuntu*|*mint*|*pop*)
        # shellcheck disable=SC2086
        run $_s apt update
        # shellcheck disable=SC2086
        run $_s apt install -y git curl libwebkit2gtk-4.1-dev build-essential \
          libssl-dev libayatana-appindicator3-dev librsvg2-dev nodejs npm ;;
      *fedora*|*rhel*|*centos*|*rocky*|*alma*)
        if have dnf; then _pm="dnf"; else _pm="yum"; fi
        # shellcheck disable=SC2086
        run $_s $_pm install -y git curl webkit2gtk4.1-devel libayatana-appindicator-gtk3-devel \
          librsvg2-devel openssl-devel nodejs npm gcc gcc-c++ make ;;
      *arch*|*cachyos*|*manjaro*|*endeavour*)
        # shellcheck disable=SC2086
        run $_s pacman -Sy --needed --noconfirm git curl base-devel \
          webkit2gtk-4.1 nodejs npm rust librsvg libsoup3 ;;
      *) warn "unknown distro — skipping dep install; need: git curl pkgconf rust node npm webkit2gtk-4.1 gtk3" ;;
    esac
  fi

  _work="$(mktemp -d)"; trap 'rm -rf "$_work"' EXIT INT TERM
  if [ "$TAG" = "latest" ]; then
    _src="https://github.com/$REPO/archive/refs/heads/main.tar.gz"
  else
    case "$TAG" in v*) _t="$TAG" ;; *) _t="v$TAG" ;; esac
    _src="https://github.com/$REPO/archive/refs/tags/$_t.tar.gz"
  fi
  log "fetching sources: $_src"
  fetch "$_src" "$_work/src.tar.gz"
  if [ "$DRY_RUN" -eq 1 ]; then
    printf '%s\n' "ufm-install: [dry-run] extract, cd UFM-Linux, npm ci, npm run build, cargo build --release"
    printf '%s\n' "ufm-install: [dry-run] install ufm-linux -> /usr/local/bin/$BIN_NAME + desktop entry"
    trap - EXIT INT TERM; rm -rf "$_work"; return 0
  fi
  tar -xzf "$_work/src.tar.gz" -C "$_work"
  _dir="$(find "$_work" -maxdepth 1 -name 'ultimate-file-manager-pro*' | head -n1)"
  [ -n "$_dir" ] && [ -d "$_dir/UFM-Linux" ] || die "unexpected source layout in $_src"
  cd "$_dir/UFM-Linux"
  log "building frontend (npm)…"
  if [ -f package-lock.json ]; then npm ci; else npm install; fi
  npm run build
  log "building backend (cargo --release)…"
  cargo build --release --manifest-path src-tauri/Cargo.toml
  _bin="src-tauri/target/release/ufm-linux"
  [ -x "$_bin" ] || die "build finished but $_bin not found"
  log "installing to /usr/local…"
  $_s install -Dm755 "$_bin" /usr/local/bin/$BIN_NAME
  $_s install -Dm644 src-tauri/icons/icon.png /usr/local/share/icons/hicolor/512x512/apps/$APP_ID.png
  $_s install -Dm644 src-tauri/icons/icon.png /usr/local/share/pixmaps/$APP_ID.png
  write_desktop "$_work/$APP_ID.desktop" /usr/local/bin/$BIN_NAME "$APP_ID"
  $_s install -Dm644 "$_work/$APP_ID.desktop" /usr/local/share/applications/$APP_ID.desktop
  $_s update-desktop-database -q /usr/local/share/applications 2>/dev/null || true
  $_s gtk-update-icon-cache -q -t -f /usr/local/share/icons/hicolor 2>/dev/null || true
  cd /; trap - EXIT INT TERM; rm -rf "$_work"
  log "done — launch with: $BIN_NAME"
}

# --- uninstall ----------------------------------------------------------------
do_uninstall() {
  _s="$(priv)"
  log "removing install.sh-managed files…"
  # User AppImage install
  for p in "$PREFIX" "$HOME/.local"; do
    # shellcheck disable=SC2086
    [ "$DRY_RUN" -eq 1 ] && printf '%s\n' "ufm-install: [dry-run] rm -f $p/bin/ufm-companion.AppImage $p/share/applications/$APP_ID.desktop" && continue
    rm -f "$p/bin/ufm-companion.AppImage" \
      "$p/share/applications/$APP_ID.desktop" \
      "$p/share/icons/hicolor/512x512/apps/$APP_ID.png" \
      "$p/share/pixmaps/$APP_ID.png" 2>/dev/null || true
  done
  # System AppImage install
  if [ -e /opt/ufm-companion/ufm-companion.AppImage ] || [ -e /usr/share/applications/$APP_ID.desktop ]; then
    if confirm "remove system-wide install (/opt/ufm-companion, /usr/share entries)?"; then
      # shellcheck disable=SC2086
      run $_s rm -rf /opt/ufm-companion /usr/bin/ufm-companion \
        /usr/share/applications/$APP_ID.desktop \
        /usr/share/icons/hicolor/512x512/apps/$APP_ID.png \
        /usr/share/pixmaps/$APP_ID.png
      if [ "$DRY_RUN" -eq 0 ]; then
        $_s update-desktop-database -q 2>/dev/null || true
      fi
    fi
  fi
  # Native packages
  if have apt && dpkg -s ultimate-file-manager-pro-companion >/dev/null 2>&1; then
    if confirm "remove .deb package (ultimate-file-manager-pro-companion)?"; then
      # shellcheck disable=SC2086
      run $_s apt remove -y ultimate-file-manager-pro-companion
    fi
  fi
  if have rpm && rpm -q ultimate-file-manager-pro-companion >/dev/null 2>&1; then
    if confirm "remove .rpm package (ultimate-file-manager-pro-companion)?"; then
      if have dnf; then
        # shellcheck disable=SC2086
        run $_s dnf remove -y ultimate-file-manager-pro-companion
      else
        # shellcheck disable=SC2086
        run $_s rpm -e ultimate-file-manager-pro-companion
      fi
    fi
  fi
  _helper="$(aur_helper || true)"
  if [ -n "$_helper" ] && "$_helper" -Q ufm-companion-bin >/dev/null 2>&1; then
    if confirm "remove AUR package (ufm-companion-bin)?"; then
      run "$_helper" -R --noconfirm ufm-companion-bin
    fi
  fi
  # FreeBSD / source build
  if [ -x /usr/local/bin/$BIN_NAME ]; then
    if confirm "remove /usr/local/bin/$BIN_NAME + desktop entry?"; then
      # shellcheck disable=SC2086
      run $_s rm -f /usr/local/bin/$BIN_NAME \
        /usr/local/share/applications/$APP_ID.desktop \
        /usr/local/share/icons/hicolor/512x512/apps/$APP_ID.png \
        /usr/local/share/pixmaps/$APP_ID.png
    fi
  fi
  log "uninstall finished"
}

# --- main ---------------------------------------------------------------------
if [ "$UNINSTALL" -eq 1 ]; then do_uninstall; exit 0; fi

case "$OS" in
  FreeBSD)
    [ "$METHOD" = "auto" ] && METHOD="source"
    case "$METHOD" in
      source) install_source ;;
      *) die "FreeBSD has no prebuilt binaries — only --method source is supported (experimental native build)" ;;
    esac
    ;;
  Linux)
    if [ "$METHOD" = "auto" ]; then
      _ids="$(distro_id)"
      case "$_ids" in
        *debian*|*ubuntu*|*mint*|*pop*|*raspbian*) METHOD="deb" ;;
        *fedora*|*rhel*|*centos*|*rocky*|*alma*) METHOD="rpm" ;;
        *suse*|*opensuse*) METHOD="rpm" ;;
        *arch*|*cachyos*|*manjaro*|*endeavour*|*garuda*) METHOD="aur" ;;
        *) METHOD="appimage" ;;
      esac
      log "detected: ${_ids:-unknown distro} -> method $METHOD (override with --method)"
    fi
    case "$METHOD" in
      deb) install_deb ;;
      rpm) install_rpm ;;
      aur) install_aur ;;
      appimage) install_appimage ;;
      source) install_source ;;
      *) die "unknown method: $METHOD (see --help)" ;;
    esac
    ;;
  *)
    die "unsupported OS: $OS (this installer supports Linux and FreeBSD)"
    ;;
esac
