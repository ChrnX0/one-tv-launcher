#!/usr/bin/env python3
"""Ajuda por QR (degrau 2, 16/11/2026): copia o manual da TV (res/values*/strings_ajuda.xml) para o site, em
site/ajuda/<idioma>.json, que a página site/help.html mostra no celular. Rode depois de mudar o texto do manual:
    python3 scripts/gerar-ajuda-site.py
O AjudaSiteTest falha se o site ficou para trás do app."""
import json, os, re, sys, xml.etree.ElementTree as ET

RAIZ = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RES = os.path.join(RAIZ, "app/src/main/res")
SAIDA = os.path.join(RAIZ, "site/ajuda")
# pasta values -> código do idioma no site (o mesmo que o launcher põe no link do QR: Locale.toLanguageTag curto)
IDIOMAS = {"values": "en", "values-pt": "pt", "values-pt-rPT": "pt-PT", "values-es": "es", "values-de": "de",
           "values-fr": "fr", "values-it": "it", "values-ru": "ru", "values-pl": "pl", "values-tr": "tr", "values-ja": "ja",
           "values-zh-rCN": "zh", "values-ko": "ko", "values-hi": "hi", "values-in": "id", "values-ar": "ar"}

def limpar(t):
    """O texto como o Android mostra: tira as aspas de fora e os escapes \\n, \\', \\"."""
    t = (t or "").strip()
    if len(t) >= 2 and t[0] == '"' and t[-1] == '"': t = t[1:-1]
    t = re.sub(r"\s*\n\s*", " ", t)   # quebra de linha do XML vira espaço (como no aapt)
    return t.replace("\\n", "\n").replace("\\'", "'").replace('\\"', '"').replace("\\@", "@").replace("\\?", "?")

def main():
    os.makedirs(SAIDA, exist_ok=True)
    for pasta, codigo in IDIOMAS.items():
        arq = os.path.join(RES, pasta, "strings_ajuda.xml")
        if not os.path.exists(arq): sys.exit(f"falta {arq}")
        s = {e.get("name"): limpar("".join(e.itertext())) for e in ET.parse(arq).getroot().iter("string")}
        topicos = {}
        for nome, v in s.items():
            m = re.fullmatch(r"aj_(\w+?)_(titulo|resumo|texto)", nome)
            if m: topicos.setdefault(m.group(1), {})[m.group(2)] = v
        json.dump({"aba": s.get("aj_aba", "Help"), "topicos": topicos}, open(os.path.join(SAIDA, codigo + ".json"), "w"),
                  ensure_ascii=False, indent=1, sort_keys=True)
    print("ok:", len(IDIOMAS), "idiomas em", SAIDA)

main()
