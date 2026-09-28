# Phone test plan — OuterTune 0.11.1 v92 Preview

This plan combines current v92 changes with earlier unverified work recovered
from Git history. Relevant earlier commits include `bace9eca4` (local playback
path recovery and local-ID resolution), `d02d8e333` (deterministic playback
source selection, stream retries and diagnostics), `33d5d4ae4` and `9ac546805`
(fail-safe scanner and data-loss/signing fixes), `84bcb9ad4` (playlist folders
and database migration), and the recent scanner/localization commits
`8c7076190`, `e72fb0548`, `85b89f10e`, `cff5fd9ca`, `609000bfa`, and
`4cb0f0937`.

Phone and service behavior has **not** been verified by this document. Record
the device model, Android version, build flavor, app version, and result for
each case. Do not use your only copy of important data. Use a backup before
database restore or any uninstall/update scenario. Mark checks requiring
accounts, network, a second build, or special test files as skipped if those
preconditions are unavailable; do not interpret skipped as passed.

Priority: **P0** must pass before ordinary use; **P1** covers the principal new
features; **P2** covers edge cases and additional regressions.

## P0 — Installation, data safety, scanner, playback, downloads

### A. Install / launch

**Precondition:** Obtain the v92 Preview APK from the intended trusted build
source; note whether it is Core or Full.

**Steps:** 1. Install the APK. 2. Launch it. 3. Open Library, Settings, and the
player, then force-stop and relaunch.

**Expected:** Installation completes and all screens open; the app starts after
force-stop without a crash or database error.

**Failure indicators:** Installer signature/package error, startup loop, crash,
blank screens, or database-open failure.

### B. Stable and Preview coexistence

**Precondition:** Stable OuterTune is installed and contains a small known
library; Preview APK is available.

**Steps:** 1. Record Stable's library and settings. 2. Install Preview. 3. Open
both apps in turn and inspect their libraries and settings. 4. Add a harmless
test playlist in Preview and re-open Stable.

**Expected:** Both apps install and retain separate app data; Preview changes
do not appear in Stable.

**Failure indicators:** Installer reports a package conflict, either app opens
the other's data, or installing Preview changes/removes Stable data. Do not
uninstall either app to troubleshoot without a backup.

### C. Existing database / user data

**Precondition:** Use an existing installation with playlists, likes, queue
history, and settings; create a verified backup first.

**Steps:** 1. Update using the offered same-package/same-signature APK if
Android accepts it. 2. Open the app. 3. Check songs, playlists, likes, and
settings. 4. Restart and check again.

**Expected:** Existing data remains present and usable after startup and
restart.

**Failure indicators:** Empty/reset library, lost playlist membership, missing
settings, migration crash, or repeated migration on each launch.

### D. Scanner: complete scan

**Precondition:** A folder/tree contains several supported audio files, nested
folders, and at least one file with readable tags; grant the requested media or
SAF access.

**Steps:** 1. Run a full scan. 2. Compare the resulting songs with the test
folder. 3. Scan again without changing files. 4. Play a scanned item.

**Expected:** Accessible supported files appear once; a repeated scan does not
duplicate songs or remove unchanged entries.

**Failure indicators:** Songs disappear, duplicate, have broken paths, or the
scan claims success while omitting accessible nested content.

### D2. Scanner: incomplete traversal and retry (P0)

**Precondition:** A SAF tree or removable/unavailable location has some
accessible audio; have a second tree with a temporarily revoked or unavailable
directory if practical.

**Steps:** 1. Start a scan. 2. Make one location inaccessible during scan, or
use a provider that returns a traversal error. 3. Observe the result. 4. Restore
access and retry.

**Expected:** Incomplete traversal is reported as incomplete/failed and does not
reconcile away previously known songs; retry scans authoritative current
content.

**Failure indicators:** A partial scan is reported as complete, existing songs
are deleted/disabled, or retry cannot recover the collection.

### D3. Scanner: API/storage access

**Precondition:** Android 10 or newer device (API 29+), local files in shared
storage, and app's requested access granted.

