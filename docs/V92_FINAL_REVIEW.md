# OuterTune 0.11.1 v92: final review

Review started 2026-09-30, continued 2026-10-01. This document separates source review, automated evidence,
and device acceptance. It does not authorize a Stable publication.

## Source and preservation

The handoff started at `f067bd08434b1c76d1725857e1436fe4a0d6a93c` on
`codex/fix-scanner-library-l10n`, with 19 modified tracked files and five
untracked files. That state was copied outside the checkout before editing.
The primary checkout's unrelated APK idsig and the separate translation
checkout were left untouched. No user database, preferences or media were used
as destructive test fixtures. Signing material and build outputs stay ignored.

The suggested historical start `1abf8d17f` is not an ancestor of this branch.
The actual merge base is `b7f58abfd3e77b7548bee353761828cfd213a0c2`.
Meaningful phases inspected:

| Phase | Fork commits | Areas |
| --- | --- | --- |
| Playback lineage | `7adb09707`, `d02d8e333`, `bace9eca4` | Local source priority, path recovery, retry/cache clients |
| Base and backports | `287bf67bb`, `33d5d4ae4`, `84bcb9ad4`, `e4ae835d1`, `62c0f80e9`, `9ac546805`, `9e4d64d7e` | Base 0.11.1, scanner, folders/schema 22, insets/screen policy, signing |
| Scanner and localization | `dd55b08fe` through `4cb0f0937` | Partial traversal, atomic reconciliation, translation auditor and locale coverage, SAF retry |
| Custom phase | `f067bd084` (139 files) | Playlist-to-library, download controls, channels, lyrics, transfers and release documentation |

Version remains `0.11.1`, code `92`, existing prerelease `0.11.1-v92`.
Stable package is `com.dd3boh.outertune`; Preview is
`com.dd3boh.outertune.preview`. Their private databases and signing certificates
are distinct. External storage selected by both applications is still shared
storage, so private-data isolation does not imply exclusive ownership of every
external file. Existing unprefixed Preview downloads are not silently claimed
as owned files. GPL/upstream attribution and native-library licenses remain.

## Requirements, wiring and evidence

Paths below are relative to `app/src/main/java/com/dd3boh/outertune` unless
another root is given. Test names identify actual executable coverage; a test
name in this matrix does not itself mean its latest run passed.

