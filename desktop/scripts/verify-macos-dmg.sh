#!/usr/bin/env bash
set -euo pipefail

dmg=${1:?DMG required}
product=${2:?product required}
version=${3:?version required}
case "$product" in
  SpaceConnect) bundle_id=gg.spacecloud.connect; display_name='Space Connect' ;;
  XDConsole) bundle_id=gg.spacecloud.xdconsole; display_name='XD Console' ;;
  *) printf 'Unknown product: %s\n' "$product" >&2; exit 2 ;;
esac

hdiutil verify "$dmg"
volume=$(mktemp -d)
hdiutil attach -readonly -nobrowse -mountpoint "$volume" "$dmg"
trap 'hdiutil detach "$volume" && rmdir "$volume"' EXIT
app="$volume/$product.app"
plist="$app/Contents/Info.plist"
exe="$app/Contents/MacOS/$product"
test -x "$exe"
test "$(plutil -extract CFBundleIdentifier raw "$plist")" = "$bundle_id"
test "$(plutil -extract CFBundleDisplayName raw "$plist")" = "$display_name"
test "$(plutil -extract CFBundleExecutable raw "$plist")" = "$product"
test "$(plutil -extract CFBundleShortVersionString raw "$plist")" = "$version"
icon=$(plutil -extract CFBundleIconFile raw "$plist")
test -s "$app/Contents/Resources/${icon%.icns}.icns"
codesign --verify --deep --strict --verbose=2 "$app"
lipo "$exe" -verify_arch arm64 x86_64
while IFS= read -r -d '' file; do
  if [[ "$(file -b "$file")" == *Mach-O* ]]; then
    lipo "$file" -verify_arch arm64 x86_64
  fi
done < <(find "$app/Contents" -type f -print0)

# Launch the installed binary, without borrowing libraries or plugins from Qt's SDK.
unset DYLD_LIBRARY_PATH DYLD_FRAMEWORK_PATH QT_PLUGIN_PATH QML2_IMPORT_PATH QML_IMPORT_PATH
python3 - "$exe" "$display_name $version" <<'PY'
import subprocess, sys
result = subprocess.run([sys.argv[1], "--version"], capture_output=True, text=True, timeout=30)
if result.returncode or sys.argv[2] not in result.stdout.splitlines():
    sys.exit(f"Launch failed ({result.returncode}): {result.stdout}\n{result.stderr}")
print(result.stdout.strip())
PY
