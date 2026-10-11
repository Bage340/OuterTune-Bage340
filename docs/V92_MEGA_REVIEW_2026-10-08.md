# v92 whole-version review — October8, continued October11

## Decision

The source review found and corrected real defects, including data-preservation
and playback defects that the earlier broad passing tests did not cover.
No blocking introduced source finding remains in the independent corrective
review. **Stable readiness is not yet established.** A successful fresh account
playlist read after the October11 continuation correction remains outstanding.
Exact-candidate phone acceptance has now
confirmed update preservation, offline playback, rescans, cancellation and local
copy; see the October11 continuation below.
This document does not authorize Stable publication.

The user requested source-led review with a minimal, high-value test set.
The corrective validation deliberately exercises the discovered failures and
their nearby paths rather than repeating the four-variant matrix.

## Scope and source provenance

- Reviewed the full current custom lineage, original requirements, source,
  production callers, existing tests, previous actual CI reports and device
  receipts. The committed starting point is
  `c7023f8fe026115d63492cceceb8a683f0b67958`.
- The suggested historical endpoint `1abf8d17f` is **not an ancestor**.
  The actual common base is `b7f58abfd3e77b7548bee353761828cfd213a0c2`.
  Some defects were inherited or represented missing older safeguards; they
  are not automatically attributed to the latest v92 commit.
- Review included scanner/storage/transfer, playback/downloads/lyrics, account
  library/playlist/UI callers, then an independent corrective integration review.
- Existing work was preserved. No private database, credentials, media, device
  diagnostics or signing material was added to source. No production restore,
  account mutation was performed. The initial source-review phase used no phone
  operations; subsequent exact-candidate phone checks are recorded below.
- Revision remains `0.11.1 v92`, code92, tag `0.11.1-v92`. Stable/Preview
  packages and certificates remain distinct; Room schema remains22. No v93.
- The release's `SOURCE_COMMIT.txt`, `SHA256SUMS.txt`, signature receipt and
  `VALIDATION.json` identify the exact candidate. The historical tag is not moved.

## Corrective findings and resolution

| Finding | Trigger / former result | Correction and meaningful evidence |
| --- | --- | --- |
| S1: failed native metadata became authoritative fallback | Plain TagLib RuntimeException during full refresh could replace existing title, artists and album with defaults | Ordinary extraction exceptions now abort; cancellation propagates. Actual injected extractor failure over two files preserves all saved rows and relations |
| S2: album title collision overwrote user state or a YTM album | Inserting a same-title local track reused an unscoped album and rebuilt its row | Local matches require local ownership; remote matches use supplied ID; existing fields survive. Room fixtures compare complete album state, cross-source collisions and explicit remote links |
| S3: local album counts and order inaccurate | New inserts reused count/index; repeated full rescans incremented count for unchanged songs | Counts/duration derive from actual maps; new indexes use MAX+1; refresh preserves existing index and recalculates previous/target album totals. Repeated scans, retag and album removal are covered |
| S4: automatic album merge lost identity | Same-title cleanup moved only maps then deleted an album while embedded album ID still referred to it | Automatic identity-destroying album merge removed. Existing same-title identities/bookmarks and song IDs survive additive and repeated full refresh |
| Account-library authority | Missing or partly parsed library/continuation could authorize overwrite reconciliation and clear memberships/bookmarks | Completeness-aware production parsing distinguishes explicit empty, decorators, missing containers, malformed rows and extra/unconsumed continuations. completed() rejects incomplete snapshots before destructive callers |
| Playlist copy/duplicate choices | Source fetch used destination's local ID; callbacks wrote remotely before confirmation; duplicate choices only wrote locally | Complete source fetch uses source identity; preparation is awaited; all confirmed choices share remote-then-local coordinator; failures/cancellation prevent false local success |
| Remote reorder race | Old drag positions indexed an asynchronously reordered database | Snapshot captured before move inside awaited transaction; per-screen mutex serializes local move/remote requests; errors surfaced. Live server acceptance remains pending |
| PB1: cache prefix/hole and premature EOF | Resolver outside caches returned opaque ID at holes; arbitrary512KiB request bound could truncate playback | Resolver operates at actual upstream holes; original DataSpec bounds retained. Real nested CacheDataSource reads continuously across a prefix/hole and checks bounded reads |
| PB2: incompatible retained bytes | Quality/client retry could select a different audio representation under one cached media ID | Persisted itag/MIME/length compatibility and shared per-media lock; required-itag fallback; reject incompatible/unknown retained identity without deleting audio. Span ends checked before newly learned shorter length |
| Physical-file priority | Old cached spans could intercept an available local/downloaded file | Direct file/content upstream bypasses remote cache chain. Real stale-cache/file fixture preserves offset, length and uriPositionOffset |
| PB3/PB4: remove/cancel caller mismatch | Single-track removal missed custom file; cancel used asynchronous removal that could delete a just-completed download | Single-track menus and notification cancel use the shared boundary; cancel uses stop reason, explicit remove handles custom/native copies |
| LY1: manual search failure reported absence | Provider failure/timeout looked like no lyrics; stale search could overwrite newer UI | Truthful completion outcome and generation ownership; partial results remain selectable; cancellation propagates. Actual ViewModel failure, timeout and replacement-race fixtures |

