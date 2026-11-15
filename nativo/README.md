# Native player libraries (FFmpeg + libass)

The video player decodes in software what the TV chip can't (Dolby/DTS/TrueHD audio, old AVI/WMV/RMVB/FLV files,
AV1 on old chips) and draws ASS/SSA subtitles with libass. This folder replaces the GPL NextLib with our own glue.

- `compilar.sh`: downloads the pinned sources (SHA-256 checked), builds them with the NDK and installs the `.so`
  files into `libs/<abi>/`. Stages: `tudo` (everything), `terceiros` (third-party libs only), `cola` (our glue only).
  `ABIS="armeabi-v7a arm64-v8a"` picks the ABIs; without an NDK it can build for the host (used by `teste/`).
  The script stops if FFmpeg's `config.h` doesn't say LGPL 2.1: no GPL or nonfree part is ever enabled.
- `cola/`: the JNI glue (`libonetvplayer.so`), called from `FfNativo.kt`.
- `libs/`: the prebuilt `.so` files, committed so the app builds without an NDK (Gradle adds them as jniLibs).
- `licencas/`: license texts and exact versions; the in-app list is `app/src/main/assets/licencas.txt`.
- `teste/rodar.sh`: host tests of the glue against sample files (not in the repo).

LGPL compliance: FFmpeg and FriBidi are LGPL-2.1-or-later and ship as separate shared libraries, so a user can
replace them. Keep it that way (no static linking of LGPL code into the glue), and keep this script able to rebuild
the exact versions shipped.
