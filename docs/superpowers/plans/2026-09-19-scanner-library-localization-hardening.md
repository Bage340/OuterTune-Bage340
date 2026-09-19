# Scanner, Local Library, and Localization Hardening Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Eliminate local-scanner crashes and data-loss semantics, complete application localization coverage, refresh repository documentation/metadata, and produce a signed test APK without publishing another GitHub Release.

**Architecture:** Treat every scanner enumeration as an explicit authoritative result only when all configured sources finish successfully. Provider, permission, storage, cancellation, and partial traversal failures abort before database reconciliation; UI boundaries report ordinary failures while preserving coroutine cancellation. Localization is validated from parsed Android resources against the canonical default key set, then completed per locale using official OuterTune translations first and reviewed translations for fork-only strings.

**Tech Stack:** Kotlin, Jetpack Compose, Android SAF/MediaStore, Room, JUnit/Robolectric/AndroidX Test, Gradle, Python 3 resource audit, GitHub REST API.

**Spec:** `C:/Users/bage/.codex/attachments/c177c5a9-56cc-4b70-9046-85c953b0c885/Вставленный текст.txt`

## Global Constraints

- Preserve the current `versionName = "0.11.1"` and `versionCode = 92`; do not increment either during this task.
- Do not create or update a GitHub Release or publish a new release tag. The already-existing `0.11.1-v92` prerelease predates this task and is not modified here.
- Never generate a new signing key and never commit keystores, passwords, signing configuration, or secrets.
- Preserve YouTube Music, downloads, local media, playlist folders, queue reordering, VISIONOS/fallback playback, stream headers, and the current identity model.
- Do not introduce destructive Room migration, `fallbackToDestructiveMigration`, database clearing, or clear-and-rebuild scanner behavior.
- Scanner reconciliation may hide/remove local records only after a complete authoritative scan; failed, partial, unavailable, denied, or cancelled scans preserve existing records.
- Tests precede production changes and must be observed failing for the intended reason.
- Scanner changes, localization work, documentation, and repository metadata use separate logical commits.

---

### Task 1: Fail-fast SAF traversal and resilient manual scan boundary

**Files:**
- Modify: `app/src/main/java/androidx/documentfile/provider/TreeDocumentFileOt.kt`
- Modify: `app/src/main/java/com/dd3boh/outertune/utils/scanners/LocalMediaScanner.kt`
- Modify: `app/src/main/java/com/dd3boh/outertune/ui/screens/settings/fragments/LocalMediaSettingsFrag.kt`
- Test: `app/src/test/java/com/dd3boh/outertune/utils/scanners/ScanTraversalPolicyTest.kt`

**Interfaces:**
- Produces: `TreeDocumentFileOt.listFilesOrThrow(): Array<DocumentFile>` and scanner traversal that opts into fail-fast listing for authoritative scans.
- Preserves: ordinary `DocumentFile.listFiles()` behavior for non-authoritative callers.
- Manual scan boundary rethrows `CancellationException`, reports unexpected `Exception`, sets failure state, and executes existing cleanup.

- [ ] Add a failing traversal-policy test proving a child listing exception is propagated rather than converted into an empty/partial result.
- [ ] Run `gradlew testCoreDebugUnitTest --tests '*ScanTraversalPolicyTest*'` and verify the expected failure.
- [ ] Add `listFilesOrThrow()` backed by a shared private query implementation; use it from authoritative scanner recursion, including `.nomedia` probes.
- [ ] Add a failing boundary-policy test proving cancellation remains cancellation and ordinary exceptions become reportable failures.
- [ ] Add explicit `CancellationException` rethrow and generic `Exception` reporting in both full and quick Settings scan branches.
- [ ] Move duplicate-URI detection after `takePersistableUriPermission()` so reselecting a configured folder can renew a lost grant.
- [ ] Run scanner unit tests and commit as `fix(scanner): abort partial scans and contain manual failures`.

### Task 2: Scanner reconciliation and startup data-safety regression suite

**Files:**
- Modify: `app/build.gradle.kts`
- Modify: `gradle/libs.versions.toml` only if an existing catalog alias is insufficient
- Create: `app/src/androidTest/java/com/dd3boh/outertune/utils/scanners/LocalMediaScannerTest.kt`
- Create: `app/src/androidTest/assets/scanner/a-before.flac`
- Create: `app/src/androidTest/assets/scanner/a-after.flac`
- Create: `app/src/androidTest/assets/scanner/b.flac`
- Modify: scanner/DAO code only when a failing test proves a remaining data-safety defect