All new visible error states use existing translated resources. No new
dependency or destructive migration was introduced. Tests first reproduced
the relevant behavior where practical; initial timestamp-only fixture failures
were corrected using deterministic stored precision without weakening full-state
assertions. Native extraction is mocked only at the native boundary, not at the
database reconciliation or ViewModel state under test.

## Whole-version requirement ledger

| Requirement family | Current source assessment | Practical evidence limit |
| --- | --- | --- |
| Custom local/remote playback lineage, retry and VISIONOS adaptations | Local source priority, path recovery, retry/cache format safeguards, production callers connected | Real provider availability, reconnect/seek and complete offline audio on replacement APK remain device checks |
| Official UI backports / insets / queue / screen-on | Semantic fork adaptations retain authorship; actual source paths and lifecycle ownership inspected | Landscape/cutout/gesture/3-button/tablet rendering is not proved by source review |
| MediaStore/SAF/TagLib authority and cancellation | Coverage checks, partial traversal/extraction abort, scanner ownership and awaited atomic mutation; failed metadata cannot authorize refresh | Provider grants/revocation/removable volumes remain device-specific; Android10 is waived for this review. Native repeated scans on Poco are recorded below |
| Scanner preservation / albums / performance | Song identity/user fields preserved; album scope/state/count/order corrections wired; indexed exact paths and memoization exist | Fuzzy matching and some extraction/SQL remain serial/per-row. No claim of fully batched processing or invented5000-file timing |
| Room21→22 / fresh database parity | Registered additive migration chain, no destructive fallback; generated schema unchanged by corrections | Historical CI covered empty supported schemas and populated1/21, not every populated legacy queue state |
| Backup/staged restore/startup recovery | Archive bounds/allowlist, database probe before replacement, main/WAL consistency and rollback/startup markers inspected | Settings copy precedes locked DB snapshot; no joint DB/DataStore lock. Real power loss/process kill not performed on user's data |
| Downloads prefilter/retry/concurrency/readiness | Existing safe common policies, actual byte/file readiness and failed scan preservation; missing single-item callers corrected | Provider retry and custom-folder permissions/remount on candidate remain device checks; default parallelism1 retained |
| Playlist folders / add-to-library | Real persisted DAO/UI wiring; canonical paths, collision handling, transactional membership preservation and idempotent additions | Case-only folder rename remains documented no-op; device rendering/recreation evidence is historical |
| Account/library/playlist sync | Individual playlist and account-library completeness safeguards; cached state preserved on failure | Owner clarified on October11 that the selected remote playlist had been deleted. Missing contents no longer establishes a failure for an existing playlist. Successful fresh server snapshot remains unverified; #14 stays open |
| Lyrics automatic/manual/editor/provider matching | Existing provider/cache/recording/timestamp logic inspected; manual failures/replacement races corrected; editor keeps plain/timed text | Real lyrics quality/availability depends on providers and recording; skipped live provider tests are not passes |
| JSON/CSV/M3U8 library/playlist transfer | Bounded parsing, canonical local identity, preparation/commit revalidation, transaction rollback and real SAF UI flows inspected | Relative M3U references and portable URI grants remain limits; no account export/import replay in this review |
| Stable/Preview/signing/source buildability | Separate package/data/certificates; code92, permanent tag, pinned Full decoder mechanism and attribution retained | Signed replacement identities must be read from actual APK; Stable build success is not Stable publication or phone upgrade acceptance |
| Localization/docs/release hygiene | Translation audit passes51 locales/574 keys/0 defects; English/Russian README, changelog and review link updated | Kotlin unsupported by Aislop0.16.1; actual result is NOT_SCORED, supplemented by manual wiring/security/preservation review |

## Current corrective verification

Final selected local invocation, JDK21 / Windows / repository SDK:

