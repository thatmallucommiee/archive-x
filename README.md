# ARCHIVE X

Android-first archive inspection and extraction app.

## Planned extraction modes\n\nARCHIVE X is being developed with two modes: Standard Mode (preserve the source archive) and Hatch Mode (a destructive, progressive in-place extraction mode inspired by KaiHatch's user workflow). Hatch Mode is **not implemented yet**. It will only be enabled for formats and Android storage providers that pass strict seekability, verification, and recovery checks. See [Hatch Mode design](docs/HATCH_MODE_DESIGN.md).\n\n## Current implementation

- Native Kotlin + Jetpack Compose Android app.
- Uses Android's Storage Access Framework: users choose an input file and an output folder; no broad storage permission or external storage is required.
- Streaming archive listing and extraction for formats supported by Apache Commons Compress's archive stream reader (including ZIP and TAR-family archives where recognised).
- Basic path traversal filtering and per-entry / entry-count safety limits.
- Recognises the PS4 PKG magic signature and reports the format, but does **not** yet parse the PKG entry table or extract PKG contents.

## Important limitations

This is an early development build, not a finished universal extractor. Standalone compressed files (such as .gz/.xz/.bz2), 7z, RAR, split archives, password-protected archives, and encrypted/signed PS4 packages are not yet supported by the current extraction engine. PS4 PKG decryption, DRM removal, signature bypass, or console-security circumvention are out of scope.

Extraction currently has conservative limits and writes directly to a folder selected by the user. A production release still needs stronger archive-bomb protections, duplicate-name handling, cancellation/progress UI, and device testing.

## Build

A GitHub Actions workflow builds a debug APK on pushes to `main` and manual workflow dispatch. Download the `ARCHIVE-X-debug` artifact from the completed Actions run.

You can also open the project in Android Studio and build the `app` debug variant.
