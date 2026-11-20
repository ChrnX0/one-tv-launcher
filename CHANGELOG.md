# Changelog

What changed in each version of Sofaly. Builds are numbered by their Release tag (`v<build>`); installed TVs update
themselves from [Releases](../../releases). The current list is the same one the TV shows in *Settings → System →
What changed*; when it is rewritten for a new Release, the previous one moves down here.

## Current version

<!-- auto:current -->
- Web panel with the Sofaly look: each area has its color on the icon, the Home page shows Sofy's notes, what is on the TV now and the worlds as doors, and nothing went missing
- Panel: a star on any block or switch adds it to “Your shortcuts” on the Home page, “Works with” links related blocks, and a change offers Undo
- Panel: new Sofy tab (today's notes, why she did it, Undo for frozen apps) and Apps with Frozen and Hidden shelves (Hidden asks for the PIN)
- TV Settings like the panel: long tabs have an “On this page” index at the top, and “Works with” pills take you to the related group
- Alarm clock: when the LG wakes up still on yesterday's loud volume, the alarm no longer starts loud
- Series: “Next episode” shows up when the credits start, “Skip recap” works like “Skip intro”, the bar says when the video ends, and ⏮ (or ▼ › Subtitles) replays the last 10 s with subtitles
- LG TV volume from the panel and the assistant: the volume and mute keys now really change the sound (they went to the HDMI output, which stays locked at maximum)
- Your phone becomes a remote: in the Sofaly phone app, tap the TV to swipe, tap and type; volume and ⏯ also in the notification and the quick settings tile
- Alarm rang on the TV: the phone with the panel installed gets a notice
- In the panel on a computer, drag a video, song or photo onto the page and watch it on the TV
- A screensaver that follows the day: in the morning it shows the day's summary, at night it gets softer and late at night it turns dark and warm, without the logo
- Search: you can speak instead of typing, and your last searches come first
- Empty screens show the mascot, what happened and what to do; a film with no poster gets its own colour instead of grey
- Remote hints at the bottom go away after you have seen them 3 times on each screen
- Text of at least 15 in the top bar and badges; the podcast player reads the focused button aloud
- Panel: 48 px buttons on phones, the chosen filter marked with the orange bar, stronger contrast for faint text
- Podcasts and radio: holding OK no longer pauses and resumes on its own, the PLAY and PAUSE keys do only what they say, and the remote's volume works inside the podcast player
- Movies and Series: switching between dubbed and subtitled continues from where you were; titles without a synopsis no longer show the word “null”
- Home (HA): the TV only talks to the Home Assistant it already knows; the podcast sleep timer no longer turns the radio off
- An update installed while the TV sleeps is now optimised that same night (before, the new version ran slower all the next day, until the screen turned off again)
- Movies and Series open faster: joining the dubbed and subtitled copies of each title now takes a fraction of the time (on big lists it kept the TV busy for over a minute after opening)
- Home screen: moving along the cross is lighter, because the faded items no longer need an extra drawing pass on every frame
- Reading the focused item aloud (Extras): closed screens no longer stay stuck in memory
- Player (movies, series, files and live TV): the ▼ menu is simpler. The first screen has Audio, Subtitles, Watch dubbed/subtitled, Picture and sound, More options; the current value shows on the right, › opens the next list, ✓ only marks what is chosen, and BACK goes to the list above
- Movies and Series: no more key hints along the bottom, so the posters get the space; the poster shows only the progress bar (the time left is in the box at the top), and labels never take two lines
- Settings: turning on high contrast (or changing the text size or a ready-made look) keeps the focus on the same option, and changing an option no longer shows the “Undo” notice that took the focus away (undo stays in System › Recent changes)
- New app name on the TV (tv.sofaly.launcher): settings, add-ons, plugins and the panel move over by themselves, with no reset
- One colour system: orange only marks the brand, the focus and progress; secondary text in soft white; every world keeps its own colour and drawing in every top-bar style
- Movies and Series get the same blue background as the home screen; “On the network” is now “From the computer”, with a folder icon
- High contrast for real: solid black background, columns at full strength; app menu with “Uninstall” set apart at the end; Diagnostics without the repeated summary; Home (HA) offline shows “Try again”
- Screens on one standard: Radio, Games, Alarm, Movies and Series titles in the same size and place; the remote hints look the same on every screen; empty Movies and Series show “Add a list”; menus without the “1 of 5”
- Clearer text: “Season 1, episode 2”, “Continue from 19 min” on the movie page, Extras in sections, the Scoreboard without a repeated line
- Errors with a way out: without network, the phone and move-from-another-TV screens show “Try again”; so does the Forecast with no data; in Diagnostics, a failing Wi-Fi opens Wi-Fi settings
- Help: the QR moved out of the clock corner to beside the text; an app’s menu covers the home screen behind it better
- The LG now turns off together when the Chromecast falls asleep on its own after sitting idle (it refused the HDMI turn-off command)
- All apps: the same header as the Radio and Games screens, with “In the grid: X · hidden: Y”; wallpaper plugins no longer show as apps
- Radio, Podcasts and Games: the list name sits right above the first item; the “Live” card leaves when the focused station is the one playing
- Bigger letters (nothing below 15) in settings, footer keys, the corner date and lists
- Phone and computer panel with the colours and fonts of the sofaly.tv website
- The new-device-on-network alert no longer repeats for the same phone; the focused button in the podcast player is clearly visible
- Series: the same two full rows of posters as Movies, with a SUB badge; the box at the top is one line (no plot) and lines up with the box on the left; long titles are shortened so the rating and year always show
- Movies and Series: your Trakt and MDBList lists now scroll with the categories, in alphabetical order; in Movies you can also pick a list in the Filter
- Movies: two full rows of posters fit on screen (6 per row on a typical TV); the title shows large at the top, and subtitled-only copies get a SUB badge
- Movies: dubbed and subtitled copies of the same film now show as one card (the details page has both versions)
- Movie and series details now come from Trakt: plot in your language, director, cast, trailer and the next episode
- Weather: forecast from MET Norway; the city or postal code is found on the TV itself, no outside service
- Simkl: connect your own account to see the community rating; the details page invites you to connect
- Top bar (Ilha): up to 3 things take turns, small status icons next to the clock, and the dot turns amber or red when something needs attention
- What just arrived (doorbell, reminder, appointment, alarm) grows under the pill for a few seconds; hold ▲ to open the quick panel
- TV in Continue: “On air” no longer disappears when the TV goes all day without opening the player (the home screen fetches the guide by itself)
- Continue: each item says what it is (TV, Series, Movie, Radio, Podcast, Game or the app), like the other categories; the title is below and the rest in the corner
- Alarm clock: the sound always starts low and rises slowly, over at least a minute and a half, even with “Ring: direct”, so it never starts at full volume
<!-- /auto:current -->

<!-- history -->

## 1.0 — 2026-11-13

First public release, then called One TV Launcher.

- **Home screen:** your apps first, stacks, hidden apps, manual or "most used" order, simple mode with large cards,
  background art and details that follow the focus, and the *Dark* and *Gallery* themes.
- **Weather:** current conditions from the nearest METAR report, three-model rain forecast, full-screen forecast with
  24 hours, 9 days, air quality, sun and moon; day/night icon that follows the real sunset.
- **Clock:** alarm clock that turns the TV on over HDMI-CEC, sleep timer, gentle shutdown, routines, reminders and
  Google Calendar.
- **Screensaver:** clock, weather, photo frame and family board, replacing the stock screensaver.
- **Live TV and movies:** IPTV (Xtream Codes / M3U) with guide, catch-up and favorites; movies and series with
  "continue watching"; audio and subtitle preferences; Plex, Jellyfin, Emby, SMB, WebDAV and UPnP.
- **Control:** PIN-protected web panel (apps, permissions, files, live screen, remote keys, backups), optional MCP
  server for Claude through a Tailscale tunnel, Wake-on-LAN.
- **Smart home:** Home Assistant switches and scenes, TV state sensor, doorbell camera pop-up.
- **Privacy and upkeep:** private DNS picker with rollback, per-app DNS and data usage, encrypted nightly backups,
  overnight maintenance, self-update from signed Releases, system updates only at night.
- **Setup without a PC:** welcome wizard with built-in ADB pairing and optional device owner.
- Two APKs: `one-tv-launcher.apk` (32-bit, Chromecast with Google TV) and `one-tv-launcher-arm64.apk` (64-bit).
