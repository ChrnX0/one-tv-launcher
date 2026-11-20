<p align="center">
  <img src="docs/readme/banner.png" alt="Sofaly: your TV, simple again. The Sofaly home screen on a TV." width="100%">
</p>

<p align="center">
  <a href="../../releases/latest"><img src="https://img.shields.io/github/v/release/ChrnX0/sofaly?label=download&style=for-the-badge&color=FF6A2B" alt="Download the latest version"></a>
  <img src="https://img.shields.io/badge/Google%20TV%20%C2%B7%20Android%20TV-10%2B-121528?style=for-the-badge" alt="Google TV and Android TV 10 or newer">
</p>

<p align="center">
  <a href="https://sofaly.tv"><b>sofaly.tv</b></a> ·
  <a href="#install">Install</a> ·
  <a href="#what-changed-in-this-version">What changed</a> ·
  <a href="#every-feature">Every feature</a> ·
  <a href="https://sofaly.tv/help.html">Help</a>
</p>

---

**Why.** The stock Google TV home is a billboard with a few apps attached: rows of ads, trailers that play by
themselves, and the app you wanted three rows down.

**What for.** Sofaly puts the house back in charge. One tidy screen anyone in the family understands, the right
weather and an alarm that turns the TV on, and a phone and a computer that can do everything the remote can.

**How.** Sofaly replaces the home screen of a Google TV or Android TV. It doesn't show ads, doesn't need an account and
doesn't send telemetry. It runs every day on a living-room Chromecast with Google TV, so it's built for a couch and a
remote, with large type for people who don't like small print.

## How it fits together

Sofaly is five parts that share one memory. Change a setting in the panel and the TV has it; the screensaver shows
the same weather and photos as the home screen; the phone gets the alarm the TV just rang.

<p align="center">
  <img src="docs/readme/map.png" alt="Map: the TV home in the middle, linked to the web panel, the screensaver, the phone and api.sofaly.tv" width="100%">
</p>

| Part | Why it exists | What you get | How it connects |
|---|---|---|---|
| **TV home** | Google's home puts ads first. | *The cross*: Continue, Watch, Listen, Play and Apps in one row, with what's inside right below. | Everything else feeds it: settings, weather, photos, reminders. |
| **Screensaver** | An idle TV is a black rectangle. | Clock, weather, your photos and the family board. In the morning a summary of the day, late at night dark and warm. | Uses the home screen's weather, photos and reminders. |
| **Web panel** | Settings with a remote are slow. | Every TV setting from a computer or phone browser, the live screen, files, backups. PIN-protected, home network only. | Same settings as the TV. A change shows up on the TV at once, with Undo. |
| **Phone app** | The remote is always lost. | Remote with touch and typing, volume keys, *Share to TV*, a notice when the TV alarm rings. | Finds the TVs at home and opens the panel with a pinned certificate. |
| **api.sofaly.tv** | Some things can't live on the TV. | Podcasts, add-ons, a trusted HTTPS name for the panel, pairing the phone with the TV by code. | No account: each device is a key pair, and messages are sealed so the server can't read them. Sofaly keeps working if it's down. |

## On your TV

<table>
  <tr>
    <td width="50%"><img src="website/assets/home/tela-en-home.webp" alt="Home screen: the cross with Continue, Watch, Listen, Play and Apps"><br><sub><b>The cross.</b> Pick a world with ◀ ▶; what's inside opens right below.</sub></td>
    <td width="50%"><img src="website/assets/home/tela-en-radio.webp" alt="Radio and podcasts"><br><sub><b>Listen.</b> Radio and podcasts with the same care as video.</sub></td>
  </tr>
  <tr>
    <td><img src="website/assets/home/tela-en-despertador.webp" alt="Alarm clock"><br><sub><b>Wake up.</b> The alarm turns the TV on and raises the volume slowly.</sub></td>
    <td><img src="website/assets/home/tela-en-jogos.webp" alt="Games"><br><sub><b>Play.</b> All your games in one place, ready for the controller.</sub></td>
  </tr>
  <tr>
    <td><img src="website/assets/home/painel-en-pc.webp" alt="Web panel on a computer"><br><sub><b>Web panel.</b> What's on the TV now, quick remote and every setting.</sub></td>
    <td><img src="website/assets/home/tela-en-configuracoes.webp" alt="Settings on the TV"><br><sub><b>Settings.</b> The same groups as the panel, with an index at the top.</sub></td>
  </tr>