**Steps:** 1. Scan shared storage. 2. Open a song. 3. Revoke the relevant access
from system settings and scan again. 4. Grant it again and retry.

**Expected:** Accessible files scan and play; denied access is surfaced without
mass-removing the known library; granting access permits recovery.

**Failure indicators:** Crash, silent destructive reconciliation, stale
permission assumptions, or no recovery after access is restored.

### E. Restart and library preservation

**Precondition:** Several local songs are scanned and one local playlist exists.

**Steps:** 1. Record song and playlist counts. 2. Force-stop the app. 3. Launch
again. 4. Restart the device if practical and re-open the app.

**Expected:** Library and playlist membership remain intact across restarts.

**Failure indicators:** Empty library, lost playlist membership, or songs
disappearing before a deliberate successful scan.

### F. Local music and recovered path

**Precondition:** Have a readable local file present in the library. If testing
the earlier reported lost-path case, identify a song whose audio path is still
readable and whose database path is stale; do not move/delete the only copy.

**Steps:** 1. Play the local song from Library. 2. Play it from a playlist and
queue. 3. If stale-path test data is available, retry playback without scanning.
4. Inspect the song after playback and restart.

**Expected:** Local playback uses the readable file; recoverable paths can be
recovered without changing song identity or playlist/like state. Unavailable
files show a local-storage problem rather than a YouTube “video unavailable”
error.

**Failure indicators:** Local ID sent to YouTube resolution, wrong file played,
identity/membership changes, or a generic remote-video error for a missing file.

### J. Single download

**Precondition:** Logged-in/usable YouTube Music account, stable network, and
enough free storage; choose a short available track.

**Steps:** 1. Download one track. 2. Wait for completion. 3. Confirm it in the
download/library UI. 4. Play it with network disabled.

**Expected:** One complete playable file is present and available offline.

**Failure indicators:** Stuck progress, partial/corrupt file counted as
complete, duplicate request, or offline playback requiring network.

### K. Bulk download

**Precondition:** A small playlist of available tracks and adequate storage.

**Steps:** 1. Start bulk download. 2. Review the preflight summary. 3. Allow
the queue to complete. 4. Compare completed items with the playlist.

**Expected:** Summary distinguishes already downloaded, already queued,
downloadable, and unavailable tracks; only needed items are submitted.

**Failure indicators:** Every track is blindly queued, unavailable items block
the batch, or summary and final results disagree.

### L. Already-downloaded filtering

**Precondition:** Playlist with at least one verified complete download and one
not-yet-downloaded item.

**Steps:** 1. Run bulk download twice. 2. Observe the completed item and queue.
3. If safe test data permits, remove the downloaded file while leaving its DB
marker and run bulk download again.

**Expected:** Valid completed file is skipped without error; stale downloaded
state is not treated as success and can be downloaded again.

**Failure indicators:** Complete file is requeued, missing file is reported as
available offline, or stale state cannot be repaired.

### M. Parallel downloads: one worker

**Precondition:** Parallel-download setting available; a playlist of several
available tracks.

**Steps:** 1. Set parallel downloads to 1. 2. Start a batch. 3. Observe the
queue and wait for completion.

**Expected:** No more than one active download; all available tracks complete
or receive a clear individual error.

**Failure indicators:** Overlapping duplicate work, queue stalls, silent
failures, or unrelated playback stops.

### R. Stream playback and retry

**Precondition:** Network available and several playable YouTube Music tracks.

**Steps:** 1. Play a remote track. 2. Seek and change tracks. 3. Repeat after
letting a stream sit long enough to become stale, if practical. 4. Test next,
previous, and return to the app after backgrounding.

**Expected:** Valid stream plays; recoverable expired/rejected streams retry
with refreshed information; player reports a clear failure for unavailable
content.

**Failure indicators:** Indefinite spinner, stale URL repeated forever, incorrect
headers/client behavior, or player crash.

### S. Playlist playback

**Precondition:** Local and YouTube Music playlists available, with a mix of
downloaded and streaming items if possible.

