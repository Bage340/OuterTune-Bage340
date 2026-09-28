# OuterTune 0.11.1 v92 custom build

The active fork iteration uses upstream base version `0.11.1`, fork revision
`v92`, and Android `versionCode` 92. Its tag is `0.11.1-v92`. A build attempt
does not increment the revision. Preview is the current test channel; a Stable
release requires explicit user acceptance after phone testing.

## Channels and installation

Stable uses application ID `com.dd3boh.outertune`. Preview uses
`com.dd3boh.outertune.preview`, so both can be installed side by side and keep
separate app-private databases and files. They are separate apps, not a data
migration path. Preview uses its own signing identity. Android may reject a
replacement Preview APK when package, signing identity, and version code match
an already installed build. Back up/export data before uninstalling; data
retention after uninstall is not guaranteed. Never change the public revision
or signing key just to work around an installation failure.

## Build and signing

Use JDK 21 and an Android SDK with the platforms and build tools required by
the project. Clone with submodules. Core omits the additional FFmpeg decoder;
Full includes it.

```text
./gradlew :app:testStableCoreDebugUnitTest :app:lintPreviewCoreUserdebug :app:assemblePreviewCoreUserdebug -DskipFormatKtlint
./gradlew :app:assembleStableCoreRelease :app:assemblePreviewCoreRelease
```

On Windows, use `gradlew.bat`. Release outputs are under
`app/build/outputs/apk/<channel><Abi>/<buildType>/` (for example,
`previewCore/userdebug` or `stableFull/release`); inspect the actual output
variant before distributing it. CI must validate package ID, version, signing
certificate, and SHA-256 before attaching an APK to a release.

Stable signing reads ignored `keystore.properties`; preserve that key to allow
updates to existing Stable installs. Preview signing reads ignored
`preview-keystore.properties`; keep a secure backup because losing the key
prevents signed in-place updates to that Preview package. Do not commit either
properties file, keystore, passwords, or signing material. Builds without the
relevant properties are unsigned and are not publishable update packages.

The expected Stable certificate SHA-256 is
`98de410a5f16c5743ca3885d4ded7850fab73730a99bfd67f5912a5d91f6b736`.
Verify the actual APK package, version, alignment, checksum, and certificate
before distribution. Do not infer successful Play Protect review from a local
build or signature check.

## Current iteration scope

This v92 iteration includes work on local scanner safety and library
preservation, local playback path recovery and stream retries, playlist-folder
organization, playlist-to-library actions, download queue filtering/retry and
parallelism settings, lyrics provider selection, library/playlist transfer,
and localization. The exact user-facing summary is in [CHANGELOG.md](CHANGELOG.md).
Use [docs/PHONE_TEST_PLAN.md](docs/PHONE_TEST_PLAN.md) for acceptance checks;
automated tests do not establish real-device behavior. In particular, SAF
provider behavior, Android lifecycle behavior, network retries, stream
availability, installation/update behavior, and Play Protect outcomes require
device or service validation.

## Source history

- Base: AsterTune `b7f58abfd3e77b7548bee353761828cfd213a0c2`.
- `0.10.15-v90` materialized source patches from the workflow in
  `072d8b119e6ebf210870b2447fb419a210c14a6e`.
- The v91 source iteration added local-file playback recovery, download and
  stream retry handling, fail-safe scanning, and playlist-folder organization.
- v92 continues from the existing `0.11.1-v92` prerelease. See Git history for
  implementation detail; this document is not a substitute for attribution
  notices in source and dependency files.
