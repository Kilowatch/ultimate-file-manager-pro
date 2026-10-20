# AUR packaging — `ufm-companion-bin`

Arch User Repository (AUR) sources for the official Linux companion **AppImage**
of [Ultimate File Manager Pro](https://github.com/Kilowatch/ultimate-file-manager-pro).

- AUR package: **`ufm-companion-bin`**
  → https://aur.archlinux.org/packages/ufm-companion-bin
- Tracks the GitHub **release tag** (e.g. `v2.1.8`) that ships
  `ufm-companion-x86_64.AppImage`, because release assets are published
  unversioned under the Android app release tag. The companion's internal
  Tauri/Cargo version (`UFM-Linux/src-tauri/tauri.conf.json`) is currently
  `0.1.0` and evolves independently.
- Installs the verified AppImage to `/opt/ufm-companion/`, symlinks
  `/usr/bin/ufm-companion`, and ships a tiling-WM friendly desktop entry
  (`za.kilowatch.ufm-companion.desktop`) with proper `Categories=` and
  `StartupWMClass`.

## Install from AUR

```bash
# with yay / paru
yay -S ufm-companion-bin
# or
paru -S ufm-companion-bin

# manual
git clone https://aur.archlinux.org/ufm-companion-bin.git
cd ufm-companion-bin
makepkg -si
```

Launch with `ufm-companion` or from your app menu
(*Ultimate File Manager Pro Companion*).

## Maintain / bump version

In-repo sources live here (`UFM-Linux/packaging/aur/`); the AUR repo at
`aur.archlinux.org/ufm-companion-bin.git` is a publication copy.

```bash
cd UFM-Linux/packaging/aur
# 1. bump pkgver in PKGBUILD to the new GitHub release tag (without leading v)
# 2. refresh checksums
updpkgsums
# 3. regenerate metadata
makepkg --printsrcinfo > .SRCINFO
# 4. test
makepkg -si
namcap PKGBUILD
# 5. publish (needs AUR ssh key)
git clone ssh://aur@aur.archlinux.org/ufm-companion-bin.git /tmp/aur-ufm
cp PKGBUILD .SRCINFO ufm-companion-bin.install za.kilowatch.ufm-companion.desktop /tmp/aur-ufm/
cd /tmp/aur-ufm && git add . && git commit -m "Update to <ver>" && git push
```

## Files

| File | Purpose |
|---|---|
| `PKGBUILD` | AUR build recipe (AppImage → `/opt`) |
| `ufm-companion-bin.install` | desktop-db + icon-cache hooks |
| `za.kilowatch.ufm-companion.desktop` | fixed desktop entry (Wayland/Tiling friendly) |
| `.SRCINFO` | generated AUR metadata (`makepkg --printsrcinfo`) |
