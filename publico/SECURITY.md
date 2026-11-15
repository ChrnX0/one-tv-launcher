# Security Policy

One TV Launcher can run with strong privileges on a TV: it may be the **device owner**, it pairs a built-in ADB client
with the TV (shell user), it serves a web panel on the local network and, if you turn it on, an MCP server reachable
through a Tailscale tunnel. Security bugs here matter, and we take them seriously.

## Supported versions

Only the [latest Release](../../releases/latest) receives fixes. Installed launchers update themselves from Releases.

## Reporting a vulnerability

**Please do not open a public issue.** Use GitHub's private reporting instead:
[**Report a vulnerability**](../../security/advisories/new) (Security tab → *Report a vulnerability*).

Include what you can: the affected build, the steps to reproduce, and what an attacker could do (e.g. "anyone on the
same Wi-Fi can install an APK without the PIN"). You should get a first answer within 7 days. Once a fix is released,
you'll be credited in the advisory unless you prefer otherwise.

## Scope

In scope, for example:

- getting past the web panel PIN, its rate limiting, or its Host/Origin checks;
- reaching the web panel or the MCP server from outside the home network without the owner enabling it;
- getting the launcher to run shell commands, install or remove apps, or read secrets without the owner's consent;
- an update accepted by the self-updater that was not signed with the project key;
- secrets (IPTV, Home Assistant, Tailscale, backup password) leaking in logs, backups or crash reports.

Out of scope: attacks that need the owner's PIN, physical access with the remote and Developer options already
unlocked, or a rooted device.

## Design notes

- Web panel: local network only, Host and Origin checks, HttpOnly + SameSite=Strict cookie, PIN stored with PBKDF2,
  growing lockout after wrong PINs, and the PIN again before destructive actions.
- MCP: OAuth 2.1 with PKCE, off by default, reachable only through the Tailscale tunnel.
- Secrets are encrypted with the Android Keystore; backups are encrypted with the owner's password (PBKDF2 + AES-GCM).
- The self-updater only installs APKs signed with the same key as the installed launcher.
