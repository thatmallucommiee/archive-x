# ARCHIVE X

Android-first archive and package inspection utility.

## Status

Early development scaffold. This project is designed to inspect and extract user-provided archives where the format is supported and the content is not protected by encryption or access controls.

## Initial goals

- Android Storage Access Framework; no broad storage permission required.
- ZIP, TAR, GZIP, BZIP2, XZ, 7z, RAR and other supported archive formats as dependencies permit.
- Streaming extraction with progress, cancellation, and low-storage safeguards.
- PS4 PKG metadata inspection and extraction of unencrypted, user-authorized packages only. It will not bypass encryption, DRM, signatures, or console security.
- GitHub Actions APK build.

## Build

Open this project in Android Studio or use the included GitHub Actions workflow to build a debug APK.
