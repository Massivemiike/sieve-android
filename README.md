# Sieve for Android

**A native, open-source media downloader and transcoder for Android — save and convert media you have the right to use, entirely on-device.**

[![License: GPLv3](https://img.shields.io/badge/License-GPLv3-blue.svg)](LICENSE)
[![Platform: Android 8.0+](https://img.shields.io/badge/Platform-Android%208.0%2B-3DDC84?logo=android&logoColor=white)](#tech-stack)
[![Built with: Kotlin & Jetpack Compose](https://img.shields.io/badge/Built%20with-Kotlin%20%26%20Jetpack%20Compose-7F52FF?logo=kotlin&logoColor=white)](#tech-stack)
[![Engine: yt-dlp](https://img.shields.io/badge/Engine-yt--dlp-red)](https://github.com/yt-dlp/yt-dlp)
[![Status: v1 in development](https://img.shields.io/badge/Status-v1%20in%20development-orange.svg)](#-project-status--roadmap)

Sieve for Android is a Kotlin/Jetpack Compose port of the Sieve desktop app, built on the [yt-dlp](https://github.com/yt-dlp/yt-dlp) engine and a self-built, full-GPL FFmpeg. Paste a link to save video or audio from more than a thousand websites, or re-encode your own media locally with hardware-accelerated conversion. It ships with a background download queue that survives leaving the app, and a download engine that keeps itself up to date without reinstalling. No account, no cloud, no app store required — a fast, private, general-purpose tool for capturing and converting media you have the right to use.

---

## ✨ Features

### ⬇️ Download

- **Paste a link and go** — Sieve analyzes the URL and picks the right formats for you.
- **8 one-tap quick presets** covering the common "just get me the video / audio" cases.
- **1000+ supported sites** through the yt-dlp engine.
- **A queue that keeps working** — a background download queue that survives leaving the app, with pause, resume, and retry.
- **Resilient by design** — auto-resumes in-flight downloads after a restart (`yt-dlp -c`) and shows a live progress notification.

### 🎞️ Transcode

- **52 FFmpeg presets** across H.264, HEVC, AV1, VP9, ProRes, DNxHR, audio-only (MP3 / AAC / Opus / FLAC / WAV), device targets, legacy DVD, and image (GIF / animated WebP) families.
- **Hardware-accelerated encoding** via Android MediaCodec for H.264/HEVC, with automatic software fallback.
- **Software encoding for everything**, powered by a self-built full-GPL FFmpeg (x264, x265, and more).
- **Simple, useful controls** — a Software / Hardware toggle, a quality (CRF) control, and audio loudness normalization.
- **Custom presets** you can import and export.

### 📁 Library

- **Grant an output folder once** via the Storage Access Framework (SAF); downloads and transcodes write straight there.
- **Built-in library browser** over your granted folder.
- **Subtitle export and frame extraction** straight into your library.

### 🔒 Private & free

- **Nothing is uploaded anywhere** — all downloading and transcoding happen on your device.
- **Self-updating engine** — the download engine updates itself at runtime from GitHub, so newly-broken sites get fixed fast without an app reinstall. Stable by default, with an opt-in bleeding-edge channel.
- **Open and self-distributing** — fully open-source under GPLv3, delivered as a signed APK you update from the project's own website (mirrored to GitHub Releases). No store gatekeeping.

---

## 📸 Screenshots

> Screenshots are coming as the UI layer lands. A UI mockup / preview of the Jetpack Compose interface exists — final captures will be dropped into `docs/screenshots/` and linked below.

<!--
Replace these placeholders with real captures once the :app UI is complete, e.g.:
![Download screen](docs/screenshots/download.png)
![Transcode screen](docs/screenshots/transcode.png)
![Queue](docs/screenshots/queue.png)
-->

---

## 🏗️ Architecture

Multi-module Gradle project. Pure logic (parsers, argument builders, the queue state machine, the preset catalog) is Android-free and JVM-unit-tested; only leaf `.android` / `.service` packages touch the framework or exec native binaries. Dependency direction flows up toward `:app`.

| Module | Package | Responsibility |
| --- | --- | --- |
| **`:engine`** | `com.sieve.engine` | yt-dlp integration: `analyze`, `download` (cold `Flow<EngineEvent>`), and runtime self-update (`updateYoutubeDL` STABLE/NIGHTLY + a direct-from-GitHub fallback). Ports the desktop argument builder (`YtdlpArgs`), the progress/error/analyze parsers, and the version-compare + GitHub-releases update layer. The native client sits behind a testable `YoutubeDLClient` seam. |
| **`:transcode`** | `com.sieve.transcode` | The **52-preset** FFmpeg catalog (byte-exact port — token order is load-bearing), MediaCodec hardware-encoder detection (`EncoderDetector` + `AndroidVideoEncoderProbe` over `MediaCodecList`), the thread-cap / CRF / loudnorm / finalize pipeline (`ArgFinalizer`, `ThreadCaps`), custom-preset import/export, and the FFmpeg `-progress` parser + `FfmpegRunner` that execs the self-built `libffmpeg.so`. |
| **`:data`** | `com.sieve.data` | Room persistence (domain-agnostic — status stored as `String`): entities/DAOs for download tasks, history, custom presets, favorites, and subscriptions, plus type `Converters`. Depended on by `:queue`. |
| **`:queue`** | `com.sieve.queue` | Pure-JVM state machine (`core`: `QueueReducer` transition matrix, `NextItemSelector`, `RetryClassifier`, `ProgressMapper`, `QueueAggregator`) + a foreground-**Service** scheduler (`service`: `QueueManager`, `JobDriver`, `QueueService`, notifications, `Real{Download,Transcode}Port`) + a Room-backed persistence bridge (`persist`: `RoomQueuePersistence`) so the queue survives process death and auto-resumes. Output location is an `OutputLocationProvider` seam. Depends on `:data`, `:engine`, `:transcode`. |
| **`:storage`** | `com.sieve.storage` | Fills the queue's `OutputLocationProvider` seam with a SAF/MediaStore implementation: `SafOutputProvider` writes to a real app-files work dir then **stream-copies** into a `DestinationSink` (`SafTreeSink` / `MediaStoreDownloadsSink` / `AppFilesSink`, chosen by `DefaultSinkSelector`) — never handing a `content://` URI to a native process. Also a `DocumentFile`-based Library browser (`LibraryNavigator` / `LibraryFilter` / `SafDocumentStore`) and subtitle (`SubtitleExporter`) / frame (`FrameExtractor`) export. Depends on `:queue`, `:transcode`. |
| **`:app`** | `com.sieve.app` | Single-Activity **Jetpack Compose + Material 3** UI and the DI wiring that assembles the four library modules into a working app: `SieveApp` builds the object graph and installs the process-wide `QueueRepository`; a 5-destination `NavHost` (Download · Queue · Transcode · Library · Settings, with About pushed) binds Compose routes to ViewModels. Depends on every other module. |

---

## 🔧 Building from source

The Android toolchain builds under **WSL2 / Ubuntu**. Native-Windows Gradle does **not** work in this environment — the Windows JVM fails Java NIO loopback (`PipeImpl` "Unable to establish loopback connection"), which breaks Gradle. The Linux JVM inside WSL2 works, and `/dev/kvm` lets the emulator run accelerated for on-device instrumentation.

Builds run through `wsl-verify.sh`, which mirrors the module sources into the WSL build tree and invokes Gradle:

```bash
# Run a module's unit tests
wsl -u root -e bash -lc "bash /mnt/c/Users/Public/sieve-android/wsl-verify.sh :engine:testDebugUnitTest"

# Assemble the app / run on-device instrumentation on the emulator
wsl -u root -e bash -lc "bash /mnt/c/Users/Public/sieve-android/wsl-verify.sh :app:assembleDebug"
wsl -u root -e bash -lc "bash /mnt/c/Users/Public/sieve-android/wsl-verify.sh :storage:connectedDebugAndroidTest"
```

Or invoke Gradle directly inside WSL:

```bash
cd /root/sieve-android
ANDROID_HOME=/opt/android-sdk ./gradlew --no-daemon :queue:testDebugUnitTest
```

> **Tip:** Always confirm `BUILD SUCCESSFUL` and explicit test counts. Piping Gradle through `grep` masks its exit code.

The self-built FFmpeg build script lives at `transcode/build-ffmpeg/ffbuild.sh`.

---

## 🚦 Project status & roadmap

**v1 scope is downloader + transcode.** No public build is published yet.

**Complete and tested** — `:engine`, `:transcode`, `:data`, `:queue`, and `:storage` all pass green JVM unit / Robolectric suites plus on-device instrumentation on an Android-15 16KB-page emulator:

- Engine analyze / download / self-update.
- The 52 transcode argument-vectors byte-verified in review, plus a real `libffmpeg.so` transcode smoke test.
- Room DAO CRUD and the full enqueue → complete → persist → process-death → resume path.
- SAF prepare / finalize / frame-grab end-to-end.

The self-built FFmpeg `.so` ships for both **arm64-v8a** and **x86_64**, 16KB-aligned and verified.

**In active development** — `:app` (the UI layer). The Compose UI and DI wiring are being built out task-by-task per the UI plan; the module currently scaffolds a launchable `MainActivity` with the screens, ViewModels, theme, and graph wiring landing incrementally.

**Deferred to post-v1** — the `:data` schema already carries entities for history, favorites, and subscriptions, but those features are out of scope for the first release. v1 focuses on a solid downloader and transcoder.

---

## 🧰 Tech stack

- **Language / toolchain:** Kotlin **2.0.21**, AGP 8.7.3, JVM 17. `minSdk 26` (Android 8.0), `targetSdk 35`, `compileSdk 35`.
- **UI:** Jetpack Compose (BOM 2024.09.03) + Material 3, Navigation Compose, Lifecycle ViewModel / Runtime Compose, Coil (thumbnails).
- **Persistence:** Room 2.6.1 (via KSP, in `:data`) and DataStore Preferences 1.1.1 (settings + SAF grant).
- **Serialization / async:** kotlinx-serialization-json, kotlinx-coroutines (Flow / StateFlow).
- **yt-dlp engine:** `io.github.junkfood02.youtubedl-android:library:0.18.1` (GPL-3.0).
- **FFmpeg:** self-built, full-GPL, **16KB-aligned** (NDK r27; libx264 + libx265 + MediaCodec/JNI; `-Wl,-z,max-page-size=16384`, verified `LOAD` align `0x4000`), built for **arm64-v8a** + **x86_64** and **exec'd as a child process** from `nativeLibraryDir` (`libffmpeg.so`, `extractNativeLibs=true`) — never linked in-process. Build script: `transcode/build-ffmpeg/ffbuild.sh`.

---

## 📄 License

Sieve for Android is distributed under the **GNU General Public License, version 3 (GPLv3)**.

The application links against GPL-licensed components at runtime — a self-built, full-GPL build of **FFmpeg** and **youtubedl-android** (which bundles **yt-dlp**). Because these libraries are covered by the GPL, the combined work as a whole is also licensed under GPLv3.

- **Written offer for source:** the complete corresponding source code for Sieve and its GPL-licensed dependencies is available, and a written offer to provide it is included with each release, in accordance with the GPL.
- **In-app license texts:** the full text of the GPLv3 and the licenses of all bundled components ship inside the app and can be viewed from its About / Licenses screen.

See `LICENSE`, `licenses/GPL.txt`, and `licenses/FFMPEG_SOURCE.txt`.

---

## 🙏 Credits and acknowledgements

Sieve is built on the work of others, with thanks to:

- **[yt-dlp](https://github.com/yt-dlp/yt-dlp)** — the media extraction and download engine (released into the public domain under the Unlicense).
- **[youtubedl-android](https://github.com/yausername/youtubedl-android)** by **yausername** — the Android packaging and runtime bindings for yt-dlp (GPLv3).
- **[FFmpeg](https://ffmpeg.org/)** — media decoding, encoding, and transcoding.
- **[Seal](https://github.com/JunkFood02/Seal)** and **[YTDLnis](https://github.com/deniscerri/ytdlnis)** — reference applications whose approaches informed this project.

---

## 📦 Distribution

Sieve is distributed as a **signed APK** in two places:

- The developer's website (primary download).
- A **GitHub Releases** mirror.

Sieve is **not** published on Google Play or F-Droid. Install the APK from one of the sources above; installing from outside an app store may require enabling installation from unknown sources on your device.

---

## ⚖️ Disclaimer

Sieve is a general-purpose tool for downloading and converting media that you have the right to access. It is not affiliated with or endorsed by any content platform or service.

You are responsible for how you use it. Only download or convert content that you own, have created, or are otherwise permitted to access, and respect the rights of content owners, applicable terms of service, and the laws of your jurisdiction.