```text
gradlew.bat :app:testPreviewCoreDebugUnitTest
  --tests *PartialTagLibSyncTest --tests *MediaMetadataInsertTest
  --tests *ConfirmedPlaylistAdditionTest --tests *ManualLyricsSearchIntegrationTest
  --tests *ManualLyricsSearchTest --tests *LyricsRefreshIntegrationTest
  --tests *CachedPlaybackContinuationTest --tests *CachedStreamRepresentationTest
  --tests *PinnedStreamFormatTest --tests *StreamUrlCacheTest --tests *PlaylistSyncTest
  --tests *LocalPlaybackUriTest --tests *DownloadCacheCompletenessTest
  :innertube:test --tests *LibraryResponseTest --tests *PlaylistResponseTest
  --continue --max-workers=2 -DskipFormatKtlint
```

Actual XML: **62 app tests /13 suites and30 Innertube tests /2 suites,
zero failures/errors/skips**. Innertube's final task reused the successful
unchanged selected result; its source had not changed. This is92 selected tests,
not all tests in the repository. `git diff --check` and translation audit pass.
The independent final reviewer inspected actual RED/GREEN receipts and production
callers; all corrective blocking source findings were resolved.

Local `lintPreviewCoreRelease`, `assemblePreviewCoreRelease` and
`compileStableFullDebugKotlin` passed. R8/minification and resource shrinking
executed; lint reports335 warnings/3 hints and no error/fatal. A third-party
Compose stack-trace mapping warning remains; it is not a failed APK build.
Actual local universal APK verifies with Preview certificate
`25f64fa07f67e341a5847286f7111d59f92d189255d0cf37b1283375f340bf63`,
package `com.dd3boh.outertune.preview`, versionName0.11.1/code92, no debug/testOnly
flag and16KiB alignment. Its hash is a local-build receipt, not assumed to be
identical to the later Linux CI candidate.

Release build/signature and exact final source/CI receipts are recorded in the
release `VALIDATION.json`. Those must be inspected for the candidate in question;
the earlier committed-source results below are not substitutes.

## Historical evidence kept separate

- Exactc702 CI: four app variants ×348 tests =1392, zero failures/errors/skips.
  Module receipts had59 tests including16 opt-in live skips; LrcLib/BetterLyrics
  NO-SOURCE. Four lint reports had338 warnings/3 hints, no error/fatal.
- Earlier release artifact inspection:13 APKs,11 signed (1 Preview/10 Stable),
  2 unsigned excluded. These APKs belong to c702, not the corrective source.
- Poco ledger:48 cases,46 PASS/2 SKIP across multiple earlier sources/APKs,
  not48 passes on this replacement. Individual MIUI SAF revocation unavailable;
  deliberate restore interruption was outside the safe authorized plan.
- Exactc702 signed Preview update and failed-refresh preservation audit matched
  all22 database tables and30 typed preferences; the cached playlist had99
  memberships with order intact. A failing refresh preserved data; it did not
  prove successful server sync. Stable on the user's phone was not replaced.
- Earlier API29 signed checks concern older source. Fresh source-matching
  API29 follow-up was not completed: automatic approval review rejected the
  composite emulator-launch action as `blocked by policy`, without further detail.

## October11 continuation on the exact signed candidate

The published Preview from source `8585c09b69b948994b97aed6ab4440c6daf5dbff`
was installed normally over Preview; installed APK readback matches SHA-256
`be3b784efe1e5348ba5a3e749116ee3da45b39721d38bd999d697ee55116dbdf`.
Fresh before/after backups match all22 tables and30 typed preferences, including
authentication and queue. Stable was not installed or restored.

On Poco X3 Pro / Android13:

- Completed downloaded audio played with Wi-Fi/mobile data disabled. Playback
  remained active after seeking to48.5s of a57s item and automatically advanced
  to queue index1 after the end. This does not prove traversal of a partial cache
  hole or playback from a custom download folder.
- A controlled30s local file played offline and remained active after a26.5s
  seek. The queue subsequently reached its last1s item and stopped. The final
  UI was unavailable because another application was foreground; no additional
  claim about that final rendered state is made.
- Repeated full native TagLib scans retained song IDs/user fields, album IDs and
  relation order. The first pass corrected two affected album counts from2 to1,
  matching their actual memberships; unrelated legacy albums were not globally
  repaired. A subsequent scan left all22 tables identical, changing only the
  last-scan preference. One format row was refreshed during the first pass.
- Cancelling an active controlled download restored the idle download control.
  All11 previously completed neighbor markers remained unchanged; physical
  byte equality and the exact completion race were not established on-device.
- Copying12 items into a new local test playlist retained exact IDs/order.
  Only the new playlist and its12 memberships were added. Cancelling the
  duplicate confirmation then left all22 tables and30 preferences identical.
  No remote playlist mutation was performed.
- Manual lyrics search displayed a real failure in the rendered result dialog.
  Successful provider search/replacement remains unverified; absence is not
  inferred from network failure.
