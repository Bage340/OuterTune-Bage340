# Release policy

OuterTune-Bage340 currently follows upstream's base `versionName` (`0.11.1`) while keeping a fork revision in the form `vXX`. The numeric part is Android `versionCode`: `v92` means code `92`. A build attempt is not a revision.

- Active iteration: **OuterTune 0.11.1 v92**, tag `0.11.1-v92`, GitHub prerelease. Update this existing entry, its notes and its Preview APK after verified fixes; do not open v93 for another v92 build.
- Stable and Preview are separate installable packages with separate signing keys and app-private data. Both may show `0.11.1 v92`; the channel is separate from version/revision.
- Publish prereleases for technical changes. Only the user's explicit confirmation after phone testing authorizes a full/stable release. Promote the same v92 release/tag if appropriate; do not increment its version solely for promotion.
- Start v93 only for a *new* development cycle after v92 has been accepted as Stable.
- Android may reject installing a different APK over the same package/signature/versionCode. Preview's separate package enables side-by-side testing with Stable but does not by itself guarantee in-place replacement of an earlier Preview v92 APK. Explain this honestly and protect app data; do not silently increase the public revision.
- Keep historical tags, commits, changelogs and releases; do not delete them without explicit authorization.

Historical revision notes can be retained in `docs/RELEASE_ARCHIVE.md` when the first superseded release is archived.
