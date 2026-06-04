#!/usr/bin/env bash
#
# Package AndroidDrop.app into a distributable AndroidDrop.dmg — the nice
# "drag the app into Applications" disk image you hand to other people.
#
#   ./make_dmg.sh
#
# Builds the app first if it isn't there yet. Uses macOS's built-in hdiutil,
# so there's nothing extra to install.
#
# Result:  mac/dist/AndroidDrop.dmg
#
# NOTE: the app is unsigned. On YOUR Mac it opens fine. If you send the .dmg to
# someone else, the first launch needs right-click → Open (Gatekeeper). Removing
# that prompt entirely requires an Apple Developer ID to sign + notarize.
#
set -euo pipefail

cd "$(dirname "$0")"
APP="dist/AndroidDrop.app"
DMG="dist/AndroidDrop.dmg"

if [ ! -d "$APP" ]; then
  echo "App not built yet — building it first…"
  ./build_app.sh
fi

echo "Packaging ${DMG} ..."
rm -f "$DMG"
STAGING="$(mktemp -d)"
cp -R "$APP" "$STAGING/"
ln -s /Applications "$STAGING/Applications"   # classic drag-to-Applications layout

hdiutil create -volname "AndroidDrop" -srcfolder "$STAGING" -ov -format UDZO "$DMG" >/dev/null
rm -rf "$STAGING"

echo "✓ Done:  $DMG"
