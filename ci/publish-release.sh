#!/usr/bin/env bash
# Publishes build artifacts to the PUBLIC space-connect-releases repo.
# Why: actions/upload-artifact is unusable when the org artifact storage quota
# is hit; GitHub Release assets are separate storage and stay publicly
# downloadable (this repo is private, releases there are the public mirror).
#
# Usage: GH_TOKEN=<pat> ci/publish-release.sh v0.1.6 file1 [file2 ...]
# Idempotent: creates the release when missing, overwrites same-name assets.
set -euo pipefail

REPO="Spike-Corp/space-connect-releases"
TAG="${1:?usage: publish-release.sh <tag> <file...>}"
shift

for f in "$@"; do
  [ -f "$f" ] || { echo "missing artifact: $f" >&2; exit 1; }
done

gh release view "$TAG" --repo "$REPO" >/dev/null 2>&1 \
  || gh release create "$TAG" --repo "$REPO" --title "Space Connect ${TAG}" --notes "Automated CI build."

gh release upload "$TAG" "$@" --repo "$REPO" --clobber
echo "Published to $REPO@$TAG: $*"
