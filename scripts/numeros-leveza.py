#!/usr/bin/env python3
"""Números de leveza de cada Release (degrau 2, item 7, 16/11/2026).

Uso (no "Build e Release", depois de copiar os APKs para publicar/):
    numeros-leveza.py <apk 32 bits> <apk 64 bits> <versionCode> <saida.json>
Imprime a seção "Lightness" em Markdown (vai para o fim das notas do Release, em inglês como elas) e grava o JSON
(asset leveza.json do Release: o site lê). Memória e tempo da HOME só a TV mede: `EXTRAS --es o leveza` (LevezaTv.kt).
Sem dependências: só a biblioteca padrão (o runner do GitHub tem Python 3).
"""
import json, os, sys, zipfile

def medir(apk):
    z = zipfile.ZipFile(apk)
    parte = {"dex": 0, "nativo": 0, "recursos": 0, "assets": 0}
    for i in z.infolist():
        n = i.filename
        if n.endswith(".dex"): parte["dex"] += i.file_size
        elif n.startswith("lib/"): parte["nativo"] += i.file_size
        elif n.startswith("assets/"): parte["assets"] += i.file_size
        elif n.startswith("res/") or n == "resources.arsc": parte["recursos"] += i.file_size
    return {"bytes": os.path.getsize(apk), "abertoBytes": sum(i.file_size for i in z.infolist()), **{k + "Bytes": v for k, v in parte.items()}}

def mb(b): return f"{b / 1048576:.1f} MB"

def main():
    if len(sys.argv) != 5: sys.exit(__doc__)
    a32, a64, versao, saida = sys.argv[1:]
    m = {"versao": int(versao), "apk32": medir(a32), "apk64": medir(a64)}
    with open(saida, "w") as f: json.dump(m, f, indent=1)
    l = m["apk32"]
    print("\n### Lightness\n")
    print("| | 32-bit | 64-bit |\n|---|---|---|")
    print(f"| Download | {mb(m['apk32']['bytes'])} | {mb(m['apk64']['bytes'])} |")
    print(f"| Installed (uncompressed) | {mb(m['apk32']['abertoBytes'])} | {mb(m['apk64']['abertoBytes'])} |")
    print(f"| Code (DEX) | {mb(l['dexBytes'])} | {mb(m['apk64']['dexBytes'])} |")
    print(f"| Native libraries | {mb(l['nativoBytes'])} | {mb(m['apk64']['nativoBytes'])} |")
    print("\nNo ads, no trackers, no analytics SDK. Memory and HOME time are measured on a real TV and published on the site.")

if __name__ == "__main__":
    main()
