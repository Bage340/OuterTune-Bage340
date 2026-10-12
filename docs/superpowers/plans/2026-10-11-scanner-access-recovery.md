# v92 scanner access recovery implementation plan

The owner authorized fixing and replacing the existing Stable v92 Core/Full APKs, keeping version, tag and signing identity.

Evidence: Poco Android 13 has READ_MEDIA_AUDIO for Stable, saved Music URI, and no Stable SAF grant. Logcat shows ExternalStorageProvider SecurityException for both methods. Manual catches suspend in showSnackbar before releasing ownership; scannerFailure is not remembered Compose state.

1. Reproduce failure on the installed release, preserve an ordinary UI backup and APK before any update. Record only necessary private diagnostics outside Git.
2. Add a regression for discovery failure leaving progress active, and grant recovery tests for a saved tree without access, read-only grants and cancellation. Keep actual provider denial and atomicity coverage.
3. Add a scan-button preflight opening ACTION_OPEN_DOCUMENT_TREE at the missing included folder; persist only read access, never replace saved roots with another folder. Retain provider checks and abort protection. Use existing translated restore guidance.
4. Stop discovery progress in finally; remember manual failure state; display error messages from a separate UI coroutine so ownership is released immediately. Test cancellation and retry.
5. Run focused scanner/permission/database tests, Stable Core/Full builds and lint. Verify signed package/version/certificates. Check actual minified release on Poco for both methods, rescan preservation, error/cancel/retry and playback.
6. Update the existing scanner issue and relevant release/source docs with evidence. Publish only the two existing universal Stable assets after verification; preserve old assets privately, release text and historical tag.

Review focus: permission cannot be transferred by backup or between packages; selecting a different folder must not narrow configured coverage; picker cancellation must not scan; failed/partial scans must not reconcile; snackbar dismissal must not retain scanner ownership.

Implementation, regression, signed CI and minified Core/Full phone checks are complete. Exact checks and their limits are recorded in [scanner verification](../../V92_SCANNER_FIX_2026-10-12.md); broader device acceptance stays separate.
