# Changelog

User-facing highlights for the accepted `0.11.1 v92` Stable release. This is
not a commit-by-commit log. Phone acceptance is tracked separately in
[docs/PHONE_TEST_PLAN.md](docs/PHONE_TEST_PLAN.md); automated checks do not
replace every case in that checklist.

## 0.11.1 v92 — Stable

- Recover access to saved scan folders through Android's folder picker when
  the current app has no SAF grant, including restored settings. Preserve the
  configured roots and library if the picker is cancelled. Stop discovery
  progress and release scanner ownership immediately on failure so retry does
  not wait for an error message to disappear.
- Preserve existing metadata when native tag extraction fails. Repeated full
  rescans retain album identities, bookmarks and order, with accurate counts
  and duration; local album titles cannot overwrite remote albums.
- Reject incomplete account-library snapshots before overwrite reconciliation,
  including malformed rows and unconsumed continuation tokens.
- Consume validated playlist-recommendation continuations when reading a complete
  playlist, so recommendations do not block export or sync or become songs.
  Unknown sections, ambiguous tokens and continuation loops still fail safely.
- Copy complete remote playlists using their source identity. Duplicate choices
  share one confirmed remote/local addition path; failures remain visible.
- Resolve stream holes inside the cache chain and read beyond the first cached
  fragment. Pin compatible stream formats across playback/download caches, and
  prefer readable physical files over stale cached fragments.
- Use the shared safe cancellation path for single-track actions and notification
  cancellation. Manual lyrics search distinguishes provider failure from absence
  and protects replacement searches from stale callbacks.
- Make local-library scans safer: incomplete traversal or metadata reads must
  not be treated as a complete scan that removes songs from the library.
- Keep newly imported local songs linked to the same album in both their
  stored metadata and album relationships, including an existing album.
- Keep scanner database reconciliation atomic and contain manual scan failures;
  SAF traversal and retries remain authoritative.
- Refresh the scanner permission label immediately after access is granted or
  denied, and when returning from Android settings. Granting access does not
  start a scan automatically.
- Improve recovery of local playback paths and avoid treating local IDs as
  YouTube IDs. Prefer valid local/downloaded files and retry remote streams with
  refreshed stream information where applicable.
- Add safer playlist-folder organization and restore handling.
- Preserve cached playlist membership when a remote refresh returns an
  incomplete or unrecognized response. Report refresh success only after the
  local replacement commits; an unconfirmed empty result keeps a nonempty
  cache instead of erasing it.
- Add playlist-to-library and library/playlist transfer workflows.
- Improve download prefiltering, retry handling, and configurable concurrency
  while retaining a conservative default.
- Bound optional YouTube PoToken initialization and requests to 30 seconds so
  a lost WebView callback cannot indefinitely occupy download workers. Preserve
  healthy shared sessions when another request times out or is cancelled.
- Expand lyrics provider matching/fallback behavior and improve localization
  coverage, including plural quantities and queue wording.
- Preserve song IDs, likes, library/download dates and playlist links during
  scans, including absent roots and unrestricted MediaStore discovery.
- Validate backup archives and staged databases before closing live stores;
  preserve coherent main/WAL snapshots and recover interrupted restores.
- Reject incomplete, missing or unreadable cache files; save migrated audio
  before removing its only cached copy. Cancel active jobs without deleting
  completed files; removing audio preserves playlist membership.
- Reconcile download readiness against actual complete cached bytes or a
  readable external file, including after migration. Preserve registry and
  metadata when a configured download folder cannot be fully scanned.
- Play readable local content/file URIs without remote fallback. Bound transfer
  parsing, deduplicate canonical local aliases and roll back cancelled imports.
- Match KuGou recording metadata and preserve real lyric timestamps; prevent
  provider/recording cache collisions and allow retry after provider failures.
- Finish the lyrics loading indicator after an offline/provider failure and
  offer retry without saving a false NOT_FOUND result. Keep cached/local lyrics
  visible during refresh and avoid a second fetch after manual refresh.
- Show concise, localized playback errors and a working Retry action. Keep
  technical diagnostics behind the existing details action instead of showing
  them in the default player message and Toast.
- Keep plain lyric lines separated by newlines without inserting extra commas.
- Open an empty lyrics editor when no text was found, instead of exposing the
  internal negative-cache marker. Preserve existing plain and timed lyrics.

Stable publication was explicitly authorized after the agreed minimal Preview
phone acceptance. Core and Full use the same accepted source `514af1f37d12824188bf0dcbeba0f3724d9321b2`,
the existing Stable package/certificate and versionCode92. Signed/minified CI
run [38159046409](https://github.com/Bage340/OuterTune-Bage340/actions/runs/38159046409) passed. All 10 Stable APK identities and public asset
checksums are checked separately from phone runtime acceptance. Android10/API29
is owner-waived; residual device/provider scenarios remain unverified.

The whole-version review and current evidence limits are recorded in
[the 2026-10-08 review](docs/V92_MEGA_REVIEW_2026-10-08.md).
