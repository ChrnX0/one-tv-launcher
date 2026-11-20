#!/usr/bin/env python3
"""Matriz "toca tudo" (elevação 6.1, 17/11/2026).

Gera cerca de 60 amostras de vídeo, áudio, contêiner e legenda, confere cada uma com o ffprobe, e (opcional) manda o
APARELHO DE TESTE tocar todas e escreve o resultado: "toca X de 60 neste aparelho".

Licença das amostras: nenhuma imagem ou som de terceiros. Tudo nasce dos geradores sintéticos do próprio ffmpeg
(testsrc2, smptehdbars, sine, anoisesrc) e de legendas escritas aqui; o conteúdo é nosso e vai como CC0 (domínio
público). Ver docs/amostras.md. As marcas de codec (Dolby, DTS) só nomeiam o formato.

Uso:
  python3 scripts/amostras.py --gerar --pasta amostras             (gera e confere; precisa do ffmpeg com libx264,
                                                                    libx265, libvpx, libsvtav1, libopus, libtheora)
  python3 scripts/amostras.py --servir --pasta amostras --porta 8000  (serve a pasta na rede da casa)
  python3 scripts/amostras.py --rodar --aparelho 192.168.x.y:5555 --base http://<este PC>:8000/ --pasta amostras
Trava de segurança: o launcher só roda a matriz num aparelho marcado como bancada (EXTRAS bancada_marcar). Nunca a Sala.
Só biblioteca padrão do Python 3.8+.
"""
import argparse
import json
import os
import subprocess
import sys
import time

LICENCA = "CC0 (gerada pelo ffmpeg a partir de fontes sintéticas; sem conteúdo de terceiros)"
SEG = 8          # duração padrão (s)
SEG_4K = 4       # 4K demora para codificar: menos segundos

# Fontes sintéticas (lavfi). Cada amostra escolhe tamanho e quadros por segundo.
def video(w, h, fps, seg=SEG, barras=False):
    src = "smptehdbars" if barras else "testsrc2"
    return ["-f", "lavfi", "-i", f"{src}=size={w}x{h}:rate={fps}:duration={seg}"]

def audio(canais=2, taxa=48000, seg=SEG):
    lay = {1: "mono", 2: "stereo", 6: "5.1", 8: "7.1"}[canais]
    # Um tom diferente por canal ajuda a ouvir a troca de canais na bancada.
    return ["-f", "lavfi", "-i", f"sine=frequency=440:sample_rate={taxa}:duration={seg}," +
            f"aformat=channel_layouts={lay}"]

SRT_PT = "1\n00:00:01,000 --> 00:00:04,000\nLegenda de teste em português: ação, coração, pão.\n\n2\n00:00:05,000 --> 00:00:07,500\nSegunda linha.\n"
SRT_EN = "1\n00:00:01,000 --> 00:00:04,000\nEnglish test subtitle.\n\n2\n00:00:05,000 --> 00:00:07,500\nSecond line.\n"
SRT_SDH = "1\n00:00:01,000 --> 00:00:04,000\n[porta bate] Quem está aí?\n"
ASS = """[Script Info]
ScriptType: v4.00+
PlayResX: 1280
PlayResY: 720

[V4+ Styles]
Format: Name, Fontname, Fontsize, PrimaryColour, SecondaryColour, OutlineColour, BackColour, Bold, Italic, Underline, StrikeOut, ScaleX, ScaleY, Spacing, Angle, BorderStyle, Outline, Shadow, Alignment, MarginL, MarginR, MarginV, Encoding
Style: Default,Arial,48,&H0000FFFF,&H000000FF,&H00000000,&H64000000,-1,0,0,0,100,100,0,0,1,3,1,8,20,20,40,1

[Events]
Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text
Dialogue: 0,0:00:01.00,0:00:06.00,Default,,0,0,0,,{\\an8}Legenda ASS no topo, amarela
"""