**Steps:** 1. Start each playlist. 2. Let at least three tracks advance. 3. Use
next/previous and seek. 4. Repeat with one playlist item unavailable.

**Expected:** Correct tracks play in order; one unavailable item does not corrupt
the queue or prevent later playable tracks.

**Failure indicators:** Wrong source/file, queue loss, repeated failed item, or
unrelated local/remote resolution.

### AL. Permissions and data safety

**Precondition:** App installed; know which media/tree permissions were granted.

**Steps:** 1. Inspect permissions in Android settings. 2. Revoke media/tree
access. 3. Attempt scan and local playback. 4. Grant access again and retry.

**Expected:** App explains unavailable access and recovers after permission is
restored; no unrelated permissions are requested.

**Failure indicators:** Silent data deletion, crash, repeated permission prompt
without user action, or access to unrelated files.

## P1 — Main new features and functional regression

### G. Local playlist

**Precondition:** Create a local playlist with multiple local songs.

**Steps:** 1. Open playlist. 2. Reorder songs. 3. Close and reopen it. 4. Play
the playlist.

**Expected:** Membership and order persist and playback uses the selected songs.

**Failure indicators:** Items vanish, reorder is lost, or remote metadata replaces
the local song.

### H. YouTube Music playlist

**Precondition:** YouTube Music login/network works and a remote playlist is
available.

**Steps:** 1. Open the playlist. 2. Refresh it. 3. Play one item. 4. Add or
remove a song if account permissions allow.

**Expected:** Playlist loads and account actions behave consistently with the
service response.

**Failure indicators:** Login loss, empty playlist despite service availability,
incorrect IDs, or local playlist corruption.

### I. Add playlist to library

**Precondition:** Local playlist containing a mix of songs already in and absent
from Library; include repeated entries if possible.

**Steps:** 1. Use “Add to library” from playlist actions. 2. Read the result
summary. 3. Verify Library and playlist entries. 4. Repeat the action.

**Expected:** Existing song entities are reused; already-library songs and
repeats are not duplicated; summary reports additions/skips; second press is
idempotent.

**Failure indicators:** Duplicate song IDs/entities, changed local paths, wrong
YTM IDs, app freeze, or an error when everything was already in Library.

### N. Parallel downloads: more than one worker

**Precondition:** Network and several available tracks; a supported setting
above 1.

**Steps:** 1. Set 2 workers. 2. Start a batch and observe active count. 3. Try
3 only if 2 remains stable. 4. Include one unavailable track if available.

**Expected:** Active count respects the selected limit; successful tracks finish
independently; one failure does not block other workers.

**Failure indicators:** Duplicate/corrupt files, setting ignored, shared-state
failures, app instability, or all work serial despite a higher setting.

### N2. Shared download folder channel isolation

**Precondition:** Both Stable and Preview are installed; use expendable tracks
and a test SAF folder granted to both channels. Back up any existing downloads.

**Steps:** 1. Download the same track in each channel into the test folder. 2.
Confirm both files exist. 3. Delete the Preview download. 4. Play the Stable
download, then reverse the test with a second track.

**Expected:** Each channel indexes and deletes only its own named file; the
other channel retains a playable copy. Separate folders remain recommended to
avoid unrelated scanner overlap.

**Failure indicators:** Removing one channel's download deletes or makes the
other channel's copy unavailable, or either channel indexes the other's file.

### N3. Legacy Preview custom-folder downloads

**Precondition:** An earlier Preview installation has unprefixed `.mka` downloads
in a custom folder. Back up the folder before upgrading. Do not use the only
copy of a track for this test.

**Steps:** 1. Record old downloaded tracks and filenames. 2. Upgrade Preview.
3. Confirm the old files still exist in the folder. 4. Open one as a local file
or download it again in Preview. 5. Check Stable's files remain untouched.

**Expected:** Old files are preserved physically. Because their ownership
cannot be distinguished from Stable files in a shared folder, Preview may no
longer mark them as its own downloads; newly downloaded Preview files have an
`otpreview-` prefix. No old file is silently deleted.

