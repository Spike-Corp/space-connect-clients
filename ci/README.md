# CI workflow (manual enable required)

`build-desktop.yml` builds the Windows x64 (portable zip) and macOS universal
(DMG) clients. It could not be committed under `.github/workflows/` by the
automation account because that token lacks the `workflow` OAuth scope.

## To enable

Move the file into place from a session/token that has `workflow` scope:

```bash
git mv ci/build-desktop.yml .github/workflows/build-desktop.yml
git commit -m "Enable desktop build CI"
git push
```

Or, in the GitHub web UI: create `.github/workflows/build-desktop.yml` and paste
the contents of `ci/build-desktop.yml`. Then run it from the **Actions** tab
(`Build Desktop Clients` → *Run workflow*).

Artifacts (`SpaceConnect-<version>-windows-x64.zip`,
`SpaceConnect-<version>-macos-universal` DMG) appear on the run page and can be
uploaded to the OVH release bucket alongside the Linux/Android builds.
