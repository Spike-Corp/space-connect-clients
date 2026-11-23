#!/usr/bin/env bash
set -euo pipefail

fail() { printf '%s\n' "$*" >&2; exit 1; }
case "${1:-all}" in
  all) products=(SpaceConnect XDConsole) ;;
  SpaceConnect|XDConsole) products=("$1") ;;
  *) fail "Usage: bash $0 [SpaceConnect|XDConsole|all]" ;;
esac
[[ $# -le 1 ]] || fail "Expected at most one product"
[[ "$(uname -s)" == Darwin ]] || fail "Este build requer macOS com Xcode; nenhum instalador foi gerado."
cd "$(dirname "$0")/.."
for tool in xcodebuild xcrun python3 node npm; do
  command -v "$tool" >/dev/null || fail "Ferramenta ausente: $tool"
done
xcodebuild -version >/dev/null && xcrun --sdk macosx --show-sdk-path >/dev/null || fail "Instale e selecione o Xcode com o SDK macOS."
python3 -c 'import sys; sys.exit(sys.version_info < (3, 9))' || fail "Use Python 3.9+."
node -e 'process.exit(Number(process.versions.node.split(".")[0]) < 18 ? 1 : 0)' || fail "Use Node.js 18+."

tools="$PWD/build/macos-tools"
out="$PWD/build/macos-preview"
mkdir -p "$tools" "$out"
shopt -s nullglob
qmakes=("$tools/qt/6.7.3"/*/bin/qmake)
if [[ ${#qmakes[@]} -eq 0 ]]; then
  python3 -m venv "$tools/venv"
  "$tools/venv/bin/python" -m pip install 'aqtinstall==3.3.0'
  "$tools/venv/bin/aqt" install-qt mac desktop 6.7.3 clang_64 -O "$tools/qt" -m qtimageformats
  qmakes=("$tools/qt/6.7.3"/*/bin/qmake)
fi
[[ ${#qmakes[@]} -eq 1 ]] || fail "Qt 6.7.3 não encontrado de forma unívoca na pasta privada do build."
if [[ ! -x "$tools/npm/node_modules/.bin/create-dmg" ]]; then
  npm install --prefix "$tools/npm" --no-audit --no-fund --no-save create-dmg@7.1.0
fi
export PATH="${qmakes[0]%/*}:$tools/npm/node_modules/.bin:$PATH"
[[ "$(qmake -query QT_VERSION)" == 6.7.3 ]] || fail "Versão inesperada do Qt."
lipo "$(qmake -query QT_INSTALL_LIBS)/QtCore.framework/QtCore" -verify_arch arm64 x86_64
lrelease app/languages/qml_pt_BR.ts app/languages/qml_pt.ts

version=$(tr -d '[:space:]' < app/version.txt)
for product in "${products[@]}"; do
  (unset SIGNING_IDENTITY SIGNING_PROVIDER_SHORTNAME NOTARY_KEYCHAIN_PROFILE
   bash scripts/generate-dmg.sh Release "$product")
  case "$product" in
    SpaceConnect) installer_dir=build/installer-Release ;;
    XDConsole) installer_dir=build/installer-Release-XDConsole ;;
  esac
  dmg="$installer_dir/$product-$version.dmg"
  name="$product-$version-macos-universal.dmg"
  bash scripts/verify-macos-dmg.sh "$dmg" "$product" "$version"
  install -m 0644 "$dmg" "$out/$name"
  (cd "$out" && shasum -a 256 "$name" > "$name.sha256")
  printf 'Instalador verificado (prévia, sem notarização): %s/%s\n' "$out" "$name"
done
