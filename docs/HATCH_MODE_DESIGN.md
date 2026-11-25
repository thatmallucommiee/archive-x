# ARCHIVE X — Hatch Mode implementation plan

## Product behaviour

ARCHIVE X will provide two explicit extraction modes:

- **Standard Mode** reads the source archive and writes extracted files while preserving the original archive.
- **Hatch Mode** extracts supported archives in verified units and progressively reclaims source-archive space. The original archive is consumed. The UI must explain this before the user confirms.

Hatch Mode is not a cosmetic progress option. It is a destructive storage operation and must never be implemented by simply deleting the source after each file.

## Reference behaviour

KaiHatch is the Android UX/behaviour reference: Standard and Hatch modes, archive preflight, extraction progress and storage statistics, pause/resume/stop, extracted-file browsing, and archive creation/planning utilities.

ARCHIVE X is an independent implementation. Do not copy KaiHatch branding, assets, source code, or UI verbatim. Its public repository identifies it as an Android implementation of HatchZip and is Apache-2.0 licensed. HatchZip's published technical explanation is useful background for the general safety model.

## Safety invariants

1. Never modify a source archive in Hatch Mode until its format, structure, output paths, and required storage have passed preflight.
2. Never reclaim the archive bytes for a unit until that unit's output has been fully written, flushed, verified, and durably recorded in a recovery journal.
3. A journal must distinguish pending, written, verified, committed, and reclaimed units. Recovery must not trust an incomplete output.
4. Stop, pause, process death, and low-storage conditions must leave a recoverable state where the format permits it. Never promise recovery where the provider or format cannot guarantee it.
5. Detect path traversal, absolute paths, symlinks, duplicate paths, file/directory collisions, unreasonable entry counts, decompression bombs, integer overflow, and output-size limits.
6. Do not follow archive-created links during extraction.
7. Hatch Mode must be disabled when the selected document provider cannot provide the required seekable, writable access to the same source document. Do not silently copy the archive to a temporary file; that defeats the low-storage purpose.
8. Always offer Standard Mode when Hatch Mode is unsupported. Show why Hatch Mode is unavailable.
9. Require a separate, explicit destructive confirmation naming the source archive. Standard Mode is the default.
10. Never claim free space was reclaimed until the underlying storage provider confirms the change.

## Format strategy

### First Hatch Mode milestone
- ZIP / ZIP64: primary target. Implement central-directory preflight, safe entry ordering, CRC verification, checkpoint journal, and end-truncation only where the underlying file descriptor supports it.
- Plain uncompressed TAR: later milestone; validate record boundaries and preserve recovery checkpoints.
- APK and JAR: treat as ZIP-compatible containers, while retaining their original file extensions and normal archive safety checks.

### Standard Mode
- ZIP / ZIP64 and plain TAR first.
- Add 7z only with a format-aware library/engine and explicit non-solid support testing.
- Solid 7z, gzip streams, encrypted/password-protected archives, split archives, and formats without safe independent extraction units remain Standard-only or unsupported until implemented and tested.

### PS4 PKG
PS4 PKG is a separate package parser, not an archive mode. First implement metadata/header and entry-table inspection for legally accessible, unencrypted package data. Do not implement decryption, DRM removal, signature bypass, or console-security circumvention. Hatch Mode must not be advertised for PKG unless a future format-specific design proves safe, seekable unit boundaries and recoverability.

## Android storage architecture

Use the Storage Access Framework for user-selected documents and destinations. Hatch Mode requires a source that can be opened as a seekable read/write file descriptor and truncated safely. SAF providers may be cloud-backed or may not support those operations; inspect capabilities and fail closed. Do not assume every `content://` URI maps to a normal local file.

Prefer an engine boundary that accepts a seekable source abstraction, destination abstraction, cancellation signal, progress callback, and durable journal store. Keep UI code independent from format parsing.

## UX requirements

- Mode selector: Standard (default) / Hatch (destructive).
- Preflight screen: detected format, entry count, compressed size, estimated output size, available storage where measurable, Hatch eligibility, and warnings.
- Explicit confirmation: state that Hatch Mode consumes the original archive.
- Active session: current file, bytes/files completed, throughput, elapsed time, estimated remaining time, source bytes reclaimed, and destination written.
- Controls: Pause, Resume, Stop; state clearly what Stop preserves.
- Recovery screen: detect incomplete journal on next launch and offer Resume, Restore Remaining Archive (only where supported), or discard recovery data with a clear warning.
- File browser: browse extracted output without rescanning the source.
- Never show fake precision for storage values or ETA.

## Milestones

1. Replace the current generic stream-only engine with a tested format registry and safe Standard ZIP/TAR extraction.
2. Build ZIP/ZIP64 preflight, CRC verification, path/collision validation, and adversarial tests.
3. Implement a seekable local-file source abstraction and crash-safe journal.
4. Implement ZIP Hatch Mode with fault-injection tests at every commit/reclaim boundary.
5. Add progress, pause/resume/stop, recovery UI, and extracted-file browsing.
6. Add plain TAR and other formats only after format-specific safety review.
7. Implement PS4 PKG metadata parsing as a separate module.

## Current status

The development branch includes a ZIP Hatch engine with final archive-level truncation (not progressive per-file reclamation) and a separate PS4 PKG parser/Hatch gate. PKG metadata extraction is available for plaintext table entries; PFS/encrypted payloads remain inaccessible and force Hatch to preserve the original. Neither implementation has been validated on the target Android device. Keep both experimental and do not claim progressive reclaim, durable recovery, or complete PS4 game extraction.
