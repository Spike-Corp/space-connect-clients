# Space Connect — Native Clients

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

All clients talk to the production API at
`https://spacecloud.gg/api/launcher/v1/`.

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