**Interfaces:**
- Consumes: fail-fast traversal from Task 1 and existing `requireSafeReconciliation` policy.
- Produces: real Room-backed tests for successful, failed, empty, partial, cancelled, concurrent, removed-file, metadata-refresh, and mixed local/remote cases.

- [ ] Port the official v0.11.1 scanner test fixtures and adapt package/database setup without importing YTM-removal or AcoustID changes.
- [ ] Seed local plus remote/downloaded records and assert failed/partial scans leave every pre-existing row and relation visible.
- [ ] Assert an authoritative successful scan disables only a physically missing local file and never remote/downloaded songs.
- [ ] Assert cancellation and two concurrent scan attempts cannot reconcile partial state.
- [ ] Assert startup initialization does not modify existing local-library visibility or `LastLocalScanKey` on failure.
- [ ] Run connected tests when a device becomes available; otherwise run compile/package of androidTest and document the runtime limitation.
- [ ] Run `testCoreDebugUnitTest` and commit as `test(scanner): cover authoritative reconciliation safety`.

### Task 3: Data-safety and migration audit

**Files:**
- Modify only if tests expose a defect: `app/src/main/java/com/dd3boh/outertune/db/**`
- Modify only if tests expose a defect: `app/src/main/java/com/dd3boh/outertune/utils/scanners/**`
- Test: existing migration tests and scanner tests

**Interfaces:**
- Verifies Room schema 22, migrations 1 through 22, foreign keys/cascades, local/download/YTM ownership, playlist relations, backup/restore, and startup database creation.

- [ ] Search for destructive migration/clear-table paths and classify every local-song deletion or visibility mutation.
- [ ] Run migration tests and scanner tests against existing schemas.
- [ ] Add a failing regression test before any corrective production edit.
- [ ] Verify no scan operation mutates `isLocal = 0` download/YTM rows or unrelated playlist/folder relations.
- [ ] Commit test-backed corrections separately as `fix(database): preserve media ownership during scans`, or record a no-change audit result.

### Task 4: Automated localization audit

**Files:**
- Create: `scripts/check_translations.py`
- Create: `scripts/translation_allowlist.json`
- Modify: `.github/workflows/build.yml` or the existing validation workflow
- Test: `scripts/tests/test_check_translations.py`

**Interfaces:**
- Consumes default `values/strings.xml` and `values/strings-ot.xml` as canonical translatable resources.
- Produces deterministic per-locale coverage, missing/extra keys, XML errors, empty values, placeholder/type/order mismatches, plural validation, and untranslated-English candidates; ignores `translatable="false"` and explicit proper-noun allowlist entries.

- [ ] Write failing script tests for missing keys, extra keys, placeholder mismatch, malformed XML, missing plural `other`, empty strings, and allowed proper nouns.
- [ ] Implement an XML parser and Android-format-token comparison without modifying resources.
- [ ] Emit machine-readable JSON plus concise human output and nonzero exit on defects.
- [ ] Wire the script into CI before Android build.
- [ ] Run script tests and the audit against the current tree; commit as `test(l10n): add translation coverage validation`.

### Task 5: Canonical locale set and upstream translation merge

**Files:**
- Modify: `app/src/main/res/values-*/strings.xml`
- Modify: `app/src/main/res/values-*/strings-ot.xml`

**Interfaces:**
- Consumes: official ref `official/v0.11.1` at `c7cfd49b64f4530206a74213b1e299a3e6ebb166` and Task 4 audit output.
- Produces: all 50 official locale directories plus any existing quality extra locale, with upstream translations preferred for matching keys.

- [ ] Replace the accidental locale-set divergence by restoring official `values-sdh` while preserving `values-b+ca+ES+valencia` as a documented extra locale.
- [ ] Merge official translations by resource name without overwriting newer fork-specific translations blindly.
- [ ] Preserve locale-specific plural quantities and positional placeholders.
- [ ] Run the audit and Android resource merge task.
- [ ] Commit as `l10n: restore official OuterTune locale set`.

### Task 6: Complete fork translations and remove hardcoded UI text