**Failure indicators:** Upgrade deletes an old file, Preview deletes a Stable
file, or a newly completed download is not recognized.

### O. Download failure and retry

**Precondition:** A track that can be made to fail safely (e.g. disable network
during download), then restore network.

**Steps:** 1. Start download. 2. Interrupt network. 3. Wait for failure state. 4.
Restore network and retry manually.

**Expected:** Failure is visible; retry obtains fresh stream information and
headers and completes without a duplicate/stale partial download.

**Failure indicators:** Permanent stale URL retry, duplicate entries, partial
file marked complete, or failure that cannot be retried.

### P. Background download

**Precondition:** Multi-track batch and stable network.

**Steps:** 1. Start download. 2. Background the app and use another app. 3. Lock
the screen briefly. 4. Return and inspect progress and results.

**Expected:** Service/worker continues as designed, and UI reconciles with final
download state after return.

**Failure indicators:** Unexpected cancellation, duplicate restart, frozen
progress, or completed item missing after return.

### Q. Offline playback and download file identity

**Precondition:** At least two fully downloaded tracks; remote streaming works
before disconnecting.

**Steps:** 1. Enable airplane mode. 2. Play each download from Library, playlist,
and queue. 3. Restart while offline and retry.

**Expected:** Completed physical downloads play offline from each entry point;
incomplete cache entries are not treated as playable downloads.

**Failure indicators:** Network required for a complete download, wrong file
selected, or partial cache reported as complete.

### T. YouTube lyrics

**Precondition:** Network available and a track known to have YouTube lyrics.

**Steps:** 1. Open lyrics for the track. 2. Change track. 3. Reopen lyrics after
backgrounding.

**Expected:** Lyrics match the current track and refresh with track changes.

**Failure indicators:** Lyrics from previous track, blank state with no recovery,
or loading loop.

### U. Lyrics fallback providers

**Precondition:** Network and a track with lyrics available from a configured
fallback provider; test a track with no likely match too.

**Steps:** 1. Disable or make the primary source unavailable where practical.
2. Open lyrics. 3. Compare title/artist/version. 4. Repeat for a live/remix track.

**Expected:** Eligible fallback lyrics are shown; ambiguous versions are not
silently mismatched; provider failure falls through cleanly.

**Failure indicators:** Wrong song/version lyrics, crash, duplicate requests, or
fallback never attempted when eligible.

### V. Synchronized lyrics

**Precondition:** Track has timed lyrics.

**Steps:** 1. Play from the beginning. 2. Seek forward and backward. 3. Rotate
or background/foreground the app.

**Expected:** Highlight follows playback time and resynchronizes after seek.

**Failure indicators:** Highlight drifts persistently, jumps to wrong line, or
stops updating after seek.

### W. Plain lyrics

**Precondition:** Track has unsynchronized/plain lyrics.

**Steps:** 1. Open lyrics. 2. Scroll. 3. Change track and return.

**Expected:** Text is readable and remains associated with the correct track;
no timing highlight is implied.

**Failure indicators:** Truncated/unreadable text, stale lyrics, or crash.

### X. Export JSON

**Precondition:** Library and playlist contain local and remote entries; grant
document destination access.

**Steps:** 1. Export library JSON. 2. Export playlist JSON. 3. Open files in a
text viewer and verify song identifiers and playlist membership are present.

**Expected:** Valid parseable JSON represents the requested content and does not
contain credentials or session secrets.

**Failure indicators:** Invalid/truncated JSON, missing entries, secrets in
export, or wrong destination.

### Y. Export CSV

**Precondition:** Library has titles/artists with commas or non-ASCII text.

**Steps:** 1. Export CSV. 2. Open it in a spreadsheet/text viewer. 3. Check
special characters and row/column alignment.

**Expected:** Rows and fields are escaped correctly and text remains intact.

**Failure indicators:** Shifted columns, broken quoting, mojibake, or omitted
tracks.

### Z. Export M3U8

**Precondition:** Playlist has local songs and, if supported, remote songs.

