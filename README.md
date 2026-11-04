# 8zip

A 7-Zip-style file manager and archiver for Android (Kotlin + Jetpack Compose).

- Browse device storage, multi-select files/folders
- **Compress** to `7z`, `zip`, `tar`, `tar.gz`
- **Extract** `7z`, `zip`, `tar`, `tar.gz` (also opens `.jar`/`.apk` as zip)
- Preview archive contents without extracting
- **Password protection:** AES-256 encrypted `7z` and `zip` (create and extract); wrong-password and missing-password prompts. tar/tar.gz have no encryption
- Zip-slip protection on extraction
- Open archives from other apps

Built on Apache Commons Compress, XZ for Java and zip4j.

## Build
```bash
gradle :app:testDebugUnitTest :app:assembleDebug
```
Requires JDK 17 and the Android SDK (compileSdk 35). CI builds the APK on every push.

## Roadmap
RAR/ISO read support, SAF/content-URI support, split archives.
