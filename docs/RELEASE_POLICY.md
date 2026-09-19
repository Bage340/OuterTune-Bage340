# Release policy

OuterTune-Bage340 keeps the upstream-compatible `versionName` and a separate fork revision in `versionCode`.

- Commits, branches, CI artifacts and signed test APKs may be produced before acceptance.
- An explicitly requested iterative test release may be published as a GitHub prerelease before phone acceptance.
- Update the existing iterative prerelease as fixes are made. Every replacement APK must have a higher `versionCode` to install over the previous build; update the same release entry and its release notes/assets.
- A stable release remains separate from the iterative prerelease and requires phone acceptance.
- When a newer accepted revision supersedes an older release with the same `versionName`, preserve its tag, commit and changelog. Before changing release visibility, verify GitHub's current supported mechanism; prefer converting the old release to a draft when safe.
- Do not delete historical tags, commits, changelogs or releases without explicit authorization.

Historical revision notes can be retained in `docs/RELEASE_ARCHIVE.md` when the first superseded release is archived.