| Requirement | Production wiring | Automated coverage / device boundary |
| --- | --- | --- |
| Android 10 storage and SAF | Manifest legacy storage compatibility; `utils/scanners/UriFileUtils.kt`, `TreeDocumentFileOt` | `UriFileUtilsTest`, traversal/policy tests; actual API29 grants/removable media remain phone checks |
| MediaStore, absent roots, no selected paths | `LocalMediaScanner`, `ScanPathPolicy`, startup/manual callers | `MediaStoreReconciliationTest`, `ScanPathPolicyTest`, `PartialTagLibSyncTest`; no-root MediaStore discovery is additive and does not claim authority to hide old rows |
| Atomic reconciliation / ownership | Room `withScannerTransaction`, scanner acquisition lock, caller ownership flags | `ScanDatabaseAtomicityTest`, `ScannerOwnershipTest`, traversal failure tests; old song IDs, likes, download dates and playlist maps retained |
| Local performance / cleanup | Path sets and exact-path lookup before fuzzy matching; batch reconciliation | Local duplicate IDs are retained because they may own separate user state/maps; future creation still deduplicates |
| Insets and queue reorder | `MainActivity`, `Player`, `MiniPlayer`, `Queue`, `Thumbnail` | Semantic adaptations in `e4ae835d1` of upstream `d55e8a071`, `53975c433`, `72260ba5`; reorder wiring remains; gesture/3-button/landscape/RTL require rendering on Android |
| Keep Screen On | `KeepScreenOn.kt`, player/lyrics requests, setting and resumed lifecycle | Upstream `d6044334` adapted in `e4ae835d1`; `KeepScreenOnPolicyTest`; paused/background/disposed states clear the request |
| Playlist folders | `PlaylistFolders`, folder/playlist DAO and library/dialog menus | Upstream `e5a04a38` adapted in `84bcb9ad4`; `PlaylistFoldersTest`, `Migration21To22Test`; case/path rules, links/order retained |
| Schema/migrations | `MusicDatabase` normal/probe builders, schemas 1–22 | Manual 1→2,14→15,15→16,16→17,21→22 plus registered auto chains; all-starting-schema test, 21→22 user-state preservation, fresh/migrated NOCASE parity; no destructive fallback |
| Backup/restore | `BackupRestoreViewModel`, `BackupSnapshot`, `BackupArchive`, startup `DatabaseRestoreFiles` recovery before opening stores | Main+WAL copied under Room write transaction; settings parsed before closing live stores; archive allowlist/bounds; staged header/version/Room/integrity/FK validation; durable rollback/commit/absence markers; `BackupSnapshotTest`, `BackupArchiveTest`, `DatabaseRestoreFilesTest` |
| Local/downloaded source priority | `MusicService`, local source helper, `DownloadUtil`, cache policy | `LocalPlaybackFileTest`, `PlaybackSourcePolicyTest`, content URI/provider fixtures, cache tests; actual offline seek/end/next remains device verification |
| Retry and cache headers | `StreamUrlCache`, `YTPlayerUtils`, playback/download resolvers | `StreamUrlCacheTest`, diagnostics tests; rejected issuing client is passed to next attempt with URL/header coherence |
| Cache migration | `migrateCachedDownload`, `DownloadManagerOt`, directory manager | `DownloadManagerOtTest`: custom save succeeds before native cache is removed; save failure preserves sole copy |
| Download completeness / batch | `DownloadCachePolicy`, `DownloadQueuePolicy`, one index cursor per batch | Full known length, hole-free spans and real readable file lengths; unknown one-byte cache is insufficient; fixtures from 0 to 5000 candidates |
| Parallel downloads | DataStore setting, startup/runtime clamp, actual Media3 manager | Default 1, range 1–3; real network overlap/retry/process death remains manual; custom byte migration is not the regular network queue |
| Cancel / remove audio | `DownloadUtil.removeDownloads`, album/playlist/selection menu and screen callers | Active cancellation stops only active jobs and preserves completed/custom copies; explicit audio removal leaves playlist membership intact; pure mixed-state policy test plus caller review |
| Playlist → Library | `SongsDao` batch transaction and `PlaylistLibraryActionCoordinator`, actual local/YTM playlist menus | `PlaylistLibraryTest`, coordinator tests: 500 songs, duplicates, concurrent presses, rollback, stable IDs/timestamps |
| Lyrics providers / fidelity | YouTube, LrcLib, BetterLyrics, SimpMusic, KuGou, subtitle; local provider separate | Provider preference snapshot, typed recording cache key, timed fallback over preferred plain; `LyricsPriorityTest`, manual/cache/error tests, actual LRC timestamps; no fabricated timing |
| KuGou candidate identity | `kugou` response models and candidate filters | Actual title/artist/album metadata, seconds vs milliseconds, version qualifiers and typed errors; deterministic response/matching fixtures; aliases/translations can conservatively miss a result |
| Library/playlist transfer | `transfer/*`, `LibraryTransferHost`, UI launchers | JSON schema1, CSV13/16/17 compatibility, M3U/M3U8; strict UTF-8/8MiB/10k tracks/500 playlists/field/line/depth/structure bounds; ordered artist/map round trips and 100/1000/5000 fixtures |
| Safe import semantics | Staging + revalidation/access check + Room transaction | Local canonical aliases deduplicate; unresolved locations skipped; imported remote playlist becomes an offline editable snapshot; existing account-linked playlists cannot be changed by the file; cancellation must roll back |
| Channels and release build | `app/build.gradle.kts`, resources, manifest, three workflows | Persistent Preview signer; Core and Full variants; minified Preview Core Release is the publication candidate; Stable candidate builds do not publish a Stable release |
| Localization | `scripts/check_translations.py`, auditor tests, resource compiler | Fresh audit: 51 localized locales, 574 canonical translatable keys, zero structural defects; auditor fixtures passed 16 tests; semantic translation quality remains manual |

