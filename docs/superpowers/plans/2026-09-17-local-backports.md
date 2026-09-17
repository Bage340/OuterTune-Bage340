# OuterTune 0.11.1 selected backports implementation plan

**Goal:** Deliver a signed test APK preserving YTM and revision 91 while selectively adapting official local improvements.

**Architecture:** Keep the mixed local/YTM source tree. Adapt independent storage and UI fixes, and add playlist organization through additive Room schema 22; never import upstream local-only database migrations.

**Tech Stack:** Kotlin 2.3.20, Room 2.8.4, Compose, Android API 24+, existing TagLib and Media3.

**Spec:** User request supplied 2026-09-16, attachment 215e73f5-2653-4c4e-ab0b-7554d7349822; technical plan presented in the task before implementation. User explicitly authorizes implementation after analysis without another approval round.

## Global constraints

- versionName = 0.11.1; versionCode = 91; package and signing certificate unchanged.
- No new release or stable tag until explicit phone acceptance and release authorization.
- Preserve YTM, TagLib, song IDs, playlist mappings, queue reorder, local playback recovery and all playback/download fixes.
- No destructive migrations; preserve schema 21 and all existing migrations.
- Rename existing repository, retain its identity/history/legacy branches.

## 1. Storage and scanner safety

Sources: 44ae23618, c558f85dc, b19f08fdc and the independent URI hunk from 5225d4a7e.
Files: AndroidManifest.xml, LocalMediaScanner.kt, UriFileUtils.kt, MainActivityUtils.kt, LocalMediaSettingsFrag.kt; focused pure scanner helper/tests if needed.

- [ ] Test directory matching (Music vs Music2, escaped SQL wildcards), empty configurations and failure-before-reconciliation.
- [ ] Add Android 10 legacy storage flag and safe external document URI support.
- [ ] Remove title prefilter that prevents same-path identity matching.
- [ ] Fix empty MediaStore query and dispatch automatic scans to the selected implementation.
- [ ] Keep TagLib default. Empty TagLib scan and invalid/unreadable configuration must not reconcile the library to empty.
- [ ] Run focused tests and commit this subsystem independently.

## 2. Playlist folders

Reference: e5a04a38 (adapt; upstream implementation is incomplete).
Files: PlaylistEntity.kt, new PlaylistFolderEntity.kt, PlaylistsDao.kt, MusicDatabase.kt, new pure PlaylistFolders.kt, LibraryViewModels.kt, LibraryPlaylistsScreen.kt, playlist dialogs/menu, new string resource file, generated schema22.

- [ ] Test canonical paths, distinct immediate children, a vs ab ancestry, subtree operations, root and empty folders.
- [ ] Add path column default '/' and folder table in migration21→22; preserve old schemas.
- [ ] Add transactionally safe create/rename/delete/move operations; deleting a folder preserves playlists by moving them to its parent.
- [ ] Protect local folder placement from stale remote metadata sync updates.
- [ ] Add list/grid navigation, create folder, folder controls and local move action for all playlists including read-only YTM bookmarks.
- [ ] Display destination paths when choosing playlists; preserve filtering and existing remote rename behavior.
- [ ] Validate migration preservation, foreign keys, backup restore/checkpoint behavior and tests; commit coherent subsystem.

## 3. Player screen and insets

References: d6044334, d55e8a0, 53975c4, 72260ba.
Files: player/Player.kt, Queue.kt, Thumbnail.kt, component/Lyrics.kt, InterfaceSettings.kt, settings constants, MainActivity.kt, PlayerScreen.kt and SearchScreen.kt only where needed.

- [ ] Test screen-awake policy for disabled/player/lyrics settings, playback pause, hidden player and inactive lifecycle.
- [ ] Use one lifecycle-aware owner of keepScreenOn, releasing on disposal/background. No wake lock.
- [ ] Adapt only needed inset changes, avoiding duplicated system/cutout padding; preserve reorder controls and custom layouts.
- [ ] Compile and inspect available Android runtime; record visual checks requiring user's phone honestly.

## 4. Repository and delivery

- [ ] Rename Bage340/OuterTune-Custom to OuterTune-Bage340, verifying same repository ID and refs, update origin and verify fetch/push.
- [ ] Set versionName0.11.1 only; update concise README fork attribution and release policy. Do not alter old releases.
- [ ] Clean build, all unit tests, lint, schema preservation and forbidden-change checks.
- [ ] Independent final review; fix blockers and rerun affected checks.
- [ ] Sign test APK with existing key, verify package/code/name/certificate and SHA256.
- [ ] Push logical source commits without tags/releases; provide APK, changelog, checks and manual regression checklist.

## Explicitly excluded

AcoustID identifiers, TagLib removal, Gramophone export, queue reorder disable, YTM removal and automatic switch to MediaStore default.
