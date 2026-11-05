# 8zip

A 7-Zip-style file manager and archiver for Android (Kotlin + Jetpack Compose).

- Browse device storage with breadcrumbs and quick locations (Download, Documents, DCIM); long-press to multi-select; search, sort (name/size/date) and show-hidden; light/dark theme with Material You colours
- **Compress** to `7z`, `zip`, `tar`, `tar.gz`
- **Extract** `7z`, `zip`, `tar`, `tar.gz`, `rar` (RAR 1-4 incl. passworded via junrar; RAR5 via libarchive, unencrypted only). Also opens `.jar`/`.apk` as zip
- Multiple selection: tap the checklist button (or long-press / tap a plain file) to select many items, then select all/none, compress, extract or delete them together
- Open an archive and tick just the files/folders you want, then extract only those (all formats, incl. passworded; a folder takes everything inside)
- Adaptive app icon (with Android 13 themed-icon support) and a splash screen
- **Delete originals (opt-in):** tick "Delete originals afterwards" when compressing (only after the archive is re-read and verified to hold every file), or turn on "Delete archive after extracting" in the sort/view menu (only after a fully successful, non-partial extract)
- **Delete** selected files/folders (with confirmation; never follows symlinks)
- Never overwrites: existing folders/archives get a ` (1)` suffix
- Preview archive contents without extracting
- **Password protection:** AES-256 encrypted `7z` and `zip` (create and extract); wrong-password and missing-password prompts. tar/tar.gz have no encryption
- Zip-slip protection on extraction
- Open archives from other apps

Built on Apache Commons Compress, XZ for Java and zip4j and junrar and libarchive.

## Build
```bash
gradle :app:testDebugUnitTest :app:assembleDebug
```
Requires JDK 17 and the Android SDK (compileSdk 35).

### Releases
CI runs the tests on every push; pushes to `main` also build a **signed** release APK and publish it as a GitHub Release:
https://github.com/Akrishna87/8zip/releases/latest/download/8zip.apk

Signing key: `app/release.p12` (encrypted PKCS12, alias `eightzip`). Add its password as the repository secret `ANDROID_KEYSTORE_PASSWORD`. Local signed build:
```bash
ANDROID_KEYSTORE_PASSWORD=... gradle :app:assembleRelease
```



## Roadmap
encrypted RAR5 and ISO read support, SAF/content-URI support, split archives.
