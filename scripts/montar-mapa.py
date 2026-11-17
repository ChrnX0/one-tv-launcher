#!/usr/bin/env python3
"""
Monta o mapa do tema "Céu de verdade" (17/11/2026): app/src/main/assets/mapa/mundo.bin, lido por MapaMundo.kt.
Fonte: Natural Earth 1:50m (domínio público, naturalearthdata.com), em GeoJSON pelo repositório oficial deles no GitHub:
costas (ne_50m_coastline), fronteiras de países (ne_50m_admin_0_boundary_lines_land) e de estados/províncias
(ne_50m_admin_1_states_provinces_lines; nessa escala só os países grandes, entre eles Brasil, EUA, Canadá, Austrália).

Formato (big-endian, para o DataInputStream do Android):
  "MAPA" | versão u8 | nº de linhas u32 | por linha: camada u8 (0 costa, 1 país, 2 estado),
  caixa (lonMin, latMin, lonMax, latMax) em centésimos de grau (s16 x4), nº de pontos u16, primeiro ponto (s16 x2)
  e os demais como diferença do anterior (s8 x2 quando cabe; senão o marcador -128 seguido de s16 x2).
Centésimo de grau = ~1 km: sobra para uma tela de ~1.700 km de largura.

Uso: python3 scripts/montar-mapa.py <pasta-com-os-geojson>
"""
import json, os, struct, sys

PASTA = sys.argv[1]
RAIZ = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SAIDA = os.path.join(RAIZ, "app/src/main/assets/mapa/mundo.bin")
CAMADAS = [("ne_50m_coastline", 0), ("ne_50m_admin_0_boundary_lines_land", 1), ("ne_50m_admin_1_states_provinces_lines", 2)]


def linhas(geo):
    for f in geo["features"]:
        g = f["geometry"]
        if g is None:
            continue
        if g["type"] == "LineString":
            yield g["coordinates"]
        elif g["type"] == "MultiLineString":
            yield from g["coordinates"]


saida = bytearray()
total = 0
for nome, camada in CAMADAS:
    geo = json.load(open(os.path.join(PASTA, nome + ".geojson")))
    for coords in linhas(geo):
        pts = []
        for lon, lat in coords:
            p = (round(lon * 100), round(lat * 100))
            if not pts or pts[-1] != p:
                pts.append(p)
        if len(pts) < 2:
            continue
        for i in range(0, len(pts) - 1, 65000):   # linha enorme vira pedaços (u16 de pontos)
            parte = pts[i:i + 65001]
            lons = [p[0] for p in parte]; lats = [p[1] for p in parte]
            saida += struct.pack(">BhhhhH", camada, min(lons), min(lats), max(lons), max(lats), len(parte))
            saida += struct.pack(">hh", *parte[0])
            for a, b in zip(parte, parte[1:]):
                dx, dy = b[0] - a[0], b[1] - a[1]
                if -127 <= dx <= 127 and -127 <= dy <= 127:
                    saida += struct.pack(">bb", dx, dy)
                else:
                    saida += struct.pack(">bhh", -128, dx, dy)
            total += 1

os.makedirs(os.path.dirname(SAIDA), exist_ok=True)
with open(SAIDA, "wb") as f:
    f.write(b"MAPA" + struct.pack(">BI", 1, total) + saida)
print(SAIDA, total, "linhas", os.path.getsize(SAIDA), "bytes")
