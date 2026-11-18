# Matriz "toca tudo" (elevação 6.1)

66 amostras curtas (4 a 8 s) que cobrem o que chega a uma TV: H.264 (Baseline a High 10, 1080i, 23,976/50/60 qps,
21:9), HEVC (8 e 10 bits, 4K60), VP8, VP9, AV1, MPEG-2, Xvid, Theora, WMV; AAC até 7.1, AC-3, E-AC-3, DTS, TrueHD,
FLAC, Opus 5.1, Vorbis, MP3, MP2, PCM; MP4, MKV, WebM, TS, M2TS, MOV, FLV, 3GP, MPEG-PS, AVI, OGG, ASF, MP4 com o
índice no fim; SRT, ASS, mov_text, WebVTT, legenda externa (UTF-8 e Latin-1), duas línguas, SDH, forçada, faixa de
audiodescrição; HDR10 (HEVC, VP9, AV1) e HLG; HLS (TS e fMP4) e DASH (MP4 e WebM).

## Licença

Nenhuma imagem, som ou texto de terceiros. Cada amostra nasce dos geradores sintéticos do ffmpeg (`testsrc2`,
`smptehdbars`, `sine`) e das legendas escritas no próprio `scripts/amostras.py`. O conteúdo é nosso e vale como **CC0**
(domínio público): pode ser usado, copiado e publicado sem pedir nada. O teste `scripts/test_amostras.py` confere que
nenhuma entrada vem de fora. Os nomes de codec (Dolby, DTS) só dizem o formato; a TV decodifica pelo chip dela ou pelo
nosso FFmpeg, que já é a base do player.

## Como rodar (só no aparelho de teste, nunca na Sala)

1. No PC: `python3 scripts/amostras.py --gerar --pasta amostras` (precisa do ffmpeg com libx264, libx265, libvpx,
   libsvtav1, libopus, libtheora, libxvid; ~190 MB, ~2 min). Cada amostra é conferida com o ffprobe.
2. `python3 scripts/amostras.py --servir --pasta amostras --porta 8000`.
3. Marcar o aparelho de teste como bancada (uma vez): `EXTRAS --es o bancada_marcar`.
4. `python3 scripts/amostras.py --rodar --aparelho <ip>:5555 --base http://<ip do PC>:8000/ --pasta amostras`.
   O launcher toca uma por vez sem tela e sem som e escreve `toca X de 66 neste aparelho (...)`, o que não tocou e
   por quê, e quais decodificadores usou (chip ou FFmpeg). O texto fica em `amostras/resultado.txt`.

A frase "toca X de 66" é a que vai para a página pública de compatibilidade, por aparelho.
