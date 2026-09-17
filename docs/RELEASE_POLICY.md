# Release policy

OuterTune-Bage340 keeps the upstream-compatible `versionName` and a separate fork revision in `versionCode`.

- Commits, branches, CI artifacts and signed test APKs may be produced before acceptance.
- A stable tag and public GitHub Release are created only after the current revision is tested on a phone and explicitly approved.
- When a newer accepted revision supersedes an older release with the same `versionName`, preserve its tag, commit and changelog. Before changing release visibility, verify GitHub's current supported mechanism; prefer converting the old release to a draft when safe.
- Do not delete historical tags, commits, changelogs or releases without explicit authorization.

Historical revision notes can be retained in `docs/RELEASE_ARCHIVE.md` when the first superseded release is archived.
