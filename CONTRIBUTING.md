# Contributing to One TV Launcher

Thanks for wanting to help! Bug reports, ideas and pull requests are all welcome.

## Reporting a bug

Open an issue with the **Bug report** template. The most useful things to include:

- the device (e.g. *Chromecast with Google TV 4K*) and Android version;
- the launcher build (Settings → *Check for a new version* shows it, or the Release tag, e.g. `v455000`);
- whether the launcher is the **device owner** (it changes what the launcher can do);
- what you pressed on the remote, what you expected, and what happened.

The web panel can prepare the report for you: *Health → Report on GitHub* opens a pre-filled issue with the launcher's
recent errors (personal data filtered out). You read and edit it before sending; nothing is sent without you.

> [!WARNING]
> Never paste PINs, passwords, IPTV credentials, Tailscale keys or Home Assistant tokens in an issue. If you found a
> security problem, follow [SECURITY.md](SECURITY.md) instead of opening a public issue.

## Suggesting a feature

Use the **Feature idea** template. Tell us the problem first ("I can't find X from the couch"), then your idea. The
project favors things that are fast, work with the remote alone and don't need a phone or PC.

## Pull requests

1. Fork the repo and create a branch from `main`.
2. Build and test (see [Build it yourself](README.md#build-it-yourself)):
   ```bash
   ./gradlew testReleaseUnitTest   # pure JVM tests, no device needed
   ./gradlew assembleDebug
   ```
3. Test on a real Android TV device if you can, and say in the PR what you tested and what you didn't.
4. Keep the PR focused on one thing. Screenshots or a short video help a lot for anything visual.

### Code style

- Kotlin with plain Android **Views built in code**: no XML layouts and no Compose. Look at `Estilo.kt` for the shared
  sizes, colors and components before creating new ones.
- Everything must work with a **D-pad remote**: every focusable item needs a visible focus state, and BACK must always
  go somewhere sensible.
- Keep the home screen fast. Heavy work (network, disk, package queries) goes off the main thread.
- Code comments and identifiers are in Brazilian Portuguese; English is fine in your own comments. User-facing text goes
  in `res/values/strings.xml` (English, the default) **and** `res/values-pt/strings.xml` (Portuguese). The app follows
  the TV's system language; any language without its own folder falls back to English.
- New permissions or new "powers" (device owner, shell, accessibility) need a clear reason in the PR, and should ask the
  user before acting when that is possible.

## License

By contributing, you agree that your contributions are licensed under the project's [license](LICENSE).
