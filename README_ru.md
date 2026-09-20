# OuterTune-Bage340

[![Значок приложения OuterTune](assets/outertune.webp)](assets/outertune.webp)

[![Последняя предварительная версия](https://img.shields.io/github/v/release/Bage340/OuterTune-Bage340?include_prereleases&sort=semver)](https://github.com/Bage340/OuterTune-Bage340/releases)
[![Сборка](https://github.com/Bage340/OuterTune-Bage340/actions/workflows/build.yml/badge.svg?branch=main)](https://github.com/Bage340/OuterTune-Bage340/actions/workflows/build.yml)
[![Лицензия](https://img.shields.io/github/license/Bage340/OuterTune-Bage340)](LICENSE)

[English](README.md) | [Русский](README_ru.md) | [日本語](README_ja.md)

Независимый публичный форк [OuterTune](https://github.com/OuterTune/OuterTune) — Android-клиента YouTube Music и локального музыкального проигрывателя. В этом форке сохранены работа с YouTube Music и воспроизведение файлов на устройстве, а также добавлены выбранные исправления и совместимые бэкпорты для воспроизведения, загрузок и локальной медиатеки. Интерфейс приложения переведён на несколько языков.

> [!IMPORTANT]
> Этот репозиторий независим и **не является** официальным проектом OuterTune или официальным каналом его сопровождающих. Оригинальный проект, его историю и собственные релизы см. в [OuterTune/OuterTune](https://github.com/OuterTune/OuterTune).

## Возможности форка

- Поиск и воспроизведение из YouTube Music, плейлисты, синхронизация аккаунта, тексты песен и необязательные офлайн-загрузки
- Воспроизведение локальных аудиофайлов вместе с контентом YouTube Music
- Сканирование, просмотр и фильтрация локальной медиатеки, а также импорт и экспорт M3U
- Интерфейс Material 3 для Android, несколько очередей воспроизведения, Android Auto, аудиоэффекты и многоязычные ресурсы
- Точечные улучшения надёжности и выбранные бэкпорты; их состав отражён в истории и pull request'ах этого репозитория

Доступность функций зависит от устройства, аккаунта, сети, региона, провайдера и варианта сборки. YouTube Music недоступен в части регионов; при необходимости используйте прокси или VPN только там, где это законно и уместно.

## Установка или сборка

Текущая версия исходного кода — **0.11.1 (код версии 92)**; до завершения проверки она считается предварительной. Если релиз опубликован, используйте APK только со страницы [Releases](https://github.com/Bage340/OuterTune-Bage340/releases) этого репозитория. Тестовые артефакты, если они доступны, прикреплены к запускам [Actions](https://github.com/Bage340/OuterTune-Bage340/actions) и не заменяют опубликованный релиз.

Для сборки из исходного кода клонируйте этот репозиторий вместе с подмодулями и используйте Android Studio или Gradle Wrapper:

```bash
git clone --recurse-submodules https://github.com/Bage340/OuterTune-Bage340.git
cd OuterTune-Bage340

# Отладочный APK Core
./gradlew assembleCoreDebug

# Отладочный APK Full с дополнительным пакетом декодеров FFmpeg
./gradlew assembleFullDebug
```

В Windows вместо `./gradlew` используйте `./gradlew.bat` или `gradlew.bat`. Предварительные требования, отличия вариантов сборки и правила участия описаны в [CONTRIBUTING.md](CONTRIBUTING.md).

## Скриншоты

![Главный экран проигрывателя](assets/main-interface.jpg)

![Экран проигрывателя](assets/player.jpg)

![Синхронизация с YouTube Music](assets/ytm-sync.jpg)

[Открыть полную галерею](assets/gallery)

## Поддержка и вклад в проект

- Об ошибках, относящихся именно к этому форку, сообщайте в [Issues](https://github.com/Bage340/OuterTune-Bage340/issues) этого репозитория.
- Перед созданием pull request ознакомьтесь с [CONTRIBUTING.md](CONTRIBUTING.md) и проверьте затронутый вариант сборки.
- Заимствования из вышестоящих проектов сохраняют исходное авторство и указание источника, когда это применимо.

## Атрибуция и лицензия

OuterTune-Bage340 основан на [OuterTune/OuterTune](https://github.com/OuterTune/OuterTune), который, в свою очередь, является форком [z-huang/InnerTune](https://github.com/z-huang/InnerTune). Спасибо их участникам, а также проектам и библиотекам, указанным в исходном коде.

Этот форк распространяется по [лицензии GNU General Public License v3.0](LICENSE). При распространении изменённых версий необходимо сохранять лицензию и применимые уведомления из репозитория.

## Отказ от ответственности

Этот проект не связан, не финансируется, не авторизован и не одобрен YouTube, Google LLC, OuterTune/OuterTune или их аффилированными лицами. Товарные знаки и иные права интеллектуальной собственности принадлежат соответствующим правообладателям.
