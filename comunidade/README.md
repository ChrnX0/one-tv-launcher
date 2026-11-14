# Community app sheets and cleanup list

The launcher reads these two files from the `main` branch once a week (`ComunidadeApps.kt`) and adds them to the
tables bundled in the APK. To add an app, open a pull request. The TV only sees what was merged.

## `fichas-apps.json`

Same format as `app/src/main/assets/fichas-apps.json`: package name → sheet.

```json
{
  "com.example.app": {
    "r": "seguro",
    "d": "What the app does (Portuguese, up to 300 characters).",
    "q": "What stops working if it is hidden or uninstalled.",
    "en": "Short English phrase for the home screen (optional, up to 120)",
    "dep": ["com.other.package"]
  }
}
```

`r` is `seguro` (safe), `cuidado` (careful) or `essencial` (essential). A community sheet never lowers the risk of a
sheet bundled in the APK: the TV keeps the more serious of the two.

## `limpeza.json`

Factory apps that the cleanup screen may offer to hide (Device Owner, reversible).

```json
[
  {
    "pacote": "com.example.bloat",
    "nome": "Example",
    "perde": "o que deixa de funcionar",
    "perdeEn": "what stops working",
    "testadoEm": "2026-11-14, Chromecast with Google TV 4K"
  }
]
```

Only add an app after hiding it on a real TV and checking voice, search, Play Store, casting and Settings. `testadoEm`
is required. Packages in `Debloat.NUNCA` (system, accounts, Cast, keyboards, the launcher itself) are always refused.
