# OuterTune v92 Final Review Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [x]`) syntax for tracking.

**Goal:** Independently verify both recent OuterTune change phases, repair confirmed defects without changing v92, and hand over a safe Preview APK plus a device checklist.

**Architecture:** Split review by data safety, playback/downloads, and lyrics/transfer; keep GitHub/build/release verification and integration with the primary agent. Each confirmed bug gets a focused regression test before the minimal production fix. The final artifact remains the existing `0.11.1-v92` prerelease, not a Stable release.

**Tech Stack:** Kotlin, Android/Room, Gradle/JDK 21, GitHub Actions, PowerShell, Android SDK build tools.

**Spec:** User-provided final review request in this task; repository rules in `AGENTS.md` and `docs/RELEASE_POLICY.md`.

## Global Constraints

- Base `0.11.1`, revision `v92`, versionCode `92`, tag `0.11.1-v92`; never create v93 or a Stable release during this pass.
- Protect existing library, database, playlists, cache, signing keys, and both channel identities.
- Keep scanner and download data-safety fixes test-first; do not infer device success from unit tests.
- Serialize Gradle runs in the shared worktree and keep code ownership disjoint.

---

### Task 1: Establish baseline and traceability

**Files:** Create `docs/V92_FINAL_REVIEW.md`; update `docs/PHONE_TEST_PLAN.md`.

**Interfaces:** Consumes Git history, GitHub state, build configuration and tests. Produces a requirements-to-code-and-test matrix and prioritized manual checks.

- [x] Inspect local status, branches, tag, history, project instructions, GitHub release, issues and CI.
- [x] Record the pre-phase baseline and separate official, scanner, localization, custom and release commits in the review report.
- [x] Compare every requested feature with production wiring and actual test coverage; mark unverified device paths honestly.

### Task 2: Repair scanner and database safety

**Files:** `LocalMediaScanner.kt`, `SongsDao.kt`, `DatabaseRestoreFiles.kt`, `BackupRestoreViewModel.kt`, `App.kt`, and focused tests in `app/src/test`.

**Interfaces:** Consumes configured scan roots and current database/backup files. Produces non-destructive scan reconciliation and crash-consistent restore recovery.

- [x] Add failing tests for unavailable second scan root, local/remote duplicate path, interrupted restore, null backup output and overlapping scanner acquisition.
- [x] Run targeted unit tests and confirm each new case fails for the intended reason.
- [x] Implement minimal fixes without changing stored user identities or deleting rollback files blindly.
- [x] Re-run targeted tests and inspect migrations/Room schema for fresh and upgraded installs.

### Task 3: Repair playback, downloads, lyrics and transfer

**Files:** Existing playback/download, lyrics and transfer sources plus focused tests; KuGou module only if its candidate mismatch is verified.

**Interfaces:** Consumes cached download resources, rejected stream clients and track/provider metadata. Produces durable cache migration, client fallback, validated lyrics selection and exact transfer round trips.

- [x] Add failing tests for cache save failure, rejected non-WEB client, incomplete cache, timed lyric priority, manual search cache identity and CSV empty metadata.
- [x] Run targeted tests to confirm red; implement each minimal root-cause fix; re-run green.
- [x] Review neighboring cancellation, retry, fallback and provider-error behavior without speculative refactors.

### Task 4: Verify final source and artifacts

**Files:** `docs/V92_FINAL_REVIEW.md`, `docs/PHONE_TEST_PLAN.md`, `docs/RELEASE_NOTES_v92.md`, `CHANGELOG.md` and release metadata only where needed.

**Interfaces:** Consumes final HEAD and build outputs. Produces auditable test/build results, signed Preview APK identity and updated existing prerelease.

- [x] Run clean local translation/unit/migration/lint/instrumentation/build matrix on corrected code; repeat same-SHA CI before publication.
- [x] Inspect local package/version/manifest/alignment/native contents and Preview signer; verify signed Stable CI candidates before publication.
- [x] Run `git diff --check`, secret exclusion check and Aislop/manual quality review; fix confirmed findings.
- [ ] Commit and push verified v92 fixes, rerun relevant CI, update existing v92 prerelease notes and APK only after successful signing checks.
- [x] Record remaining manual-only limitations and deliver the P0/P1/P2 phone checklist.
