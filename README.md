# OuterTune-Bage340

[![OuterTune app icon](assets/outertune.webp)](assets/outertune.webp)

[![Latest prerelease](https://img.shields.io/github/v/release/Bage340/OuterTune-Bage340?include_prereleases&sort=semver)](https://github.com/Bage340/OuterTune-Bage340/releases)
[![Build](https://github.com/Bage340/OuterTune-Bage340/actions/workflows/build.yml/badge.svg?branch=main)](https://github.com/Bage340/OuterTune-Bage340/actions/workflows/build.yml)
[![License](https://img.shields.io/github/license/Bage340/OuterTune-Bage340)](LICENSE)

[English](README.md) | [Русский](README_ru.md) | [日本語](README_ja.md)

An independent public fork of [OuterTune](https://github.com/OuterTune/OuterTune): an Android YouTube Music client and local music player. This fork preserves both YouTube Music and on-device playback while carrying selected fixes and compatible backports for playback, downloads, and local-library support. The app UI is multilingual.

> [!IMPORTANT]
> This repository is independent and is **not** the official OuterTune project or an official OuterTune maintainer channel. For the upstream project, history, and its own releases, see [OuterTune/OuterTune](https://github.com/OuterTune/OuterTune).

## What this fork provides

- YouTube Music browsing, streaming, playlists, account synchronization, lyrics, and optional offline downloads
- Playback of local audio files alongside YouTube Music content
- Local-library scanning, browsing, filtering, and M3U import/export
- A Material 3 Android interface, multiple playback queues, Android Auto, audio effects, and multilingual resources
- Targeted reliability work and selected backports, with scope documented in this repository's history and pull requests

Feature availability can vary with device, account, network, region, provider, and build flavor. YouTube Music is unavailable in some regions; use of a proxy or VPN may be necessary where lawful and appropriate.

## Install or build

The current source version is **0.11.1 (version code 92)** and is treated as a prerelease while it is being validated. When a release is published, use only the APKs on this repository's [Releases](https://github.com/Bage340/OuterTune-Bage340/releases) page. Test-build artifacts, when available, are attached to this repository's [Actions](https://github.com/Bage340/OuterTune-Bage340/actions) runs and are not a substitute for a published release.

To build from source, clone this repository with submodules and use Android Studio or the Gradle wrapper:

```bash
git clone --recurse-submodules https://github.com/Bage340/OuterTune-Bage340.git
cd OuterTune-Bage340

# Core debug APK
./gradlew assembleCoreDebug

# Full debug APK, including the additional FFmpeg decoder package
./gradlew assembleFullDebug
```

On Windows, run `./gradlew` as `./gradlew.bat` or `gradlew.bat`. See [CONTRIBUTING.md](CONTRIBUTING.md) for prerequisites, flavor details, and contribution guidance.

## Screenshots

![Main player interface](assets/main-interface.jpg)

![Player interface](assets/player.jpg)

![YouTube Music synchronization](assets/ytm-sync.jpg)

[View the full image gallery](assets/gallery)

## Support and contributions

- Report bugs specific to this fork through this repository's [Issues](https://github.com/Bage340/OuterTune-Bage340/issues).
- Before opening a pull request, follow [CONTRIBUTING.md](CONTRIBUTING.md) and test the affected build flavor.
- Changes from upstream projects retain their original authorship and attribution where applicable.

## Attribution and license

OuterTune-Bage340 is derived from [OuterTune/OuterTune](https://github.com/OuterTune/OuterTune), itself a fork of [z-huang/InnerTune](https://github.com/z-huang/InnerTune). Thanks to their contributors and to the projects and libraries credited in the source tree.

This fork is distributed under the [GNU General Public License v3.0](LICENSE). The license and applicable notices in the repository apply to this fork and must be preserved when redistributing modified versions.

## Disclaimer

This project is not affiliated with, funded, authorized, endorsed by, or otherwise associated with YouTube, Google LLC, OuterTune/OuterTune, or their respective affiliates. Trademarks and other intellectual-property rights belong to their respective owners.
