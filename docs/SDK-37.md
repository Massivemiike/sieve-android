# Sieve 1.0.4 on the latest Android SDK (API 37, Android 17)

Owner decision: **"Move 1.0.4 to the latest SDK, nothing old."** Every module compiles against and targets the latest *stable* SDK,
Android 17 = API 37 (platform `android-37.0`, build-tools 37.0.0). `minSdk` stays **26**: it is the oldest supported device, not a target.
Preview, beta and canary platforms are never used. `versionName` stays 1.0.4 / `versionCode` 5.

This is the record of that move on the 1.0.4 release candidate: what changed, what each Android 16 / 17 behaviour change means for Sieve
and what was decided, which device checks are still owed, and what was deliberately left out. The same move was done and independently
reviewed on the integration branch `feat/full-parity` (its `docs/SDK-37.md` is the long form); this branch re-applies it on the RC
(`feat/windows-parity` at `f8ba443`), keeping the RC's behaviour otherwise. Nothing from milestone M1/M2 came with it: the Room schema is
still version 1 and `data/schemas` is not part of this branch.

## 1. What moved

| | RC before | Now |
|---|---|---|
| `compileSdk`, all six modules | 35 | **37** (platform `android-37.0`) |
| `targetSdk`, `:app` | 35 | **37** |
| `testOptions.targetSdk`, the five library modules (their instrumentation APKs) | not set (= minSdk 26) | **37** (a library's androidTest APK targets minSdk otherwise and Play Protect blocks it) |
| `minSdk` | 26 | 26 |
| build-tools | AGP default | **37.0.0**, pinned in all six modules (AGP 9.4.1 would otherwise download 36.0.0) |
| Android Gradle Plugin | 8.7.3 | **9.4.1** (built-in Kotlin: `org.jetbrains.kotlin.android` and `kotlinOptions` are gone) |
| Gradle | 8.11.1 | **9.8.0** (wrapper pinned by sha256) |
| Kotlin (compose and serialization plugins, `kotlin-test`) | 2.0.21 | **2.4.20** |
| KSP | 2.0.21-1.0.28 | **2.3.12** |
| Room | 2.6.1 | **2.8.5** (2.6.1 does not work with KSP2) |
| Robolectric | 4.13 | **4.17** (4.13 refuses the targetSdk AGP 9 puts in unit-test manifests) |
| Robolectric emulated Android | 34 | **35** (the highest JDK 17 can run, see 3) |
| Espresso, `:app` androidTest | 3.5.0 (transitive) | **3.7.0** |
| JDK (Gradle, CI) | 17 | 17 |
| `release.yml` SDK packages | `platform-tools` | `platform-tools platforms;android-37.0 build-tools;37.0.0` |

Unchanged on purpose: Compose BOM 2024.09.03, material3, activity-compose 1.9.2, navigation-compose 2.8.0, lifecycle 2.8.6, DataStore 1.1.1,
Coil 2.7.0, core-ktx 1.13.1, WorkManager 2.9.1, coroutines, kotlinx-serialization 1.6.3, Gson, Turbine, androidx.test 1.6.x, MockWebServer,
youtubedl-android 0.18.1, and the release build type (`isMinifyEnabled = false`, `useLegacyPackaging = true`, arm64 only, 16 KB-aligned).

Why not `37.2` (established on `feat/full-parity`, not re-checked here): it also builds, but Android 17 QPR2 was still called a beta when that was done and its SDK package carries
an unexpanded description. QPR1/QPR2 only add APIs and Sieve uses none. Revisit when QPR2 is announced stable.

Three tests keep the move from rotting (all in `:app`): `SdkLevelsTest` fails when a module's `compileSdk`, build-tools or `testOptions.targetSdk`
differs from the app's `targetSdk`, or when a workflow that installs the Android SDK does not install the matching platform and build-tools (the level
itself is not pinned: the next Android release means changing scripts and workflow together, and the test checks that they moved together);
`ManifestPermissionsTest` keeps the local-network permission and `targetSdk >= 37` together; `NativeLibPackagingTest` keeps
`useLegacyPackaging = true` in the four modules whose APK loads a native binary by path. The files they read are declared as inputs of the unit-test
task (`app/build.gradle.kts`), because the build cache is on.

