#!/usr/bin/env python3
"""Bancada da madrugada (elevação 2.2, onda 0, 17/11/2026).

Roda, num computador com adb ao lado do APARELHO DE TESTE (nunca na Sala), um roteiro medido:
  1. abrir a tela inicial 10 vezes (am start -W: TotalTime) e os quadros perdidos da HOME (dumpsys gfxinfo);
  2. trocar 20 canais no player (tecla ▲ pelo input; o próprio launcher mede tecla até o primeiro quadro, item 5.1,
     e a bancada lê essa série pelo `EXTRAS --es o placar`);
  3. um filme (opcional: --filme <url de um arquivo de teste>), 60 s tocando, quadros perdidos do player;
  4. memória (PSS) do processo principal e do :player.
Guarda uma linha por noite em <pasta>/serie.jsonl, desenha <pasta>/grafico.html (SVG, sem biblioteca) e compara com
a mediana das últimas 7 noites: piorou mais que o limite, sai com código 3 e escreve PIOROU em <pasta>/alerta.txt
(a conversa do Release lê esse arquivo antes de publicar).

Trava de segurança: só roda num aparelho marcado como bancada pelo próprio launcher
(`am broadcast ... -a br.paulo.tvlauncher.EXTRAS --es o bancada_marcar`). A Sala nunca é marcada.

Uso:  python3 scripts/bancada.py --aparelho 192.168.x.y:5555 --pasta bancada [--filme URL] [--pacote tv.sofaly.launcher]
      python3 scripts/bancada.py --conferir --pasta bancada      (só olha o alerta da última noite)
Só biblioteca padrão do Python 3.8+ (roda no Windows e no Linux).
"""
import argparse
import json
import os
import re
import statistics
import subprocess
import sys
import time

LIMITE_PIORA = 0.15        # 15% pior que a mediana das últimas noites
NOITES_COMPARAR = 7
# Métricas e se "maior é pior" (todas são).
METRICAS = ["home_ms", "home_jank_pct", "canal_ms", "filme_jank_pct", "pss_principal_mb", "pss_player_mb"]


# ---------------------------------------------------------------- leitura (pura, testada em test_bancada.py)

def total_time(saida):
    """TotalTime do `am start -W` (None se não abriu)."""
    if not saida or re.search(r"^\s*Error", saida, re.M):
        return None
    m = re.search(r"TotalTime:\s*(\d+)", saida)
    return int(m.group(1)) if m else None


def jank_pct(gfxinfo):
    """'Janky frames: 12 (3.40%)' -> 3.4 (None se não houver quadros)."""
    m = re.search(r"Janky frames:\s*\d+\s*\(([\d.]+)%\)", gfxinfo or "")
    return float(m.group(1)) if m else None


def pss_mb(meminfo):
    """TOTAL PSS do `dumpsys meminfo <processo>` em MB (Android 14: 'TOTAL PSS:  55123')."""
    s = meminfo or ""
    m = re.search(r"TOTAL PSS:\s*(\d+)", s) or re.search(r"^\s*TOTAL\s+(\d+)", s, re.M)
    return round(int(m.group(1)) / 1024, 1) if m else None


def mediana(xs):
    xs = [x for x in xs if x is not None]
    return statistics.median(xs) if xs else None


def pioras(noite, anteriores, limite=LIMITE_PIORA, n=NOITES_COMPARAR):
    """Lista de (métrica, valor, referência) que pioraram mais que [limite] contra a mediana das [n] noites antes."""
    out = []
    ult = anteriores[-n:]
    for k in METRICAS:
        v = noite.get(k)
        ref = mediana([a.get(k) for a in ult])
        if v is None or ref is None or ref <= 0:
            continue
        if v > ref * (1 + limite):
            out.append((k, v, ref))
    return out


def grafico_html(serie):
    """Uma linha por métrica, SVG à mão (sem biblioteca), cor da marca."""
    partes = ["<!doctype html><meta charset=utf-8><title>Bancada da madrugada</title>",
              "<style>body{font:16px system-ui;background:#141414;color:#f4f1ec;margin:24px}"
              "svg{background:#1e1e1e;border-radius:8px;margin:8px 0 24px}</style><h1>Bancada da madrugada</h1>"]
    for k in METRICAS:
        pts = [(i, n.get(k)) for i, n in enumerate(serie) if n.get(k) is not None]
        partes.append(f"<h2>{k}</h2>")
        if len(pts) < 2:
            partes.append("<p>ainda não medido</p>")
            continue
        lo = min(v for _, v in pts); hi = max(v for _, v in pts)
        span = (hi - lo) or 1
        w, h = 800, 160
        xs = [20 + (w - 40) * i / max(1, len(serie) - 1) for i, _ in pts]
        ys = [h - 20 - (h - 40) * (v - lo) / span for _, v in pts]
        linha = " ".join(f"{x:.1f},{y:.1f}" for x, y in zip(xs, ys))
        partes.append(f'<svg width="{w}" height="{h}"><polyline fill="none" stroke="#FF6A2B" stroke-width="3" points="{linha}"/>'
                      f'<text x="8" y="16" fill="#aaa">{hi}</text><text x="8" y="{h-4}" fill="#aaa">{lo}</text></svg>')
    return "\n".join(partes)


# ---------------------------------------------------------------- adb