**Steps:** 1. Export M3U8. 2. Inspect text/encoding and entry paths. 3. Import it
back using AA.

**Expected:** UTF-8 playlist is readable; local paths remain meaningful on this
device; unsupported remote references are represented or summarized clearly.

**Failure indicators:** Invalid encoding, malformed paths, silent loss, or
unexplained broken local entries.

### AA. Import supported files

**Precondition:** Test JSON, CSV, and M3U8 files, including a round-trip export.

**Steps:** 1. Import each format. 2. Review preview/summary. 3. Confirm import.
4. Verify resulting tracks and playlists.

**Expected:** Format is identified; valid items import; unavailable local paths
are reported rather than silently creating unusable songs.

**Failure indicators:** Wrong format detection, app crash, malformed partial
database state, or broken entries silently accepted.

### AB. Import duplicates

**Precondition:** Import file includes IDs/paths already present and a genuinely
distinct version/remix with similar title/artist.

**Steps:** 1. Import once. 2. Import the same file again. 3. Compare library
counts and playlist contents.

**Expected:** Stable IDs/paths are deduplicated; distinct versions are not merged
solely because their titles/artists match.

**Failure indicators:** Duplicate entities on re-import or distinct recordings
collapsed into one song.

### AD. Playlist folders

**Precondition:** Several playlists; create a test folder; backup database first.

**Steps:** 1. Move playlists into/out of folder. 2. Rename folder. 3. Reorder
items if available. 4. Restart. 5. Test backup/restore in AI.

**Expected:** Folder organization persists and playlist membership/content is
unchanged; no playlist is lost.

**Failure indicators:** Missing playlists, wrong folder, repeated folders, or
database migration/restore crash.

### AJ. Queue operations

**Precondition:** Queue contains at least five distinct local and remote tracks.

**Steps:** 1. Reorder queued songs. 2. Remove one middle item. 3. Clear queue.
4. Add songs again and move to next/previous.

**Expected:** Queue order/actions persist and clearing the final item does not
crash.

**Failure indicators:** Wrong track order, removed item returns, app crash on
empty queue, or queue metadata resolves to the wrong source.

### AK. Shuffle and repeat

**Precondition:** Queue with at least four tracks.

**Steps:** 1. Toggle shuffle and advance several tracks. 2. Toggle repeat-one.
3. Toggle repeat-all and reach queue end.

**Expected:** Shuffle changes progression; repeat-one repeats current track;
repeat-all cycles queue as indicated.

**Failure indicators:** Mode indicator disagrees with behavior, duplicate storm,
or playback stops/loops incorrectly.

### AF. Portrait UI

**Precondition:** Device in portrait; library and player populated.

**Steps:** 1. Visit Home, Library, playlist, settings, player, and dialogs. 2.
Scroll and open menus.

**Expected:** Controls and text are reachable, not clipped, and menus dismiss
normally.

**Failure indicators:** Overlapping controls, clipped actions, inaccessible
buttons, or layout-triggered crash.

### AG. Landscape UI

**Precondition:** Auto-rotate available.

**Steps:** 1. Rotate player, library, settings, and playlist screens. 2. Open a
dialog/menu in landscape. 3. Rotate back.

**Expected:** Layout adapts or remains usable; state and playback survive
rotation.

**Failure indicators:** Lost playback/selection, clipped dialogs, blank screen,
or crash.

### AH. Localization and RTL

**Precondition:** Use system/app languages including Russian, English, and an
RTL locale if available.

**Steps:** 1. Change language. 2. Inspect navigation, queue, plural counts,
download/transfer dialogs, and playlist actions. 3. In RTL, inspect text order
and icons. 4. Switch back.

**Expected:** Strings are translated where supplied; plural quantities read
naturally; RTL layout remains understandable; no raw resource keys appear.

**Failure indicators:** Missing keys, incorrect count grammar, clipped text,
reversed identifiers, or broken navigation.

### AI. Backup and restore

**Precondition:** Verified backup destination and a separate test copy/device if
available. Preserve the original backup.

