# 8zip

A 7-Zip-style file manager and archiver for Android (Kotlin + Jetpack Compose).

- Browse device storage with breadcrumbs and quick locations (Download, Documents, DCIM); long-press to multi-select; search, sort (name/size/date) and show-hidden; light/dark theme with Material You colours
- **Compress** to `7z`, `zip`, `tar`, `tar.gz`
- **Extract** `7z`, `zip`, `tar`, `tar.gz`, `rar` (RAR 1-4, incl. passworded; RAR5 shows a clear "not supported" message). Also opens `.jar`/`.apk` as zip
- Never overwrites: existing folders/archives get a ` (1)` suffix
- Preview archive contents without extracting
- **Password protection:** AES-256 encrypted `7z` and `zip` (create and extract); wrong-password and missing-password prompts. tar/tar.gz have no encryption
- Zip-slip protection on extraction
- Open archives from other apps

Built on Apache Commons Compress, XZ for Java and zip4j and junrar.

## Build
```bash
gradle :app:testDebugUnitTest :app:assembleDebug
```
Requires JDK 17 and the Android SDK (compileSdk 35). CI builds the APK on every push.

## Roadmap
RAR5 and ISO read support, SAF/content-URI support, split archives.
