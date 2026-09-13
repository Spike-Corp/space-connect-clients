# Space Connect Crimson Beta — Native Clients

This directory is an isolated beta workspace. It is a copy of the existing
Space Connect/Moonlight source and must not be used as the production checkout.
Changes here do not modify the currently published Space Connect client.

## Beta scope

- Crimson Desert as the first game-only catalog entry.
- Space Connect account login inherited from the native launcher.
- Server-side eligibility for premium plans before a session is reserved.
- Dedicated GPU session and persistent game disk orchestration.
- Apollo/Sunshine connection through the existing Moonlight streaming stack.
- A beta-only visual layer that follows the Space Connect design tokens and
  keeps the desktop hidden from the player.

The beta must keep the existing streaming, pairing, authentication, and
platform implementations intact unless a change is explicitly made in this
checkout for the Crimson flow.

Native launcher clients for [SpaceCloud](https://spacecloud.gg) cloud gaming.
Users sign in with their SpaceCloud account, boot their machine, join the shared
queue (priority by plan), and stream via Moonlight — no separate config needed.

These clients are forks of the open-source
[Moonlight](https://moonlight-stream.org) projects and are therefore distributed
under the **GNU GPLv3**. Full corresponding source is published in this
repository to satisfy the license.

## Components

| Folder        | Client                    | Base project        |
|---------------|---------------------------|---------------------|
| `desktop/`    | Windows / macOS / Linux   | `moonlight-qt`      |
| `android/`    | Android (phone / TV)      | `moonlight-android` |
| `mic-bridge/` | Windows microphone bridge | Space Connect       |

The beta will use a separate API base and feature flag before any real
connection to infrastructure is enabled. Never point a beta build at the
production launcher API by accident.

The desktop beta reads `SPACE_CONNECT_CRIMSON_BETA_API` at runtime and calls
`POST /v1/games/crimson-desert/start`. The response follows the beta session
contract and never contains Steam credentials. Set the variable to the isolated
beta API URL when building/testing the native client.

## Downloads

Official builds are published to the OVH object storage release bucket:

- **Linux (AppImage):** https://spaceconnect-releases.s3.bhs.perf.cloud.ovh.net/v0.1.0/SpaceConnect-0.1.0-x86_64.AppImage
- **Android (APK):** https://spaceconnect-releases.s3.bhs.perf.cloud.ovh.net/v0.1.0/SpaceConnect-0.1.0-android.apk
- **Checksums:** https://spaceconnect-releases.s3.bhs.perf.cloud.ovh.net/v0.1.0/SHA256SUMS.txt

Windows (portable zip) and macOS (universal DMG) builds are produced by the
`Build Desktop Clients` GitHub Actions workflow and uploaded to the same bucket.

> macOS DMGs from CI are **ad-hoc signed**, not Apple-notarized. First launch
> requires right-click → Open (or `xattr -dr com.apple.quarantine`). Setting the
> `SIGNING_IDENTITY` / `NOTARY_KEYCHAIN_PROFILE` secrets enables full signing +
> notarization automatically.

## Building

### Desktop (Qt)

Requires Qt 6.7+, and the platform toolchain (MSVC on Windows, Xcode on macOS,
gcc/pkg-config libs on Linux).

```bash
cd desktop
qmake moonlight-qt.pro CONFIG+=release
make -j$(nproc)          # Windows: scripts\jom.exe release
```

- **Linux AppImage:** `scripts/build-appimage.sh` (or linuxdeploy with the
  `app/deploy/linux/gg.spacecloud.connect.desktop` entry).
- **macOS DMG:** `bash scripts/generate-dmg.sh Release` (universal).
- **Windows portable:** see `.github/workflows/build-desktop.yml`.

### Android

```bash
cd android
./gradlew assembleNonRootRelease   # application id: gg.spacecloud.connect
```

## License

GPLv3 for `desktop/` and `android/` (inherited from Moonlight). See
`desktop/LICENSE` and `android/LICENSE.txt`.
