#!/bin/bash
set -euo pipefail
cd "$(dirname "$0")/.."
version="${VERSION:-1.0.0}"
if [[ ! "$version" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]]; then echo 'VERSION must be x.y.z' >&2; exit 1; fi
mkdir -p build dist
app='dist/Anki Popup.app'
mkdir -p "$app/Contents/MacOS" "$app/Contents/Resources"
for arch in arm64 x86_64; do
    xcrun swiftc Sources/*.swift -O -target "$arch-apple-macosx13.0" -framework AppKit -lsqlite3 -o "build/AnkiPopup-$arch"
done
lipo -create build/AnkiPopup-arm64 build/AnkiPopup-x86_64 -output "$app/Contents/MacOS/AnkiPopup"
cp Resources/Info.plist "$app/Contents/Info.plist"
/usr/libexec/PlistBuddy -c "Set :CFBundleShortVersionString $version" "$app/Contents/Info.plist"
/usr/libexec/PlistBuddy -c "Set :CFBundleVersion $version" "$app/Contents/Info.plist"
if [[ -f Resources/AppIcon.icns ]]; then cp Resources/AppIcon.icns "$app/Contents/Resources/"; fi
codesign --force --sign - "$app"
codesign --verify --strict "$app"
ditto -c -k --sequesterRsrc --keepParent "$app" "dist/AnkiPopup-$version-universal.zip"
(cd dist && shasum -a 256 "AnkiPopup-$version-universal.zip" > SHA256SUMS.txt)
echo "Built $app and universal ZIP (macOS 13+, Intel and Apple silicon)."
