# v92 whole-version review — October8, continued October11

## Decision

The source review found and corrected real defects, including data-preservation
and playback defects that the earlier broad passing tests did not cover.
No blocking introduced source finding remains in the independent corrective
review. **No known blocking finding remains under the owner's source-led review
and minimal phone acceptance scope.** The October11 continuation correction
passed complete existing-playlist export and local cache refresh on the exact
signed replacement APK. This supports source readiness for Stable; residual
device/provider scenarios remain explicitly unverified.
Parent-candidate8585c09b phone acceptance has now
confirmed update preservation, offline playback, rescans, cancellation and local
copy; see the October11 continuation below.
After this review and Preview acceptance, the owner explicitly requested Stable
publication. The signed Stable release work is recorded below; it does not
relabel Preview runtime evidence as a Stable phone upgrade.

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
- The then-current Preview prerelease's `SOURCE_COMMIT.txt`, `SHA256SUMS.txt`,
  signature receipt and `VALIDATION.json` identified that exact candidate. The
  historical tag is not moved; those receipts were later removed from Stable.

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
| Account/library/playlist sync | Individual playlist and account-library completeness safeguards; cached state preserved on failure | Owner clarified the old selected playlist was deleted. The new existing-playlist complete export and cache refresh pass with73 memberships/order on514af1f37; full account-library reconciliation is not a new phone pass |
| Lyrics automatic/manual/editor/provider matching | Existing provider/cache/recording/timestamp logic inspected; manual failures/replacement races corrected; editor keeps plain/timed text | Real lyrics quality/availability depends on providers and recording; skipped live provider tests are not passes |
| JSON/CSV/M3U8 library/playlist transfer | Bounded parsing, canonical local identity, preparation/commit revalidation, transaction rollback and real SAF UI flows inspected | Existing remote-playlist JSON export passes on514af1f37; account-specific import was not replayed. Relative M3U references and portable URI grants remain limits |
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

## October11 continuation on the signed parent candidate8585c09b

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
No schema, version, signing or remote-account write change.

### Final replacement candidate514af1f37 — October11

- Exact APK source: `514af1f37d12824188bf0dcbeba0f3724d9321b2`.
- Signed Preview CI run38118136028 succeeded with app unit tests, release lint,
  minification/resource shrinking and release build. The workflow does not attach
  test XML; no aggregate CI test count is invented.
- Actual signed APK SHA-256:
  `4cb037ff673e10c34d2865be54e7c483aa187205356efc59bd8c8fb2ebd72ce3`.
  Package/certificate/code92 match Preview, no debug/testOnly flag,16KiB alignment.
- Normal update succeeded; installed APK readback matches that hash. Immediate
  before/after backups match all22 tables and30 typed preferences; integrity and
  foreign keys pass. Stable package/data remain untouched.
- An existing remote playlist fully exports through the JSON document picker;
  all73 songs match the refreshed local cache IDs and order. The application
  shows the successful-sync message. Recommendation cards are excluded.
- All other cached playlist membership rows are unchanged, including the deleted
  playlist's99 entries/order. New cache creation legitimately adds song/artist/
  album relations; all30 typed preferences are unchanged. No remote account write.
- Temporary screen/network controls restored, playback stopped. Private backups,
  export files, account values and raw responses are not published.

The final report is updated in a documentation-only follow-up; the APK's
SOURCE_COMMIT remains514af1f37. Parent8585c09b phone tests above are not relabelled
as a full rerun on this later APK.

1. Complete existing-playlist read, export and local cache refresh now pass on
   the replacement Preview with exact membership/order. The old missing playlist
   remains a safe failure preserving cached data; its owner confirmed deletion.
2. Parent8585c09b phone checks passed completed downloaded and direct local
   offline playback/seek, repeated native scan, local copy, active cancellation
   and duplicate cancellation. They are parent receipts, not a full phone rerun
   on the later continuation correction. Partial cache traversal, custom-folder
   audio, the exact cancellation race, successful lyrics retry and remote-write
   cases remain explicitly unverified. The owner's minimal-check request does
   not require repeating the historical full plan or mutating a personal remote
   playlist; these limits remain visible rather than being marked PASS.
3. Exact APK/source identity, update backups, complete export/cache comparison
   and restoration of temporary phone controls are recorded. No required gate
   remains unfinished under the current minimal acceptance scope.
4. Android10 is deferred by the owner. The subsequent explicit Stable release
   request authorizes promotion; the review itself did not supply that approval.

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
promise every device/provider scenario works perfectly. Issue#14 is closed after
actual completed playlist acceptance. Issue#15 stays open for residual unverified
device scenarios, with the completed minimal scope recorded. The owner then
explicitly requested Stable publication; the separate release work follows.

## Stable promotion — October11, explicitly authorized

- Source stays `514af1f37d12824188bf0dcbeba0f3724d9321b2` for both Stable and Preview; promotion changes
  the release channel without moving historical tag `0.11.1-v92` or creating v93.
- Signed CI run [38159046409](https://github.com/Bage340/OuterTune-Bage340/actions/runs/38159046409) passed Stable Core/Full unit tests,
  Stable Core release lint and both minified/shrunk release builds. The workflow
  does not attach test XML; no aggregate CI test count is inferred.
- At promotion, all 10 Stable APKs (Core/Full, universal plus four ABIs each)
  were inspected locally: package `com.dd3boh.outertune`, versionName0.11.1/code92,
  certificate `98de410a5f16c5743ca3885d4ded7850fab73730a99bfd67f5912a5d91f6b736`, no debug/testOnly flag and16KiB alignment.
  Native ABI sets match; Full includes its decoder, Core omits it.
- The two universal APKs retained on the release page are the same inspected
  Core and Full files: Core SHA-256
  `8bfcbe852097d517f0d95f2daa08f719bf8ed6436392cb78d856288ec1858ad0`; Full
  SHA-256 `bd001d8bcf13383749063e2008a01b8b88a169b0d6c5d38a3ba6fbb2f6252b60`.
- Read-only inspection of installed Poco Stable v91 verified the same package
  and certificate. VersionCode92 is higher; this supports update compatibility,
  without claiming a newly performed Stable install or migration acceptance.
  Stable was not replaced; phone data and controls were untouched by promotion.
- The Stable release was initially promoted with the inspected APK variants and
  validation receipts. On October 11, at the owner's request, its attachments
  were reduced to the two universal Stable APKs (Core and Full). Preview, split
  APKs, reports, checksums and all `.md` attachments were removed; the retained
  APK identities are unchanged. The release tag and source commit were not
  changed.
- No app source, dependency, schema, signing key or public version changed during
  promotion. Android10 remains WAIVED and issue#15 remains open. Private
  diagnostics, databases, account data and signing material are excluded.
