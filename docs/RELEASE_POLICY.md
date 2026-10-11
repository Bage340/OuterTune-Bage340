# Release policy

OuterTune-Bage340 currently follows upstream's base `versionName` (`0.11.1`) while keeping a fork revision in the form `vXX`. The numeric part is Android `versionCode`: `v92` means code `92`. A build attempt is not a revision.

- Accepted release: **OuterTune 0.11.1 v92 — Stable**, tag `0.11.1-v92`. The owner explicitly requested promotion after the agreed minimal Preview phone acceptance on October11. Core/Full keep the Stable identity; Preview remains separate. Promotion does not open v93 or move the historical tag.
- Stable and Preview are separate installable packages with separate signing keys and app-private data. Both may show `0.11.1 v92`; the channel is separate from version/revision.
- Publish prereleases for further technical changes unless Stable publication is explicitly authorized. Only the user's explicit confirmation after phone testing authorizes a full/stable release; this authorization was received for v92. Promote the same release/tag if appropriate; do not increment its version solely for promotion.
- Start v93 only for a *new* development cycle after v92 has been accepted as Stable.
- Android may reject installing a different APK over the same package/signature/versionCode. Preview's separate package enables side-by-side testing with Stable but does not by itself guarantee in-place replacement of an earlier Preview v92 APK. Explain this honestly and protect app data; do not silently increase the public revision.
- Keep historical tags, commits, changelogs and releases; do not delete them without explicit authorization.

Historical revision notes can be retained in `docs/RELEASE_ARCHIVE.md` when the first superseded release is archived.
