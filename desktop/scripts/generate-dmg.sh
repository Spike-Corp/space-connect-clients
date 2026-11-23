#!/usr/bin/env bash
# Requires create-dmg from https://github.com/sindresorhus/create-dmg
BUILD_CONFIG=$1
PRODUCT=${2:-SpaceConnect}

fail()
{
	echo "$1" 1>&2
	exit 1
}

if [ "$BUILD_CONFIG" != "Debug" ] && [ "$BUILD_CONFIG" != "Release" ]; then
  fail "Invalid build configuration - expected 'Debug' or 'Release'"
fi
case "$PRODUCT" in
  SpaceConnect) FLAVOR=(); SUFFIX= ;;
  XDConsole) FLAVOR=("CONFIG+=xdconsole"); SUFFIX=-XDConsole ;;
  *) fail "Invalid macOS product: $PRODUCT" ;;
esac

BUILD_ROOT=$PWD/build
SOURCE_ROOT=$PWD
INSTALLER_FOLDER="$BUILD_ROOT/installer-$BUILD_CONFIG$SUFFIX"
VERSION=$(tr -d '[:space:]' < "$SOURCE_ROOT/app/version.txt") || fail "Missing app version"

if [ "$SIGNING_PROVIDER_SHORTNAME" == "" ]; then
  SIGNING_PROVIDER_SHORTNAME=$SIGNING_IDENTITY
fi
if [ "$SIGNING_IDENTITY" == "" ]; then
  SIGNING_IDENTITY=$SIGNING_PROVIDER_SHORTNAME
fi

[ "$SIGNING_IDENTITY" == "" ] || git diff-index --quiet HEAD -- || fail "Signed release builds must not have unstaged changes!"

mkdir -p "$INSTALLER_FOLDER" || fail "Cannot create installer directory"
BUILD_FOLDER=$(mktemp -d "$BUILD_ROOT/build-$PRODUCT-$BUILD_CONFIG.XXXXXX") || fail "Cannot create build directory"
STAGING=$(mktemp -d "$BUILD_ROOT/stage-$PRODUCT.XXXXXX") || fail "Cannot create DMG staging directory"
BUNDLE="$BUILD_FOLDER/app/$PRODUCT.app"

# Workaround: Qt <= 6.8's qyieldcpu.h calls the ARM ACLE intrinsic __yield()
# without including <arm_acle.h>. Apple Clang 16 (macOS 15 runners) treats the
# resulting implicit declaration as an error under -Werror, breaking the arm64
# slice of the universal build. Inject the header at file scope, guarded by arch,
# so x86_64 is unaffected. Idempotent and applied to the installed Qt only.
echo "Applying Qt qyieldcpu.h <arm_acle.h> workaround"
QT_PREFIX=$(qmake -query QT_INSTALL_PREFIX 2>/dev/null)
for QYH in $(find $QT_PREFIX "$HOME/work" -name qyieldcpu.h 2>/dev/null | sort -u); do
  if ! grep -q "arm_acle.h" "$QYH"; then
    perl -0777 -pi -e 's{(#include <QtCore/qtconfigmacros.h>\n)}{$1\n#if defined(__has_include)\n#  if defined(__aarch64__) || defined(__arm__)\n#    if __has_include(<arm_acle.h>)\n#      include <arm_acle.h>\n#    endif\n#  endif\n#endif\n}' "$QYH"
    echo "Patched: $QYH"
  fi
done

# Workaround: Qt's .prl link metadata still references "-framework AGL", but Apple
# removed the AGL (legacy OpenGL) framework from recent macOS SDKs (Xcode 16+),
# which fails the final link. Strip it from the installed Qt metadata.
echo "Stripping obsolete -framework AGL from Qt link metadata"
if [ -n "$QT_PREFIX" ]; then
  find "$QT_PREFIX" -name '*.prl' -type f -exec grep -lF -- "-framework AGL" {} + | while read -r f; do
    sed -i '' -E 's/-framework AGL//g' "$f" && echo "Stripped AGL: $f"
  done
