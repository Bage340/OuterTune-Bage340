# OuterTune-Bage340

[![OuterTune アプリアイコン](assets/outertune.webp)](assets/outertune.webp)

[![最新プレリリース](https://img.shields.io/github/v/release/Bage340/OuterTune-Bage340?include_prereleases&sort=semver)](https://github.com/Bage340/OuterTune-Bage340/releases)
[![ビルド](https://github.com/Bage340/OuterTune-Bage340/actions/workflows/build.yml/badge.svg?branch=main)](https://github.com/Bage340/OuterTune-Bage340/actions/workflows/build.yml)
[![ライセンス](https://img.shields.io/github/license/Bage340/OuterTune-Bage340)](LICENSE)

[English](README.md) | [Русский](README_ru.md) | [日本語](README_ja.md)

[OuterTune](https://github.com/OuterTune/OuterTune) をベースにした、独立した公開フォークです。Android 向けの YouTube Music クライアントとローカル音楽プレイヤーとして、YouTube Music と端末内音楽の再生を維持しつつ、再生、ダウンロード、ローカルライブラリ向けの選択的な修正と互換性のあるバックポートを取り込みます。アプリの UI は複数言語に対応しています。

> [!IMPORTANT]
> このリポジトリは独立しており、公式の OuterTune プロジェクトまたは公式メンテナーチャネルでは**ありません**。アップストリームのプロジェクト、履歴、独自リリースについては [OuterTune/OuterTune](https://github.com/OuterTune/OuterTune) をご覧ください。

## このフォークが提供するもの

- YouTube Music の検索・再生、プレイリスト、アカウント同期、歌詞、任意のオフラインダウンロード
- YouTube Music のコンテンツと並行して利用できるローカル音声ファイルの再生
- ローカルライブラリのスキャン、閲覧、フィルタリング、M3U のインポート／エクスポート
- Android 向け Material 3 UI、複数の再生キュー、Android Auto、音声エフェクト、多言語リソース
- 信頼性に関する対象を絞った改善と選択的なバックポート。対象範囲はこのリポジトリの履歴と pull request に記録されます

機能の利用可否は、端末、アカウント、ネットワーク、地域、プロバイダー、ビルドフレーバーにより異なります。YouTube Music を利用できない地域があります。必要な場合は、適法かつ適切な範囲でプロキシまたは VPN を使用してください。

## インストールまたはビルド

現在のソースバージョンは **0.11.1（バージョンコード 92）** です。検証中のためプレリリースとして扱います。リリースが公開されている場合は、このリポジトリの [Releases](https://github.com/Bage340/OuterTune-Bage340/releases) ページにある APK のみを使用してください。テストビルドのアーティファクトは、利用可能な場合にこのリポジトリの [Actions](https://github.com/Bage340/OuterTune-Bage340/actions) の実行結果へ添付されますが、公開リリースの代わりにはなりません。

ソースからビルドするには、サブモジュールを含めてこのリポジトリをクローンし、Android Studio または Gradle Wrapper を使用します。

```bash
git clone --recurse-submodules https://github.com/Bage340/OuterTune-Bage340.git
cd OuterTune-Bage340

# core デバッグ APK
./gradlew assembleCoreDebug

# 追加の FFmpeg デコーダーパッケージを含む full デバッグ APK
./gradlew assembleFullDebug
```

Windows では `./gradlew` の代わりに `./gradlew.bat` または `gradlew.bat` を実行してください。前提条件、フレーバーの違い、コントリビュートの手順は [CONTRIBUTING.md](CONTRIBUTING.md) をご覧ください。

## スクリーンショット

![メインプレイヤー画面](assets/main-interface.jpg)

![プレイヤー画面](assets/player.jpg)

![YouTube Music との同期](assets/ytm-sync.jpg)

[画像ギャラリーを開く](assets/gallery)

## サポートとコントリビュート

- このフォーク固有の不具合は、このリポジトリの [Issues](https://github.com/Bage340/OuterTune-Bage340/issues) に報告してください。
- pull request を作成する前に [CONTRIBUTING.md](CONTRIBUTING.md) を確認し、変更したビルドフレーバーをテストしてください。
- アップストリームプロジェクトから取り込んだ変更には、該当する場合に元の著者と帰属を保持します。

## 帰属とライセンス

OuterTune-Bage340 は [OuterTune/OuterTune](https://github.com/OuterTune/OuterTune) を基にしており、OuterTune は [z-huang/InnerTune](https://github.com/z-huang/InnerTune) のフォークです。各プロジェクトのコントリビューター、およびソースツリーでクレジットされているプロジェクトとライブラリに感謝します。

このフォークは [GNU General Public License v3.0](LICENSE) の下で配布されています。変更版を再配布する場合は、リポジトリ内のライセンスと該当する通知を保持してください。

## 免責事項

このプロジェクトは、YouTube、Google LLC、OuterTune/OuterTune、およびそれぞれの関連会社から、提携、資金提供、認可、推奨を受けておらず、その他の関係もありません。商標およびその他の知的財産権は、それぞれの権利者に帰属します。