# Matriz: (id, arquivo, categoria, descrição, argumentos do ffmpeg depois das entradas, entradas, extras)
# categoria: video | audio | conteiner | legenda | stream | hdr
def matriz():
    m = []
    def add(id_, arquivo, cat, desc, entradas, args, legenda_externa=None, tem_video=True, faixas_texto=0):
        m.append(dict(id=id_, arquivo=arquivo, categoria=cat, descricao=desc, entradas=entradas, args=args,
                      legenda_externa=legenda_externa, video=tem_video, legendas=faixas_texto, licenca=LICENCA))
    v1080 = video(1920, 1080, 30); v720 = video(1280, 720, 30); a2 = audio(2); a6 = audio(6)
    h264 = ["-c:v", "libx264", "-preset", "veryfast", "-pix_fmt", "yuv420p"]
    h265 = ["-c:v", "libx265", "-preset", "ultrafast", "-x265-params", "log-level=error", "-tag:v", "hvc1"]
    aac = ["-c:a", "aac", "-b:a", "160k"]
    # --- vídeo (codec e perfil)
    add("v01", "v01_h264_high_1080p_aac.mp4", "video", "H.264 High 1080p30 + AAC estéreo (MP4)", v1080 + a2, h264 + ["-profile:v", "high"] + aac)
    add("v02", "v02_h264_baseline_480p.mp4", "video", "H.264 Baseline 480p", video(854, 480, 30) + a2, h264 + ["-profile:v", "baseline"] + aac)
    add("v03", "v03_h264_main_720p60.mp4", "video", "H.264 Main 720p60", video(1280, 720, 60) + a2, h264 + ["-profile:v", "main"] + aac)
    add("v04", "v04_h264_high10_1080p.mkv", "video", "H.264 High 10 bits (raro em chip de TV)", v1080 + a2,
        ["-c:v", "libx264", "-preset", "veryfast", "-pix_fmt", "yuv420p10le", "-profile:v", "high10"] + aac)
    add("v05", "v05_h264_1080i_entrelacado.mkv", "video", "H.264 1080i entrelaçado", video(1920, 1080, 30) + a2,
        h264 + ["-flags", "+ilme+ildct", "-x264opts", "tff=1"] + aac)
    add("v06", "v06_h264_23976.mkv", "video", "H.264 1080p 23,976 qps (troca de taxa)", video(1920, 1080, "24000/1001") + a2, h264 + aac)
    add("v07", "v07_h264_50fps.mkv", "video", "H.264 1080p50 (padrão europeu)", video(1920, 1080, 50) + a2, h264 + aac)
    add("v08", "v08_h264_21x9.mkv", "video", "H.264 2560x1080 (21:9)", video(2560, 1080, 24) + a2, h264 + aac)
    add("v09", "v09_hevc_main_1080p.mp4", "video", "HEVC Main 1080p + AAC (MP4)", v1080 + a2, h265 + aac)
    add("v10", "v10_hevc_main10_1080p.mkv", "video", "HEVC Main 10 1080p", v1080 + a2, h265 + ["-pix_fmt", "yuv420p10le"] + aac)
    add("v11", "v11_hevc_2160p60_main10.mp4", "video", "HEVC Main 10 2160p60", video(3840, 2160, 60, SEG_4K) + audio(2, seg=SEG_4K),
        h265 + ["-pix_fmt", "yuv420p10le"] + aac)
    add("v12", "v12_hevc_720p_opus.mkv", "video", "HEVC 8 bits 720p + Opus", v720 + a2, h265 + ["-c:a", "libopus", "-b:a", "128k"])
    add("v13", "v13_vp9_1080p_opus.webm", "video", "VP9 1080p + Opus (WebM)", v1080 + a2,
        ["-c:v", "libvpx-vp9", "-deadline", "realtime", "-cpu-used", "8", "-b:v", "2M", "-c:a", "libopus"])
    add("v14", "v14_vp9_vorbis.mkv", "video", "VP9 720p + Vorbis (MKV)", v720 + a2,
        ["-c:v", "libvpx-vp9", "-deadline", "realtime", "-cpu-used", "8", "-b:v", "1M", "-c:a", "libvorbis"])
    add("v15", "v15_vp8_vorbis.webm", "video", "VP8 720p + Vorbis", v720 + a2,
        ["-c:v", "libvpx", "-deadline", "realtime", "-cpu-used", "8", "-b:v", "1M", "-c:a", "libvorbis"])
    add("v16", "v16_av1_1080p_aac.mp4", "video", "AV1 1080p + AAC (MP4)", v1080 + a2,
        ["-c:v", "libsvtav1", "-preset", "12", "-crf", "40"] + aac)
    add("v17", "v17_av1_opus.webm", "video", "AV1 720p + Opus (WebM)", v720 + a2,
        ["-c:v", "libsvtav1", "-preset", "12", "-crf", "40", "-c:a", "libopus"])
    add("v18", "v18_mpeg2_ts.ts", "video", "MPEG-2 1080i + AC-3 (TV aberta, TS)", video(1920, 1080, 30) + a2,
        ["-c:v", "mpeg2video", "-b:v", "8M", "-flags", "+ilme+ildct", "-c:a", "ac3", "-b:a", "192k"])
    add("v19", "v19_mpeg4_xvid.avi", "video", "MPEG-4 Part 2 (Xvid) + MP3 (AVI)", video(720, 400, 25) + a2,
        ["-c:v", "libxvid", "-q:v", "4", "-c:a", "libmp3lame", "-b:a", "128k"])
    add("v20", "v20_theora.ogv", "video", "Theora + Vorbis (OGG)", video(854, 480, 30) + a2, ["-c:v", "libtheora", "-q:v", "6", "-c:a", "libvorbis"])
    add("v21", "v21_wmv2.wmv", "video", "WMV 8 + WMA 2 (ASF)", video(854, 480, 30) + a2, ["-c:v", "wmv2", "-b:v", "1M", "-c:a", "wmav2"])
    # --- áudio dentro de vídeo (o que mais falha em TV: Dolby/DTS sem passagem)
    add("a01", "a01_ac3_51.mkv", "audio", "AC-3 (Dolby Digital) 5.1", v720 + a6, h264 + ["-c:a", "ac3", "-b:a", "448k"])
    add("a02", "a02_eac3_51.mkv", "audio", "E-AC-3 (Dolby Digital Plus) 5.1", v720 + a6, h264 + ["-c:a", "eac3", "-b:a", "640k"])
    add("a03", "a03_dts_51.mkv", "audio", "DTS 5.1", v720 + a6, h264 + ["-c:a", "dca", "-strict", "-2", "-b:a", "768k"])
    add("a04", "a04_truehd_51.mkv", "audio", "Dolby TrueHD 5.1", v720 + a6, h264 + ["-c:a", "truehd", "-strict", "-2"])
    add("a05", "a05_flac.mkv", "audio", "FLAC estéreo", v720 + a2, h264 + ["-c:a", "flac"])
    add("a06", "a06_opus_51.mkv", "audio", "Opus 5.1", v720 + a6, h264 + ["-c:a", "libopus", "-b:a", "256k", "-mapping_family", "1"])
    add("a07", "a07_vorbis.mkv", "audio", "Vorbis estéreo", v720 + a2, h264 + ["-c:a", "libvorbis"])
    add("a08", "a08_mp3.mkv", "audio", "MP3 estéreo", v720 + a2, h264 + ["-c:a", "libmp3lame", "-b:a", "192k"])
    add("a09", "a09_aac_51.mp4", "audio", "AAC 5.1", v720 + a6, h264 + ["-c:a", "aac", "-b:a", "384k"])
    add("a10", "a10_aac_71.mkv", "audio", "AAC 7.1", v720 + audio(8), h264 + ["-c:a", "aac", "-b:a", "512k"])
    add("a11", "a11_pcm.mkv", "audio", "PCM 16 bits", v720 + a2, h264 + ["-c:a", "pcm_s16le"])
    add("a12", "a12_mp2.ts", "audio", "MP2 (rádio/TV antiga, TS)", v720 + a2, h264 + ["-c:a", "mp2", "-b:a", "192k"])
    add("a13", "a13_duas_faixas.mkv", "audio", "Duas faixas de áudio (pt e en)", v720 + a2 + audio(2),
        h264 + ["-map", "0:v", "-map", "1:a", "-map", "2:a", "-c:a", "aac", "-metadata:s:a:0", "language=por",
                "-metadata:s:a:1", "language=eng"])
    add("a14", "a14_audiodescricao.mkv", "audio", "Faixa de audiodescrição marcada (visual_impaired)", v720 + a2 + audio(2),
        h264 + ["-map", "0:v", "-map", "1:a", "-map", "2:a", "-c:a", "aac", "-metadata:s:a:0", "language=por",
                "-metadata:s:a:1", "language=por", "-disposition:a:1", "visual_impaired+descriptions"])
    # --- só áudio
    add("s01", "s01_musica.mp3", "audio", "Arquivo MP3", audio(2), ["-c:a", "libmp3lame", "-b:a", "192k"], tem_video=False)
    add("s02", "s02_hires.flac", "audio", "FLAC 24 bits 96 kHz", audio(2, 96000), ["-c:a", "flac", "-sample_fmt", "s32"], tem_video=False)
    add("s03", "s03_aac.m4a", "audio", "AAC em M4A", audio(2), aac, tem_video=False)
    add("s04", "s04_opus.opus", "audio", "Opus em OGG", audio(2), ["-c:a", "libopus"], tem_video=False)
    add("s05", "s05_pcm.wav", "audio", "WAV PCM", audio(2), ["-c:a", "pcm_s16le"], tem_video=False)
    # --- contêiner
    add("c01", "c01_h264_aac.ts", "conteiner", "MPEG-TS H.264 + AAC (como as listas IPTV)", v720 + a2, h264 + aac)
    add("c02", "c02_hevc_aac.ts", "conteiner", "MPEG-TS HEVC + AAC", v720 + a2, h265 + aac)
    add("c03", "c03_bluray.m2ts", "conteiner", "M2TS (Blu-ray) H.264 + AC-3", v1080 + a2, h264 + ["-c:a", "ac3", "-mpegts_m2ts_mode", "1"])
    add("c04", "c04_mov.mov", "conteiner", "QuickTime MOV", v720 + a2, h264 + aac)
    add("c05", "c05_flv.flv", "conteiner", "FLV", v720 + a2, h264 + aac)
    add("c06", "c06_3gp.3gp", "conteiner", "3GP (celular antigo)", video(352, 288, 15) + audio(1, 16000), h264 + ["-profile:v", "baseline", "-c:a", "aac", "-b:a", "48k"])
    add("c07", "c07_mpg_ps.mpg", "conteiner", "MPEG-PS (DVD) MPEG-2 + MP2", video(720, 480, 30) + a2, ["-c:v", "mpeg2video", "-b:v", "4M", "-c:a", "mp2", "-f", "vob"])
    add("c08", "c08_moov_no_fim.mp4", "conteiner", "MP4 com o índice no fim (sem faststart): pular no meio pela rede", v1080 + a2, h264 + aac)
    add("c09", "c09_faststart.mp4", "conteiner", "MP4 com faststart", v1080 + a2, h264 + aac + ["-movflags", "+faststart"])
    # --- legenda
    add("l01", "l01_srt_embutida.mkv", "legenda", "SRT dentro do MKV (UTF-8, acentos)", v720 + a2 + ["-i", "{pt.srt}"],
        h264 + aac + ["-map", "0:v", "-map", "1:a", "-map", "2", "-c:s", "srt", "-metadata:s:s:0", "language=por"], faixas_texto=1)
    add("l02", "l02_ass.mkv", "legenda", "ASS com estilo (amarela, no topo)", v720 + a2 + ["-i", "{teste.ass}"],
        h264 + aac + ["-map", "0:v", "-map", "1:a", "-map", "2", "-c:s", "ass"], faixas_texto=1)
    add("l04", "l04_mov_text.mp4", "legenda", "mov_text dentro do MP4", v720 + a2 + ["-i", "{pt.srt}"],
        h264 + aac + ["-map", "0:v", "-map", "1:a", "-map", "2", "-c:s", "mov_text", "-metadata:s:s:0", "language=por"], faixas_texto=1)
    add("l05", "l05_webvtt.mkv", "legenda", "WebVTT dentro do MKV", v720 + a2 + ["-i", "{pt.srt}"],
        h264 + aac + ["-map", "0:v", "-map", "1:a", "-map", "2", "-c:s", "webvtt"], faixas_texto=1)
    add("l06", "l06_externa.mp4", "legenda", "Legenda externa .srt ao lado do arquivo", v720 + a2, h264 + aac, legenda_externa="l06_externa.srt", faixas_texto=1)
    add("l07", "l07_externa_latin1.mp4", "legenda", "Legenda externa em Latin-1 (acentos de PC antigo)", v720 + a2, h264 + aac,
        legenda_externa="l07_externa_latin1.srt", faixas_texto=1)
    add("l08", "l08_duas_legendas.mkv", "legenda", "Duas legendas (pt e en) e duas faixas de áudio", v720 + a2 + audio(2) + ["-i", "{pt.srt}", "-i", "{en.srt}"],
        h264 + aac + ["-map", "0:v", "-map", "1:a", "-map", "2:a", "-map", "3", "-map", "4", "-c:s", "srt",
                      "-metadata:s:a:0", "language=por", "-metadata:s:a:1", "language=eng",
                      "-metadata:s:s:0", "language=por", "-metadata:s:s:1", "language=eng"], faixas_texto=2)
    add("l09", "l09_sdh.mkv", "legenda", "Legenda para surdos marcada (hearing_impaired)", v720 + a2 + ["-i", "{sdh.srt}"],
        h264 + aac + ["-map", "0:v", "-map", "1:a", "-map", "2", "-c:s", "srt", "-metadata:s:s:0", "language=por",
                      "-disposition:s:0", "hearing_impaired"], faixas_texto=1)
    add("l10", "l10_forcada.mkv", "legenda", "Legenda forçada (só falas em outra língua)", v720 + a2 + ["-i", "{pt.srt}"],
        h264 + aac + ["-map", "0:v", "-map", "1:a", "-map", "2", "-c:s", "srt", "-metadata:s:s:0", "language=por",
                      "-disposition:s:0", "forced"], faixas_texto=1)
    # --- HDR (metadados no fluxo; a TV mostra o selo e escolhe o modo)
    hdr10 = ["-x265-params", "log-level=error:hdr10=1:repeat-headers=1:colorprim=bt2020:transfer=smpte2084:colormatrix=bt2020nc:"
             "master-display=G(13250,34500)B(7500,3000)R(34000,16000)WP(15635,16450)L(10000000,1):max-cll=1000,400"]
    add("h01", "h01_hevc_hdr10_2160p.mkv", "hdr", "HEVC HDR10 2160p + E-AC-3", video(3840, 2160, 24, SEG_4K, barras=True) + audio(6, seg=SEG_4K),
        ["-c:v", "libx265", "-preset", "ultrafast", "-pix_fmt", "yuv420p10le"] + hdr10 + ["-c:a", "eac3"])
    add("h02", "h02_hevc_hlg_1080p.mkv", "hdr", "HEVC HLG 1080p", video(1920, 1080, 30, barras=True) + a2,
        ["-c:v", "libx265", "-preset", "ultrafast", "-pix_fmt", "yuv420p10le", "-x265-params",
         "log-level=error:colorprim=bt2020:transfer=arib-std-b67:colormatrix=bt2020nc"] + aac)
    add("h03", "h03_vp9_hdr10.webm", "hdr", "VP9 Profile 2 HDR10 2160p", video(3840, 2160, 24, SEG_4K, barras=True) + audio(2, seg=SEG_4K),
        ["-c:v", "libvpx-vp9", "-deadline", "realtime", "-cpu-used", "8", "-b:v", "8M", "-pix_fmt", "yuv420p10le", "-profile:v", "2",
         "-color_primaries", "bt2020", "-color_trc", "smpte2084", "-colorspace", "bt2020nc", "-c:a", "libopus"])
    add("h04", "h04_av1_hdr10.mkv", "hdr", "AV1 10 bits HDR10 2160p", video(3840, 2160, 24, SEG_4K, barras=True) + audio(2, seg=SEG_4K),
        ["-c:v", "libsvtav1", "-preset", "12", "-crf", "45", "-pix_fmt", "yuv420p10le", "-color_primaries", "bt2020",
         "-color_trc", "smpte2084", "-colorspace", "bt2020nc", "-c:a", "libopus"])
    # --- transmissão (HLS e DASH, como os serviços e as listas)
    add("t01", "t01_hls/mestre.m3u8", "stream", "HLS com segmentos TS (H.264 + AAC)", v720 + a2,
        h264 + aac + ["-g", "48", "-f", "hls", "-hls_time", "2", "-hls_playlist_type", "vod", "-hls_segment_filename", "{dir}/seg%03d.ts"])
    add("t02", "t02_hls_fmp4/mestre.m3u8", "stream", "HLS com fMP4 (HEVC + AAC)", v720 + a2,
        h265 + aac + ["-g", "48", "-f", "hls", "-hls_time", "2", "-hls_playlist_type", "vod", "-hls_segment_type", "fmp4",
                      "-hls_segment_filename", "{dir}/seg%03d.m4s"])
    add("t03", "t03_dash/manifesto.mpd", "stream", "DASH (H.264 + AAC)", v720 + a2, h264 + aac + ["-g", "48", "-f", "dash", "-seg_duration", "2"])
    add("t04", "t04_dash_vp9/manifesto.mpd", "stream", "DASH WebM (VP9 + Opus)", v720 + a2,
        ["-c:v", "libvpx-vp9", "-deadline", "realtime", "-cpu-used", "8", "-b:v", "1M", "-c:a", "libopus", "-g", "48", "-f", "dash",
         "-seg_duration", "2", "-dash_segment_type", "webm"])
    return m


