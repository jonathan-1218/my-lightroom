#!/bin/bash
# Builds Lumen.app (release), copies the shared schema and design tokens in, signs it for this Mac, and runs the self-test.
#   ./build.sh          build + self-test
#   ./build.sh install  …and copy to /Applications
set -euo pipefail
cd "$(dirname "$0")"
swift build -c release --arch arm64
APP=Lumen.app
rm -rf "$APP"; mkdir -p "$APP/Contents/MacOS" "$APP/Contents/Resources"
cp .build/release/Lumen "$APP/Contents/MacOS/Lumen"
cp ../src/main/assets/schema.sql ../src/main/assets/migrate_*.sql ../src/main/assets/tokens.json "$APP/Contents/Resources/" # one source of truth with Android
cp AppIcon.icns "$APP/Contents/Resources/" # made from ../logo/lumen-icon-fullbleed.svg
cat > "$APP/Contents/Info.plist" <<PLIST
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0"><dict>
  <key>CFBundleName</key><string>Lumen</string>
  <key>CFBundleDisplayName</key><string>Lumen</string>
  <key>CFBundleIdentifier</key><string>dev.lumen.mac</string>
  <key>CFBundleExecutable</key><string>Lumen</string>
  <key>CFBundlePackageType</key><string>APPL</string>
  <key>CFBundleIconFile</key><string>AppIcon</string>
  <key>CFBundleShortVersionString</key><string>1.0</string>
  <key>CFBundleVersion</key><string>1</string>
  <key>LSMinimumSystemVersion</key><string>14.0</string>
  <key>LSApplicationCategoryType</key><string>public.app-category.photography</string>
  <key>NSHighResolutionCapable</key><true/>
  <key>NSPrincipalClass</key><string>NSApplication</string>
</dict></plist>
PLIST
codesign --force --options runtime --sign - "$APP" # ad-hoc + hardened runtime (no code injection); a Developer ID is needed to hand it to others
"$APP/Contents/MacOS/Lumen" --selftest
du -sh "$APP" | cut -f1 | sed 's/^/app size: /'
if [ "${1:-}" = install ]; then rm -rf /Applications/Lumen.app; cp -R "$APP" /Applications/; echo "installed to /Applications/Lumen.app"; fi