## 2. Toolchain pitfalls found (all reproduced)

* **The Gradle wrapper has to be updated first.** `./gradlew wrapper` cannot run inside the project once the build names AGP 9.4.1; the wrapper was
  generated in an empty project and copied in.
* **`sourceSets["x"]` throws at configuration under AGP 9** (`ClassCastException`); only the block form binds. `:data` registers its androidTest schema
  assets directory that way (`assets.directories.add`).
* **Kotlin 2.4** rejects reifying an intersection type: `RetryClassifierTest.data()` got an explicit return type.
* **Robolectric >= 4.14** answers the `media` authority with a fake provider that throws for an unknown row: `OutputIntentsTest` registers a stub provider.
* **Unit-test manifests of libraries now carry targetSdk 37**, and Robolectric picks its SDK from it unless told. Every module that runs Robolectric therefore has one
  `src/test/resources/robolectric.properties` (`sdk=35`; new in `:queue`, `:storage`, `:data`), and the class-level `@Config(sdk = [34])` pins moved with it
  (the deliberate `[30]` pin, pre-Android-13 behaviour, stays).
* `android:extractNativeLibs="true"` is gone from the source manifest: AGP 9 warns about it and `packaging.jniLibs.useLegacyPackaging = true`
  (`:app`, `:engine`, `:storage`, `:transcode`) makes AGP write it into the merged manifest itself. `:engine` gained the same switch: with AGP 9 and
  minSdk 26 its instrumentation APK stores native libs uncompressed, and youtubedl-android opens `lib/<abi>/libpython.zip.so` by path.
* **Room 2.8.5 writes a shorter schema JSON** than 2.6.1 (it leaves out every key that holds its default). The RC commits no schema, so there is nothing to
  regenerate and nothing in `data/schemas` changed. Whoever commits the first schema file (milestone M1 does) will get the compact form, and any reader of
  that JSON must treat a missing `notNull`, `unique` or list as its default (`feat/full-parity` fixed its `SchemaJson` test reader for exactly that, 16a9c1a).
* `Configuration.setVisible(boolean) has been deprecated` is printed under `Configure project :app` by every run: it comes from AGP 9.4.1's own plugin code.
* Gradle 9.8.0 + AGP 9.4.1 sit one step above the range Kotlin documents for KGP 2.4.20 (Gradle <= 9.7.0, AGP <= 9.3.1). Verified green end to end;
  fallbacks if upstream regresses: Gradle 9.7.1 (AGP 9.4.1 needs >= 9.6.0), or AGP 9.3.x.
* The Compose compiler 2.4.20 is paired with the old Compose runtime of BOM 2024.09.03. A runtime `NoSuchMethodError` on a device would be fixed by a newer BOM.

## 3. Open item of the toolchain: JDK 21 for Robolectric SDK 36/37

Robolectric 4.17 runs SDK 34 and 35 on JDK 17 but needs **JDK 21 for SDK 36 and 37**. The WSL runner and CI use JDK 17 (AGP 9.4.1, Gradle 9.8.0 and Kotlin 2.4.20 are
all fine on it), so Robolectric emulates **SDK 35**. Nothing Sieve ships depends on that: compile and target are 37, and Robolectric cannot validate any Android 16/17
behaviour either way. To emulate 37 once a JDK 21 exists: JDK 21 for the runner and `java-version: '21'` in `release.yml`, `sdk=37` in the four `robolectric.properties`
files, and the two `@Config(sdk = [35])` pins to `[37]`. Expect small fake differences like the media provider one.

## 4. The local-network permission (Android 17, targetSdk 37)

