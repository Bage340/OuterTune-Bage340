# OuterTune-Bage340 maintainer rules

This is the public `Bage340/OuterTune-Bage340` fork of OuterTune. Preserve YouTube Music and local-music functionality, installed-user data, signing continuity, upstream attribution, and source-buildability. Do not make destructive database migrations or commit credentials, keystores, cookies, tokens, local media, or private diagnostics.

## Version and release channels

- The upstream-aligned base version is currently `0.11.1`. The fork revision uses **`vXX`**, never `rXX`; `v92` corresponds to Android `versionCode` 92.
- The active development revision is **`0.11.1 v92`**, tag **`0.11.1-v92`**. Commit count and APK build attempts do not increment it. Fix and replace the existing v92 prerelease as often as needed without creating v93.
- Stable and Preview are distinct installable Android packages and signing identities. A channel change does not change the public revision. Never use the Stable signing key for Preview or overwrite one channel's private data with the other.
- Publish/update a GitHub **prerelease** with the tested signed Preview APK and concise user-facing plus technical notes after requested technical changes. The current v92 prerelease was already authorized. Do **not** publish or promote a full/stable release until the user explicitly confirms phone testing and requests it. Promotion keeps the same `0.11.1-v92` tag/revision; only the next separate development cycle after accepted Stable v92 starts v93.
- An APK with the same package and `versionCode` may not install over a different same-code build on every device. Do not silently change the public revision or promise in-place updating; document uninstall/data-backup steps or a separately justified internal build counter if required. Existing Stable installations must remain upgrade-compatible.

## Engineering workflow

- Inspect affected code, tests, build configuration, runtime evidence and existing mechanisms before editing. Make a proportional plan for non-trivial work; favor root-cause fixes and regression tests.
- New user-visible strings belong in every maintained Android locale, with valid escaping/placeholders; run `scripts/check_translations.py`.
- For non-trivial features/bugs, maintain a useful GitHub Issue with labels/milestone and verification status. Close only after the feature is genuinely finished; leave device-only checks open until the user verifies them. Avoid activity-graph churn.
- After a substantial change, check whether source, tests, English/Russian README, changelog, docs, build instructions, CI, screenshots, release notes, labels, milestone, and version metadata need updating. Change only what the work warrants.
- Verify relevant Gradle unit tests, build variants, signing/package/version identity, `git diff --check`, translation audit, and a proportionate quality/security review. Device-only behavior must be described as unverified until actually tested.
- Never treat a green CI build as proof of offline playback, lyrics quality, SAF access, Play Protect acceptance, or Android update semantics on the user's phone.

See [release policy](docs/RELEASE_POLICY.md) and [build guide](CUSTOM_BUILD.md).
