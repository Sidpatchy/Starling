#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
distribution="$repo_root/composeApp/build/compose/binaries/main/app/Starling"
appdir="$repo_root/composeApp/build/appimage/Starling.AppDir"
artifactdir="$repo_root/composeApp/build/appimage/artifacts"
version="${APP_VERSION:-0.1.0}"

if [[ ! -x "$distribution/bin/Starling" ]]; then
  echo "Missing Compose desktop distribution; run :composeApp:createDistributable first." >&2
  exit 1
fi
if [[ ! -x "$repo_root/linuxdeploy-x86_64.AppImage" || ! -x "$repo_root/appimagetool-x86_64.AppImage" ]]; then
  echo "Download linuxdeploy-x86_64.AppImage and appimagetool-x86_64.AppImage into the repository root first." >&2
  exit 1
fi

rm -rf "$appdir"
mkdir -p \
  "$appdir/usr/lib/starling" \
  "$appdir/usr/share/applications" \
  "$appdir/usr/share/metainfo" \
  "$appdir/usr/share/icons/hicolor/512x512/apps" \
  "$artifactdir"
cp -a "$distribution/." "$appdir/usr/lib/starling/"
cp "$repo_root/packaging/flatpak/dev.starling.Starling.metainfo.xml" \
  "$appdir/usr/share/metainfo/dev.starling.Starling.metainfo.xml"
cp "$repo_root/packaging/flatpak/dev.starling.Starling.metainfo.xml" \
  "$appdir/usr/share/metainfo/dev.starling.Starling.appdata.xml"
cp "$repo_root/assets/branding/starling-light-512.png" \
  "$appdir/usr/share/icons/hicolor/512x512/apps/dev.starling.Starling.png"

cat > "$appdir/usr/share/applications/dev.starling.Starling.desktop" <<'EOF'
[Desktop Entry]
Type=Application
Name=Starling
Comment=Chat over Reticulum
Exec=AppRun
Icon=dev.starling.Starling
Terminal=false
Categories=Network;InstantMessaging;
StartupWMClass=Starling
EOF

cd "$repo_root"
# linuxdeploy runs ldd on JRE libraries individually, so expose libjvm to it.
runtime_server_lib="$appdir/usr/lib/starling/lib/runtime/lib/server"
LD_LIBRARY_PATH="$runtime_server_lib${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}" \
  NO_STRIP=true APPIMAGE_EXTRACT_AND_RUN=1 ./linuxdeploy-x86_64.AppImage \
  --appdir "$appdir" \
  --desktop-file "$appdir/usr/share/applications/dev.starling.Starling.desktop" \
  --icon-file "$appdir/usr/share/icons/hicolor/512x512/apps/dev.starling.Starling.png" \
  --custom-apprun "$repo_root/packaging/linux/AppRun" \
  --executable "$appdir/usr/lib/starling/bin/Starling"

rm -f "$artifactdir/Starling-$version-x86_64.AppImage"
APPIMAGE_EXTRACT_AND_RUN=1 VERSION="$version" \
  ./appimagetool-x86_64.AppImage --no-appstream "$appdir" \
  "$artifactdir/Starling-$version-x86_64.AppImage"
