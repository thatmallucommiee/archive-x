# ARCHIVE X — Product and implementation architecture

## Product definition

ARCHIVE X is a native Android archive and package workspace combining archive inspection, safe extraction, extracted-file browsing, Android application handoff, file sharing, and (in later milestones) archive/package-specific storage reclamation.

Four core areas:
1. **Inspect** — identify formats and inspect contents.
2. **Extract** — Standard preserves the source; Hatch is a separately engineered destructive mode.
3. **Manage** — browse and operate on extracted contents.
4. **Integrate** — hand files to compatible Android apps, share/export, and use supported import flows.

Android-only, no root, and user-authorised Storage Access Framework (SAF) access are foundational constraints.

## Current implementation status

| Capability | Status |
|---|---|
| Native Kotlin / Compose app | Implemented |
| SAF source and destination selection | Implemented |
| ZIP/ZIP64 streaming extraction | Implemented in development branch; CI/device validation pending |
| TAR extraction | Implemented for recognisable TAR streams; CI/device validation pending |
| Path validation and duplicate filename handling | Implemented in development branch |
| Best-effort cleanup of incomplete outputs | Implemented; provider behaviour varies |
| Basic extracted-folder browsing | Implemented in development branch |
| Android open/share handoff | Implemented in development branch |
| APK installer handoff | Uses Android ACTION_VIEW; Android controls installation approval |
| Copy/move/rename/delete/repackage | Not implemented |
| RAR / 7z / standalone compressed streams | Not implemented |
| PS4 PKG parsing and extraction | Not implemented; previous main build only recognised signature |
| Hatch Mode / source reclamation | Not implemented |
| Durable sessions, pause/resume, recovery journal | Not implemented |

A successful Gradle build is not proof of runtime correctness. Test on real Android storage providers and the target POCO device.

## Architecture

```
app/
  Dashboard
  ArchiveInspector
  ExtractionWorkspace
  FileBrowser
  IntegrationCenter

core/
  FormatDetection
  ArchiveRegistry
  StorageAccess
  PathSafety
  TaskLifecycle
  SessionStore

engines/
  ZipEngine
  TarEngine
  SevenZipEngine       (future)
  RarEngine            (future)
  Ps4PkgEngine         (separate future parser)

filemanager/
  Browser
  Selection
  CopyMove
  RenameDelete
  Preview
  Repack

integration/
  AndroidIntents
  PackageInstallerHandoff
  DocumentProviders
  ShareExport
  ImportAdapters

hatch/
  EligibilityPreflight
  ZipDirectoryParser
  CheckpointJournal
  Verification
  Recovery
  StorageReclaimer
```

Format engines must be isolated from Compose UI and use streaming I/O. They must not load whole archives or entries into memory.

## Extraction rules

- ZIP/ZIP64 and TAR are the initial Standard-mode targets.
- Reject absolute paths, drive-qualified paths, traversal segments, and NUL-containing names.
- Do not follow symbolic links or archive-created links.
- Bound entry count, per-entry bytes, and total output bytes.
- Resolve duplicate names without overwriting existing user files.
- Create output only under the SAF folder selected by the user.
- Report exception class and actionable failure reason.
- Remove files created by a failed attempt where the provider allows it; never claim rollback if deletion fails.
- Standard mode never modifies the source archive.

## Android integration boundaries

Files are handed to other apps using Android intents and content URIs. APK files can be handed to Android's package installer. Android or the receiving app may reject the operation, require user confirmation, or require the user to allow ARCHIVE X to install unknown apps.

ARCHIVE X cannot silently write into another application's private data directory or bypass Android package installation security. Import is supported only where the receiving app exposes a compatible intent, document provider, or public API.

## Hatch Mode safety contract

Hatch Mode is destructive and must remain disabled until a format-specific implementation proves:
1. The selected source is seekable and writable through the same underlying document.
2. The format has independently reclaimable units and validated boundaries.
3. Each extracted unit is written, flushed, and verified before durable journal commit.
4. The source is truncated only at a safe boundary after journal commit.
5. Interruption and recovery are tested with fault injection.
6. The UI explains that the original archive is consumed.

SAF providers may not expose safe in-place truncation. If capability cannot be proved, Hatch must fail closed and offer Standard Mode. Do not create a hidden full-size temporary copy.

ZIP/ZIP64 is the first Hatch target. PS4 PKG is not Hatch-compatible without an independent safety design.

## PS4 PKG scope

PS4 PKG is not a normal ZIP/TAR archive. It requires its own header and entry-table parser. Work is limited to legally accessible, unencrypted package data. Decryption, DRM removal, signature bypass, and console-security circumvention are out of scope. Extracted PS4 content can be browsed/exported; it cannot be installed on Android as a PS4 game.

## Testing gates

- ZIP with nested directories, empty/large files, duplicate names, and Unicode names.
- TAR with nested directories and long names.
- Truncated/corrupt archives.
- Absolute paths and traversal attempts.
- Entry-count and expanded-size limits.
- Destination provider refusing create/write/delete.
- Low-storage and interrupted operations.
- APK handoff, generic document open, and share intents.
- Device testing on Android 16 / POCO X7.
- Hatch fault-injection tests before enabling destructive mode.
