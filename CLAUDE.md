# One TV Launcher — notes for AI coding sessions

Custom home screen (launcher) for Google TV / Android TV, in Kotlin with plain Views (no XML layouts, no Compose).
Code comments and identifiers are in Brazilian Portuguese; user-facing strings exist in `values/` (English, the default)
and `values-pt/` (Portuguese); the app follows the TV's system language. Read `README.md` first for the feature list.

## Rules that protect installed TVs (do not break)

- **Never change `applicationId` (`br.paulo.tvlauncher`), the Kotlin namespace, or the `AdminReceiver` component name.**
  Installed TVs have this exact component as Device Owner; a different package/component is a different app and the
  owner can only be restored with a factory reset.
- **Never uninstall the launcher or clear its data on a TV**, and never run `dpm remove-active-admin`, `MASTER_CLEAR`,
  `wipe` or `reboot recovery`. If `adb install -r` fails because of the signature, stop: don't uninstall to "fix" it.
- **Release tag = `v<versionCode>`** (e.g. `v455713`). `Atualizador.kt` reads the number from the tag; a tag like
  `v1.0` stops every TV from updating. `versionCode` comes from the build time (`app/build.gradle.kts`) and always grows.
- Release assets are `one-tv-launcher.apk` (armeabi-v7a) and `one-tv-launcher-arm64.apk`; the updater also accepts the
  older `tv-launcher*.apk`.
- **Publishing is separate from merging.** Merging to `main` builds nothing. A Release is created only by running the
  "Build e Release" workflow by hand on `main` (`workflow_dispatch`), which signs with the `ASSINATURA_*` secrets.
  That run executes the JVM tests first (a red test publishes nothing). Inputs: `canal=teste` publishes a prerelease
  that only TVs with `EXTRAS canal --es canal teste` install ("Promover para todos" workflow releases it to everyone);
  `commit=<hash on main>` rebuilds an older commit with a newer versionCode (the way back from a bad version).
  Pull requests only compile (and cost CI minutes): batch commits; use `[skip ci]` for text-only commits.
- The signing key and its password never go into git, issues or logs.
- No personal data in the repo (city, coordinates, IPs, names, account ids). Per-owner build values go in
  `pessoal.properties` (git-ignored; template `pessoal.properties.exemplo`) and reach the code via `BuildConfig`.
- Before touching a real TV, check it is idle (`dumpsys power`, `topResumedActivity`): people may be watching.
  Don't test DNS/VPN/network changes on a TV in use; use unit tests instead.
- Never weaken TLS verification. Refuse anything involving bootloader unlock, root or exploits.

## Privilege architecture (the part that's easy to get wrong)

1. **Device Owner, in process** (`PrivilegioDO.kt`): `DevicePolicyManager` APIs only — hide apps, grant permissions,
   persistent HOME, always-on VPN, screen timeout, reboot, clear another app's data. It is a fixed list of APIs, **not**
   shell powers: no arbitrary `pm`/`cmd`, no reading other apps' data. Becoming owner from the TV: `VirarDono.kt`
   (temporarily disables GMS so the account check passes; see `docs/device-owner.md`). Without an owner:
   `Congelar.kt` (`pm disable-user` via shell), `TravaSemDono.kt`, `GuardarDadosActivity`.
2. **Own shell (uid 2000)**: built-in ADB client (`AdbConnectionManager.kt`, libadb-android) pairs with the TV's
   wireless debugging (SPAKE2) and connects to itself over TLS. Pairing without a PC: `PareamentoNaTv.kt` reads the
   code from the screen through accessibility. `GuardiaoShizuku.kt` (historical name; Shizuku was removed) is the single
   adb queue with self-healing. `org/conscrypt/` bridges `SSLSockets.exportKeyingMaterial` for the pairing.
3. **Accessibility service** (`AutorizaShizukuService.kt`, historical name): remote keys, HOME short/long press,
   window watcher that keeps our HOME on top.

Google's launcher (`com.google.android.apps.tv.launcherx`) must stay **enabled**: Gemini chat, search result pages,
the notification dashboard and the GMS account picker live in it. Its HomeActivity has `priority=2`, which beats
any preferred-activity setting, so `Home.kt` + the accessibility service handle HOME instead. Keep the
`<queries><package launcherx/>` entry in the manifest.