def legendas(pasta):
    """Arquivos de legenda usados como entrada e as externas (ao lado do vídeo)."""
    os.makedirs(pasta, exist_ok=True)
    fontes = {"pt.srt": SRT_PT, "en.srt": SRT_EN, "sdh.srt": SRT_SDH, "teste.ass": ASS}
    caminhos = {}
    for nome, texto in fontes.items():
        p = os.path.join(pasta, "_fontes", nome)
        os.makedirs(os.path.dirname(p), exist_ok=True)
        with open(p, "w", encoding="utf-8") as f:
            f.write(texto)
        caminhos[nome] = p
    with open(os.path.join(pasta, "l06_externa.srt"), "w", encoding="utf-8") as f:
        f.write(SRT_PT)
    with open(os.path.join(pasta, "l07_externa_latin1.srt"), "w", encoding="latin-1") as f:
        f.write(SRT_PT)
    return caminhos


def comando(a, pasta, fontes):
    """A linha do ffmpeg de uma amostra (pura, testada em test_amostras.py)."""
    saida = os.path.join(pasta, a["arquivo"])
    d = os.path.dirname(saida)
    troca = lambda s: s.replace("{dir}", d).replace("{pt.srt}", fontes.get("pt.srt", "pt.srt")).replace(
        "{en.srt}", fontes.get("en.srt", "en.srt")).replace("{sdh.srt}", fontes.get("sdh.srt", "sdh.srt")).replace(
        "{teste.ass}", fontes.get("teste.ass", "teste.ass"))
    return ["ffmpeg", "-hide_banner", "-loglevel", "error", "-y"] + [troca(x) for x in a["entradas"]] + \
        [troca(x) for x in a["args"]] + ["-t", str(SEG)] + [saida]