`Full` consumes tracked `prebuilt/ffMetadataEx-release.aar`, not the unused
ffmpeg-maker clone formerly present in release CI. Its pinned source and
licenses are documented in `prebuilt/README.md`; TagLib is linked in all
flavors, FFmpeg/nextlib only in Full. Compiler/R8 checks and APK native contents
must both be inspected before publication.

## Handoff findings and corrections

| Finding | Severity | Evidence and correction |
| --- | --- | --- |
| A: partial multi-root authority | High | Root accessibility checked before discovery and before transaction completion; existing rows outside coverage/exclusions remain; unrestricted discovery does not hide absent-volume or content URI entries |
| B: duplicate cleanup could touch remote rows | High | Local-only DAO predicates retained; automatic deletion of existing duplicate song IDs removed so playlist-linked/user-state rows survive |
| C: interrupted restore / rollback | Critical | Startup recovery, stale WAL removal, per-file originally-absent markers, durable commit marker and retained rollback until all recovery steps succeed; restart required once live stores are closed |
| D: false backup success / UI blocking | High | Null output and copy/close errors fail truthfully; work runs on IO; main+WAL snapshot and parsed preferences covered |
| E: competing scanner owners | High | Only an acquired owner can destroy its scanner; busy/reentrant/cancel cases covered |
| F: migration deleted only cache copy first | High | New custom save must finish before `removeResource`; injected save failures preserve cache |
| G: rejected wrong stream client | Medium | Actual cached issuing client is invalidated and excluded on next attempt, not a hardcoded WEB client |
| H: plain preference hid timed fallback | Medium | Real timed provider fallback wins; real plain remains when no timed result exists |
| I: KuGou unrelated candidate | Medium | Actual metadata deserialization and title/artist/version/duration checks on both paths; available album metadata checked |
| J: partial/corrupt completed cache | High | Known complete byte range and actual readable span files required; deleted/truncated file regressions reproduced |
| K: mixed state hid Cancel | High | Active state precedes missing; empty list not complete; all batch caller cancellation preserves completed files; download removal no longer clears playlist maps |
| L: scanner format units | Medium | MediaStore sample rate/bit depth assigned correctly and SIZE bytes used; TagLib uses actual file byte length |
| M: recording/provider cache collision | Medium | Typed mediaId/title/artist/duration/provider-selection cache key; incomplete manual results are not cached, failure/cancellation propagate |
| N: empty/null CSV album | Medium | Explicit albumPresent field preserves distinction, older 13/16-column input remains supported |
| O: historical Full artist order | Medium, disproved on current source | DAO already explicitly orders artist maps by position; historical partial failure did not reproduce; correct assertion retained and passes all four unit variants |
| P: local URI playback | High | File/localhost/content URIs probed for readable local access; DataSpec fields preserved; five local URI/data-source tests pass |
| Q: unknown ZIP members | High | Reject unrecognized/duplicate/directory entries before copying or draining; known entries bounded and cancellation checked; archive tests pass |
| R: unreadable custom download | High | `canRead` required before treating metadata as a complete local copy; fake DocumentFile regression passes |
| S: repeated artist queries | Medium | Exact-name memoization avoids one lookup per repeated track without changing database collation or source identities; query-count regression passes |
| T: cancelled import commit | High | Check coroutine cancellation through tracks, artists, membership and final transaction completion; cancellation during last actual INSERT rolls back all import tables |

Further independent findings were reproduced and corrected: local content/file
URI playback, unknown ZIP members bypassing bounds, unreadable custom-file
metadata causing false skips, repeated shared-artist lookups and missing import
cancellation checks. `LocalPlaybackUriTest`, `BackupArchiveTest`, directory-file
tests and `TransferRepositoryTest` cover those contracts. The local resolver
retains DataSpec offsets/length/key/headers and closes content access probes.
Unknown ZIP entries are rejected before being drained. Artist memoization uses
exact names, and cancellation during the last actual SQL insertion rolls back
the whole transaction. A separate Luna High read-only review of these five
corrective patches reported PASS; it did not claim to run tests.

