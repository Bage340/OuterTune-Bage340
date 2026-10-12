# Stable v92 scanner repair

APK source: `d38a7f35f3bfd26eb5cb82f7ea1a9f67bf67b424`. The owner authorized replacement of the existing Stable Core/Full assets without changing revision, historical tag, package or signing key. Phone checks below ran on October 11; publication completion was resumed October 12.

## Cause and change

Stable had READ_MEDIA_AUDIO and a saved Music tree but lacked its own persisted SAF permission. Original-release logcat showed ExternalStorageProvider permission denial before discovery for both MediaStore and TagLib. The original Core APK matched the published asset by SHA-256.

Manual scan now requests missing read access through the system picker. Cancelling or selecting a different folder does not replace the saved roots or start a scan. After granting the intended folder, tap Scan again. Discovery resets progress in finally; the manual error message no longer holds scanner ownership until snackbar dismissal. The failure flag survives Compose recomposition. Existing coverage and atomic reconciliation protections remain in place.

## Verification

- The new discovery-state regression failed before the fix and passed after it. The focused scanner, permission and startup/download-directory group passed 59 tests with no failures, errors or skips.
- Signed CI [38166066360](https://github.com/Bage340/OuterTune-Bage340/actions/runs/38166066360) passed both Stable Core/Full unit suites, Release lint and both minified Release builds. Local Release lint had zero errors; existing unrelated warnings remain.
- Both APKs have package `com.dd3boh.outertune`, versionName `0.11.1`, versionCode `92`, the existing sole Stable signer, 16 KiB alignment, and no debuggable/testOnly flag. Installed APK readback matched each candidate SHA-256.
- Poco M2102J20SG / Android 13 accepted Core and Full with `adb install -r`, without uninstall or data reset. Both methods scanned and rescanned two controlled 20-second WAV files while preserving their identities. Both builds played the WAV with advancing playback position.
- Picker cancellation left all 22 database tables and preferences unchanged. Choosing the wrong folder preserved the saved Music root. A controlled unavailable folder produced an immediately idle error state; retry succeeded while the error message was still visible, without restarting the app. This was checked on Core/TagLib and Full/MediaStore.
- Cancellation on the original Music root left song, album and playlist records and the successful-scan timestamp unchanged. After restoring only the controlled test session's ordinary backup, all 22 tables matched the baseline.
- Both methods then completed scanning the real Music root. MediaStore reactivated 1,578 existing local songs; TagLib preserved exactly that database result. All 2,783 original song IDs remained. Titles, durations, likes, playlists, queue and 20 other tables were unchanged; file content lengths were refreshed. Only task-owned phone files were removed. Music, MediaStore and automatic scanning were restored; Core remains installed.

Private backups, device logs, signing diagnostics and publication journals stay outside Git and release assets. The release contains only the two universal Stable APKs. The original APKs are retained privately for rollback.

| APK | SHA-256 |
| --- | --- |
| Core | `6b517e296121ddd04b84a0535b7aa95f5d0e01fa6c26b3b812d7daa484db5685` |
| Full | `c1017f865e366329aed154cb6fffdb073961508f0ac01156df4894df126d6820` |

Android 10/API29 and non-local network/service behavior were not verified by these scanner checks. Broader acceptance issues remain open.
