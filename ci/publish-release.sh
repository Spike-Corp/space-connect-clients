#!/usr/bin/env bash
# Publishes build artifacts to the PUBLIC space-connect-releases repo.
# Why: actions/upload-artifact is unusable when the org artifact storage quota
# is hit; GitHub Release assets are separate storage and stay publicly
# downloadable (this repo is private, releases there are the public mirror).
#
# Deps: curl + python3 only (runs on the self-hosted runner AND inside the
# ubuntu:22.04 container of the linux job, where gh CLI is not installed).
#
# Usage: GH_TOKEN=<pat> ci/publish-release.sh v0.1.7 file1 [file2 ...]
# Idempotent: creates the release when missing, overwrites same-name assets.
set -euo pipefail

REPO="Spike-Corp/space-connect-releases"
TAG="${1:?usage: publish-release.sh <tag> <file...>}"
shift

for f in "$@"; do
  [ -f "$f" ] || { echo "missing artifact: $f" >&2; exit 1; }
done
[ -n "${GH_TOKEN:-}" ] || { echo "GH_TOKEN env var is required" >&2; exit 1; }

API="https://api.github.com/repos/$REPO"
AUTH="Authorization: Bearer $GH_TOKEN"

json_get() { python3 -c "import sys,json;d=json.load(sys.stdin);print($1)"; }

RELEASE_ID=$(curl -sf -H "$AUTH" "$API/releases/tags/$TAG" | json_get "d['id']" || true)
if [ -z "${RELEASE_ID:-}" ]; then
  RELEASE_ID=$(curl -sf -X POST -H "$AUTH" -H "Content-Type: application/json" "$API/releases" \
    -d "{\"tag_name\":\"$TAG\",\"name\":\"Space Connect $TAG\",\"draft\":false,\"prerelease\":false}" | json_get "d['id']")
fi
echo "Release $TAG id=$RELEASE_ID"

for f in "$@"; do
  name=$(basename "$f")
  # clobber: remove asset com o mesmo nome, se existir
  OLD_ID=$(curl -sf -H "$AUTH" "$API/releases/$RELEASE_ID/assets?per_page=100" \
    | python3 -c "import sys,json;print(next((a['id'] for a in json.load(sys.stdin) if a['name']=='$name'),''))" || true)
  if [ -n "${OLD_ID:-}" ]; then
    curl -sf -X DELETE -H "$AUTH" "$API/releases/assets/$OLD_ID" > /dev/null
  fi
  curl -sf -X POST -H "$AUTH" -H "Content-Type: application/octet-stream" \
    "https://uploads.github.com/repos/$REPO/releases/$RELEASE_ID/assets?name=$name" \
    --data-binary "@$f" | json_get "d['name']"
done
echo "Published to $REPO@$TAG: $*"
