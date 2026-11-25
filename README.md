# ARCHIVE X

**An Android-first archive and package workspace.**

ARCHIVE X is being developed as a native Kotlin + Jetpack Compose application for inspecting archives, extracting files, browsing extracted folders, and handing files to compatible Android applications.

## Current development

- User-selected source files and destination folders through Android's Storage Access Framework.
- Streaming ZIP/ZIP64 and TAR extraction work in progress.
- Path validation, duplicate-name handling, bounded extraction, and clearer failure diagnostics.
- Basic extracted-folder browsing.
- Android open/install handoff and file sharing.

## Not yet implemented

- Hatch Mode and safe in-place storage reclamation.
- PS4 PKG entry-table parsing or extraction.
- RAR, 7z, and other additional archive engines.
- Full file operations such as copy, move, rename, delete, and repackaging.
- Durable extraction sessions, pause/resume, and crash recovery.

Do not treat the current development build as a universal extractor. A successful CI build still requires real-device runtime testing.

## Android integration limits

Files are handed to other apps using Android intents and content URIs. APK installation is handled by Android's package installer and may require user approval or settings changes. ARCHIVE X cannot bypass Android's app sandbox or install PS4 packages on Android.

## Hatch Mode

Hatch Mode is a destructive feature intended to reclaim source-archive storage progressively. It is not enabled until format-specific verification, durable journaling, provider capability checks, and interruption recovery are implemented and tested. See [the Hatch Mode design](docs/HATCH_MODE_DESIGN.md).

## Build

GitHub Actions builds the debug APK for pushes and pull requests targeting `main`, and for manual workflow dispatch. Download the `ARCHIVE-X-debug` artifact from the completed Actions run.
