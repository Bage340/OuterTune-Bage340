# Changelog

User-facing highlights for the active `0.11.1 v92` Preview iteration. This is
not a commit-by-commit log. Phone acceptance is tracked separately in
[docs/PHONE_TEST_PLAN.md](docs/PHONE_TEST_PLAN.md); automated checks do not
replace every case in that checklist.

## 0.11.1 v92 — Preview / pre-release

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

This iteration remains a Preview/pre-release. No Stable release has been
authorized by this changelog.
