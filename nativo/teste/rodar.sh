#!/usr/bin/env bash
# Testa a cola nativa no computador (Linux): compila as bibliotecas para o host, gera a ponte Java e roda o Teste.
# Uso: nativo/teste/rodar.sh <pasta com as amostras>
# Amostras (públicas, do FFmpeg): https://samples.ffmpeg.org/ V-codecs/MPEG4/pegasus-1958-chiptune.avi,
#   asf-wmv/asf_with_chapters.wmv, A-codecs/AC3/Canyon-5.1-48khz-448kbit.ac3
set -euo pipefail
AQUI=$(cd "$(dirname "$0")" && pwd)
AMOSTRAS=${1:?pasta das amostras}
[[ -f "$AQUI/../obra/host-libs/libonetvplayer.so" ]] || ABIS=host "$AQUI/../compilar.sh"
SAIDA=$(mktemp -d)
javac -d "$SAIDA" "$AQUI"/br/paulo/tvlauncher/*.java "$AQUI/Teste.java"
# fontconfig do computador para o teste da legenda (no aparelho o app escreve um apontando para /system/fonts).
cat > "$SAIDA/fonts.conf" <<EOF
<?xml version="1.0"?><!DOCTYPE fontconfig SYSTEM "fonts.dtd">
<fontconfig><dir>/usr/share/fonts</dir><cachedir>$SAIDA/cache</cachedir></fontconfig>
EOF
java -cp "$SAIDA" Teste "$AMOSTRAS" "$AQUI/../obra/host-libs" "$SAIDA/fonts.conf"