class Adb:
    def __init__(self, aparelho):
        self.alvo = ["-s", aparelho]

    def sh(self, cmd, prazo=60):
        r = subprocess.run(["adb", *self.alvo, "shell", cmd], capture_output=True, text=True, timeout=prazo)
        return r.stdout

    def extras(self, pacote, o):
        return self.sh(f"am broadcast -n {pacote}/br.paulo.tvlauncher.ControleShizukuAdbReceiver -a br.paulo.tvlauncher.EXTRAS --es o {o}")


def resultado_broadcast(saida):
    m = re.search(r'data="(.*)"', saida or "", re.S)
    return m.group(1) if m else ""


def rodar(a):
    adb = Adb(a.aparelho)
    subprocess.run(["adb", "connect", a.aparelho], capture_output=True, text=True, timeout=30)
    if resultado_broadcast(adb.extras(a.pacote, "bancada")).strip() != "sim":
        print("RECUSADO: este aparelho não está marcado como bancada (a Sala nunca é).", file=sys.stderr)
        return 2
    # Ninguém assistindo? (o aparelho de teste fica sozinho, mas confere mesmo assim)
    if "mWakefulness=Awake" in adb.sh("dumpsys power") and "PlayerActivity" in adb.sh("dumpsys activity activities | grep topResumedActivity"):
        print("RECUSADO: o player está na tela (alguém assistindo?).", file=sys.stderr)
        return 2
    home = f"{a.pacote}/br.paulo.tvlauncher.MainActivity"
    noite = {"em": int(time.time()), "aparelho": adb.sh("getprop ro.product.model").strip()}

    # 1. HOME 10 vezes
    adb.sh(f"dumpsys gfxinfo {a.pacote} reset")
    tempos = []
    for _ in range(10):
        adb.sh("am start -W -a android.settings.SETTINGS"); time.sleep(2)
        tempos.append(total_time(adb.sh(f"am start -W -n {home}"))); time.sleep(3)
    noite["home_ms"] = mediana(tempos)
    noite["home_jank_pct"] = jank_pct(adb.sh(f"dumpsys gfxinfo {a.pacote}"))
    noite["pss_principal_mb"] = pss_mb(adb.sh(f"dumpsys meminfo {a.pacote}"))

    # 2. 20 canais (o launcher mede e guarda; a bancada lê a mediana das 20 últimas)
    adb.sh(f"am start -n {a.pacote}/br.paulo.tvlauncher.PlayerActivity"); time.sleep(8)
    for _ in range(20):
        adb.sh("input keyevent KEYCODE_DPAD_UP"); time.sleep(5)
    try:
        placar = json.loads(resultado_broadcast(adb.extras(a.pacote, "placar")))
        serie = [p[0] for p in placar.get("serie_canal", [])][-20:]
        noite["canal_ms"] = mediana(serie)
    except ValueError:
        noite["canal_ms"] = None
    noite["pss_player_mb"] = pss_mb(adb.sh(f"dumpsys meminfo {a.pacote}:player"))

    # 3. filme (opcional)
    if a.filme:
        adb.sh(f"dumpsys gfxinfo {a.pacote}:player reset")
        adb.sh(f"am start -a android.intent.action.VIEW -d '{a.filme}' -t video/* -n {a.pacote}/br.paulo.tvlauncher.VodActivity")
        time.sleep(60)
        noite["filme_jank_pct"] = jank_pct(adb.sh(f"dumpsys gfxinfo {a.pacote}:player"))
    adb.sh(f"am start -n {home}")

    os.makedirs(a.pasta, exist_ok=True)
    arq = os.path.join(a.pasta, "serie.jsonl")
    anteriores = ler_serie(arq)
    p = pioras(noite, anteriores)
    with open(arq, "a", encoding="utf-8") as f:
        f.write(json.dumps(noite, ensure_ascii=False) + "\n")
    with open(os.path.join(a.pasta, "grafico.html"), "w", encoding="utf-8") as f:
        f.write(grafico_html(anteriores + [noite]))
    alerta = "PIOROU: " + "; ".join(f"{k} {v} (antes {r})" for k, v, r in p) if p else "ok"
    with open(os.path.join(a.pasta, "alerta.txt"), "w", encoding="utf-8") as f:
        f.write(alerta + "\n")
    print(json.dumps(noite, ensure_ascii=False)); print(alerta)
    return 3 if p else 0


def ler_serie(arq):
    if not os.path.exists(arq):
        return []
    with open(arq, encoding="utf-8") as f:
        return [json.loads(l) for l in f if l.strip()]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--aparelho")
    ap.add_argument("--pasta", default="bancada")
    ap.add_argument("--pacote", default="tv.sofaly.launcher")
    ap.add_argument("--filme")
    ap.add_argument("--conferir", action="store_true")
    a = ap.parse_args()
    if a.conferir:
        t = open(os.path.join(a.pasta, "alerta.txt"), encoding="utf-8").read().strip() if os.path.exists(os.path.join(a.pasta, "alerta.txt")) else "sem medida"
        print(t)
        return 3 if t.startswith("PIOROU") else 0
    if not a.aparelho:
        ap.error("--aparelho é obrigatório (o Chromecast de TESTE, nunca a Sala)")
    return rodar(a)


if __name__ == "__main__":
    sys.exit(main())