**Files:**
- Modify: all supported `app/src/main/res/values-*/strings*.xml`
- Modify: `app/src/main/res/values/strings*.xml`
- Modify: Kotlin/Compose files identified by the audit, including `App.kt`, `FolderScreen.kt`, and `SelectionSongsMenu.kt`

**Interfaces:**
- Produces 100% translated coverage for every translatable key in every supported locale, with explicit allowlist only for brands/proper nouns/protocol/codec terms.

- [ ] Move genuine user-visible literals to named resources; leave logs, protocol fields, dynamic metadata, previews, and developer diagnostics out of translation resources.
- [ ] Translate missing keys locale-by-locale using official terminology and consistent music-player vocabulary.
- [ ] Verify placeholders and plural quantities after each locale family batch.
- [ ] Search for UI behavior comparing translated display text and replace it with stable enum/id values where found.
- [ ] Run the localization audit until every locale reports 100% or a documented non-translatable exception.
- [ ] Commit coherent locale-family batches, ending with `l10n: complete fork interface translations`.

### Task 7: RTL, pseudo-locale, lint, and clean build validation

**Files:**
- Modify resources/layout code only for reproduced RTL or clipping defects
- Modify no lint configuration merely to suppress findings

**Interfaces:**
- Verifies Arabic/Persian/Hebrew RTL semantics, generated `en-XA`/`ar-XB` resources where supported, placeholder/plural/XML correctness, and Android lint localization checks.

- [ ] Confirm `supportsRtl` and directional icons/layout behavior from manifests and Compose APIs.
- [ ] Build/test pseudo-locales or enable the supported Gradle pseudo-locale path for debug validation.
- [ ] Run `check_translations.py`, `lintCoreDebug`, `testCoreDebugUnitTest`, and a clean `assembleCoreUserdebug`.
- [ ] Fix root causes of `MissingTranslation`, `ExtraTranslation`, `StringFormatMatches`, plural, typography, and XML findings.
- [ ] Commit any corrections as `fix(l10n): resolve RTL and resource validation defects`.

### Task 8: README and repository metadata

**Files:**
- Modify: `README.md`
- Create: `README_ru.md`
- Preserve/update: `README_ja.md`
- Update through GitHub API after source commit: repository description and topics

**Interfaces:**
- Produces: English default README, real-language selector for existing localized READMEs, Russian README, upstream/GPL attribution, and one universal English About description.

- [ ] Document that GitHub exposes a single repository `description` field and does not localize it per viewer language.
- [ ] Rewrite the default README as an independent OuterTune fork preserving YTM/local playback with selected safe backports and multilingual UI.
- [ ] Add compact `English | Русский | 日本語` selector and translated Russian content.
- [ ] Preserve upstream attribution to `OuterTune/OuterTune` and GPL license notice without implying official maintainer status.
- [ ] Set About description to `Independent OuterTune fork preserving YouTube Music and local playback, with fixes for playback, downloads, and local-library support.`
- [ ] Set accurate topics: `android`, `kotlin`, `jetpack-compose`, `music`, `music-player`, `youtube-music`, `outertune`.
- [ ] Commit source documentation as `docs: refresh fork description and localized README`.

### Task 9: Full regression, signing, push, and test APK handoff

**Files:**
- Modify no version fields
- Produce ignored artifact: signed `OuterTune-0.11.1-v92-*-test.apk`

**Interfaces:**
- Verifies package `com.dd3boh.outertune`, versionName `0.11.1`, versionCode `92`, existing signing certificate, APK v2+ signature, and SHA-256.

- [ ] Run scanner/localization/unit/migration/lint checks and clean release-equivalent build.
- [ ] Audit YTM playback, fallback clients/headers/URL validation, download retry/content-length, local/download priority, playlists/folders, and queue controls for unintended diffs.
- [ ] Run Aislop on all branch changes and resolve material findings.
- [ ] Request independent whole-branch review and fix every blocking finding.
- [ ] Push `codex/fix-scanner-library-l10n` and merge/push to `main` only after all checks pass, as authorized by the task.
- [ ] Build/sign through the existing CI signing workflow without exposing secrets; download the Actions artifact.
- [ ] Verify `apksigner`, certificate fingerprint, package/version badging, file size, and SHA-256.
- [ ] Hand off the APK and a concise phone checklist; explicitly state that no new GitHub Release was created.
