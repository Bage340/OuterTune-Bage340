# Changelog

User-facing highlights for the active `0.11.1 v92` Preview iteration. This is
not a commit-by-commit log. Phone behavior remains unverified until the
checklist in [docs/PHONE_TEST_PLAN.md](docs/PHONE_TEST_PLAN.md) has been run.

## 0.11.1 v92 — Preview / pre-release

- Make local-library scans safer: incomplete traversal or metadata reads must
  not be treated as a complete scan that removes songs from the library.
- Keep scanner database reconciliation atomic and contain manual scan failures;
  SAF traversal and retries remain authoritative.
- Improve recovery of local playback paths and avoid treating local IDs as
  YouTube IDs. Prefer valid local/downloaded files and retry remote streams with
  refreshed stream information where applicable.
- Add safer playlist-folder organization and restore handling.
- Add playlist-to-library and library/playlist transfer workflows.
- Improve download prefiltering, retry handling, and configurable concurrency
  while retaining a conservative default.
- Expand lyrics provider matching/fallback behavior and improve localization
  coverage, including plural quantities and queue wording.
- Preserve song IDs, likes, library/download dates and playlist links during
  scans, including absent roots and unrestricted MediaStore discovery.
- Validate backup archives and staged databases before closing live stores;
  preserve coherent main/WAL snapshots and recover interrupted restores.
- Reject incomplete, missing or unreadable cache files; save migrated audio
  before removing its only cached copy. Cancel active jobs without deleting
  completed files; removing audio preserves playlist membership.
- Play readable local content/file URIs without remote fallback. Bound transfer
  parsing, deduplicate canonical local aliases and roll back cancelled imports.
- Match KuGou recording metadata and preserve real lyric timestamps; prevent
  provider/recording cache collisions and allow retry after provider failures.

This iteration remains a Preview/pre-release. No Stable release has been
authorized by this changelog.
