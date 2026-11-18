#!/usr/bin/env python3
# Gera app/src/main/assets/feriados/feriados.json: os feriados NACIONAIS (categoria pública, sem os estaduais) de cada
# país, de ANO_INICIO a ANO_FIM, a partir da biblioteca python-holidays (MIT, https://github.com/vacanza/holidays).
# Uso: python3 -m venv v && v/bin/pip install holidays && v/bin/python scripts/gerar-feriados.py
# Formato: {"BR":"2026:01010403...;2027:..."} = por ano, as datas MMDD coladas. Rodar de novo uma vez por ano
# (a tabela cobre 15 anos; o FeriadosTv avisa no log quando o ano corrente sai dela).
import json, sys, holidays
ANO_INICIO, ANO_FIM = 2026, 2040
saida = sys.argv[1] if len(sys.argv) > 1 else "app/src/main/assets/feriados/feriados.json"
tabela = {}
for c in sorted(c for c in holidays.list_supported_countries() if len(c) == 2):
    try:
        h = holidays.country_holidays(c, years=list(range(ANO_INICIO, ANO_FIM + 1)))
    except Exception as e:
        print("pulado", c, e, file=sys.stderr); continue
    por = {}
    for d in sorted(h.keys()):
        por.setdefault(d.year, []).append(d.strftime("%m%d"))
    if por:
        tabela[c] = ";".join(f"{a}:{''.join(v)}" for a, v in sorted(por.items()))
open(saida, "w").write(json.dumps(tabela, separators=(",", ":"), sort_keys=True))
print(len(tabela), "países; holidays", holidays.__version__)