- Failed refresh preserved all99 cached memberships/order and all22 tables/
  30 preferences. On October11 the owner corrected their earlier statement:
  the selected remote playlist no longer exists. Separate read-only computer
  requests matched the stored account name/handle but returned no contents for
  that deleted playlist. These requests are not captures of the phone response.

The focused October11 rerun executed21 tests in four suites
(`PartialTagLibSyncTest`, `ManualLyricsSearchIntegrationTest`,
`CachedPlaybackContinuationTest`, `PlaylistSyncTest`), with zero failures,
errors or skips. This is a subset rerun, not21 additional unique tests or a
repeat of the four-variant matrix. No production code changed in this continuation.

The owner explicitly deferred Android10/API29 for this release review. It is
**WAIVED**, not PASS and not a remaining acceptance blocker. The original
full phone plan remains a reference; the current request is a small set of
high-value checks. Private backups, account values and raw diagnostics remain
private. Original network/screen settings were restored after phone work.

## Residual limitations and the smallest remaining acceptance

### Existing-playlist continuation correction

An existing playlist supplied by the owner opened on the signed8585c09b Preview,
but full export failed with `Unconsumed playlist section continuation`. The
blanket completion guard had no reader for the separate recommendation section.
Separate read-only computer requests showed73 membership rows, no song token,
and one section token yielding10 playlist recommendation cards. No raw response
or playlist/account identifier is committed. Failed export preserved songs,
playlists, memberships and all30 preferences; intervening playback legitimately
changed queue, play/event records and negative lyric cache.

The correction consumes and validates the separate section response instead of
ignoring its token. Only recognized playlist recommendation cards are accepted
as non-membership. Unknown/mixed/song-bearing sections remain failures. Raw
renderer/row validation prevents lenient decoding from hiding unknown siblings;
ambiguous metadata, repeated tokens and excessive paging are rejected. Original
song order is retained and both continuation tokens are cleared only after
complete verified reading. Cancellation propagates.

Synthetic intercepted-transport tests reproduce the failure before the fix and
exercise actual playlist completion afterward, including rejection paths.
Independent review found a competing initial membership-container case that
could otherwise authorize truncated replacement. It was reproduced before the
additional guard and corrected; the independent recheck found no remaining
blocking source issue in this change. Fresh module XML records76 passing tests
(59 playlist,12 library,5 account),14 explicitly skipped live-service tests and
zero failures/errors. A fresh app rerun records10 PlaylistSyncTest passes with
zero failures/errors/skips. Unsupported Kotlin remains NOT_SCORED by Aislop;
manual review, compilation and actual regression tests provide the evidence.
The new signed candidate and phone replay must be recorded separately from the
8585c09b passes above. No schema, version, signing or remote-account write change.

1. Confirm one successful complete authenticated playlist/library read on the
   replacement Preview. Validate order/membership and cached state after failure.
   The previously selected playlist was deleted by its owner. Use an existing
   playlist; missing contents are rejected safely rather than replacing cache.
2. On that same APK, check continuous playback/seek across a partial cache,
   completed/custom file playback offline, and cancel versus completed download.
   Completed downloaded and direct local playback/seek passed on this candidate;
   partial cache traversal, custom-folder audio and the exact cancellation race
   remain unverified. Old device passes cannot be transferred.
3. Check a repeated full TagLib rescan of representative files preserves likes,
   album identity/count/order; manual lyrics timeout/retry and copy confirmation
   behave in the rendered UI. Repeated native scan, local copy and duplicate
   cancellation passed; successful lyrics retry and remote-write cases remain
   unverified. Do not mutate a personal remote playlist for acceptance.
4. Android10 is deferred by the owner. Stable promotion still requires an explicit
   release request; the current Preview verification does not authorize it.

Remote per-song batches can partly succeed before a later request fails; local
commit is withheld, but server mutations cannot be rolled back atomically. Remote
reorder can leave local/server order different after transport failure. Neither
operation is advertised as a distributed transaction.

Cache representation pins are conservative identity checks, not a cryptographic
proof of byte equality. Known compatible legacy formats may be inferred from
saved metadata and bounds; unknown legacy partial audio is retained and rejected.
Empty sibling cache metadata can keep quality pinned until restart after clearing
all spans in one process; ignoring empty reservations could reintroduce writer
races. No byte loss was established for that caveat.

All scopes requested by the original v92 handoff were compared with the finished
source. This review improves and verifies the implementation; it cannot honestly
promise every device/provider scenario works perfectly. Publish/update Preview
only, keep acceptance issues open, and wait for explicit successful phone
acceptance plus a Stable release request.
