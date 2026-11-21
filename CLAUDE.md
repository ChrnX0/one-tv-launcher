# One TV Launcher — notes for AI coding sessions

Custom home screen (launcher) for Google TV / Android TV, in Kotlin with plain Views (no XML layouts, no Compose).
Code comments and identifiers are in Brazilian Portuguese (the Android app); **new public or technical names are in English**
(domains, services, Workers, API routes and fields, new folders and files; Paulo's rule, 18/11/2026), e.g. `server/api` at
`api.sofaly.tv` (publish it ONLY with `npm run deploy` inside `server/api`, see `server/README.md`); user-facing strings exist in `values/` (English, the default)
and `values-pt/` (Portuguese); the app follows the TV's system language. Read `README.md` first for the feature list.
Code layout (21/11/2026 refactor): sources live in subpackages by area under `br/paulo/tvlauncher/` (home, player,
listen, panel, mcp, weather, alarm, screensaver, privilege, network, backup, observer, family, games, settings, input,
search); manifest components, JNI classes and the core (Fundo, Estilo, Prefs, Http...) stay in the root package. Tests
that read sources find files with `CodeSource.file(name)`. Background work goes through `Fundo.rodar`/`Fundo.juntos`
(coroutines); only endless loops (servers, watchers) keep their own Thread.

## Rules that protect installed TVs (do not break)

- **The app's package is `tv.sofaly.launcher`** (19/11/2026, Paulo: no public package with his name), on the site and on
  Play; once published on Play it can never change. **Never change it, the Kotlin namespace (`br.paulo.tvlauncher`, the
  code/class names, see `AppIdentity.kt`) or the `AdminReceiver` class.** A Device Owner only moves to another package
  through `transferOwnership`; any other change of package/component loses the owner, and that is undone only by a
  factory reset. Class names use `AppIdentity.NAMESPACE`/`className()`, package names use `packageName`/`AppIdentity.ours()`.
- **Old package `br.paulo.tvlauncher` = only the bridge build** (`-Plegacy`, Release input `bridge=true`, assets
  `one-tv-launcher*.apk`). A TV still on it installs the bridge, which installs `tv.sofaly.launcher` (same key), copies
  all data to it and hands it the Device Owner (`PackageMigration.kt`; the old app ends hidden, not uninstalled).
  adb: `EXTRAS --es o migration` (state), `migration_start` (old app), `migration_rollback` (new app: owner goes back).
  `migration_remove_legacy` (new app) uninstalls the old one and removes the way back: only when Paulo asks.
- **Never uninstall the launcher or clear its data on a TV** (the old package after a migration: see above), and never
  run `dpm remove-active-admin`, `MASTER_CLEAR`, `wipe` or `reboot recovery`. If `adb install -r` fails because of the
  signature, stop: don't uninstall to "fix" it.
- **Release tag = `v<versionCode>`** (e.g. `v455713`). `Atualizador.kt` reads the number from the tag; a tag like
  `v1.0` stops every TV from updating. `versionCode` comes from the build time (`app/build.gradle.kts`) and always grows.
- Release assets are `sofaly-launcher.apk` (armeabi-v7a) and `sofaly-launcher-arm64.apk` (package `tv.sofaly.launcher`),
  plus the bridge `one-tv-launcher*.apk` only when published with `bridge=true`. Old-package apps also accept the
  older `tv-launcher*.apk`.
- **Publishing is separate from merging.** Merging to `main` builds nothing. A Release is created only by running the
  "Build e Release" workflow by hand on `main` (`workflow_dispatch`), which signs with the `ASSINATURA_*` secrets.
  That run executes the JVM tests first (a red test publishes nothing). Inputs: `canal=teste` publishes a prerelease
  that only TVs with `EXTRAS canal --es canal teste` install ("Promover para todos" workflow releases it to everyone);
  `commit=<hash on main>` rebuilds an older commit with a newer versionCode (the way back from a bad version).
  Before each Release, rewrite `res/values*/strings_versoes.xml`: `novidades` ("What changed", Settings > System) and
  the `roteiro_*` checklist ("Check on the TV"; answers via `EXTRAS --es o roteiro`), in both languages. Then run
  `python3 scripts/readme.py`: the README feature list, "What changed", the Release notes and the CHANGELOG come from
  that file and from the website's feature list (`website/features.json` + `content/en.json`); the workflow refuses to
  publish when they are stale. README pictures: `node scripts/readme-images.js` (source `docs/readme/art.html`).
  Pull requests only compile (and cost CI minutes): batch commits; use `[skip ci]` for text-only commits.
- **Closed source (decided 15/11/2026).** The code repo becomes private; TVs keep reading the PUBLIC repo with the old name
  (`Config.ATUALIZACAO_REPO`). Repo variable `REPO_PUBLICO` + secret `PUBLICO_TOKEN` make "Build e Release" / "Promover"
  publish there, and the "Repo publico" workflow mirrors `site/`, `comunidade/`, `publico/`, screenshots and a code-free
  README to it (Pages, `volta.html`, raw `comunidade/` keep their URLs). Nothing else from this repo goes public.
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
   code from the screen through accessibility. `GuardiaoShizuku.kt` (historical name) is the single
   adb queue with self-healing. **Lending the shell** (15/11/2026, `ShizukuEmbutido.kt`, off by default, Extras/paid plan):
   the vendored Shizuku server (`shizuku/`, see `shizuku/NOTICE.md`) is started through that queue; the APK must **never
   define** `moe.shizuku.*` permissions (that blocked installing next to the Shizuku app; `RegrasEmprestimoTest` checks it).
   Clients are authorized only through the server's config after the panel PIN on the TV (`PedidoShizukuActivity`). **Lending the Device Owner** (same date, `DonoEmprestado.kt`, off by default, paid plan, only while we ARE owner):
   built-in Dhizuku server (`dhizuku/server_api`, MIT, own `DhizukuProcess` instead of the LGPL AndroidAppProcess), provider
   `<pkg>.dhizuku_server.provider`, PIN on the TV (`PedidoDhizukuActivity`). Without "full power" a client only reaches
   `IDevicePolicyManager`, through `FiltroDpm`/`RegrasDono` (never wipe/clear owner/transfer; never target our package;
   unreadable call = denied). "Full power" (process, user service, delegated scopes, other binders) is per app with PIN. `org/conscrypt/` bridges `SSLSockets.exportKeyingMaterial` for the pairing.
3. **Accessibility service** (`AutorizaShizukuService.kt`, historical name): remote keys, HOME short/long press,
   window watcher that keeps our HOME on top.

Google's launcher (`com.google.android.apps.tv.launcherx`) must stay **enabled**: Gemini chat, search result pages,
the notification dashboard and the GMS account picker live in it. Its HomeActivity has `priority=2`, which beats
any preferred-activity setting, so `Home.kt` + the accessibility service handle HOME instead. Keep the
`<queries><package launcherx/>` entry in the manifest.

## adb-only commands (receivers protected by `android.permission.DUMP`)

`am broadcast -n tv.sofaly.launcher/br.paulo.tvlauncher.ControleShizukuAdbReceiver -a <action>` (on a TV still on the old
package: `-n br.paulo.tvlauncher/.ControleShizukuAdbReceiver`; action names keep the `br.paulo.tvlauncher.` prefix): `br.paulo.tvlauncher.shizuku.INICIAR`
/ `ESTADO` (shell status), `ROTULOS`, `OCULTAR --es pacote <pkg> --ez oculto true|false`, `REINICIAR` (restarts the
launcher process; shell `force-stop` can't kill a device owner with accessibility), `IA_TESTE` (open a screen via the
closed action catalog, e.g. `{"acoes":[{"tipo":"abrir_tela","tela":"previsao"}]}`), `EXTRAS --es o <cmd>`
(see `ComandosExtras.kt`), `br.paulo.tvlauncher.UNIFI --es o relato --ei horas 8` (read-only router report: UniFi uptime and
the TV's Wi-Fi events, to explain a drop). Care from afar (ideas 77-79, `CuidarDeLonge.kt`): `EXTRAS --es o cuidar` (state),
`cuidar_rodar`, `cuidar_perguntar` (opens the consent question; only a PHYSICAL remote key accepts it), `cuidar_desligar`.
It is off by default and needs the resident's consent on the TV: never turn it on at the Sala TV unless Paulo asks.

## Web panel, MCP and tunnel

- Panel: `PainelWeb.kt` on port 8080 (ports < 1024 are not allowed for apps). Pure logic with JVM tests in
  `PainelHttp.kt` / `PainelSeguranca.kt`. LAN only, Host/Origin checks, `X-Painel: 1` on POST, HttpOnly SameSite=Strict
  cookie, PBKDF2 PIN, growing lockout; destructive actions ask for the PIN again. Frontend in `assets/painel/`.
  Panel text is written in Portuguese; `i18n.js` picks the browser language and loads `i18n-<l>.js` (14 languages,
  one line per text: Portuguese key -> translation, plus `P` regexes for assembled phrases). A new panel text needs a
  line in all 14 files (`PainelI18nTest` checks they match) and each file must be in `TIPOS` in `PainelWeb.kt`.
- HTTPS at home (for installing the panel as a PWA): same panel also on port 8444 (8443 is taken by the built-in Chromecast, `mediashell`) with a certificate the TV generates on
  first use (`PainelTls.kt` + `CertificadoX509.kt`, SAN `tv`, `tv.lan`, `tv.local` + the TV's IP, names in
  `Config.PAINEL_NOMES_HTTPS`; the base name can be changed in the panel, e.g. `tv-sala`, and is stored in prefs
  `painel_tls`, used on the next certificate). The CA key is discarded right after signing (renewing = new CA, devices reinstall);
  the server key is AES-GCM-wrapped by an Android Keystore key in `noBackupFilesDir/painel-tls`. Step-by-step page:
  `/certificado` (filled server-side by `PaginaCertificado.kt`). Nothing of this goes into the repo.
- Network drive (one switch, off by default). Windows: MTP over IP (`MtpIp.kt` pure responder + `MtpNaRede.kt`, port 15740,
  announced by SSDP as `urn:microsoft-com:device:mtp:1` in `Ssdp.kt`/`AnuncioUpnp.kt`), so the TV shows in "This PC" like
  a phone; no password (the protocol has none): LAN only + an on-screen "Allow?" per computer (`mtp_permitidos` prefs,
  out of the backup). Each mounted USB drive is its own MTP storage (`MtpArmazem`, hot-plug via StoreAdded/StoreRemoved).
  Windows reconnects after a launcher restart only on a single `uuid:<udn>` byebye + alive (`Ssdp.avisoDoAparelho`); brand
  icon in `assets/upnp/`. Real-Windows findings and the PC bench (`MtpBancadaWindowsTest`): `docs/unidade-de-rede.md`. Whole server runs in `MtpIpTest`; real client: `gphoto2 --port ptpip:127.0.0.1` against
  `ONETV_MTP_SERVIR=1`. Mac/Linux/phones: WebDAV, `PainelDav.kt` at `https://<tv>:8444/dav/`, password = panel PIN with
  the same lockout; pure logic `DavLogica.kt`, server test `DavServidorTest`; `/unidade/conectar.cmd` (`ConectorWindows.kt`)
  maps it on Windows with a drive letter. SMB is impossible (port 445 < 1024): `docs/unidade-de-rede.md`.
- Panel shortcut on Windows (18/11/2026): `AnuncioUpnp` announces "<TV> · painel" in Explorer's Network whenever the panel
  runs (not tied to the drive switch; MTP joins via `mostrarMtp`) and re-announces when the IP changes (checked every
  minute). `PanelShortcut.kt`: a virtual read-only "Open the TV panel.url" (localized, `ur_atalho_nome`) at the root of
  internal storage in MTP (`MtpLoja.atalho`) and WebDAV (`Ambiente.atalho`), built with the current IP on each listing
  (`PainelWeb.atalhoPainel`, cache dir). A real file with the same name wins.
- MCP: `McpServidor.kt` on 127.0.0.1:8091, OAuth 2.1 (DCR + PKCE) in `McpAutorizacao.kt`, tools in `McpFerramentas.kt`.
  Reachable from outside only through `tunel/main.go` (Tailscale `tsnet`, userspace, Funnel :443), compiled to
  `libtsnet.so` by the `compilarTunel` Gradle task when Go is installed. Machine name: `onetv-<device name>`.
- Encrypted full backup: `BackupCompleto.kt` + `CofreBackup.kt` (PBKDF2 150k + AES-GCM).

## Design tools (AI skills)

Taste Skill (`design-taste-frontend`), `transitions-dev` and `transitions-polish` are fetched into
`.claude/skills/` by `scripts/install-design-skills.sh` on session start (git-ignored; if a skill is
missing, run the script and read its `SKILL.md`). React Bits is reference only (no React here):
`.claude/skills/react-bits-reference/`. Use them for the website and the web panel; for the Kotlin
app only as reference. Project rules beat skill defaults (brand colours, never pink, large type,
the cross look). Details and licenses: `docs/design-tools.md`.

**Rule (Paulo, 19/11/2026): before ANY design, UI, UX or motion work** (TV screen, website, panel,
artifact, mockup), first consult these tools and any other design reference available (the design
skills, earlier studies in project files, real products of the same kind), and only then create.
Reason: what we make from defaults alone always comes out with "the same face". Say in the reply
which references shaped the result.

## Build, test, measure

- JDK 21, Android SDK 36, Gradle wrapper 8.14, AGP 8.11, Kotlin 2.2. `./gradlew assembleRelease`;
  JVM tests: `./gradlew testReleaseUnitTest` (or `scripts/testes-puros.sh`).
- Screen test (idea 96): `./gradlew testReleaseUnitTest -Ptelas` draws the main screens with Robolectric (native
  graphics, 960x540 dp TV, en + pt) into `app/build/telas/` and fails if text shrank, got cut, overlaps other text or
  focus vanished, compared with `app/src/test/telas/referencia.json`. Changed a screen on purpose (or fixed one)? Rerun
  with `-PtelasAtualizar` and commit the new reference. Workflow `telas.yml` runs it on PRs that touch screens.
- Android 14 forces the HOME app to `speed-profile`; `app/src/main/baseline-prof.txt` + `androidx.profileinstaller`
  provide the profile. Debug builds run in `verify` mode: don't measure performance with them.
- Measuring: `dumpsys gfxinfo tv.sofaly.launcher reset`, return to HOME by component name 10×, `dumpsys gfxinfo`,
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
