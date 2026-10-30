# Moonlight Android

[![AppVeyor Build Status](https://ci.appveyor.com/api/projects/status/232a8tadrrn8jv0k/branch/master?svg=true)](https://ci.appveyor.com/project/cgutman/moonlight-android/branch/master)
[![Translation Status](https://hosted.weblate.org/widgets/moonlight/-/moonlight-android/svg-badge.svg)](https://hosted.weblate.org/projects/moonlight/moonlight-android/)

[Moonlight for Android](https://moonlight-stream.org) is an open source client for NVIDIA GameStream and [Sunshine](https://github.com/LizardByte/Sunshine).

Moonlight for Android will allow you to stream your full collection of games from your Windows PC to your Android device,
whether in your own home or over the internet.

Moonlight also has a [PC client](https://github.com/moonlight-stream/moonlight-qt) and [iOS/tvOS client](https://github.com/moonlight-stream/moonlight-ios).

You can follow development on our [Discord server](https://moonlight-stream.org/discord) and help translate Moonlight into your language on [Weblate](https://hosted.weblate.org/projects/moonlight/moonlight-android/).

## Downloads
* [Google Play Store](https://play.google.com/store/apps/details?id=com.limelight)
* [Amazon App Store](https://www.amazon.com/gp/product/B00JK4MFN2)
* [F-Droid](https://f-droid.org/packages/com.limelight)
* [APK](https://github.com/moonlight-stream/moonlight-android/releases)

## Building
* Install Android Studio and the Android NDK
* Run ‘git submodule update --init --recursive’ from within moonlight-android/
* In moonlight-android/, create a file called ‘local.properties’. Add an ‘ndk.dir=’ property to the local.properties file and set it equal to your NDK directory.
* Build the APK using Android Studio or gradle

## SpaceConnect managed sessions

The dashboard CTA opens `SessionLoadingActivity` and one `SessionOrchestrator` attempt.
It follows the selected machine's real queue/boot status, preserves admin status/phase/reason
texts, pairs silently through the existing authenticated launcher API, and selects **Desktop's
actual app ID** from GameStream's app list. Other running apps are not stopped or used as a fallback.
Tips and management features remain on the dashboard. Personal PCs retain manual PIN pairing.

The same dark overlay covers `Game`'s normal stream surface until
`MediaCodec.OnFrameRenderedListener` reports its first rendered frame. Connection-start,
decode submission and buffer release do not reveal it. Reveal takes 500 ms; system reduced-motion
settings disable animation. Native audio starts normally, independently of this visual gate.
No host monitor or virtual display is configured.
The minimum supported Android API is 24. API 21–22 are not supported and have no first-frame
fallback; queued/decoded frames are never described as rendered frames.

The loading activity keeps its own service binding and five-second session worker alive while
the dashboard is paused. Queue/boot waiting has no five-minute cutoff. Leaving the loading
activity cancels that attempt; retrying cannot revive its old PIN/handshake callbacks.

Cancellation invalidates the attempt, cancels only its HTTP calls/workers and interrupts/stops its
own native connection. A possibly accepted queue join is reconciled for up to 15 seconds using
`DELETE queue?machineId=…`, even after a POST timeout/503 or when status still says idle/ready.
Queue deletion is machine-scoped and queue-only; it never calls session-end or power-off.
New attempts wait for that bounded cleanup. PIN acknowledgement is not proof of pairing:
the native handshake must succeed. Only a freshly paired device retries launch HTTP 403 while
the agent grants permission (up to 20 seconds). The server must honor machine-scoped queue deletion.

Local validation (Java 17, Android SDK/NDK configured):

```sh
./gradlew :app:testNonRootDebugUnitTest :app:assembleNonRootDebug
./gradlew :app:lintNonRootDebug
```

Tests use local HTTP mocks, Robolectric and a simulated native bridge; they do not prove physical
MediaCodec rendering, audio output, TV remote input or live agent authorization. Validate those on
an authorized test host/device before release. Lint has pre-existing `NewApi` errors in
`ControllerHandler.java` (not suppressed by this feature).

## Authors

* [Cameron Gutman](https://github.com/cgutman)  
* [Diego Waxemberg](https://github.com/dwaxemberg)  
* [Aaron Neyer](https://github.com/Aaronneyer)  
* [Andrew Hennessy](https://github.com/yetanothername)

Moonlight is the work of students at [Case Western](http://case.edu) and was
started as a project at [MHacks](http://mhacks.org).