fi

echo Configuring the project
pushd "$BUILD_FOLDER" || exit 1
qmake "$SOURCE_ROOT/moonlight-qt.pro" QMAKE_APPLE_DEVICE_ARCHS="x86_64 arm64" "${FLAVOR[@]}" || fail "Qmake failed!"
popd

echo "Compiling $PRODUCT in $BUILD_CONFIG configuration"
pushd "$BUILD_FOLDER" || exit 1
make -j$(sysctl -n hw.logicalcpu) $(echo "$BUILD_CONFIG" | tr '[:upper:]' '[:lower:]') || fail "Make failed!"
popd

echo Saving dSYM file
dsymutil "$BUNDLE/Contents/MacOS/$PRODUCT" -o "$BUILD_FOLDER/$PRODUCT-$VERSION.dsym" || fail "dSYM creation failed!"
ditto "$BUILD_FOLDER/$PRODUCT-$VERSION.dsym" "$INSTALLER_FOLDER/$PRODUCT-$VERSION.dsym" || fail "dSYM copy failed!"

echo Creating app bundle
EXTRA_ARGS=
if [ "$BUILD_CONFIG" == "Debug" ]; then EXTRA_ARGS="$EXTRA_ARGS -use-debug-libs"; fi
echo Extra deployment arguments: $EXTRA_ARGS
macdeployqt "$BUNDLE" $EXTRA_ARGS "-qmldir=$SOURCE_ROOT/app/gui" -appstore-compliant || fail "macdeployqt failed!"

ICONSET="$BUILD_FOLDER/spaceconnect.iconset"
mkdir "$ICONSET" || fail "Cannot create icon set"
for points in 16 32 128 256; do
  for scale in 1 2; do
    pixels=$((points * scale))
    [ "$pixels" -le 256 ] || continue
    name="icon_${points}x${points}"
    [ "$scale" -eq 1 ] || name="${name}@2x"
    sips -z "$pixels" "$pixels" "$SOURCE_ROOT/app/res/spaceconnect_icon_256.png" --out "$ICONSET/$name.png" >/dev/null || fail "Cannot render icon"
  done
done
iconutil -c icns "$ICONSET" -o "$BUNDLE/Contents/Resources/spaceconnect.icns" || fail "Cannot package icon"

if [ "$SIGNING_IDENTITY" != "" ]; then
  echo Signing app bundle
  codesign --force --deep --options runtime --timestamp --sign "$SIGNING_IDENTITY" "$BUNDLE" || fail "Signing failed!"
else
  echo "Applying an ad-hoc signature for this test build (not a Developer ID signature)"
  codesign --force --deep --sign - "$BUNDLE" || fail "Ad-hoc signing failed!"
fi

echo Creating DMG
if [ "$SIGNING_IDENTITY" != "" ]; then
  create-dmg "$BUNDLE" "$STAGING" --identity="$SIGNING_IDENTITY" || fail "create-dmg failed!"
else
  create-dmg "$BUNDLE" "$STAGING"
  case $? in
    0) ;;
    2) ;;
    *) fail "create-dmg failed!";;
  esac
fi

shopt -s nullglob
dmgs=("$STAGING"/*.dmg)
[ "${#dmgs[@]}" -eq 1 ] || fail "Expected exactly one newly generated DMG"
GENERATED_DMG=${dmgs[0]}
if [ "$NOTARY_KEYCHAIN_PROFILE" != "" ]; then
  echo Uploading to App Notary service
  xcrun notarytool submit --keychain-profile "$NOTARY_KEYCHAIN_PROFILE" --wait "$GENERATED_DMG" || fail "Notary submission failed"

  echo Stapling notary ticket to DMG
  xcrun stapler staple -v "$GENERATED_DMG" || fail "Notary ticket stapling failed!"
fi

mv "$GENERATED_DMG" "$INSTALLER_FOLDER/$PRODUCT-$VERSION.dmg" || fail "Cannot deliver DMG"
echo Build successful