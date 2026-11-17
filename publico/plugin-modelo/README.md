# Sofaly plugin template

Two small Android TV apps that put a card on the Sofaly home screen. Copy this folder, rename the package and make it
yours. License: MIT No Attribution (use it any way you like, no credit needed).

| Module | What the card shows | Internet |
|---|---|---|
| `contagem` (the template) | Days left until a date the person picks on the TV ("Christmas: 38") | no |
| `lua` | Today's moon phase and days to the next full moon | no |

## Build

```
./gradlew assembleDebug testDebugUnitTest
adb install contagem/build/outputs/apk/debug/contagem-debug.apk
```

Then on the TV: **Settings › Appearance › Plugins**, turn the plugin on (it asks for the parental PIN) and go back to
the home screen. The card appears at the end of the **Watch** row.

## The contract (all of it)

1. A provider, `exported="true"`, with `<action android:name="tv.sofaly.plugin.CARTAO"/>` in its intent filter
   (see `contagem/src/main/AndroidManifest.xml`).
2. `query()` on `content://<authority>/cartao` returns one row with `titulo` (30 chars), `valor` (12),
   `detalhe` (80), `cor` (`#RRGGBB`) and optionally `acao` (an `intent:` URI that opens your own app only).
3. Be fast and local: the launcher reads the card every time the person returns home, with a 2-second limit, away from
   the screen thread. Fetch from the internet in the background and keep the result (WorkManager, a cache file).

The launcher draws the card in its own style, with large letters. It never sends anything to your app.
Full page: https://chrnx0.github.io/one-tv-launcher/plugins.html

---

**Português:** dois apps de exemplo que põem um cartão na tela inicial da Sofaly. `contagem` é o modelo (dias até uma
data escolhida na TV); `lua` mostra a fase da Lua calculada na própria TV. Copie, troque o pacote e publique. Nenhum dos
dois usa internet, conta ou dado da pessoa.