</table>

<sub>These are the same pictures as sofaly.tv, so both pages change together.</sub>

## Install

You need a **Google TV / Android TV** device (Android 10 or newer). It's tested every day on a Chromecast with Google
TV (Android 14). Download the APK from [**Releases**](../../releases/latest) or [sofaly.tv](https://sofaly.tv):

| File | For |
|---|---|
| `sofaly-launcher.apk` | 32-bit devices (`armeabi-v7a`), e.g. **Chromecast with Google TV** |
| `sofaly-launcher-arm64.apk` | 64-bit devices (`arm64-v8a`) |

Already installed? Sofaly updates itself from here.

### Install with Downloader (no computer needed)

1. Install **Downloader** by AFTVnews from the Play Store on your TV.
2. Open it and enter the code **`6566820`** (or the short link [`aftv.news/6566820`](https://aftv.news/6566820)). It downloads
   `sofaly-launcher.apk` (32-bit, Chromecast with Google TV).
3. Tap **Install**.

On a 64-bit TV, type this address in Downloader instead:
`sofaly.tv/download-arm64`

### Then

1. **Install the APK** with Downloader (above), any other sideloading app, or `adb install sofaly-launcher.apk`.
2. **Open it.** A welcome wizard walks you through each step with the remote. You don't need a computer:
   1. Turn on the launcher's remote-key service (accessibility).
   2. Turn on Developer options. The wizard shows you where.
   3. Pair the launcher with the TV. It reads the pairing code from the screen for you.
   4. *(Optional)* Make it the **device owner**. This lets it pin the HOME button, hide apps and protect itself from
      "Force stop".
3. Press **HOME**. Done.

> [!IMPORTANT]
> **The device owner step is optional, and only a factory reset undoes it.** Google turns off for a few seconds while
> it's applied; your Google account stays, but any extra user profiles on the TV are removed. Everything else works
> without it. Details: [docs/device-owner.md](docs/device-owner.md).

> [!NOTE]
> Google's own launcher stays installed. Gemini, voice search results and Google's notifications panel live inside it.
> Sofaly keeps HOME for itself and lets Google handle the rest.

## What changed in this version

The same list the TV shows in *Settings → System → What changed*. Older versions: [CHANGELOG.md](CHANGELOG.md) and
[Releases](../../releases).

<!-- auto:changes -->
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
<!-- /auto:changes -->

## Every feature

<!-- auto:features -->
**121 features in 12 areas**, the same list as [sofaly.tv](https://sofaly.tv/#mundos). Open an area to see each one and where to find it on the TV.

<details>
<summary><b>The cross</b> · A tidy screen anyone understands, arranged the way you like. (19)</summary>

- **The cross.** Continue, Watch, Listen, Play and Apps in one row. The world you pick grows big, with what's inside right below it. <sub>◀ ▶ switch worlds, ▲ ▼ go down the column, OK opens.</sub>
- **Continue.** One place to get back to what you were doing: the episode, the station, the podcast or the last app. <sub>It's the first item of the cross.</sub>
- **Top bar.** Search, voice, the weather in one line and the clock, always in the same place. It folds away while you browse so the screen can breathe. <sub>Go up to it with ▲.</sub>
- **Google keeps working.** The Google Assistant, Gemini, search and your Google account work as before. Sofaly changes the home screen, not your TV. <sub>Use the microphone button as always.</sub>
- **Quick panel.** Wi-Fi, Bluetooth, sleep timer, sound output, alarm, profiles, notifications and the shopping list, from any app. <sub>Hold BACK.</sub>
- **Your remote, your rules.** HOME always comes back to Sofaly. Holding BACK opens the quick panel, holding HOME opens the House, and holding OK on an app opens its menu. <sub>A key tester shows every button the TV receives.</sub>
- **Now playing.** A pill at the top with the cover and title of what's playing. OK pauses or resumes, holding OK opens the app. <sub>Turn it on in Home screen › More options.</sub>
- **Themes and backgrounds.** Dark, with the art of what's in focus. Gallery, with a painting from The Met every day. Sky, with satellite and map. Or a background of your own, photo or video. Plugin backgrounds work too, like the aerial footage from Overflight. <sub>Dark, Gallery and Sky in Basic; the others in Pro.</sub>
- **Icons you like.** Use an icon pack from Play or change a single app's icon, one by one. <sub>Hold OK on the app › Change icon.</sub>
- **Animations that fit your TV.** Strong TVs animate everything; simpler ones animate only what you picked and slow down by themselves when frames drop. <sub>Settings › Appearance.</sub>
- **Moon phase.** Tonight's moon and how much of it is lit, worked out on the TV itself, with no internet. <sub>In the forecast, on the screensaver and on the weather icon at night. Settings › Appearance.</sub>
- **Countdown.** How many days are left until the date you pick, or until the next holiday. <sub>In the pill at the top of the screen. Settings › Appearance.</sub>
- **House reminder.** A note that comes back on the weekdays you choose: recycling day, medicine, a class. <sub>In the pill at the top of the screen. Settings › Appearance.</sub>
- **Your service's status.** The TV checks an address you give it and shows whether it's up: the home server, the shop's website. <sub>In the pill at the top of the screen. Settings › Appearance.</sub>
- **Today's rates.** Currencies, bitcoin and ether at today's value, from open official sources. <sub>Next to the weather, in the top bar. Settings › Appearance.</sub>
- **Your team's next match.** When and against whom your team plays next, on a card on the home screen. <sub>In the TV item of the cross. Settings › Appearance.</sub>
- **Cards from other apps.** Besides Sofaly's own cards, partner apps put theirs on the home screen: the delivery order, the bus, the notice from the building. <sub>Cards show at the end of Watch.</sub>
- **The Island at the top of the screen.** Notices take turns in a pill at the top: no internet, reminder, calendar and alarm. What matters grows for 5 seconds with “▲ to open”, the Home doorbell shows up right away and, when you turn on the TV, a summary of what happened appears. Hold ▲ at the top of the cross to open the Hub. <sub>Home screen, at the top.</sub>
- **The right suggestion at the right time.** No artificial intelligence: from the week's use, the TV suggests what makes sense at that hour. <sub>Home screen.</sub>

</details>

<details>
<summary><b>Watch</b> · Your movies, your series and a home-theater player. (14)</summary>

- **From computer.** Videos, music and photos from Plex, Jellyfin, Emby, PC or NAS folders (SMB, WebDAV, FTP, SFTP, NFS), OneDrive, Dropbox, Box, Google Drive, UPnP and USB drives. <sub>Servers that are on and USB drives show up by themselves; the rest go in Add.</sub>
- **A player that plays everything.** Dolby (AC3, E-AC3), DTS and TrueHD sound, and Blu-ray, DVD and anime subtitles, without installing Kodi or VLC. <sub>Press ▼ during a video for audio, subtitles and subtitle size.</sub>
- **Picks up where you stopped.** Movies and episodes come back at the right spot. The next episode starts after 10 seconds, and after 3 in a row the TV asks if you're still watching. <sub>Works by itself.</sub>
- **Skip intro.** When a series' intro is known, “Skip intro · OK” shows up. You can also mark the intro once and the TV remembers. <sub>On out of the box.</sub>
- **Preferred audio and subtitles.** Original with subtitles or dubbed, chosen once and remembered per title. Subtitles from the internet when the file has none. <sub>TV and movies › Audio and subtitles.</sub>
- **Movies without judder.** The TV switches to 24 Hz while a movie plays, so camera pans stay smooth. <sub>Turns on with the movie; the screen blinks black for a second.</sub>
- **See and hear better.** Subtitles on a black box or in yellow, “Clearer voice” that brings out speech, and “Even volume” that tames loud movies and lifts quiet ones. <sub>In the player's ▼ menu.</sub>
- **Automatic subtitles.** When there's no subtitle in your language, the TV translates the one there is. <sub>Pick it in the player's subtitle menu.</sub>
- **Full movie pages.** Poster, rating, cast, trailer, age rating and the movie's collection on a beautiful page, in large text. With “Who is that actor?”: photo, character and other work. <sub>OK on a movie or series.</sub>
- **Your Trakt and Simkl lists.** What you want to watch and what you've watched, synced with your account and your other devices. <sub>Sign in with the QR in TV and movies › Services.</sub>
- **One search for everything.** Finds movies, series, stations and apps at once, and carries the same search to YouTube, Netflix or Prime Video. <sub>In the top bar, or by voice.</sub>
- **Two subtitles at once.** For example, your language at the bottom and the original on top, to learn or to watch together. <sub>Player ▼ menu › Subtitles.</sub>
- **Louder dialogue.** When 5.1 sound becomes stereo, speech stays in front and explosions don't drown out voices. <sub>Player ▼ menu.</sub>
- **No spoilers and a second language.** The summary of an episode you haven't seen stays hidden. No audio or subtitles in your language? The second one you chose comes in.

</details>

<details>
<summary><b>Listen</b> · Radio and podcasts with the same care as video. (5)</summary>

- **Radio from all over the world.** Thousands of open radio stations, by name, country or genre, with your favorites and the last ones you played. <sub>Listen › Radio.</sub>
- **Podcasts.** Subscribe by name, listen on the TV and pick the episode up where you stopped. <sub>Listen › Podcasts.</sub>
- **Discover podcasts.** Search the whole open podcast catalog and see what's trending in your country. <sub>Listen › Podcasts › Search.</sub>
- **Full-screen player.** Big cover, chapters, speed and a sleep timer that pauses at the end of the episode. <sub>Listen › Now playing › Open the player.</sub>
- **Sound in your headphones.** The movie's sound goes to your phone's headphones, in sync, to watch at night without waking anyone. <sub>Player ▼ menu › Sound on phone.</sub>

</details>

<details>
<summary><b>Play</b> · All your games in one place, ready for the controller. (6)</summary>

- **All your games in one place.** GeForce NOW, Steam Link, Moonlight, PS Remote Play, RetroArch and the TV's games leave the apps row and live in Play, grouped into Cloud, From the PC, Retro and On the TV. <sub>Hold OK on a game to change its group.</sub>
- **Play on the PC upstairs.** The TV wakes the computer over the network, waits for it to answer and opens Moonlight or Steam Link. Waking the PC over the network also works outside Play, from a home screen shortcut. <sub>Save the computer once in the web panel.</sub>
- **Test the network.** Tells you whether your internet can handle cloud gaming, and warns before opening a cloud game on a weak network. <sub>Play › Test the network.</sub>
- **Game controllers.** Pair a controller without hunting for menus, see the connected ones at the top and, if you like, make a connected controller open Play. <sub>Play › Pair controller.</sub>
- **Game mode.** While a game is open, the screensaver, the night filter and notices wait for the match to end. <sub>Turns on by itself.</sub>
- **Your Discord crew.** See who from your Discord server is online and in which game, and call the crew right from the TV. <sub>Turn on the server widget and paste its number.</sub>

</details>

<details>
<summary><b>Apps</b> · Your apps in an order that makes sense. (3)</summary>

- **Your favorites up front.** Apps shows the 8 apps you open most, and “All apps” brings the rest in a grid. <sub>Apps, on the cross.</sub>
- **Your way.** Move, stack or hide any app. Factory apps you don't use step out of the way. <sub>Hold OK on an app.</sub>
- **Free Netflix and YouTube buttons.** The remote's app buttons open any app you want. Press to open, hold 3 seconds to change it. <sub>Works with the original remote.</sub>

</details>

<details>
<summary><b>The house</b> · Alarm clock, timers, automations and the connected home. (13)</summary>

- **An alarm that turns the TV on.** On time, the TV turns itself on, the volume rises slowly and there's snooze. Each alarm has a name, days, a sound and a volume, even an app or the last station. <sub>Hold BACK › Alarm.</sub>
- **Timers and hourglass.** Up to 3 named timers with an on-screen notice, and a stopwatch. <sub>“Hey Google, open Remind me in 10 minutes”.</sub>
- **Gentle shutdown.** The sleep timer dims the screen and lowers the volume in the last 5 minutes, so nobody wakes up at the cut. <sub>Timer tile in the quick panel.</sub>
- **Turns off if nobody's watching.** After the time you pick without a key press, the TV asks in large text whether you're still watching, and then sleeps. <sub>Screen and power › Turn off when idle.</sub>
- **Ready-made recipes.** Sleep, Soap opera and Match set up the TV in one go (sound, screen and notices). One OK applies, another undoes. <sub>Quick panel.</sub>
- **Automations.** At a set time, when a button is pressed or when something happens (the movie paused, everyone left, rain is coming): the TV opens an app, runs a scene or turns off. <sub>House and automations › Automations.</sub>
- **Home Assistant on the TV.** Lights, plugs, fans, covers, air conditioning and scenes, right from the couch. It talks to your server on your network, with no cloud in between. <sub>Hold HOME.</sub>
- **Who's at the door.** When the doorbell rings, the camera's snapshot pops up over whatever you're watching. <sub>With Home Assistant.</sub>
- **Sleeps when everyone leaves.** With a UniFi router, the TV notices that the household's phones have left and goes to sleep. <sub>Screen and power › More options.</sub>
- **Voice shortcuts.** “Hey Google, open Weather report” reads the forecast aloud. Movies, My home, Board, timers and turning off open the same way. <sub>Use the microphone button.</sub>
- **Turns on with the TV.** Turned on the TV with its own remote? The device wakes up too, no black screen. Plus LG volume over the network; when the device falls asleep, the LG turns off with it.
- **Do not disturb.** During the hours you choose, notices and sounds wait. <sub>Home and automations.</sub>
- **“Only if” and holidays.** Automations that only run on weekdays, with someone home or when it rains, aware of your country's holidays.

</details>

<details>
<summary><b>Weather and screensaver</b> · The right weather and a screensaver worth leaving on. (15)</summary>

- **Today's weather, measured.** The temperature comes from the nearest airport's report, and the day/night icon follows sunrise and sunset where the TV is. <sub>At the top of the home screen.</sub>
- **Rain from three models.** The chance of rain is the median of three weather models (ECMWF, ICON and GFS), which misses less than any one of them. <sub>Shows up in the forecast by itself.</sub>
- **Full-screen forecast.** The next 24 hours, a rain chart, 9 days, air quality, the sun and the moon. <sub>OK on the weather.</sub>
- **Rain alert.** A notice over your app when rain is about to start. Time to bring the laundry in. <sub>Home screen › Weather.</sub>
- **A screensaver worth leaving on.** Greeting, a big clock, the date, the weather and cards with notifications, the next alarm and messages. In the Gallery theme, the painting of the day drifts slowly so nothing burns in. <sub>Screen and power › Screensaver.</sub>
- **Your photos on the screensaver.** Send photos from your phone or pick a network folder. They stay in a folder only the TV can see. <sub>A QR opens the panel on the right page.</sub>
- **Good morning.** When you stop the alarm: greeting, time, forecast and today's agenda, and the TV can read it aloud. <sub>“Hey Google, open Good morning”.</sub>
- **Google Calendar.** Today's events and birthdays on the TV, from one account or all of them together. <sub>OK on the date at the top.</sub>
- **Night filter.** At night the screen gets warmer and softer on the eyes. <sub>Screen and power › More options.</sub>
- **Weekly recap.** On Sunday night, a card with screen time per app, internet used and network drops. <sub>Screen and power › More options.</sub>
- **Strong heat or cold alert.** The same alert as rain, over any screen, when strong heat or cold is coming.
- **The night sky.** Planets, the Space Station and eclipses, calculated on the TV itself, no internet needed. <sub>Screensaver.</sub>
- **Earth seen from space.** Photos taken by astronauts and a real Sky theme, with a map of your area.
- **Fun facts and the time in words.** One fun fact at a time on the screensaver and the clock written out: “nine fifty-one”. <sub>Screensaver.</sub>
- **Your TV's year.** In December, a look back at the year: what was watched, heard and played most on your TV. It stays on the TV. <sub>Shows up in December.</sub>

</details>

<details>
<summary><b>Family</b> · A profile for everyone, care for the kids and a warm welcome for guests. (12)</summary>

- **A profile for everyone.** Each Google account on the TV has its own favorites, things to continue and app order. <sub>Hold BACK › Profiles.</sub>
- **Kid profile.** Mark a profile as a kid's: kids mode and bedtime turn on by themselves, and leaving asks for the parents' PIN. <sub>Quick panel › Profiles.</sub>
- **Bedtime.** During the hours you choose, only allowed apps open and the volume has a ceiling. <sub>Family › More options.</sub>
- **Daily screen time.** A daily TV limit for whoever you choose. <sub>Family › More options.</sub>
- **Guest mode.** Hides your profile, your calendar and the apps you choose while you have visitors. <sub>Guest tile in the quick panel.</sub>
- **Guest Wi-Fi.** Type the password once; guests scan a QR on the TV and they're in. <sub>Family › Guest Wi-Fi.</sub>
- **Family board.** Shopping list, pinned notes and today's birthdays, in the quick panel and on the screensaver. <sub>Edit from your phone; OK ticks an item off.</sub>
- **Messages from afar.** Family who live far away send messages and photos to the TV at home, from anywhere. <sub>Invite by link.</sub>
- **Video messages.** A short video from the family shows up on the TV with the sender's name. <sub>From the panel on the phone.</sub>
- **Age ratings.** In kids mode, movies and series by the right age, on top of the categories. <sub>Family.</sub>
- **Host mode.** For rentals: guests use the TV, and your things stay put away and come back at the end of the stay.
- **Care from afar.** Someone who lives alone asks for help by holding a key, and the caregiver's phone gets the alert. Only turns on with a yes from the resident, given on the TV itself. <sub>Family › Care from afar.</sub>

</details>

<details>
<summary><b>Phone and computer</b> · The TV talks to the devices at home. (11)</summary>

- **Panel on your phone and computer.** Remote control, typing with accents, a live view of the TV screen, screensaver photos, the board and backups, from the browser of any device at home. <sub>Remote access › Sign in from the phone (QR).</sub>
- **Ask the TV.** Type or say “open YouTube”, “remind me in 7 minutes”, “turn off in 30 minutes” and the TV does it. <sub>In the panel on the phone.</sub>
- **Share from your phone.** Installed as an app, the panel shows up in Share: a YouTube video opens on the TV, a link opens on the TV and a text becomes a message. <sub>Share › Sofaly.</sub>
- **The TV as a network drive.** Copy files to the TV by dragging them: on Windows it shows up in This PC like a phone; on Mac, Linux and phones, over secure WebDAV. <sub>Remote access › Show as a network drive.</sub>
- **Shortcut on Windows.** The TV shows up in Explorer's Network with a shortcut that opens the panel, even if its IP changes. <sub>On whenever the panel is on.</sub>
- **Continue on another TV.** Stopped in the living room? Send it to the bedroom: the other TV shows “Continue where you stopped?” and opens at the same spot. <sub>Player menu › Send to another TV.</sub>
- **Watch together.** Two homes watch the same thing at the same time: pause here, it pauses there, with reactions in the corner. <sub>Invite between family TVs.</sub>
- **Several homes.** Look after the TV at your place and at your parents' from the same phone. <sub>From the phone app.</sub>
- **Talk to your TV through Claude.** Chat with your TV through Claude: it tells you what is on, opens apps and adjusts things. A QR code on the screen, no key and no password. <sub>Remote access › Claude over the internet.</sub>
- **Phone keyboard.** A text field opened on the TV? The keyboard shows up on your phone. <sub>Turns on by itself with the panel.</sub>
- **Alerts on your phone.** Alarm, doorbell and notes reach your phone, with no server in between. <sub>Panel › Alerts on your phone.</sub>

</details>

<details>
<summary><b>Takes care of itself</b> · Backup, maintenance and diagnosis without you remembering. (12)</summary>

- **Locked backup, every night.** While the TV is idle, a full backup locked with your password. The last 7 are kept, and every night the TV checks it would restore. <sub>System › Automatic backup.</sub>
- **A copy in the cloud.** Copies also go to your Google Drive, OneDrive, Dropbox, Box, NAS or USB drive. Locked: not even the cloud can read them. <sub>System › Automatic backup.</sub>
- **Overnight maintenance.** Clears leftovers, repairs permissions and reschedules alarms while you sleep. <sub>Runs by itself.</sub>
- **TV diagnosis.** Internet, DNS and why an app stopped, all at once and in sentences you can understand. <sub>“Hey Google, open TV diagnosis”.</sub>
- **Internet speed test.** Measures your internet in one tap and shows Wi-Fi signal, latency and speed in plain words. <sub>Settings › Advanced › Network.</sub>
- **How this TV is doing.** The TV measures its own numbers (time to open, crashes, alarms on time) and shows them on one screen. Nothing leaves your home. <sub>System › How this TV is doing.</sub>
- **Safe mode.** If a new version misbehaves right after installing, the TV falls back to a safe mode and shows the way out. <sub>Shows up only when needed.</sub>
- **What changed.** Each version explains what changed and brings a short checklist to try with the remote. <sub>System › What changed.</sub>
- **Report a problem.** Tell what happened with the remote or from the phone; the TV gathers what's needed, nothing personal. <sub>System › Report a problem.</sub>
- **Wi-Fi that comes back by itself.** If Wi-Fi doesn't come back after an outage, the TV reconnects on its own.
- **No internet, no scare.** The home screen tells you in one line what still works while the internet is down.
- **Clean up factory apps.** Moves out of the way the apps that came with the TV and you don't use, with undo, and shows the measured before and after. <sub>System › Cleanup.</sub>

</details>

<details>
<summary><b>For everyone</b> · Made for people who see, hear or use the remote in different ways. (5)</summary>

- **15 languages.** Portuguese, English, Spanish, French, German, Italian, Russian, Polish, Turkish, Japanese, Chinese, Korean, Hindi, Indonesian and Arabic, with right-to-left screens. It follows the TV's language. <sub>Changes with the TV by itself.</sub>
- **Text size.** Normal, Large or Larger, on every screen, tested so text never gets cut or overlaps. <sub>Home screen › Text size.</sub>
- **Simple mode.** Big cards, fewer things on screen and the app's name spoken aloud. For people who find the remote hard. <sub>Home screen › More options.</sub>
- **Read aloud.** The TV says what's in focus, with navigation sounds so you know where you are. <sub>Home screen › More options.</sub>
- **High contrast.** Text at a 7:1 contrast ratio and every screen readable by Android's screen reader. <sub>Follows the TV's accessibility settings.</sub>

</details>

<details>
<summary><b>Privacy</b> · No ads, no tracking, and a PIN where it matters. (6)</summary>

- **No ads at all.** Not on the free plan, not one sponsored pick. The screen is yours. <sub>Always.</sub>
- **No tracking.** No account, no usage analytics and no crash report leaving without you sending it. <sub>Always.</sub>
- **A PIN where it matters.** Anything that changes the TV asks for a PIN, and actions that delete something ask again. Too many wrong tries? The wait grows each time. <sub>Remote access › PIN.</sub>
- **Private DNS.** Pick a secure DNS (DNS over TLS); if it doesn't answer, the TV goes back to the previous one by itself. <sub>Advanced › Network.</sub>
- **Internet per app.** How much internet each app used and who it talked to, kept on the TV for 7 days. <sub>Advanced › Network.</sub>
- **PIN to change the home screen.** Nobody messes up your screen: moving, hiding or swapping asks for the PIN.

</details>
<!-- /auto:features -->

## Privacy and safety

- No ads, no account and no telemetry. Data about your home stays on your TV.
- The web panel only answers on your home network, checks where each request comes from, and asks for the PIN again
  before anything that can't be undone. Wrong PINs lock it for longer each time.
- Passwords and keys are encrypted with the Android Keystore. Backups are encrypted with your own password.
- Updates come only from this repository's [Releases](../../releases), and only APKs signed with the project key are
  accepted.
- Found a security problem? Please report it privately, as described in [SECURITY.md](SECURITY.md).

## Ideas and bugs

Bug reports and ideas are welcome in [Issues](../../issues): see [CONTRIBUTING.md](CONTRIBUTING.md). Pull requests
aren't accepted.

## License

Sofaly is proprietary software: © 2026 ChrnX0, all rights reserved (see [LICENSE](LICENSE)). Copying it, changing it
or building apps from it needs written permission. Versions up to Release v458906 (15 November 2026) were published
under the GPL-3.0, and those copies keep that license. Third-party code keeps its own license (see Credits below).

<!-- This README is partly generated: run `python3 scripts/readme.py` after changing website/features.json,
     website/content/en.json or res/values/strings_versoes.xml. Pictures: `node scripts/readme-images.js`. -->

## Credits

The fonts, icons, libraries and data sources all come with their licenses in
[`app/src/main/assets/licencas.txt`](app/src/main/assets/licencas.txt). You can also read them on the TV in
*Settings → Licenses*. The weather data comes from [Open-Meteo](https://open-meteo.com) and METAR reports, and the paintings come from
[The Met Open Access](https://www.metmuseum.org/about-the-met/policies-and-documents/open-access).

---

<p align="center"><sub>Not affiliated with Google. "Google TV", "Android TV" and "Chromecast" are trademarks of Google LLC.</sub></p>
