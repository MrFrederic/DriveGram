# DriveGram

Hands-free Telegram for Android Auto. Listen to your chats and reply by voice while driving.

## Features

- **Car screen:** your Telegram chat folders, unread counts, and a Play and a Mic button for each chat.
- **Play:** reads the unread messages aloud, or the last few if everything is read. An optional AI step fixes typos and punctuation first so the speech sounds natural.
- **Mic:** records your reply, converts it to text, and can tidy up filler and false starts. It reads the result back and sends it only after you confirm, unless you turn on auto-send.
- **Notifications:** new messages show up in Android Auto with Reply and Mark as read.
- **Phone app:** Telegram login and settings. Material 3 with dynamic colour.
- **Swappable engines:** text to speech, speech to text and the AI rewriter sit behind small interfaces (`Engines.kt`). The default is [OpenRouter](https://openrouter.ai), with Android's built-in text to speech as an alternative.

## Build

1. Get an `api_id` and `api_hash` at <https://my.telegram.org> → API development tools, and put them in `local.properties` (it isn't committed):
   ```
   telegram.apiId=1234567
   telegram.apiHash=0123456789abcdef0123456789abcdef
   ```
2. Run `./gradlew :app:installDebug`.
3. In the app, sign in to Telegram. If you want the AI features, paste an OpenRouter key in Settings.

Requires Android 10 or later.

## Run it in Android Auto

Android Auto hides apps that weren't installed from Google Play unless you allow unknown sources:

1. On the phone, open Android Auto settings (Settings → Connected devices → Connection preferences → Android Auto).
2. Tap **Version** at the bottom about 10 times to turn on developer mode.
3. Open ⋮ → **Developer settings** → turn on **Unknown sources**.
4. Reconnect to the car. DriveGram appears in the app launcher.

To test without a car, use the Desktop Head Unit with a real phone over USB. Play won't install Android Auto on an emulator.

```
sdkmanager --channel=1 --install "extras;google;auto"
desktop-head-unit --usb
```

## TDLib

The `:tdlib` module contains a prebuilt [TDLib](https://github.com/tdlib/td) 1.8.68 (commit `a94b83d`) for `arm64-v8a` and `x86_64`, built with NDK 29 and OpenSSL 3.5.9. To rebuild it, copy `tdlib/Dockerfile` into `td/example/android/` and run:

```
docker build --target build -t drivegram-tdlib .
id=$(docker create drivegram-tdlib /bin/true) && docker cp "$id:/home/td/example/android/tdlib" out && docker rm "$id"
```

Then copy `out/java/org/drinkless/tdlib/*.java` to `tdlib/src/main/java/org/drinkless/tdlib/` and `out/libs/*` to `tdlib/src/main/jniLibs/`.

## Privacy

When the AI features are on, the text of messages you play and your dictated replies go to OpenRouter and the model providers it routes them to. Release builds don't log message content.

## Licence

[PolyForm Noncommercial 1.0.0](LICENSE): you may use, modify and share DriveGram, including your own modified builds, for any noncommercial purpose. Commercial use isn't allowed.

TDLib is under the [Boost Software License 1.0](tdlib/LICENSE_1_0.txt). DriveGram isn't affiliated with Telegram.