With targetSdk 37 on Android 17 an app can only reach the local network once the user grants `android.permission.ACCESS_LOCAL_NETWORK` (runtime, group
NEARBY_DEVICES). It covers everything in the app's UID, including the yt-dlp child process: TCP times out, UDP gets `EPERM`; loopback, DNS and the internet are
unaffected. Local means `10/8`, `172.16/12`, `192.168/16`, `169.254/16`, `100.64/10`, `224/4`, broadcast, IPv6 link-local / unique-local / multicast, `.local`
names and a globally routable IPv6 address whose prefix the Wi-Fi holds on-link. Sieve supports LAN links on purpose (single-label hosts like `nas`, any proxy host), so without work a NAS
link or a LAN proxy would time out and be retried as a flaky network.

| Piece | Where |
|---|---|
| The permission, declared; kept together with `targetSdk >= 37` by a test | `app/src/main/AndroidManifest.xml`, `ManifestPermissionsTest` |
| Pure classifier (`isLocalHost`, `connectsToLocalNetwork`, `proxyOfArgs`, `needsLookup`, `isGlobalIpv6`, `ipv6InPrefix`) | `engine/.../site/LocalNetwork.kt`, `LocalNetworkTest` |
| Device facts: required from API 37, granted or not, the app's settings page | `app/.../net/LocalNetworkAccess.kt` |
| A public-looking name that points at the LAN (split-horizon DNS, Plex, Tailscale) is resolved once and judged; 1.5 s at most, never throws | `app/.../net/HostLookup.kt` |
| The prefixes the connected Wi-Fi / Ethernet networks hold on-link, read from `LinkProperties` | `app/.../net/OnLinkNetworks.kt` |
| The two decisions: `needsPermission(url)` (Download screen) and `guard` (queue) | `app/.../net/LocalNetworkGate.kt`, `LocalNetworkGateTest` |
| Ask **at the point of use**: Analyze and Download ask when the link, or the proxy a new download would use, is on the LAN, and run after a grant; after a refusal nothing starts and a snackbar says why, with a Settings button; if the Activity was recreated while the dialog was up the answer still arrives | `app/.../ui/common/LocalNetworkPrompt.kt`, `DownloadRoute.kt`, `LocalNetworkPromptTest` |
| Name the cause when it still happens (permission revoked while a row is queued, a path that does not ask): a timeout or unknown network failure of a LAN row becomes `ErrorKind.LOCAL_NETWORK` ("Sieve can't reach your local network", hint: allow Nearby devices) and is **never auto-retried** | `queue/.../service/LocalNetworkGuard.kt`, `JobDriver.kt`, `engine/.../parse/YtdlpErrors.kt` |

**Adapted to the RC.** (a) A queued row is judged by the `--proxy` it carries: the RC bakes the Settings proxy into the row's yt-dlp arguments when it is enqueued, and a new
download is judged by today's Settings proxy. If a later change moves the proxy to spawn time, `LocalNetworkGate.guard` must read the Settings proxy for rows without the flag.
(b) The RC has no app-wide message mailbox, so the dialog's answer is posted through a small one, `SnackbarMessages`, which `rememberAppSnackbarHost` collects.

Limits, on purpose: never asked at launch and never below Android 17 (there the permission does not exist, so every question answers "not required" first); no prompt when the Proxy
setting is saved (the first download that would use a LAN proxy asks); paths that do not go through the Download screen (Retry or Resume of a row that was queued before the permission was revoked) do not ask,
for them the failure message is the safety net; Coil thumbnails from a LAN host do not load without the permission; the system stops showing the dialog after repeated refusals,
the Settings button is then the only way; the on-link read is a snapshot of what `LinkProperties` reports, and a name is judged by the answer the resolver gives at that moment.

## 5. Android 16 and 17 behaviour changes: one decision per row

Raising targetSdk 35 to 37 crosses the API 36 and API 37 targeting changes, and the Android 16 / 17 "all apps" changes apply on the devices that run them whatever the target.
*Robolectric cannot show any of this; every "device check" below is owed.* The owner's S26 runs **Android 16 (API 36)**: it shows the API 36 rows and the Android 16 all-apps rows;
the API 37 rows and the Android 17 all-apps rows need an Android 17 emulator or device.