def conferir(a, pasta):
    """ffprobe: o arquivo abre e tem os fluxos esperados. Devolve (ok, motivo)."""
    p = os.path.join(pasta, a["arquivo"])
    if not os.path.exists(p):
        return False, "não gerado"
    # Da pasta do arquivo: o ffprobe acha os pedaços de HLS/DASH pelo caminho relativo à pasta atual.
    r = subprocess.run(["ffprobe", "-v", "error", "-show_entries", "stream=codec_type,codec_name", "-of", "json", os.path.basename(p)],
                       capture_output=True, text=True, cwd=os.path.dirname(os.path.abspath(p)))
    if r.returncode != 0:
        return False, r.stderr.strip()[:200]
    tipos = [s.get("codec_type") for s in json.loads(r.stdout).get("streams", [])]
    if a["video"] and "video" not in tipos:
        return False, "sem vídeo"
    if "audio" not in tipos:
        return False, "sem áudio"
    if a["legendas"] and not a["legenda_externa"] and tipos.count("subtitle") < a["legendas"]:
        return False, "faltou legenda"
    return True, ",".join(s.get("codec_name", "?") for s in json.loads(r.stdout)["streams"])


def publicar_matriz(lista, pasta):
    """matriz.json: o que o launcher lê para tocar (sem as linhas do ffmpeg)."""
    campos = ["id", "arquivo", "categoria", "descricao", "video", "legendas", "legenda_externa", "licenca"]
    with open(os.path.join(pasta, "matriz.json"), "w", encoding="utf-8") as f:
        json.dump({"versao": 1, "amostras": [{k: a[k] for k in campos} for a in lista]}, f, ensure_ascii=False, indent=1)


