# Notas ao revisor (Play Console › Conteúdo do app / Acesso ao app / Declarações)

Princípio decidido pelo Paulo: **transparência absoluta**. Cada poder vai declarado com o motivo e o caminho descartado.
Texto em inglês (o revisor lê em inglês); colar no campo "Notas para o revisor" e nas declarações de cada permissão.

---

Sofaly is a home screen (launcher) for Google TV / Android TV, built for older and non-technical people: big letters,
fewer buttons, and a family member can help from afar. Everything below is **off by default** and turned on by the
person at the TV, with an on-screen explanation. No data is sent to us: we have no server.

**1. Accessibility service (`AccessibilityService`, declared with `isAccessibilityTool=false`)**
- Why: Google TV's own launcher has a higher HOME priority than any third-party launcher, so the HOME button would
  always go back to it. The service (a) sends HOME presses to Sofaly, (b) lets long-press keys open favourite apps,
  (c) lets the family's remote help reach the TV. It does not read other apps' content for any other purpose, does not
  log text, and does not send anything off the device.
- Alternative tried and dropped: `RoleManager.ROLE_HOME` / preferred activity — ignored by Google TV (launcherx has priority 2).
- Prominent disclosure: shown before the system Accessibility settings open; the person must press "Agree" with the remote.

**2. Shell through Wireless debugging (ADB over TLS, paired on the TV itself)**
- Why: the TV has no other way to grant `WRITE_SECURE_SETTINGS` (private DNS), `PACKAGE_USAGE_STATS` (sort apps by use),
  "Alarms & reminders" and to install an APK the person sends from the phone. Pairing needs the person to open Developer
  options and type the 6-digit code; the code never leaves the TV.
- Alternative dropped: `REQUEST_INSTALL_PACKAGES` / self-update — removed from this build; the store updates Sofaly.

**3. Device Owner (optional, only set by the person through ADB / setup)**
- Why: kiosk-style "simple mode" for an older relative: hide apps, keep Sofaly as HOME, always-on VPN for parental DNS.
  Uses only `DevicePolicyManager` APIs. Never wipes, never transfers ownership.

**4. Lending these powers to other apps (Shizuku-compatible server, Dhizuku-compatible server)**
- Off by default; each requesting app needs the panel PIN typed on the TV. Requests to wipe the device, clear the owner,
  transfer ownership or touch Sofaly itself are always refused (`FiltroDpm`).

**5. Notification listener** — only to show "Now playing" (title and artwork of the media session) on the home screen.

**6. Help from afar (family)**
- Only actions **started by the person**: holding BACK 3 s sends a help request; once a day the TV asks "Let Ana know
  you're fine?" and only the button sends it. Consent is given on the TV, with a physical remote key, and can be
  withdrawn at any time. No location, no screen recording, no activity monitoring. (Automatic "TV not turned on"
  alerts are not part of this app.)

**7. AI assistants (MCP) and web panel** — the person's own Claude/ChatGPT can control the TV through a closed action
catalog, over the person's own Tailscale account. Every action shows a notice on the TV. Shell commands from AI are
blocked by default and need the PIN.

**8. Subscriptions** — Pro and Ultra are sold only with Google Play Billing. No external payment links.

Test access: no account needed. To see the help-from-afar flow, open Settings › Care from afar.