| Change | Hits Sieve? | Decision |
|---|---|---|
| Local network permission (37) | **yes** | **Handled in code**, section 4. Verified only on Android 17 (check 7): below API 37 the prompt, the guard and the error text are inert, so the S26 cannot rehearse them. |
| Lock-free `MessageQueue` (37); Espresso < 3.7.0 and Robolectric < 4.17 reflect into it | test dependencies only | **Handled**: `espresso-core:3.7.0` in `:app` androidTest, Robolectric 4.17. Production code never touches `MessageQueue`. Device check 9. |
| Intent-redirection hardening (36, all apps); background-activity-launch hardening for `IntentSender` (37) | maybe | **Investigated, no code.** One site: `InstallResultReceiver` starts the confirm intent the system PackageInstaller puts in `EXTRA_INTENT` of its status broadcast, the pattern the protection allows. Do not call `removeLaunchSecurityProtection`. Check 1 (S26, live now): Settings > Updates, install a higher-versionCode APK, the confirm dialog must appear. Pressing Home right after Install is a different question and pre-existing: a start from a receiver while Sieve is not visible is a background activity launch, restricted on every recent release whatever the target. |
| Predictive back on by default (36) | maybe | **No code.** Back handling is AndroidX only (`NavHost`, the Material 3 `AlertDialog`s of Library, Queue and Settings); no `onBackPressed`, no `BackHandler`, no bottom sheet. `android:enableOnBackInvokedCallback` was not added (it would change back behaviour on Android 13-15 and nothing needs it). Check 2. |
| Edge-to-edge opt-out removed (36) | maybe | **No delta**: no opt-out attribute was ever set and enforcement applies since targetSdk 35. What is there is Material 3 `Scaffold` defaults, unverified on a device: `SieveNavHost` passes the outer Scaffold's padding on and the five screens each open a Scaffold of their own, so a doubled gap under the status bar and above the bottom `NavigationBar` is the **expected finding of check 3**, not a regression of this branch. Not changed here. |
| 16 KB page size (36, all apps) | yes on 16 KB devices, **not caused by the SDK** | The RC's release APK is 16 KB-aligned (`zipalign -c -P 16`, checked, section 7) and Sieve's own `libsieveffmpeg.so` is `0x4000`-aligned. **Exception (upstream, youtubedl-android 0.18.1):** the ffmpeg companion payload `libffmpeg.zip.so`, extracted at run time, holds five 4 KB-aligned libraries (`libwebp`, `libwebpmux`, `libwebpdemux`, `libwebpdecoder`, `libsharpyuv`), so yt-dlp's merge/convert cannot start on a 16 KB-page device. Not fixed here (follow-up F1 below). `android:pageSizeCompat` is deliberately not set. |
| App memory limits (Android 17, all apps) | maybe | **No code.** The docs publish no thresholds and say nothing about child processes, and Sieve's load is exactly that (ffmpeg x265 / SVT-AV1 / x264, CPython yt-dlp). Highest-value Android 17 check: 4K SVT-AV1 and x265 transcodes, two at once (check 8). |
| SELinux domain split: a target-37 app runs as `untrusted_app` instead of `untrusted_app_34` | maybe | **No code expected** (the exec / dlopen rules Sieve relies on are shared). Check 10 on an arm64 Android 17 device: a curl_cffi download, a merge, a software and a MediaCodec transcode, `logcat -b all` for `avc: denied`. |
| Certificate Transparency and ECH on by default (37) | maybe (low) | **No code**, no `network_security_config`. Only app-process HTTPS is affected (update manifest and APK, GitHub release API, Coil thumbnails); yt-dlp and curl_cffi bring their own TLS. Check 10. |
| Safer native DCL: `System.load` of a writable file throws (37) | no | No `System.load` in Sieve or in youtubedl-android 0.18.1; libraries are exec'd from the read-only `nativeLibraryDir`, Python dlopens in a child process. Check 10 watches logcat for `Attempt to load writable file`. |
| Optimized configuration changes (Android 17): no recreation for `touchscreen`, `keyboard`, `keyboardHidden`, `navigation`, `colorMode`, desk-only `uiMode` | no | **No code.** `MainActivity` declares no `configChanges`, no resource is qualified by any of these, nothing in the app reads them. Check 13. |
| Restoring default IME visibility after rotation (Android 17, all apps) | **yes, cosmetic** | **No code.** Rotating with the keyboard up on the URL field or in a Settings text dialog leaves the keyboard hidden on Android 17; text and screen survive. Do not set `windowSoftInputMode="stateAlwaysVisible"` (the keyboard would show on every launch on every version). Check 13. |
| `dataSync` foreground-service time limit (6 h) | **yes, pre-existing, not part of this move** | `QueueService` overrides only `onTimeout(startId)`. On Android 15+ the system calls `onTimeout(startId, fgsType)` for a `dataSync` service, so after 6 hours the RC's pause-and-stop handler is not reached. Same on the RC at targetSdk 35; milestone M1's `ONTIMEOUT` change (405d086 on `feat/full-parity`) is the fix. Not ported here (it brings a notification channel and a requeue path). Listed under "Not changed" because it is the one thing in this document the owner may want in 1.0.4 anyway. |
| Developer verification for self-distributed APKs (from 2026-09-30), Advanced Protection Mode | maybe | **Owner decision, not code**: register the signing key, document the advanced flow, tell AAPM users in the Updates card. Test the self-update on such a device if one is available. |
| Post-quantum hybrid APK signing (Android 17) | no | Not adopted: it needs a new classical key and a proper rotation; the update chain depends on the current key. |
| Safer Intents (opt-in) | no | Not opted in. Do not set `android:intentMatchingFlags="enforceIntentFilter"` on `MainActivity`: `QueueNotification.appIntent()` is an explicit, action-less intent. |
| Orientation / resizability ignored on large screens (36, no opt-out at 37) | no | The manifest declares none of them. Tablet / foldable smoke of the phone layout (check 11). |
| Everything else in the Android 16 / 17 lists (health, Bluetooth, GPU, photo APIs, ordered broadcasts, a11y announcements, widgets, SMS OTP, contacts, background audio, keystore limits, implicit URI grants, minor SDKs) | no | None of the APIs is used. `ShareFiles`-style URI access is granted explicitly; `usesCleartextTraffic` appears only in the debug manifest. |

