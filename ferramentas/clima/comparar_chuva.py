"""Compara a chance de chuva por hora da WeatherAPI (campos do plano Free) com a do Open-Meteo.

Teste de escolha de fornecedor (16/11/2026), não faz parte do app. Roda no GitHub Actions (workflow
"Comparar chuva"), que passa a chave WEATHERAPI_CHAVE como variável de ambiente; a chave nunca é impressa.

Modos:
  prever     -> baixa as próximas 48 h das duas fontes e imprime um CSV (hora local) + resumo.
  verificar  -> lê o CSV salvo (ferramentas/clima/previsao.csv) e compara com a chuva que caiu,
                tirada da reanálise ERA5 (arquivo do Open-Meteo, fonte independente dos dois modelos).
"""
import csv, io, json, os, sys, urllib.error, urllib.parse, urllib.request
from datetime import datetime, timedelta, timezone

CIDADES = {  # coordenadas públicas do centro de cada capital
    "Sao Paulo": (-23.55, -46.63), "Rio de Janeiro": (-22.91, -43.17), "Belo Horizonte": (-19.92, -43.94),
    "Manaus": (-3.12, -60.02), "Porto Alegre": (-30.03, -51.23),
}

def pegar(url):
    req = urllib.request.Request(url, headers={"User-Agent": "Sofaly-teste-clima/1.0"})
    try:
        with urllib.request.urlopen(req, timeout=30) as r:
            return json.load(r)
    except urllib.error.HTTPError as e:
        # A mensagem de erro da API não repete a chave; ajuda a saber se é chave errada ou ainda não ativa.
        print(f"HTTP {e.code} em {url.split('?')[0]}: {e.read()[:300].decode(errors='replace')}")
        raise

def weatherapi(lat, lon, chave):
    # Só campos do Free: forecast 3 dias, hour[].chance_of_rain e precip_mm.
    q = urllib.parse.urlencode({"key": chave, "q": f"{lat},{lon}", "days": 3, "aqi": "no", "alerts": "no"})
    d = pegar("https://api.weatherapi.com/v1/forecast.json?" + q)
    return {h["time"].replace(" ", "T"): (h["chance_of_rain"], h["precip_mm"])
            for dia in d["forecast"]["forecastday"] for h in dia["hour"]}

def openmeteo(lat, lon):
    q = urllib.parse.urlencode({"latitude": lat, "longitude": lon, "hourly": "precipitation_probability,precipitation",
                                "timezone": "auto", "forecast_days": 3})
    r = pegar("https://api.open-meteo.com/v1/forecast?" + q)
    d = r["hourly"]
    return {t: (p, mm) for t, p, mm in zip(d["time"], d["precipitation_probability"], d["precipitation"])}, r["utc_offset_seconds"]

def prever():
    chave = os.environ["WEATHERAPI_CHAVE"].strip().strip('"').strip("'")
    print(f"chave: {len(chave)} caracteres, so letras/numeros: {chave.isalnum()}")
    linhas = []
    for nome, (lat, lon) in CIDADES.items():
        wa = weatherapi(lat, lon, chave)
        om, off = openmeteo(lat, lon)
        agora = (datetime.now(timezone.utc).replace(tzinfo=None) + timedelta(seconds=off)).replace(minute=0, second=0, microsecond=0)
        for i in range(1, 49):
            t = (agora + timedelta(hours=i)).strftime("%Y-%m-%dT%H:%M")
            if t in wa and t in om and om[t][0] is not None:
                linhas.append([nome, t, wa[t][0], wa[t][1], om[t][0], om[t][1]])
    out = io.StringIO()
    w = csv.writer(out); w.writerow(["cidade", "hora_local", "wa_chance", "wa_mm", "om_chance", "om_mm"]); w.writerows(linhas)
    print("=====CSV=====\n" + out.getvalue() + "=====FIM=====")
    resumo(linhas)

def resumo(linhas):
    for nome in CIDADES:
        ls = [l for l in linhas if l[0] == nome]
        if not ls: continue
        dif = sum(abs(l[2] - l[4]) for l in ls) / len(ls)
        disc = sum(1 for l in ls if (l[2] >= 50) != (l[4] >= 50))
        print(f"{nome}: {len(ls)} h | media WA {sum(l[2] for l in ls)/len(ls):.0f}% | media OM {sum(l[4] for l in ls)/len(ls):.0f}% "
              f"| diferenca media {dif:.0f} pontos | horas em que so um diz >=50%: {disc}")

def verificar():
    # Chuva observada: ERA5 pelo arquivo do Open-Meteo (atraso de ~5 dias).
    linhas = list(csv.DictReader(open("ferramentas/clima/previsao.csv")))
    bs = {"wa": [], "om": []}
    for nome, (lat, lon) in CIDADES.items():
        ls = [l for l in linhas if l["cidade"] == nome]
        if not ls: continue
        ini, fim = ls[0]["hora_local"][:10], ls[-1]["hora_local"][:10]
        q = urllib.parse.urlencode({"latitude": lat, "longitude": lon, "hourly": "precipitation", "timezone": "auto",
                                    "start_date": ini, "end_date": fim, "models": "era5"})
        d = pegar("https://archive-api.open-meteo.com/v1/archive?" + q)["hourly"]
        obs = dict(zip(d["time"], d["precipitation"]))
        b = {"wa": [], "om": []}
        for l in ls:
            o = obs.get(l["hora_local"])
            if o is None: continue
            choveu = 1.0 if o >= 0.1 else 0.0
            for k, c in (("wa", "wa_chance"), ("om", "om_chance")):
                b[k].append((float(l[c]) / 100 - choveu) ** 2)
        for k in b: bs[k] += b[k]
        if b["wa"]:
            print(f"{nome}: Brier WA {sum(b['wa'])/len(b['wa']):.3f} | Brier OM {sum(b['om'])/len(b['om'])/1:.3f} "
                  f"| horas com chuva observada {sum(1 for l in ls if (obs.get(l['hora_local']) or 0) >= 0.1)}")
    if bs["wa"]:
        print(f"TOTAL (menor = melhor): Brier WA {sum(bs['wa'])/len(bs['wa']):.3f} | Brier OM {sum(bs['om'])/len(bs['om']):.3f}")

if __name__ == "__main__":
    {"prever": prever, "verificar": verificar}[sys.argv[1] if len(sys.argv) > 1 else "prever"]()