## adb-only commands (receivers protected by `android.permission.DUMP`)

`am broadcast -n br.paulo.tvlauncher/.ControleShizukuAdbReceiver -a <action>`: `br.paulo.tvlauncher.shizuku.INICIAR`
/ `ESTADO` (shell status), `ROTULOS`, `OCULTAR --es pacote <pkg> --ez oculto true|false`, `REINICIAR` (restarts the
launcher process; shell `force-stop` can't kill a device owner with accessibility), `IA_TESTE` (open a screen via the
closed action catalog, e.g. `{"acoes":[{"tipo":"abrir_tela","tela":"previsao"}]}`), `EXTRAS --es o <cmd>`
(see `ComandosExtras.kt`).

## Web panel, MCP and tunnel

- Panel: `PainelWeb.kt` on port 8080 (ports < 1024 are not allowed for apps). Pure logic with JVM tests in
  `PainelHttp.kt` / `PainelSeguranca.kt`. LAN only, Host/Origin checks, `X-Painel: 1` on POST, HttpOnly SameSite=Strict
  cookie, PBKDF2 PIN, growing lockout; destructive actions ask for the PIN again. Frontend in `assets/painel/`.
- HTTPS at home (for installing the panel as a PWA): same panel also on port 8444 (8443 is taken by the built-in Chromecast, `mediashell`) with a certificate the TV generates on
  first use (`PainelTls.kt` + `CertificadoX509.kt`, SAN `tv`, `tv.lan`, `tv.local` + the TV's IP, names in
  `Config.PAINEL_NOMES_HTTPS`). The CA key is discarded right after signing (renewing = new CA, devices reinstall);
  the server key is AES-GCM-wrapped by an Android Keystore key in `noBackupFilesDir/painel-tls`. Step-by-step page:
  `/certificado` (filled server-side by `PaginaCertificado.kt`). Nothing of this goes into the repo.
- MCP: `McpServidor.kt` on 127.0.0.1:8091, OAuth 2.1 (DCR + PKCE) in `McpAutorizacao.kt`, tools in `McpFerramentas.kt`.
  Reachable from outside only through `tunel/main.go` (Tailscale `tsnet`, userspace, Funnel :443), compiled to
  `libtsnet.so` by the `compilarTunel` Gradle task when Go is installed. Machine name: `onetv-<device name>`.
- Encrypted full backup: `BackupCompleto.kt` + `CofreBackup.kt` (PBKDF2 150k + AES-GCM).

## Build, test, measure

- JDK 21, Android SDK 36, Gradle wrapper 8.14, AGP 8.11, Kotlin 2.2. `./gradlew assembleRelease`;
  JVM tests: `./gradlew testReleaseUnitTest` (or `scripts/testes-puros.sh`).
- Android 14 forces the HOME app to `speed-profile`; `app/src/main/baseline-prof.txt` + `androidx.profileinstaller`
  provide the profile. Debug builds run in `verify` mode: don't measure performance with them.
- Measuring: `dumpsys gfxinfo br.paulo.tvlauncher reset`, return to HOME by component name 10×, `dumpsys gfxinfo`,
  and the `TvLauncher.Apps` log line (`carregar=… desenhar=…`).

## Testing limits (don't rediscover)

- `input keyevent` from adb does **not** pass through the accessibility key filter. To test the key logic without the
  remote use `EXTRAS --es o tecla --es tecla HOME [--el ms 1500]` (fake key into the same funnel, `TeclaFalsa.kt`);
  the remote's hardware itself still needs a real remote.
- `uiautomator dump` disconnects accessibility services while it runs: navigate by screenshot.
- In Developer options, "Revoke authorizations", turning debugging off, etc. drop the adb connection.
- `pm dump` / `resolve-activity` don't show the Device Owner's persistent preferred activity.

## Known bugs fixed (don't repeat)

- `pm path` output has a trailing `\r` (CRLF): split with `/\r?\n/` before trimming.
- `dumpsys package` repeats `runtime permissions:` per Android user: deduplicate, keep user 0.
- Filenames with spaces from `ls -la`: the name is fields 8+ joined, not the last token.
- A CSS class with `display` beats the UA `[hidden]` rule: keep `[hidden] { display: none !important; }`.