## 6. The transcode hang fix (also in this branch)

Found on the S26 during the M1 gate: about one hardware transcode in three hung (the Qualcomm codec service aborted, the `libsieveffmpeg` child stayed alive at 100 % CPU with no
progress), the first Cancel tap did nothing and the second ended it seconds later. Cause: on Android `java.lang.Process.destroyForcibly()` is SIGTERM again, so the cancel
escalation `q, SIGTERM, "SIGKILL"` was really `q, SIGTERM, SIGTERM`, which a wedged codec thread ignores. Re-applied on the RC:

* `AndroidFfmpegProcess.destroyForcibly()` is a real SIGKILL (`android.os.Process.sendSignal` on the pid read from `Process.toString()`, logged), `awaitExit` declares a child that
  survives SIGKILL gone after 3 s, stdin writes and `destroy()` are bounded and abandonable, lines are read with a length bound (`BoundedLineReader`, 8192 chars).
* `FfmpegRunner`: a stall watchdog (no `-progress` advance for `STALL_TIMEOUT_MS` = 120 s; counted in 1 s ticks so an OS freeze does not count) stops the process (`q`, SIGTERM, SIGKILL);
  a run whose **video encoder is MediaCodec** (`-c:v h264_mediacodec` / `hevc_mediacodec` in the preset args, not merely the encoder toggle) that has not advanced at all 20 s after its spawn
  (`FIRST_PROGRESS_TIMEOUT_MS`; healthy MediaCodec runs produce their first frame within about a second, the signed 1.0.4 test saw four of five save a 19 s clip in 0.7-0.8 s and the fifth hang for 121 s)
  that has shown no media progress 20 s after its spawn is stalled too, so a hang before ffmpeg has written its first frame or packet costs about 20 s, not two minutes. "Media progress" is an `out_time`
  or a `frame` count beyond zero (or the end block): the muxer header's `total_size` and the repeated `frame=0 out_time=N/A` blocks of a wedged-but-open codec do not count, and the 20 s runs from the
  spawn. The short bound is for the first frame only (after it a mid-run stall keeps the 120 s), and never applies to CPU runs
  (including the presets whose encoder is always software, AV1, VP9, ProRes, DNxHR, DVD, GIF, WebP and the audio-only ones, even with the encoder toggle on hardware), to a hardware decoder behind a CPU
  encoder, to the CPU retry, or to a run that seeks with `-ss` (ffmpeg decodes and drops the skipped part first);
  a stalled or crashed (SIGABRT, SIGSEGV, ...) hardware run is retried **once** on the CPU path, a stalled CPU run ends `Done(124, "ffmpeg stopped making progress")`; a run the caller asked
  to stop is never retried; the readers are detached and abandoned after 2 s so a blocked `read(2)` cannot hold the queue slot; `StderrLog` collapses repeated lines, paces the rest
  (300 burst, 50/s) and keeps the 64 KB / 30-line tails; events leave through a 256-slot `DROP_OLDEST` buffer.
