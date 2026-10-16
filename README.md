# Moggogram

**Русский** | [English](README.en.md)

Неофициальный Telegram-клиент для Android с открытым исходным кодом.

> Unofficial open-source Telegram client for Android, a fork of [NagramX](https://github.com/risin42/NagramX). **[Read in English →](README.en.md)**

## Откуда он взялся

Moggogram — форк **[NagramX](https://github.com/risin42/NagramX)** от [risin42](https://github.com/risin42). NagramX был заархивирован автором 23.08.2026, и Moggogram продолжает его развитие: подтягивает новые версии Telegram и сохраняет все функции NagramX (сохранение удалённых сообщений, режим призрака и остальное).

Цепочка происхождения:

```
DrKLO/Telegram  →  Nekogram  →  Nagram (NextAlone)  →  NagramX (risin42)  →  Moggogram
```

- [Telegram for Android](https://github.com/DrKLO/Telegram) — официальный клиент, основа всего (GPL v2).
- [Nekogram](https://github.com/Nekogram/Nekogram) — исходный сторонний клиент, от которого пошло дерево `tw.nekomimi.nekogram`.
- [Nagram](https://github.com/NextAlone/Nagram) — форк Nekogram от NextAlone.
- [NagramX](https://github.com/risin42/NagramX) — форк Nagram от risin42, на котором построен Moggogram.

Moggogram не связан с Telegram FZ-LLC и с авторами перечисленных проектов.

## Что добавлено поверх NagramX

- Обновление до Telegram 13.0.2 (7175).
- Свои пуш-уведомления: Telegram Web Push → свой шлюз ([push-gateway/](push-gateway)) → FCM; без Google Play Services — постоянное соединение.
- Кружки: 720p, переключение камер и зум, выбор камеры при старте.
- Скорость воспроизведения по тапу 1x → 3x, плавающий плеер голосовых и кружков, массовая загрузка медиа.
- Локальные закрепы без лимита, обманный пароль, скрывающий выбранные чаты.
- Статистика чата, живой архив чата, сохранение защищённого контента.
- Значки рядом с именами других пользователей Moggogram.
- @ugar — генерация картинок Gemini прямо из поля ввода.
- Проверка обновлений, список изменений в приложении, отчёты о сбоях.

Полный список — в истории коммитов ветки `moggogram`.

## Сборка

1. Клонировать с сабмодулями:

    ```bash
    git clone --recursive --shallow-submodules https://github.com/aaaannnyyd/Moggogram.git
    ```

2. Получить `TELEGRAM_APP_ID` и `TELEGRAM_APP_HASH` на [my.telegram.org](https://my.telegram.org/auth) и создать в корне `local.properties`:

    ```properties
    TELEGRAM_APP_ID=<your_telegram_app_id>
    TELEGRAM_APP_HASH=<your_telegram_app_hash>
    ```

3. Для подписи — свой keystore и в `local.properties`:

    ```properties
    KEYSTORE_PASS=<your_keystore_password>
    ALIAS_NAME=<your_alias_name>
    ALIAS_PASS=<your_alias_password>
    ```

4. Для пушей — свой `TMessagesProj/google-services.json` и свой шлюз из `push-gateway/`.

5. Открыть проект в Android Studio и собрать.

## Лицензия

[GPL v3](LICENSE), как у NagramX. Код Telegram for Android распространяется под GPL v2 или более поздней версией.

## Благодарности

- [NagramX](https://github.com/risin42/NagramX) — risin42
- [Nagram](https://github.com/NextAlone/Nagram)
- [Nekogram](https://github.com/Nekogram/Nekogram)
- [Telegram for Android](https://github.com/DrKLO/Telegram)
- [AyuGram](https://github.com/AyuGram/AyuGram4A)
- [Cherrygram](https://github.com/arsLan4k1390/Cherrygram)
- [Dr4iv3rNope](https://github.com/Dr4iv3rNope/NotSoAndroidAyuGram)
- [exteraGram](https://github.com/exteraSquad/exteraGram)
- [OctoGram](https://github.com/OctoGramApp/OctoGram)
- [NagramXTurbo](https://github.com/temporaryna/NagramXTurbo) — образец разрешения конфликтов при обновлении Telegram
