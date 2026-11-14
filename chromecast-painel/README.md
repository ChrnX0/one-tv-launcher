# chromecast-painel (optional, older)

A small Node.js panel (port 9000) that manages the TVs of the house from a Windows PC over `adb`: apps, permissions,
files and the screen. It came before the web panel that now lives inside the launcher (`app/src/main/assets/painel/`,
port 8080) and is **not needed** to use One TV Launcher.

```bash
cd chromecast-painel
npm install
node server.js        # needs `adb` in the PATH; start.ps1 / start.vbs start it hidden on Windows
```

`start.vbs` assumes the clone is in `%USERPROFILE%\one-tv-launcher`; change the path if yours is elsewhere.