* `RealTranscodePort` passes `stopRequested = { run.cancelRequested }` and SIGKILLs a process spawned after a cancel already landed; `RetryClassifier` treats "stopped making progress" as
  permanent, so a hung CPU run ends FAILED with that message instead of being auto-retried.

Not ported (milestone M1, TX-05d): `-nostats` and the stats-line filter. The RC still spawns ffmpeg without `-nostats`, so its periodic `frame= ... speed=` line still reaches stderr; the
log guard handles it (it never repeats, about two lines a second).

## 7. How this was verified (2026-10-02, WSL, JDK 17, no device)

* **Baseline, measured first on the unchanged RC** (`f8ba443`, Gradle 8.11.1 / AGP 8.7.3): 957 unit tests, all green: engine 224, queue 279, transcode 178, storage 91, data 3, app 182.
* **After, a full `--rerun-tasks` run** (615 of 615 tasks executed, none from the build cache, run on the final commit): engine **265**, queue **295**, transcode **225**, storage 91, data 3, app **240** = **1119**, 0 failures.
  The 162 new tests: `LocalNetworkTest` and `YtdlpErrorsLocalNetworkTest` (+41, :engine); `JobDriverLocalNetworkTest`, `RealTranscodePortTest`, `QueueManagerHungTranscodeTest` and `RetryClassifierStallTest` (+16, :queue);
  `AndroidFfmpegProcessTest` (more cases), `FfmpegRunnerStallTest` and `RunnerLogGuardsTest` (+47, :transcode); `LocalNetworkGateTest`, `LocalNetworkAccessTest`, `HostLookupTest`, `OnLinkNetworksTest`,
  `LocalNetworkPromptTest`, `SnackbarMessagesTest`, `SdkLevelsTest`, `ManifestPermissionsTest` and `NativeLibPackagingTest` (+58, :app).
* **A flake found by that run, and fixed.** The first no-cache run failed two tests of `RealTranscodePortTest` (an old one, "cancel after the process exited is a no-op", and a new one, "a hardware run that
  crashes by itself is retried on the CPU"); they passed in 12 isolated repeats and in every earlier run. Cause: those tests wait for the runner's fake process on a *real* thread (the probe hops to
  `Dispatchers.IO`, the polls to `Dispatchers.Default`) while `runTest` owns the clock, so while the test body is suspended the virtual clock can run ahead and the 120 s stall watchdog expires on a perfectly
  healthy fake process (a `q` nobody asked for; the retry logged "stopped making progress" instead of "crashed"). Reproduced deterministically by shortening the watchdog to 3 ticks: exactly those assertions fail.
  The watchdog is production code behaving correctly; the tests were racing it. `RealTranscodePort` has an internal `limits` parameter (production always uses the defaults) and the two test classes that run a fake
  process under `runTest` there (`RealTranscodePortTest`, `QueueManagerHungTranscodeTest`) switch the stall bound off (`NO_STALL`); `FfmpegRunnerStallTest` still owns the watchdog and is pure virtual time. With the
  3-tick watchdog those two classes now pass. `feat/full-parity` has the same latent race in its copies of both classes.
