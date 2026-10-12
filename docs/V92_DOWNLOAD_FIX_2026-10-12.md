# v92 download failure investigation — 2026-10-12

The installed signed Stable Core scanner repair was reproduced failing to download a remote song on the Poco X3 Pro / Android 13. The user reports the same failure on all tracks. No database reset, uninstall, route change, or signing/version change is part of this repair.

Observed separately:

- Native Android VR/iOS URLs passed the one-byte validation request, then actual downloads returned HTTP 403 without progress.
- Web fallback could not decipher the current `5203c085` player: NewPipe's patterns failed and the bundled WebView configuration lacked this hash.
- DownloadUtil appended a full-file `range` query parameter after URL validation. Media3 already supplies HTTP Range from DataSpec; initial and resumed downloads should use the validated URL unchanged.

The first candidate adds only the observed player config/alias from [ZemerTeam/zemer-cipher commit a293fb91f05a405644a03982e06a280c950200b3](https://github.com/ZemerTeam/zemer-cipher/blob/a293fb91f05a405644a03982e06a280c950200b3/library/src/main/assets/player_configs.json), retains all existing entries, removes the download URL mutation, and propagates cancellation in the newly available cipher path. No runtime configuration download is introduced.

Acceptance plan:

1. Missing-player regression must fail on the old asset and pass through the production parser on the updated asset; validate all bundled parameter identifiers and aliases.
2. Run stream URL, representation pinning and PoToken unit coverage, both Stable unit suites, release lint, and both signed minified universal builds.
3. Verify package `com.dd3boh.outertune`, sole existing Stable signer, versionCode 92, versionName 0.11.1, and APK hashes.
4. On the connected phone, retry the reproduced failure, observe bytes/progress, complete a whole track beyond 1 MiB, and verify offline playback. Exercise Core and Full without resetting private data.
5. Only after device acceptance, replace both APKs in the existing Stable release and verify remote digests. Keep the historical tag unchanged.

The first candidate has not yet passed phone acceptance. A tiny GET, an anonymous desktop bot response, or a successful build does not prove full download reliability. Cache representation pins remain strict: zero cached spans do not exclude a concurrent upstream already in flight.
