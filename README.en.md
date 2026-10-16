# Moggogram

[Русский](README.md) | **English**

Unofficial open-source Telegram client for Android.

## Where it comes from

Moggogram is a fork of **[NagramX](https://github.com/risin42/NagramX)** by [risin42](https://github.com/risin42). NagramX was archived by its author on 2026-08-23; Moggogram continues it: it pulls in new Telegram releases and keeps every NagramX feature (saving deleted messages, ghost mode and the rest).

Lineage:

```
DrKLO/Telegram  →  Nekogram  →  Nagram (NextAlone)  →  NagramX (risin42)  →  Moggogram
```

- [Telegram for Android](https://github.com/DrKLO/Telegram) — the official client everything is built on (GPL v2).
- [Nekogram](https://github.com/Nekogram/Nekogram) — the original third-party client the `tw.nekomimi.nekogram` tree comes from.
- [Nagram](https://github.com/NextAlone/Nagram) — NextAlone's fork of Nekogram.
- [NagramX](https://github.com/risin42/NagramX) — risin42's fork of Nagram, the base of Moggogram.

Moggogram is not affiliated with Telegram FZ-LLC or with the authors of the projects above.

## What's added on top of NagramX

- Updated to Telegram 13.0.2 (7175).
- Own push notifications: Telegram Web Push → own gateway ([push-gateway/](push-gateway)) → FCM; without Google Play Services — a persistent connection.
- Round videos: 720p, lens switching and zoom, choice of camera at start.
- Tap-to-cycle playback speed 1x → 3x, floating voice/round player, bulk media download.
- Unlimited local pins, a decoy passcode that hides selected chats.
- Chat statistics, live chat archive, saving protected content.
- Badges next to the names of other Moggogram users.
- @ugar — Gemini image generation right from the message field.
- Update checks, in-app changelog, crash reports.

The full list is in the commit history of the `moggogram` branch.

## Building

1. Clone with submodules:

    ```bash
    git clone --recursive --shallow-submodules https://github.com/aaaannnyyd/Moggogram.git
    ```

2. Get `TELEGRAM_APP_ID` and `TELEGRAM_APP_HASH` at [my.telegram.org](https://my.telegram.org/auth) and create `local.properties` in the project root:

    ```properties
    TELEGRAM_APP_ID=<your_telegram_app_id>
    TELEGRAM_APP_HASH=<your_telegram_app_hash>
    ```

3. For signing — your own keystore, plus in `local.properties`:

    ```properties
    KEYSTORE_PASS=<your_keystore_password>
    ALIAS_NAME=<your_alias_name>
    ALIAS_PASS=<your_alias_password>
    ```

4. For push — your own `TMessagesProj/google-services.json` and your own gateway from `push-gateway/`.

5. Open the project in Android Studio and build.

## License

[GPL v3](LICENSE), same as NagramX. Telegram for Android code is licensed under GPL v2 or later.

## Acknowledgments

- [NagramX](https://github.com/risin42/NagramX) — risin42
- [Nagram](https://github.com/NextAlone/Nagram)
- [Nekogram](https://github.com/Nekogram/Nekogram)
- [Telegram for Android](https://github.com/DrKLO/Telegram)
- [AyuGram](https://github.com/AyuGram/AyuGram4A)
- [Cherrygram](https://github.com/arsLan4k1390/Cherrygram)
- [Dr4iv3rNope](https://github.com/Dr4iv3rNope/NotSoAndroidAyuGram)
- [exteraGram](https://github.com/exteraSquad/exteraGram)
- [OctoGram](https://github.com/OctoGramApp/OctoGram)
- [NagramXTurbo](https://github.com/temporaryna/NagramXTurbo) — reference for resolving conflicts when updating Telegram