* The six `compileDebugAndroidTestKotlin` tasks, `:app:assembleDebug`, the six `assembleDebugAndroidTest` APKs and `:app:assembleRelease` (unsigned) build.
* `aapt2 dump badging` (build-tools 37.0.0), release and debug APK: `versionName 1.0.4`, `versionCode 5`, `compileSdkVersion 37` (`platformBuildVersionName 17`), `targetSdkVersion 37`; the merged manifest lists
  `ACCESS_LOCAL_NETWORK` and `extractNativeLibs=true`. The six androidTest APKs (`:app`, `:engine`, `:data`, `:queue`, `:storage`, `:transcode`) report `targetSdkVersion 37` and `compileSdkVersion 37` too; those of `:app`,
  `:engine`, `:storage` and `:transcode` carry `extractNativeLibs=true` (`:engine`'s `libpython.zip.so` is deflated, i.e. extracted), `:data` and `:queue` (no native binary loaded by path) the AGP 9 default `false`.
* Release APK: arm64 only, `zipalign -c -P 16 -v 4` passes, and every ELF in it (`libsieveffmpeg.so`, `libffmpeg.so`, `libffprobe.so`, `libpython.so`, `libqjs.so`, the two androidx libraries) has `LOAD` alignment `0x4000`;
  `libsieveffmpeg.so` is compressed in the APK (the legacy packaging). Unsigned, so no signature check was made.
* Mutation checks in the lane mirror, each killed (the tests that guard it fail): `destroyForcibly` falling back to SIGTERM; the stall watchdog disabled; `stopRequested` not passed by `RealTranscodePort`;
  "stopped making progress" not permanent; the job driver not naming the local network; `cancel()` without its SIGKILL step; `targetSdk` 36 in `:app`; `build-tools` missing from `release.yml`; `useLegacyPackaging = false` in `:engine`.
* `data/schemas` is not part of this branch: the Room export the build writes into the worktree was deleted before every commit, and nothing under `data/schemas` is in the diff.
* **Not run:** any device, emulator or adb (section 8), a signed release, the JVM suites on JDK 21.

## 8. Device checks still owed

Nothing here could run: this work had no device, emulator or adb.

**On the S26 (Android 16), with the target-37 APK**
1. Self-update install end to end (the confirm dialog appears), also with Home pressed right after Install.
2. Back-gesture matrix in gesture and 3-button navigation: Settings sub-pages, every `AlertDialog`, a tab root while the queue runs.
3. Insets and theme pass, keyboard on the URL field and Settings dialogs. Expect, and note rather than fix, a doubled system-bar inset from the nested Scaffolds.
4. The download matrix (YouTube, SoundCloud, Twitch, Dailymotion, Vimeo via curl_cffi, a merge, a transcode) with `adb logcat -b all | grep "avc: denied"` compared with a target-35 build.
5. The OS symptom of the local-network block on Android 16, nothing more: `adb shell am compat enable RESTRICT_LOCAL_NETWORK com.sieve.app`, reboot, a LAN download (`python -m http.server` on a PC) and a LAN
   proxy. Expect the OS behaviour: the connection times out and Sieve retries the row as a flaky network, with no Sieve prompt. That is correct there: below API 37 the prompt, guard and error text are inert.
6. Room 2.6.1 to 2.8.5: open an existing v1 database (upgrade over 1.0.3 or earlier), run the androidTest suites (they are only compiled in this work).
7. **The transcode hang fix:** a hardware (MediaCodec) transcode of a 4K AV1 clip, repeated until one hangs (about one in three on the S26 before the fix): ONE Cancel tap ends the row at once;
   a hang before ffmpeg has written its first frame or packet ends after about 20 s with a CPU retry once (one that hangs after progress began, within about two minutes), and a hang on the CPU retry ends FAILED with "ffmpeg stopped making progress". Then a normal long transcode (an hour of
   video, faststart rewrite) must NOT be cut by the 120 s watchdog, and neither must a 4K AV1/VP9 software preset with the encoder toggle on hardware (its first frame can take 30 s or more). `adb logcat -s SieveTx` shows the SIGKILL line:
   on a hang it must appear about 21 s after the transcode starts, not about 121 s (the signed 1.0.4 evidence cannot say which kind of hang the S26 produces, a silent one or one that keeps printing `frame=0` blocks; this settles it).

**On an Android 17 emulator (`system-images;android-37.0;google_apis;x86_64`, debug build) or device**
8. App memory limits: 4K SVT-AV1 and x265 transcodes with Max transcodes 2, `adb shell am memory-limiter status`, `dumpsys activity exit-info com.sieve.app`; repeat with `am memory-limiter ignore <uid>`.
9. The Compose androidTest suite with Espresso 3.7.0; probe: `am compat enable USE_NEW_MESSAGEQUEUE com.sieve.app`.
10. Update check, thumbnails, one download and one transcode with a logcat sweep for `Attempt to load writable file` and `avc: denied`.
11. Tablet AVD (sw >= 600dp) smoke of the phone layout.
12. 16 KB: `system-images;android-37.0;google_apis_ps16k;x86_64`, a download that merges video and audio (expected to fail until follow-up F1 is done).
13. Attach and detach a hardware keyboard on the Download screen with a half-typed URL, then rotate: text and screen survive. Then rotate with the on-screen keyboard open: on Android 17 the keyboard
    staying hidden afterwards is expected, the text and the dialog must survive.
14. **The real `ACCESS_LOCAL_NETWORK` prompt** (the one check only Android 17 can do): deny (nothing starts, message with Settings), grant (works), revoke and ask again, Nearby devices already granted;
    a loopback proxy (`socks5://127.0.0.1:1080`) and an internet download still work with it denied; a public-looking name that points at the LAN (`http://192.168.1.5.nip.io:8000/x.mp4`) must ask like a
    LAN address does; rotate while the system dialog is up (refuse: the "Not started" message with Settings still appears; grant: "Nearby devices allowed. Tap again to start.").

**Needs a real arm64 Android 17 phone** (the x86_64 emulator runs the debug APK and the x86_64 ffmpeg, not the shipped arm64 paths): the curl_cffi load, the arm64 `libsieveffmpeg.so` exec under the
new SELinux domain, and the 16 KB loads.

## 9. Not changed, and open follow-ups

| ID | What | Gate |
|---|---|---|
| F1 | **16 KB pages and the yt-dlp ffmpeg companion** (section 5): five 4 KB-aligned libraries in youtubedl-android's run-time payload. Routes: a newer upstream payload; or rebuild those libraries 16 KB-aligned (`-Wl,-z,max-page-size=16384`, as `transcode/build-ffmpeg/ffbuild.sh` does for Sieve's own ffmpeg); or point yt-dlp at `libsieveffmpeg.so`. | Do not promote an APK to a 16 KB-page device, or call 16 KB supported, until done. 4 KB-page devices are not affected. |
| F2 | Run Robolectric on SDK 37 (section 3). | None (test fidelity only). |
| F3 | The `dataSync` 6-hour `onTimeout(startId, fgsType)` handler (section 5, last-but-two row). | Owner's call whether 1.0.4 waits for it. |
| F4 | Optional: when the install confirm intent arrives and Sieve is not visible, post a notification that carries it, so the dialog is not silently lost. | Only if check 1 (Home pressed right after Install) shows it is lost. |

Left out of this branch on purpose (all of it lives on `feat/full-parity`): `ci.yml`, `ReleaseBuildConfigTest` and the other H0b guard tests (the RC has `release.yml` only), the Room schema v2 chain and
`data/schemas`, the settings shell and search, `docs/parity`, `tools/device-matrix`, the `-nostats` change (TX-05d), the `ONTIMEOUT` handler, and the later SS-03 proxy-at-spawn change. When the RC is merged into
`feat/full-parity` expect conflicts in all six `build.gradle.kts`, `AppGraph.kt`, `DownloadRoute.kt`, `JobDriver.kt`, `FfmpegRunner.kt` and `release.yml`; the full-parity side is the superset in each case.
