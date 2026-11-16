#!/usr/bin/env python3
"""Teste do Trakt com 20 títulos (16/11/2026, venda na Play, item 1 do roadmap §2).
Pergunta: o Trakt sozinho (conta Standard, sem TMDB) dá o que as fichas do launcher mostram, em português do Brasil?
Para cada título confere: título e sinopse em pt-BR (translations/pt, país br), pôster (extended=images), elenco
(/people), classificação indicativa do Brasil (/releases/br para filme, /certifications para série) e a nota.
Uso: TRAKT_ID=<client id> python3 scripts/teste-trakt.py  (no GitHub: workflow teste-trakt.yml, secret TRAKT_ID).
Só leitura; a chave nunca é impressa."""
import json, os, sys, time, urllib.request, urllib.error

CHAVE = os.environ.get("TRAKT_ID", "").strip()
if not CHAVE:
    sys.exit("falta TRAKT_ID")
API = "https://api.trakt.tv"
# (tipo, slug ou id tmdb, nome para o relatório). Mistura: estrangeiros grandes, brasileiros, séries, infantis, antigos.
TITULOS = [
    ("movie", "tmdb:157336", "Interestelar"), ("movie", "tmdb:603", "Matrix"), ("movie", "tmdb:598", "Cidade de Deus"),
    ("movie", "tmdb:666", "Central do Brasil"), ("movie", "tmdb:7347", "Tropa de Elite"), ("movie", "tmdb:1022789", "Divertida Mente 2"),
    ("movie", "tmdb:157433", "Pet Sematary (2019)"), ("movie", "tmdb:1184918", "Robô Selvagem"), ("movie", "tmdb:129", "A Viagem de Chihiro"),
    ("movie", "tmdb:1000837", "Ainda Estou Aqui"), ("movie", "tmdb:550", "Clube da Luta"),
    ("show", "tmdb:1396", "Breaking Bad"), ("show", "tmdb:1399", "Game of Thrones"), ("show", "tmdb:66732", "Stranger Things"),
    ("show", "tmdb:71446", "La Casa de Papel"), ("show", "tmdb:82856", "The Mandalorian"), ("show", "tmdb:76479", "The Boys"),
    ("show", "tmdb:94997", "A Casa do Dragão"), ("show", "tmdb:63351", "Narcos"), ("show", "tmdb:1416", "Grey's Anatomy"),
]

def get(caminho):
    req = urllib.request.Request(API + caminho, headers={
        "trakt-api-version": "2", "trakt-api-key": CHAVE, "Content-Type": "application/json",
        "User-Agent": "Sofaly-teste/1.0"})
    for tentativa in range(3):
        try:
            with urllib.request.urlopen(req, timeout=20) as r:
                return json.loads(r.read().decode() or "null")
        except urllib.error.HTTPError as e:
            if e.code == 429:
                time.sleep(2 + tentativa * 3); continue
            return {"_erro": e.code}
        except Exception as e:
            return {"_erro": type(e).__name__}
    return {"_erro": 429}

linhas = []
for tipo, ref, nome in TITULOS:
    tmdb = ref.split(":")[1]
    achado = get(f"/search/tmdb/{tmdb}?type={tipo}&extended=full,images")
    if not isinstance(achado, list) or not achado:
        linhas.append({"nome": nome, "erro": f"não achou ({achado if isinstance(achado, dict) else 'vazio'})"}); continue
    item = achado[0][tipo]
    slug = item["ids"]["slug"]; plural = "movies" if tipo == "movie" else "shows"
    tr = get(f"/{plural}/{slug}/translations/pt")
    br = next((t for t in tr if t.get("country") == "br"), None) if isinstance(tr, list) else None
    gente = get(f"/{plural}/{slug}/people")
    elenco = len(gente.get("cast", [])) if isinstance(gente, dict) else 0
    if tipo == "movie":
        rel = get(f"/movies/{slug}/releases/br")
        cert_br = next((r.get("certification") for r in rel if r.get("certification")), "") if isinstance(rel, list) else ""
    else:
        cer = get(f"/shows/{slug}/certifications")
        cert_br = next((c.get("certification") for c in cer if c.get("country") == "br"), "") if isinstance(cer, list) else ""
    imgs = item.get("images") or {}
    poster = (imgs.get("poster") or [None])[0]
    fundo = (imgs.get("fanart") or [None])[0]
    linhas.append({
        "nome": nome, "tipo": tipo, "titulo_br": (br or {}).get("title") or "", "sinopse_br": bool((br or {}).get("overview")),
        "poster": bool(poster), "fundo": bool(fundo), "elenco": elenco, "idade_us": item.get("certification") or "",
        "idade_br": cert_br or "", "nota": item.get("rating"), "votos": item.get("votes"), "ano": item.get("year"),
        "generos": len(item.get("genres") or []), "duracao": item.get("runtime"),
    })
    time.sleep(0.4)   # longe do limite de 1000 GET / 5 min

def sim(v): return "sim" if v else "**não**"
print("| Título | Título pt-BR | Sinopse pt-BR | Pôster | Fundo | Elenco | Idade BR | Idade US | Nota (votos) |")
print("|---|---|---|---|---|---|---|---|---|")
for l in linhas:
    if "erro" in l:
        print(f"| {l['nome']} | {l['erro']} | | | | | | | |"); continue
    print(f"| {l['nome']} ({l['tipo']}, {l['ano']}) | {l['titulo_br'] or '**não**'} | {sim(l['sinopse_br'])} | {sim(l['poster'])} | "
          f"{sim(l['fundo'])} | {l['elenco']} | {l['idade_br'] or '**não**'} | {l['idade_us'] or '—'} | {l['nota']:.1f} ({l['votos']}) |"
          if l['nota'] is not None else f"| {l['nome']} | dados parciais |")
ok = [l for l in linhas if "erro" not in l]
print()
for campo, rot in (("titulo_br", "título pt-BR"), ("sinopse_br", "sinopse pt-BR"), ("poster", "pôster"), ("fundo", "fundo"),
                   ("elenco", "elenco"), ("idade_br", "idade BR")):
    print(f"- {rot}: {sum(1 for l in ok if l[campo])}/{len(TITULOS)}")
json.dump(linhas, open("trakt-resultado.json", "w"), ensure_ascii=False, indent=1)
