# This script requires create-dmg to be installed from https://github.com/sindresorhus/create-dmg
BUILD_CONFIG=$1

fail()
{
	echo "$1" 1>&2
	exit 1
}

if [ "$BUILD_CONFIG" != "Debug" ] && [ "$BUILD_CONFIG" != "Release" ]; then
  fail "Invalid build configuration - expected 'Debug' or 'Release'"
fi

BUILD_ROOT=$PWD/build
SOURCE_ROOT=$PWD
BUILD_FOLDER=$BUILD_ROOT/build-$BUILD_CONFIG
INSTALLER_FOLDER=$BUILD_ROOT/installer-$BUILD_CONFIG
VERSION=`cat $SOURCE_ROOT/app/version.txt`

if [ "$SIGNING_PROVIDER_SHORTNAME" == "" ]; then
  SIGNING_PROVIDER_SHORTNAME=$SIGNING_IDENTITY
fi
if [ "$SIGNING_IDENTITY" == "" ]; then
  SIGNING_IDENTITY=$SIGNING_PROVIDER_SHORTNAME
fi

[ "$SIGNING_IDENTITY" == "" ] || git diff-index --quiet HEAD -- || fail "Signed release builds must not have unstaged changes!"

echo Cleaning output directories
rm -rf $BUILD_FOLDER
rm -rf $INSTALLER_FOLDER
mkdir $BUILD_ROOT
mkdir $BUILD_FOLDER
mkdir $INSTALLER_FOLDER

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

echo Configuring the project
pushd $BUILD_FOLDER
qmake $SOURCE_ROOT/moonlight-qt.pro QMAKE_APPLE_DEVICE_ARCHS="x86_64 arm64" || fail "Qmake failed!"
popd

echo Compiling Moonlight in $BUILD_CONFIG configuration
pushd $BUILD_FOLDER
make -j$(sysctl -n hw.logicalcpu) $(echo "$BUILD_CONFIG" | tr '[:upper:]' '[:lower:]') || fail "Make failed!"
popd

echo Saving dSYM file
pushd $BUILD_FOLDER
dsymutil app/SpaceConnect.app/Contents/MacOS/SpaceConnect -o SpaceConnect-$VERSION.dsym || fail "dSYM creation failed!"
cp -R SpaceConnect-$VERSION.dsym $INSTALLER_FOLDER || fail "dSYM copy failed!"
popd

echo Creating app bundle
EXTRA_ARGS=
if [ "$BUILD_CONFIG" == "Debug" ]; then EXTRA_ARGS="$EXTRA_ARGS -use-debug-libs"; fi
echo Extra deployment arguments: $EXTRA_ARGS
macdeployqt $BUILD_FOLDER/app/SpaceConnect.app $EXTRA_ARGS -qmldir=$SOURCE_ROOT/app/gui -appstore-compliant || fail "macdeployqt failed!"

echo Removing dSYM files from app bundle
find $BUILD_FOLDER/app/SpaceConnect.app/ -name '*.dSYM' | xargs rm -rf

if [ "$SIGNING_IDENTITY" != "" ]; then
  echo Signing app bundle
  codesign --force --deep --options runtime --timestamp --sign "$SIGNING_IDENTITY" $BUILD_FOLDER/app/SpaceConnect.app || fail "Signing failed!"
else
  echo "No SIGNING_IDENTITY set - applying ad-hoc signature so the bundle launches after Gatekeeper quarantine removal"
  codesign --force --deep --sign - $BUILD_FOLDER/app/SpaceConnect.app || fail "Ad-hoc signing failed!"
fi

echo Creating DMG
if [ "$SIGNING_IDENTITY" != "" ]; then
  create-dmg $BUILD_FOLDER/app/SpaceConnect.app $INSTALLER_FOLDER --identity="$SIGNING_IDENTITY" || fail "create-dmg failed!"
else
  create-dmg $BUILD_FOLDER/app/SpaceConnect.app $INSTALLER_FOLDER
  case $? in
    0) ;;
    2) ;;
    *) fail "create-dmg failed!";;
  esac
fi

if [ "$NOTARY_KEYCHAIN_PROFILE" != "" ]; then
  DMG_TO_NOTARIZE=$(ls "$INSTALLER_FOLDER"/*.dmg | head -1)
  echo Uploading to App Notary service
  xcrun notarytool submit --keychain-profile "$NOTARY_KEYCHAIN_PROFILE" --wait "$DMG_TO_NOTARIZE" || fail "Notary submission failed"

  echo Stapling notary ticket to DMG
  xcrun stapler staple -v "$DMG_TO_NOTARIZE" || fail "Notary ticket stapling failed!"
fi

GENERATED_DMG=$(ls "$INSTALLER_FOLDER"/*.dmg | head -1)
mv "$GENERATED_DMG" "$INSTALLER_FOLDER/SpaceConnect-$VERSION.dmg"
echo Build successful