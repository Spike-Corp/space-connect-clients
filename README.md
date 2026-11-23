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

Official builds are published to [GitHub Releases](https://github.com/Spike-Corp/space-connect-releases/releases)
and served by `https://downloads.spacecloud.gg`.

- **Android 0.2.5 (APK):** https://downloads.spacecloud.gg/SpaceConnect-0.2.5-android.apk
- **Available platforms, download links, SHA-256 and sizes:** https://spacecloud.gg/api/launcher/v1/releases

Platforms are released independently. An Android hotfix does not change the
Windows/Linux binaries or their version. Only entries marked `available` in the
manifest are published downloads; generating an Android AAB does not submit it
to the Play Store.

> macOS previews are **ad-hoc signed**, not Apple-notarized. Gatekeeper may
> block them. These test builds must not be advertised as stable downloads.

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
- **macOS previews (Intel + Apple Silicon):** on a Mac with Xcode selected,
  Python 3.9+ and Node.js 18+ with npm, run `bash desktop/scripts/build-macos-preview.sh`
  from the repository root. It installs Qt and the DMG builder under `desktop/build/`
  without `sudo`, then builds **Space Connect** (customer) and **XD Console** (staff).
  Pass `SpaceConnect` or `XDConsole` to build just one. The script mounts each DMG
  read-only and checks its identity, signature, both architectures and `--version`
  before writing the DMG and SHA-256 file to `desktop/build/macos-preview/`.
- **Windows portable:** see `.github/workflows/build-desktop.yml`.

### Android

```bash
cd android
./gradlew assembleNonRootRelease   # application id: gg.spacecloud.gg.connect
```

Android 0.2.5 fixes the loading dialog remaining over an already connected stream.
Connection stages remain visible while connecting; completing the connection
dismisses the dialog without finishing the streaming Activity.

```bash
cd android
./gradlew :app:testNonRootDebugUnitTest --tests 'com.limelight.utils.SpinnerDialogTest'
```

These Robolectric tests exercise Android 7/API 24 and Android 14/API 34 dialog
lifecycle, queued callbacks and cancellation. They do not replace installed-app
testing on Samsung One UI or a real streaming session. CI runs the unit suite
before publishing the signed APK/AAB.

## License

GPLv3 for `desktop/` and `android/` (inherited from Moonlight). See
`desktop/LICENSE` and `android/LICENSE.txt`.
