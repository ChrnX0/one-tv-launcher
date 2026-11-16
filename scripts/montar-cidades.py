#!/usr/bin/env python3
# Uso: baixe cities15000.zip, cities1000.zip e admin1CodesASCII.txt de https://download.geonames.org/export/dump/,
# descompacte numa pasta e rode: python3 scripts/montar-cidades.py <pasta> app/src/main/assets/cidades.tsv.gz
# Monta app/src/main/assets/cidades.tsv.gz a partir do GeoNames (CC BY 4.0).
# Linhas: nome \t apelidos(;) \t país \t estado \t lat \t lon \t população \t fuso. Mundo: cidades15000; Brasil: cidades1000.
import sys, gzip, unicodedata, re
geo, saida = sys.argv[1], sys.argv[2]
adm = {}
for l in open(f"{geo}/admin1CodesASCII.txt", encoding="utf-8"):
    c, nome, ascii_, _ = l.rstrip("\n").split("\t")
    adm[c] = nome
def norm(s): return re.sub(r"[^a-z0-9 ]", "", unicodedata.normalize("NFD", s.lower()).encode("ascii", "ignore").decode())
linhas = {}
def ler(arq, filtro):
    for l in open(f"{geo}/{arq}", encoding="utf-8"):
        f = l.rstrip("\n").split("\t")
        gid, nome, ascii_, alt, lat, lon, cc, a1, pop, fuso = f[0], f[1], f[2], f[3], f[4], f[5], f[8], f[10], int(f[14] or 0), f[17]
        if not filtro(cc) or gid in linhas: continue
        apel = []
        if pop >= 300000:
            vistos = {norm(nome)}
            for a in alt.split(","):
                n = norm(a)
                if not n or n in vistos or len(a) > 40: continue
                if not re.fullmatch(r"[A-Za-zÀ-ÿ' .-]+", a): continue   # só alfabeto latino (o teclado da TV)
                vistos.add(n); apel.append(a)
                if len(apel) >= 12: break
        linhas[gid] = (pop, "\t".join([nome, ";".join(apel), cc, adm.get(f"{cc}.{a1}", ""), "%.4f" % float(lat), "%.4f" % float(lon), str(pop), fuso]))
ler("cities15000.txt", lambda cc: True)
ler("cities1000.txt", lambda cc: cc == "BR")
with gzip.open(saida, "wt", encoding="utf-8", compresslevel=9) as g:
    for _, t in sorted(linhas.values(), key=lambda x: -x[0]):
        g.write(t + "\n")
print(len(linhas))