The migration chain includes an authentic pre-reset schema-1 fixture from
upstream `8bb2735f3^`, stored as `legacy-schema-1.json`. Upstream reset its schema
lineage in `8bb2735f3`, so the current exported schema 1 does not describe the
old integer-artist/playlist migration input. Tests exercise every compatible
starting schema through 22 and a populated legacy-1 migration, retaining likes,
artist and playlist links. Populated 21→22 and fresh/migrated folder parity are
separate tests. Unsupported databases are rejected rather than rebuilt empty.

## Fresh verification ledger

Initial compilers exposed the unfinished regression APIs in playback and KuGou;
these were connected to production contracts rather than deleting tests.
The targeted app RED run reproduced 21 failures in 84 tests. A subsequent
cache/cancel RED run reproduced three failures in 12 tests. Scanner/restore
RED reproduced four failures in 37 tests; the backup failure was a fixture's
blocking checkpoint on the main thread, corrected to IO after its actual
WAL/integrity/liked-row assertions already passed.

The clean run explicitly removed outputs with `:app:clean :kugou:clean
:simpmusic:clean :innertube:clean :lrclib:clean :betterlyrics:clean`, then used
JDK 21.0.12.1, Gradle 9.4.1, the configured Android SDK and
`--max-workers=2 --continue --no-build-cache -DskipFormatKtlint`.
Historical 198-test reports and old APK hashes are not final evidence.

| Fresh unit suite | Tests | Failures/errors | Skips |
| --- | ---: | ---: | ---: |
| Stable Core Debug | 277 | 0 | 0 |
| Preview Core Debug | 277 | 0 | 0 |
| Stable Full Debug | 277 | 0 | 0 |
| Preview Full Debug | 277 | 0 | 0 |
| KuGou | 14 | 0 | 2 live-service tests |
| SimpMusic | 8 | 0 | 0 |
| Innertube | 19 | 0 | 14 live-service tests |

App total: 1108 passes. Module total: 25 passes and 16 explicit skips.
LrcLib and BetterLyrics have no unit test sources; their Gradle `test` tasks
are NO-SOURCE, not additional passing tests. Added coverage explains the rise
from historical 198 to 277 app tests per variant.

Local matrix completed **BUILD SUCCESSFUL in 28m 6s**, 1106 actionable tasks:
582 executed and 524 up-to-date dependency tasks. App and the five provider
modules were cleaned before execution; unchanged included Media3 outputs were
not deleted. CI repeats the matrix from a fresh checkout of the final commit.

| Gate | Actual local result |
| --- | --- |
| `lintStableCoreUserdebug`, `lintPreviewCoreUserdebug`, `lintStableCoreRelease`, `lintPreviewCoreRelease` | PASS, zero errors; each XML contains 339 warnings and three hints |
| `compileStableCoreDebugAndroidTestKotlin`, `compilePreviewCoreDebugAndroidTestKotlin` | PASS, compilation only |
| `assembleStableCoreUserdebug`, `assemblePreviewCoreUserdebug` | PASS |
| Core/Full Release, both Stable and Preview | PASS; R8/minification and resource shrinking executed for all four |
| Release package/version/manifest | PASS: channel-specific package, 0.11.1/code92, no debuggable/testOnly true flags |
| Release ZIP alignment | PASS: SDK 37 `zipalign -c -P 16 -v 4` for all four universal APKs |
| Local Preview signer, Core and Full | PASS: apksigner verifies the persistent Preview certificate below |
| Local Stable signer | Unsigned because no local Stable properties exist; signed candidate CI is a separate mandatory gate |
| Native contents | Core has 12 shared libraries; Full has 44 including FFmpeg/nextlib; all arm64-v8a/x86_64 PT_LOAD alignments are at least 16KiB |
| R8 keep/mapping inspection | TagLib JNI names retained; KuGou generated serializers retained; Full ffMetadataEx keep rule present |