def gerar(pasta):
    lista = matriz()
    fontes = legendas(pasta)
    falhas = []
    for a in lista:
        os.makedirs(os.path.dirname(os.path.join(pasta, a["arquivo"])), exist_ok=True)
        r = subprocess.run(comando(a, pasta, fontes), capture_output=True, text=True)
        ok, motivo = conferir(a, pasta) if r.returncode == 0 else (False, r.stderr.strip()[:300])
        print(("ok    " if ok else "FALHOU") + f" {a['id']} {a['arquivo']}: {motivo}")
        if not ok:
            falhas.append(a["id"])
    publicar_matriz(lista, pasta)
    print(f"{len(lista) - len(falhas)} de {len(lista)} amostras geradas e conferidas")
    return 0 if not falhas else 1


def adb(aparelho, *args):
    return subprocess.run(["adb", "-s", aparelho] + list(args), capture_output=True, text=True).stdout


def extras(aparelho, pacote, *pares):
    return adb(aparelho, "shell", "am", "broadcast", "-n", f"{pacote}/.ControleShizukuAdbReceiver",
               "-a", f"{pacote}.EXTRAS", "--es", "o", *pares)


def rodar(aparelho, base, pasta, pacote):
    if "sim" not in extras(aparelho, pacote, "bancada"):
        print("Aparelho não marcado como bancada: nada a fazer (nunca rode na Sala).")
        return 2
    print(extras(aparelho, pacote, "matriz", "--es", "base", base))
    for _ in range(240):       # até 20 min
        time.sleep(5)
        r = extras(aparelho, pacote, "matriz_resultado")
        if "toca " in r and "rodando" not in r:
            with open(os.path.join(pasta, "resultado.txt"), "w", encoding="utf-8") as f:
                f.write(r)
            print(r)
            return 0
    print("A matriz não terminou em 20 min.")
    return 1


def servir(pasta, porta):
    import http.server
    import functools
    h = functools.partial(http.server.SimpleHTTPRequestHandler, directory=pasta)
    print(f"Servindo {pasta} em http://0.0.0.0:{porta}/ (Ctrl+C para parar)")
    http.server.ThreadingHTTPServer(("0.0.0.0", porta), h).serve_forever()


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("--gerar", action="store_true")
    ap.add_argument("--servir", action="store_true")
    ap.add_argument("--rodar", action="store_true")
    ap.add_argument("--pasta", default="amostras")
    ap.add_argument("--porta", type=int, default=8000)
    ap.add_argument("--aparelho")
    ap.add_argument("--base")
    ap.add_argument("--pacote", default="tv.sofaly.launcher")
    o = ap.parse_args()
    if o.gerar:
        sys.exit(gerar(o.pasta))
    if o.servir:
        servir(o.pasta, o.porta)
    if o.rodar:
        if not o.aparelho or not o.base:
            sys.exit("--rodar precisa de --aparelho e --base")
        sys.exit(rodar(o.aparelho, o.base, o.pasta, o.pacote))
    ap.print_help()
