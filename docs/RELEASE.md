# Sieve for Android — Release & Signing Runbook

## 1. One-time: create the release keystore

The signing key is **permanent** — a changed key breaks every future in-app self-update, because
the updater rejects an APK signed by a different certificate. Create it once, back it up in **two**
secure places, and never commit it.

```bash
keytool -genkeypair -v \
  -keystore sieve-release.jks \
  -keyalg RSA -keysize 4096 -validity 10000 \
  -alias sieve
# choose a strong store + key password; record them in your password manager
```

Keep `sieve-release.jks` and its passwords **out of the repo** (`.gitignore` already excludes
`*.jks`, `*.keystore`, and `app/keystore.properties`).

## 2. Local signed build

Copy `app/keystore.properties.example` → `app/keystore.properties` and fill in `storeFile`
(absolute path to the `.jks`), `storePassword`, `keyAlias`, `keyPassword`. Then:

```bash
# in WSL (native-Windows Gradle is blocked here)
wsl -u root -e bash -lc "bash /mnt/c/Users/Public/sieve-android/wsl-verify.sh :app:assembleRelease"
# signed APK: app/build/outputs/apk/release/app-release.apk  (arm64-v8a only)
```

Without `keystore.properties` the release build is **unsigned** (still validates R8) — useful for CI
dry-runs, but never distribute an unsigned APK.

## 3. CI release (GitHub Actions — the mirror)

Add these repo secrets (Settings → Secrets and variables → Actions):

| Secret | Value |
| --- | --- |
| `SIGNING_KEYSTORE` | `base64 -w0 sieve-release.jks` |
| `SIGNING_STORE_PASSWORD` | keystore password |
| `SIGNING_KEY_ALIAS` | `sieve` |
| `SIGNING_KEY_PASSWORD` | key password |

Release flow:

1. Bump `versionCode` **and** `versionName` in `app/build.gradle.kts` (versionCode must strictly
   increase — the updater compares it).
2. Commit, then tag: `git tag v1.1.0 && git push origin v1.1.0`.
3. `.github/workflows/release.yml` runs unit tests, assembles the signed APK, generates
   `update-manifest.json`, and attaches both to a GitHub Release.

## 4. Publish to the PRIMARY channel (the website)

The GitHub Release is the **mirror**; the website is primary. Upload to the site:

- `sieve-vX.Y.Z.apk`
- `update-manifest.json`

and point `UpdateRepository.PRIMARY_MANIFEST_URL` (in
`app/src/main/java/com/sieve/app/update/UpdateRepository.kt`) at the website's
`update-manifest.json`. `MIRROR_MANIFEST_URL` already targets the GitHub Release's
`latest/download/update-manifest.json`.

The manifest shape (from `UpdateManifest`):

```json
{ "versionCode": 3, "versionName": "1.1.0", "apkUrl": "https://…/sieve-v1.1.0.apk",
  "sha256": "…", "minSdk": 26, "changelog": "…" }
```

## 5. GPL corresponding-source checklist (v1 launch blocker)

The whole APK is **GPLv3** (it links a self-built full-GPL FFmpeg and youtubedl-android/yt-dlp).
Before any public APK is distributed, ALL of the following must be true:

- [ ] The app's **complete source** is public (this repo).
- [ ] The **exact corresponding source** for the bundled FFmpeg + yt-dlp is hosted **on the same
      site as the APK**, plus a written **3-year offer** for the source.
- [ ] The FFmpeg `./configure` flags are documented and reproducible (see
      `transcode/build-ffmpeg/ffbuild.sh`) — built **full-gpl**, **never** `--enable-nonfree`.
- [ ] Full license texts ship **in-app** (Settings → About → Licenses: GPLv3, yt-dlp Unlicense,
      the FFmpeg source offer, font OFL) **and** on the download page.
- [ ] Release/store copy positions Sieve as a **general-purpose downloader** — no
      "YouTube ripper" / "rip to MP3" language.
- [ ] A fresh legal review immediately before launch (see the spec's legal/risk posture).

## 6. Manual verification before publishing

The one thing CI can't do is confirm a real install: on a physical device,
`adb install app-release.apk`, launch, open Settings → Updates, and confirm "Check for updates"
works against a staging manifest. (Committing a real `PackageInstaller` install can't run in the
instrumentation suite — it would replace the app under test — so it's verified here by hand.)
