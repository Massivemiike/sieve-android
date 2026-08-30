# Sieve for Android

A native Kotlin + Jetpack Compose downloader/transcoder built on
[yt-dlp](https://github.com/yt-dlp/yt-dlp) (via
[youtubedl-android](https://github.com/yausername/youtubedl-android)) and a
self-built full-GPL ffmpeg. Android port of the Sieve desktop app.

**License:** GPLv3 (this app links GPL libraries — see `LICENSE`).

## Status

- **`engine/`** — the yt-dlp engine module: analyze, download, runtime
  self-update (`updateYoutubeDL`), the ported argument builders, presets input
  model, progress/error parsers, data model, and the GitHub-releases update
  layer. Pure logic is JUnit-tested; the native wrapper (`youtubedl-android`)
  is behind a testable seam. **Complete and green.**
- Transcode, queue, storage, UI, and distribution modules follow (see the
  design spec + per-subsystem plans).

## Building

The Android toolchain builds under **WSL2/Ubuntu** here (a Windows JVM NIO
loopback issue blocks native-Windows Gradle). Unit tests:

```bash
wsl -u root -e bash -lc "cd /path/to/sieve-android && ANDROID_HOME=/opt/android-sdk ./gradlew :engine:testDebugUnitTest"
```

Design spec, Phase-0 spike report, and implementation plans live in the Sieve
docs (`docs/superpowers/`).