**Steps:** 1. Back up database/library. 2. Confirm file exists and is nonempty.
3. Restore only in the test environment. 4. Inspect songs, folders, playlists,
likes, and settings.

**Expected:** Restore preserves supported data; malformed/partial restore does
not silently replace valid current data.

**Failure indicators:** Data loss, partial database state, wrong file selected,
or failure to report invalid backup.

### AM. Update from existing build

**Precondition:** Existing same-channel app with data and a v92 update signed by
the same key. Current public iteration remains v92.

**Steps:** 1. Export/backup data. 2. Attempt in-place update. 3. If Android
rejects same-version replacement, stop and retain the old install/data. 4. Only
perform a clean reinstall if the test owner explicitly accepts data loss or a
verified backup/restore route is available.

**Expected:** Compatible update preserves data; incompatible same-version APK
is clearly recognized as an installer limitation and no data is erased.

**Failure indicators:** Silent uninstall/data wipe, misleading success, or
unexpected change to the public revision.

## P2 — Edge cases and platform checks

### D4. Tag metadata scan failure

**Precondition:** A small test folder with valid songs and one malformed or
unreadable media file; keep a copy of the files.

**Steps:** 1. Scan the folder. 2. Observe scan status and library. 3. Remove or
replace the bad file. 4. Retry scan.

**Expected:** A TagLib/read failure does not authorize destructive
reconciliation; retry can complete and preserve known songs.

**Failure indicators:** Other songs disappear, partial scan reports completion,
or repeated failure prevents recovery after the bad file is removed.

### M2. Parallel download boundaries and interruption

**Precondition:** Several test tracks and a network connection that can be
interrupted safely.

**Steps:** 1. Try minimum 1. 2. Try allowed higher values one at a time. 3. Try
invalid/out-of-range values only if UI permits. 4. Interrupt network and
background/restart the app during a batch.

**Expected:** Value stays within supported limits and never becomes zero; queue
recovers without duplicate completed files or app crash.

**Failure indicators:** Zero/unbounded workers, persistent corruption, queue
deadlock, or crash after restart. Do not run a large stress batch on mobile data.

### AC. Invalid import file

**Precondition:** Copy of malformed JSON/CSV/M3U8 and an unrelated file.

**Steps:** 1. Select each invalid file for import. 2. Cancel one import midway if
possible. 3. Reopen Library and compare pre-test data.

**Expected:** Clear validation error; cancellation or parse failure does not
leave partially imported/corrupted database data.

**Failure indicators:** Crash, app hang, wrong file accepted, or partial
unexplained records.

### AE. Keep screen on

**Precondition:** Find the playback or relevant screen setting that controls
screen-on behavior; note system timeout.

**Steps:** 1. Enable the option and wait longer than timeout. 2. Disable it and
repeat. 3. Leave screen and return.

**Expected:** Screen behavior follows the option and normal lock behavior
returns when disabled.

**Failure indicators:** Screen never sleeps after disabling, or sleeps despite
the enabled setting where it is meant to apply.

### AL2. Security and diagnostics review

**Precondition:** Diagnostics/export feature available; use a test account and
non-sensitive test playlist where possible.

**Steps:** 1. Review requested permissions. 2. Generate a diagnostic report if
playback fails. 3. Inspect it before sharing. 4. Check exports for account
tokens/session material.

**Expected:** No credentials or session secrets are exposed; reports that may
include IDs or local paths are clearly reviewed before public sharing.

**Failure indicators:** Tokens/passwords, private account data, or unexpectedly
broad file access appear in report/export.

### T2. Provider and lyrics edge cases

**Precondition:** Network can be disabled; have one track with known synced
lyrics and one with no lyrics.

**Steps:** 1. Open lyrics offline. 2. Change rapidly between tracks. 3. Seek
repeatedly on timed lyrics. 4. Return online and reload.

**Expected:** Offline/provider failures are graceful; stale responses do not
replace current-track lyrics; timed display resumes after reconnect/reload.

**Failure indicators:** Wrong-track lyrics, crash, stuck loading, or time
highlight tied to a previous track.