Warnings are reported, not suppressed to manufacture a clean score. Most concern
translations/resources, typography, dependency updates or style. The scanner
static-field warning is conservative: its stored context is explicitly
`applicationContext`. Compiler deprecation warnings remain. Compose tooling
could not collect one third-party AboutLibraries stack-trace mapping; ordinary
R8 mappings were produced and retained. This affects enhanced stack-trace
metadata and did not fail APK compilation.

The local APKs were built from the final corrected code before its commit and
are not the publication bytes. Publication uses the signed Preview Core Release
artifact from CI at the exact final source commit. This prevents attributing a
dirty local build to the old handoff SHA.

Translation audit rerun on 2026-10-01: 51 locales, 574 canonical keys, zero
defects; all 16 auditor tests passed. Three edited workflows parsed without
YAML errors or duplicate keys using the existing installed YAML parser.
`git diff --check` passed. Aislop 0.16.1 reported zero diagnostics but **NOT
SCORED**: only two supported files and 400 unsupported files, predominantly
Kotlin. Kotlin confidence comes from compilation, regression tests and source
review, not an invented Aislop score.

No Android device/emulator was attached during initial verification. JVM
Robolectric tests use isolated test databases. Instrumentation compilation is
not an instrumented/device pass. Network services, real grants/SAF storage,
offline decoder playback/seek, UI rendering, 1–3 concurrent network jobs,
background/process death, installation replacement and Play Protect need the
user's phone. Corresponding issues must remain open until evidence is supplied.

## Publication provenance and phone acceptance

The existing prerelease can be updated only after final checks. Publish the
signed Preview APK with `SHA256SUMS.txt`, signer verification and
`SOURCE_COMMIT.txt` identifying the exact build commit. The historical tag is
not silently force-moved; release notes must explicitly identify any newer
source commit used for the replacement artifact. A tag/source mismatch must
never be hidden behind a claim that the tag's source archive built the APK.

Final source SHA and completed CI run IDs/results are recorded in the release's
`SOURCE_COMMIT.txt` and `VALIDATION.json`; the finalized copy of this report is
attached as `V92_FINAL_REVIEW.md` with exact source, run URLs and APK digest.
This source document belongs to that same source tree and records the local
gate. No commit hash of the old handoff is reused as the corrected build SHA.
Mandatory CI gates: fresh four-variant matrix (`build.yml`), signed Preview
Core Release (`build_ot_pre_release.yml`), and signed Stable Core/Full candidates
(`build_ot_release.yml`). They must all conclude success on the identical SHA
before release assets are replaced. Stable candidates are not a Stable release.

Preview certificate SHA-256:
`25f64fa07f67e341a5847286f7111d59f92d189255d0cf37b1283375f340bf63`.
Stable certificate SHA-256:
`98de410a5f16c5743ca3885d4ded7850fab73730a99bfd67f5912a5d91f6b736`.
No stable release or version93 is authorized here.

[PHONE_TEST_PLAN.md](PHONE_TEST_PLAN.md) contains 13 P0, 21 P1 and 14 P2
unchecked Russian cases with purpose, steps, expected result and safe failure
evidence. Backup restore uses an isolated Preview copy; Stable is a read-only
source of a separately preserved backup. No device case is marked passed by
this source review.

## Review decision

**PASSED FOR MANUAL VERIFICATION** for the tested corrected source: 22
requirements mapped, 20 findings evaluated, 19 corrected and historical finding
O disproved on current source, no known unresolved technical blocker.
Independent Sol review supplied additional corrective findings; a separate
Luna High review checked their finished patches. Root integration used the
Superpowers debugging/verification workflows and the limited Aislop gate.
Publication remains conditional on the same-SHA CI/signing gates above; their
completed results and the distributed bytes are in the finalized release
report. The 48 phone cases remain unchecked and related issues remain open.
