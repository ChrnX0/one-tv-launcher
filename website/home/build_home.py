"""Home page of sofaly.tv, cozy design (approved 20/11/2026; built from the reviewed preview).

One long page: the hero (the Sofaly TV-sofa logo with the home screen running inside it and a menu that changes
what is on it), the six "made for the whole house" points, the panel on a computer and a phone, the 12 worlds with
EVERY feature of the app, developers, plans, questions and the final call. Called by build.py (page "index").

Texts. The page is assembled with Portuguese text and translated at the end by `translate()`, which swaps whole text
nodes, attribute texts and JavaScript strings (never parts of them) using a table "Portuguese text -> translation":
  - texts that also exist in content/pt.json come from the same place in content/<lang>.json (no double work);
  - the rest live in content/home/<lang>.json. A missing one stops the build (no half-translated page).
  `python3 website/home/build_home.py --keys` lists what still needs a translation in each language.

Images (assets/home/): app screens drawn by the "Telas" workflow (Robolectric, no personal data) and the web panel
drawn by home/render_panel.js with example data. Remake them with home/make_images.py.
"""
import html
import json
import re
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
WEB = HERE.parent
B = None            # build.py module (set by build.py before calling page_home)
e = html.escape

# ---------- languages of the images ----------
SCREEN_LANG = {"pt": "pt", "pt-PT": "pt", "ar": "ar"}          # app screens exist in pt, en and ar; others use en
PANEL_LANGS = {"ar", "de", "en", "es", "fr", "hi", "id", "it", "ja", "ko", "pl", "pt", "ru", "tr", "zh"}


def screen_lang(code):
    return SCREEN_LANG.get(code, "en")


def panel_lang(code):
    c = "pt" if code.startswith("pt") else code
    return c if c in PANEL_LANGS else "en"


# ---------- colours, icons, extra features ----------
def world_colors(F):
    c = {ch["id"]: ch["color"] for ch in F["chapters"]}
    # never violet or pink on the site (brand rule): Jogar and Celular get blues
    c.update({"play": "#8FA2FF", "phone": "#7FB8FF", "everyone": "#F5C04A", "privacy": "#8FD07A"})
    return c


# Features the app has that features.json does not list yet (inventory of 19-20/11/2026). No plan label on purpose:
# the plans are decided after the app is finished.
EXTRA = {
    "home": [("A Ilha no alto da tela", "Os avisos se revezam numa pílula no topo: sem internet, lembrete, agenda e despertador. O que é importante cresce por 5 segundos com “▲ para abrir”, a campainha da Casa aparece na hora e, ao ligar a TV, vem um resumo do que aconteceu. Segure ▲ no topo da cruz para abrir a Central.", "Tela inicial, no alto.", "bell"),
             ("Sugestão na hora certa", "Sem inteligência artificial: pelo uso da semana, a TV sugere o que faz sentido naquela hora.", "Tela inicial.", "bulb")],
    "watch": [("Duas legendas ao mesmo tempo", "Por exemplo, português embaixo e a original em cima, para aprender ou para ver junto.", "Menu ▼ do player › Legenda.", "subtitle"),
              ("Diálogo mais alto", "Quando o som 5.1 vira estéreo, a fala fica na frente e as explosões não abafam a voz.", "Menu ▼ do player.", "speaker"),
              ("Sem spoiler e segundo idioma", "O resumo do episódio que você não viu fica escondido. Sem áudio ou legenda no seu idioma, entra o segundo que você escolheu.", "TV e filmes › Áudio e legendas.", "eye-off")],
    "house": [("Liga junto com a TV", "Ligou a TV pelo controle dela? O aparelho acorda junto, sem tela preta. E o volume da LG pela rede; quando o aparelho dorme sozinho, a LG desliga junto.", "Liga sozinho.", "power"),
              ("Não perturbe", "Nas horas que você escolher, avisos e sons esperam.", "Casa e automações.", "moon"),
              ("“Só se” e feriados", "Automação que só roda em dia útil, com gente em casa ou com chuva, sabendo os feriados do seu país.", "Casa e automações › Automações.", "calendar")],
    "sky": [("Aviso de calor ou frio forte", "O mesmo aviso da chuva, por cima de qualquer tela, quando vem calor ou frio forte.", "Tela inicial › Clima.", "sun"),
            ("O céu da noite", "Planetas, a Estação Espacial e eclipses, calculados na própria TV, sem internet.", "Protetor de tela.", "star"),
            ("A Terra vista do espaço", "Fotos tiradas pelos astronautas e o tema Céu de verdade, com o mapa da sua região.", "Tela e energia › Protetor de tela.", "globe"),
            ("Curiosidade e hora por extenso", "Uma curiosidade por vez no protetor e o relógio escrito por extenso: “nove e cinquenta e um”.", "Protetor de tela.", "text"),
            ("Sua TV no ano", "Em dezembro, uma retrospectiva do ano: o que mais se viu, ouviu e jogou na sua TV. Fica só na TV.", "Aparece em dezembro.", "sparkle")],
    "family": [("Classificação indicativa", "No modo infantil, filmes e séries pela idade certa, além das categorias.", "Família.", "child"),
               ("Modo anfitrião", "Para casa de aluguel: o hóspede usa a TV, e o que é seu fica guardado e volta no fim da estadia.", "Família › Mais opções.", "door"),
               ("Cuidar de longe", "Quem mora sozinho pede ajuda segurando uma tecla, e o celular de quem cuida recebe o aviso. Só liga com o sim de quem mora, dado na própria TV.", "Família › Cuidar de longe.", "heart")],
    "phone": [("Fale com a TV pelo Claude", "Converse com a sua TV pelo Claude: ela responde o que está passando, abre apps e ajusta coisas. Um QR na tela, sem chave e sem senha.", "Acesso remoto › Claude pela internet.", "chat"),
              ("Teclado do celular", "Um campo de texto abriu na TV? O teclado aparece no seu celular.", "Liga sozinho com o painel.", "text"),
              ("Avisos no celular", "Despertador, campainha e recados chegam no celular, sem servidor no meio.", "Painel › Avisos no celular.", "bell")],
    "care": [("Wi-Fi que volta sozinho", "Se o Wi-Fi não volta depois de uma queda, a TV reconecta sozinha.", "Liga sozinho.", "wifi"),
             ("Sem internet, sem susto", "A tela inicial diz numa linha o que continua funcionando enquanto a internet não volta.", "Aparece só quando precisa.", "cloud"),
             ("Limpeza de apps de fábrica", "Tira da frente os apps que vieram com a TV e você não usa, com desfazer, e mostra o antes e depois medido.", "Sistema › Limpeza.", "sparkle")],
    "privacy": [("PIN para mexer na tela inicial", "Ninguém bagunça a sua tela: mover, esconder ou trocar pede o PIN.", "Tela inicial › Mais opções.", "lock")],
}
FIRST = {"care": ("TV nova em 2 minutos", "Comprou outra TV? Leia um QR e tudo passa para ela, cifrado.",
                  "Sistema › Backup › Levar para uma TV nova.", "tv")}
SHOT = {"home": "configuracoes", "watch": "home", "listen": "radio", "play": "jogos", "house": "despertador",
        "family": "boas-vindas", "phone": "entrar-celular", "care": "mudar-tv", "everyone": "ajuda"}
LS = 'stroke="#C7A98E" stroke-width="2.4" fill="none" stroke-linejoin="round" stroke-linecap="round"'
LO = 'stroke="#FF8A55"'
ILL = {
    "apps": f'<svg viewBox="0 0 480 270"><g {LS}><path d="M90 200 H390"/><path d="M110 200 V90 M370 200 V90 M90 90 H390"/>'
            + "".join(f'<rect x="{130 + i * 48}" y="{110 + (i % 2) * 6}" width="36" height="{70 - (i % 2) * 6}" rx="8"/>' for i in range(5))
            + f'<path d="M290 150 h40 v40 h-40z" {LO}/><path d="M300 140 q10-18 20 0" {LO}/></g></svg>',
    "sky": f'<svg viewBox="0 0 480 270"><g {LS}><rect x="130" y="40" width="220" height="180" rx="14"/><path d="M240 40 V220 M130 130 H350"/>'
           f'<path d="M300 70 a22 22 0 1 0 22 34 a18 18 0 0 1-22-34z" {LO}/><path d="M160 186 q0-20 22-20 q8-16 26-10 q20 0 20 20 q14 2 14 12 H160z"/>'
           f'<circle cx="175" cy="70" r="2" {LO}/><circle cx="205" cy="95" r="2" {LO}/><circle cx="275" cy="160" r="2" {LO}/><path d="M110 220 H370"/></g></svg>',
    "privacy": f'<svg viewBox="0 0 480 270"><g {LS}><path d="M150 230 V110 L240 50 L330 110 V230 Z"/><rect x="215" y="160" width="50" height="70" rx="6"/>'
               f'<circle cx="256" cy="196" r="3" {LO}/><rect x="170" y="125" width="34" height="34" rx="4"/><rect x="276" y="125" width="34" height="34" rx="4"/>'
               f'<path d="M228 140 v-10 a12 12 0 0 1 24 0 v10" {LO}/><rect x="222" y="138" width="36" height="22" rx="5" {LO}/></g></svg>',
}
SHOWL = [("forecast", "#6FB7FF", "sun"), ("screensaver", "#E2B866", "image"), ("player", "#FF8A55", "film"),
         ("radio", "#3CC6B4", "radio"), ("alarm", "#F5C04A", "alarm"), ("cards", "#FFB547", "star"),
         ("games", "#8FA2FF", "gamepad"), ("phone", "#8FD07A", "phone"), ("windows", "#4FA3FF", "folder"),
         ("kids", "#5FD3E8", "moon")]
PLAN = {"basic": "", "pro": "Pro", "ultra": "Ultra"}


def ico(n, c="currentColor", cls="ico"):
    return (f'<svg class="{cls}" viewBox="0 0 24 24" fill="none" stroke="{c}" stroke-width="2" stroke-linecap="round" '
            f'stroke-linejoin="round" aria-hidden="true">{B.ICONS[n]}</svg>')


def add_css(s, css):
    return s.replace("@media (prefers-reduced-motion:reduce)", css + "@media (prefers-reduced-motion:reduce)", 1)


# ---------- the page ----------
def page_home(L):
    F = json.loads((WEB / "features.json").read_text())
    C = json.loads((WEB / "content" / "pt.json").read_text())   # structure in pt; translate() swaps to L
    D = L.d
    COR = world_colors(F)
    pre = L.prefix
    sl, pl = screen_lang(L.code), panel_lang(L.code)
    s = (HERE / "base.html").read_text()
    # The cross counter ("2 of 3") is a JS template literal, which translate() does not reach.
    s = s.replace("${s+1} de ${c.itens.length}", "${s+1} " + D["ui"]["of"] + " ${c.itens.length}", 1)

    # Worlds with every feature
    secs, nav, total, counts = [], [], 0, {}
    for ch in F["chapters"]:
        cid = ch["id"]
        t, lead = C["chapters"][cid]
        feats = [(*C["features"][fid], plan, ic) for fid, plan, ic in ch["features"]]
        if cid in FIRST:
            a, b, c, ic = FIRST[cid]
            feats.insert(0, (a, b, c, "", ic))
        feats += [(a, b, c, "", ic) for a, b, c, ic in EXTRA.get(cid, [])]
        total += len(feats)
        counts[cid] = len(feats)
        cor = COR[cid]
        if cid in SHOT:
            vis = (f'<div class="shotbig rv"><img loading="lazy" width="1600" height="900" alt="{e(t)}" '
                   f'src="{pre}assets/home/tela-{sl}-{SHOT[cid]}.webp"></div>')
        else:
            vis = f'<div class="illu rv">{ILL[cid]}</div>'
        lis = "".join(
            f'<li class="rv"><details><summary><span class="fi">{ico(ic)}</span><span class="fn">{e(a)}</span>'
            f'<i>{PLAN.get(p, "")}</i></summary><div class="sp">{e(b)}<br><b>Onde:</b> <span>{e(c)}</span></div></details></li>'
            for a, b, c, p, ic in feats)
        hl = "".join(f'<div class="hl rv"><span class="hi">{ico(ic)}</span><h3>{e(a)}</h3><p>{e(b)}</p></div>'
                     for a, b, c, p, ic in feats[:3])
        secs.append(f'''<section class="world" id="{cid}" style="--w:{cor}">
 <header class="wh rv"><span class="wi">{ico(ch['icon'])}</span><div><h2>{e(t)}</h2><p>{e(lead)}</p></div><b class="wc">{len(feats)}</b></header>
 {vis}
 <div class="hls">{hl}</div>
 <ul class="fl">{lis}</ul>
</section>''')
        nav.append(f'<a href="#{cid}" style="--w:{cor}">{ico(ch["icon"])}<span>{e(t)}</span></a>')
    grade = '<ul class="wgrid">' + "".join(
        f'<li class="rv" style="--w:{COR[c["id"]]}"><a href="#{c["id"]}"><span class="wi2">{ico(c["icon"])}</span>'
        f'<span class="wt"><b>{e(C["chapters"][c["id"]][0])}</b><span>{e(C["chapters"][c["id"]][1])}</span></span>'
        f'<em>{counts[c["id"]]}</em></a></li>' for c in F["chapters"]) + "</ul>"

    h = C["home"]
    faq = "".join(f'<details><summary>{e(q)}</summary><div class="sp">{e(a)}</div></details>' for q, a in h["faq"])
    HC2 = ["#F5C04A", "#F5C04A", "#FF8A55", "#7FB8FF", "#6FB7FF", "#9CD3A8"]
    casa = ('<section class="sec"><div class="wrap"><h2 class="h2c rv">' + e(h["highlights_title"]) + '</h2><ul class="casa">'
            + "".join(f'<li class="rv" style="--w:{c}"><span class="wi2">{ico(i)}</span><h3>{e(t)}</h3><p>{e(d)}</p></li>'
                      for (i, t, d), c in zip(h["highlights"], HC2)) + "</ul></div></section>\n")

    # The web panel on a computer and a phone (real panel, example data)
    pids = [("web-panel", "#7FB8FF", "phone"), ("ask-tv", "#F5C04A", "chat"), ("share", "#9CD3A8", "share"),
            ("windows", "#7FB8FF", "monitor"), ("network-drive", "#FF8A55", "folder"), ("homes", "#F5C04A", "house")]
    pli = "".join(f'<li class="rv" style="--w:{c}"><span class="wi2">{ico(i)}</span><div><b>{e(C["features"][k][0])}</b>'
                  f'<span>{e(C["features"][k][1])}</span></div></li>' for k, c, i in pids)
    painel = f"""<section class="sec" id="painel"><div class="wrap">
<h2 class="h2c rv">A TV também no seu celular e no computador.</h2>
<p class="devlead rv">Abra o navegador na mesma rede, digite o PIN e pronto: controle, digitação com acento, a tela da TV ao vivo, recados e ajustes. Sem instalar nada e sem conta.</p>
<div class="devices rv"><figure class="lap"><div class="lsc"><img src="{pre}assets/home/painel-{pl}-pc.webp" width="1500" height="865" alt="Painel da Sofaly aberto no computador: o que está na TV agora, controle rápido e Pedir à TV" loading="lazy"></div><div class="lbase"></div></figure>
<figure class="cel"><img src="{pre}assets/home/painel-{pl}-cel.webp" width="560" height="1212" alt="O mesmo painel no celular, com os botões grandes do controle" loading="lazy"></figure></div>
<ul class="pfeat">{pli}</ul>
<p class="mini rv">Telas do painel de verdade, com dados de exemplo.</p>
</div></section>
"""

    # Developers (same texts as the old developers page)
    Dv = C["dev_page"]
    DC = ["#F5C04A", "#FF8A55", "#9CD3A8", "#7FB8FF", "#F5C04A", "#FF8A55"]
    dev = f"""<section class="sec" id="dev"><div class="wrap">
<div class="devtop"><div><h2 class="h2c rv">{e(Dv['h1'])}</h2><p class="devlead rv">{e(Dv['lead'])}</p></div><ul class="demo rv">{''.join(f'<li style="--w:{c}"><small>{e(a)}</small><b>{e(b)}</b></li>' for (a, b), c in zip(Dv['demo'], DC[1:]))}</ul></div>
<h3 class="h3c rv">{e(Dv['why_title'])}</h3><ul class="casa">{''.join(f'<li class="rv" style="--w:{c}"><span class="wi2">{ico(i)}</span><h3>{e(t)}</h3><p>{e(d)}</p></li>' for (i, t, d), c in zip(Dv['why'], DC))}</ul>
<h3 class="h3c rv">{e(Dv['can_title'])}</h3><ul class="can">{''.join(f'<li class="rv" style="--w:{c}"><b>{e(a)}</b><span>{e(b)}</span></li>' for (a, b), c in zip(Dv['can'], DC))}</ul>
<div class="devduo"><div><h3 class="h3c rv">{e(Dv['steps_title'])}</h3><ol class="steps2"><li class="rv">{e(Dv['step1'])}</li><li class="rv">{e(Dv['step2'])}</li></ol><p class="mini rv"><b>{e(Dv['template_title'])}</b> <span>{e(Dv['template_text'])}</span></p></div>
<div><h3 class="h3c rv">{e(Dv['rules_title'])}</h3><ul class="rules">{''.join(f'<li class="rv">{e(r)}</li>' for r in Dv['rules'])}</ul><p class="mini rv"><b>Já na TV:</b> <span>fundos de plugin, pacotes de ícones e a vitrine de plugins na própria TV.</span></p></div></div>
</div></section>
"""

    extra = f'''
<section class="sec" id="mundos"><div class="wrap">
 <p class="lead rv"><b class="num">{total}</b> <span class="lt">coisas que a sua TV passa a fazer.</span> <span>Organizadas em 12 mundos, do jeito que a cruz organiza a tela.</span></p>
 {grade}
 <div class="mw"><nav class="side" id="side">{''.join(nav)}</nav><div class="worlds">{''.join(secs)}</div></div>
</div></section>
{dev}<section class="sec" id="planos"><div class="wrap duo"><h2><small>Planos</small>{e(h['plans_title'])}</h2><div><p>{e(h['plans_text'])}</p><p class="mini">Os preços saem quando o app chegar à Google Play.</p></div></div></section>
<section class="sec" id="duvidas"><div class="wrap duo"><h2><small>Dúvidas</small>Perguntas de sofá</h2><div class="faq">{faq}</div></div></section>
<section class="sec fim" id="instalar"><div class="wrap"><h2>{e(h['final_title'])}</h2><p>{e(h['final_text'])}</p><div class="ctabox">{B.LOGO.replace('class="logo-mark"', 'class="logo-mark espera"').replace('var(--logo-screen)', '#241913')}<span class="cta">{e(C['ui']['cta_get'])}</span></div>
<div class="dl rv"><p><span>No app Downloader da TV, digite o código</span> <b class="code">6566820</b></p><p class="ou"><span>Ou baixe o APK em</span> <a href="https://sofaly.tv/download">sofaly.tv/download</a></p></div></div></section>
'''
    a = s.index('<section class="sec"><div class="wrap">\n <div class="duo">')
    b = s.index("</section>", a) + len("</section>")
    s = s[:a] + casa + painel + extra + s[b:]
    i = s.index('<section class="sec" id="mundos">')
    # casa + painel already placed before mundos
    s = add_css(s, (HERE / "home.css").read_text())

    # Top bar (brand, menu, language) instead of the preview note
    ui = C["ui"]
    brand = f'<a class="brand" href="{L.url("index")}" aria-label="Sofaly">{B.LOGO.replace("var(--logo-screen)", "#241913")}<span>sofa<i>ly</i></span></a>'
    opts = "".join(f'<option value="{L.url("index", c)}"{" selected" if c == L.code else ""} data-code="{c}">{e(L.langs[c]["label"])}</option>'
                   for c in L.langs)
    lang = f'<label class="lang"><span class="sr">Idioma</span>{ico("globe")}<select id="lang">{opts}</select></label>'
    top = (f'<header class="top"><div class="in">{brand}<nav><a href="#mundos">{e(ui["nav_features"])}</a><a href="#planos">{e(ui["nav_plans"])}</a>'
           f'<a href="#dev">{e(ui["nav_developers"])}</a><a href="{pre}help.html?l={L.code}">{e(ui["nav_help"])}</a></nav>{lang}</div></header>\n<main id="main">')
    s = re.sub(r'<p class="note">.*?</p>', lambda m: top, s, count=1, flags=re.S)
    foot = f"""</main>
<footer class="foot"><div class="wrap"><div class="fb">{brand}<span>{e(ui['footer_tagline'])}</span></div>
<nav aria-label="Links"><a href="#mundos">{e(ui['nav_features'])}</a><a href="{L.url('plans')}">{e(ui['nav_plans'])}</a><a href="#dev">{e(ui['nav_developers'])}</a>
<a href="{pre}help.html?l={L.code}">{e(ui['nav_help'])}</a><a href="{pre}compatibility.html">{e(ui['footer_compat'])}</a>
<a href="{pre}privacy.html{'#pt' if L.code.startswith('pt') else ''}">{e(ui['footer_privacy'])}</a><a href="{pre}terms.html{'#pt' if L.code.startswith('pt') else ''}">{e(ui['footer_terms'])}</a></nav>
<p class="fine">© 2026 Sofaly · <span>{e(ui['footer_trademark'])}</span></p></div></footer>"""

    # Hero: the cross replica follows the app (no live TV on the site; "Na rede" is "Do computador" since #314)
    s = s.replace('["tv","TV","Os canais da sua TV, com a grade"],', "")
    s = s.replace('["server","Na rede","Vídeos, músicas e fotos da rede da casa"]', '["server","Do computador","Seus vídeos, músicas e fotos"]')
    s = s.replace(".hero{min-height:calc(100svh - 56px);", ".hero{min-height:calc(100svh - 60px);")
    s = s.replace("width:min(100%,calc((100svh - 110px) * 240 / 168))", "width:min(100%,calc((100svh - 120px) * 240 / 168))")
    s = s.replace('<div class="fig">', '<div class="fig rv">')
    parts = s.split('<div class="fig rv">')
    for k, c in zip(range(1, 4), ["#FF8A55", "#F5C04A", "#3CC6B4"]):
        x, y = parts[k].split("</svg>", 1)
        parts[k] = x.replace('stroke="#C7A98E"', f'stroke="{c}"').replace('stroke="#FF8A55"', 'stroke="#FFF3E6"') + "</svg>" + y

    s = '<div class="fig rv">'.join(parts)

    # Hero menu: the cross + the ten screens of the old showcase, which run inside the TV (iframe, 1280x720 scaled)
    scr = "".join(f'<div class="scr scr-{k}" data-k="{k}" style="--k:{c}">{B.SCREENS[k](D["show"]["screens"][k][2], D["home"]["mock"])}</div>'
                  for k, c, _ in B.SHOW)
    doc = (f'<!doctype html><html lang="{e(D["lang"])}"{" dir=rtl" if D.get("dir") == "rtl" else ""}><head><meta charset="utf-8">'
           f'<link rel="stylesheet" href="https://fonts.googleapis.com/css2?family=Manrope:wght@400;600;700;800&family=Nunito:wght@400;600;700&display=swap">'
           f'<link rel="stylesheet" href="{pre}assets/site.css"><style>html,body{{margin:0;background:#0b0d18;overflow:hidden}}'
           f'.screen{{width:100vw;height:100vh;aspect-ratio:auto;border:0;border-radius:0;box-shadow:none}}.scr:not(.on){{display:none!important}}</style></head>'
           f'<body><div class="screen">{scr}</div><script>window.mostrar=function(k){{document.querySelectorAll(".scr").forEach(function(s){{s.classList.toggle("on",s.dataset.k===k)}})}};mostrar("forecast");</script></body></html>')
    ifr = f'<iframe class="shot" id="velha" title="Sofaly" tabindex="-1" aria-hidden="true" srcdoc="{e(doc, quote=True)}"></iframe>'
    s = re.sub(r'<img class="shot"[^>]*>', "", s)
    s = s.replace('<div class="tecla" id="tecla"></div>\n    </div>', '<div class="tecla" id="tecla"></div>\n    </div>\n    ' + ifr, 1)
    s = s.replace(".shot{position:absolute;inset:0;width:100%;height:100%;object-fit:cover;opacity:0;transition:opacity .45s ease}",
                  ".shot{position:absolute;left:0;top:0;width:1280px;height:720px;border:0;opacity:0;pointer-events:none;transform-origin:0 0;transform:scale(calc(100cqw / 1280px));transition:opacity .45s ease;background:#0b0d18}")
    s = s.replace("const ro=new ResizeObserver(()=>stage.style.setProperty('--s',scr.clientWidth/960));",
                  "const ro=new ResizeObserver(()=>{stage.style.setProperty('--s',scr.clientWidth/960);const v=document.getElementById('velha');if(v&&!CSS.supports('width','1cqw'))v.style.transform='scale('+scr.clientWidth/1280+')'});")
    SH = C["show"]["screens"]
    itens = [("cruz", "A cruz", "Continuar, Assistir, Ouvir, Jogar e Aplicativos numa linha. Anda sozinha; no computador, use as setas.", "cross", "#FF8A55")]
    itens += [(k, SH[k][0], SH[k][1], i, c) for k, c, i in SHOWL]
    menu = ('<ol class="menu" id="menu">' + "".join(
        f'<li data-k="{k}" style="--w:{c}"><button type="button">{ico(i)}<span class="t">{e(t)}</span><span class="d">{e(d)}</span>'
        f'<span class="bar"></span></button></li>' for k, t, d, i, c in itens) + '</ol><p class="cap" id="cap" aria-live="polite"></p>')
    s = re.sub(r'<ol class="menu" id="menu">.*?</ol>', lambda m: menu, s, count=1, flags=re.S)
    s = s.replace("shotsEl.forEach(im=>im.classList.toggle('on',im.dataset.k===k));",
                  "const fr=document.getElementById('velha');fr.classList.toggle('on',k!=='cruz');try{if(k!=='cruz')fr.contentWindow.mostrar(k)}catch(_){}"
                  "const cp=document.getElementById('cap');cp.textContent=lis[mi].querySelector('.d').textContent;cp.style.setProperty('--w',lis[mi].style.getPropertyValue('--w'));")
    s = s.replace("const dur=k==='cruz'?14000:6000;", "const dur=k==='cruz'?14000:7000;")
    s = s.replace(".menu li.on .n{color:var(--orange)}", ".menu li.on .n{filter:brightness(1.2)}")
    s = s.replace(".menu li.on .bar{animation", ".menu li.on .bar{background:currentColor;animation")

    # Mascot: arms and feet become their own parts
    for old, new in [('<rect x="3.5" y="104" width="20.5" height="50.5" rx="9.5" fill="#FF6A2B"/>', "braco"),
                     ('<rect x="216" y="104" width="20.5" height="50.5" rx="9.5" fill="#FF6A2B"/>', "braco braco2"),
                     ('<path d="M12.5 147 H24.5 L17 163.8 Q16 166 13.6 166 H11.4 Q9 166 9.3 163.6 Z" fill="#FF6A2B"/>', "pe"),
                     ('<path d="M227.5 147 H215.5 L223 163.8 Q224 166 226.4 166 H228.6 Q231 166 230.7 163.6 Z" fill="#FF6A2B"/>', "pe pe2")]:
        assert s.count(old) == 1, new
        s = s.replace(old, f'<g class="{new}">{old}</g>')
    s = s.replace("</body>", "") + foot + "<script>\n" + (HERE / "home.js").read_text() + "</script>\n</body>\n</html>\n"

    # Head of the real site (title, description, hreflang, canonical, icons) instead of the preview's
    s = re.sub(r"^<title>.*?</title>\s*<meta name=\"claude:viewport-fit-sides\"[^>]*>\s*", "", s, flags=re.S)
    hd = B.head(L, "index", D["home"]["title"], D["home"]["description"])
    hd = re.sub(r'<link rel="stylesheet" href="[^"]*assets/site.css">\n<script src="[^"]*assets/site.js" defer></script>\n', "", hd)
    hd = hd.replace('<meta name="theme-color" content="#121528">', '<meta name="theme-color" content="#17110D">')
    hd = hd.replace("</head>\n<body>\n", "")
    s = hd + s.replace("<style>", "<style>", 1)
    s = s.replace("<style>", "</head>\n<body>\n" if False else "<style>", 1)
    i = s.index("</style>") + len("</style>")
    s = s[:i] + "\n</head>\n<body>\n" + s[i:]
    s = re.sub(r'<a class="skip" href="#main">[^<]*</a>\n?', "", s, count=1)   # the shared head's skip link (whole tag: its text leaked above the page)
    s = s.replace("<body>\n", f'<body>\n<a class="skip" href="#main">{e(ui["skip"])}</a>\n', 1)
    return translate(s, table(L.code))


# ---------- translation ----------
_TABLES = {}


def _flat(o, path=()):
    if isinstance(o, str):
        yield path, o
    elif isinstance(o, list):
        for i, x in enumerate(o):
            yield from _flat(x, path + (i,))
    elif isinstance(o, dict):
        for k, x in o.items():
            yield from _flat(x, path + (k,))


def _get(o, path):
    for p in path:
        o = o[p]
    return o


def table(code):
    """Portuguese text -> text in `code` (pt: none)."""
    if code == "pt":
        return {}
    if code in _TABLES:
        return _TABLES[code]
    pt = json.loads((WEB / "content" / "pt.json").read_text())
    other = json.loads((WEB / "content" / f"{code}.json").read_text())
    t = {}
    for path, v in _flat(pt):
        if v not in t:
            try:
                t[v] = _get(other, path)
            except (KeyError, IndexError, TypeError):
                pass
    f = WEB / "content" / "home" / f"{code}.json"
    if f.exists():
        t.update(json.loads(f.read_text()))
    _TABLES[code] = t
    return t


TEXT = re.compile(r">([^<>]+)<")
ATTR = re.compile(r'\b(alt|title|aria-label|placeholder)="([^"]*)"')
JSSTR = re.compile(r'"((?:[^"\\\n]|\\.)*)"|\'((?:[^\'\\\n]|\\.)*)\'')
SKIP = re.compile(r"^[\s\d:·.,/+()%×\-–—▲▼◀▶›→←•]*$")
SAME = {"Sofaly", "Netflix", "YouTube", "Pro", "Ultra", "sofa", "ly", "GeForce NOW", "Steam Link", "Moonlight", "PS Remote Play",
        "OK", "Links", "IntersectionObserver", "xMidYMid meet", "Coming soon", "sofaly.tv/download"}
SAME |= {json.loads(p.read_text())["label"] for p in (WEB / "content").glob("*.json")}   # language names stay as they are


def _js_text(v):
    """A JavaScript string is text for people only when it reads like it (code names are lower-case words)."""
    if re.search(r"[#{}()=<>/;$`]", v):
        return False
    return bool(re.match(r"[A-ZÀ-Ý“¿¡]", v)) or (" " in v and re.search(r"[a-zà-ÿ]{3}", v) is not None)


def _segments(s):
    """Split into (kind, text): 'style', 'script' or 'html'."""
    out, pos = [], 0
    for m in re.finditer(r"<(style|script)\b[^>]*>.*?</\1>", s, flags=re.S):
        out.append(("html", s[pos:m.start()]))
        out.append((m.group(1), m.group(0)))
        pos = m.end()
    out.append(("html", s[pos:]))
    return out


def walk(s, fn):
    """Calls fn(text) for every translatable text; fn returns the replacement (or None to keep)."""
    res = []
    for kind, part in _segments(s):
        if kind == "style":
            res.append(part)
            continue
        if kind == "script":
            def js(m):
                q = '"' if m.group(1) is not None else "'"
                v = m.group(1) if m.group(1) is not None else m.group(2)
                if "<" in v or "\\" in v or not _js_text(v):
                    return m.group(0)
                r = fn(v)
                return m.group(0) if r is None else q + r.replace(q, "\\" + q) + q
            res.append(JSSTR.sub(js, part))
            continue

        def txt(m):
            raw = m.group(1)
            core = raw.strip()
            if not core:
                return m.group(0)
            r = fn(html.unescape(core))
            if r is None:
                return m.group(0)
            lead = raw[:len(raw) - len(raw.lstrip())]
            trail = raw[len(raw.rstrip()):]
            return ">" + lead + e(r, quote=False) + trail + "<"

        def att(m):
            r = fn(html.unescape(m.group(2)))
            return m.group(0) if r is None else f'{m.group(1)}="{e(r)}"'
        part = ATTR.sub(att, part)
        res.append(TEXT.sub(txt, part))
    return "".join(res)


def translate(s, t):
    if not t:
        return s
    return walk(s, lambda v: None if v in SAME else t.get(v))


def keys(s):
    """Every Portuguese text of the page that needs a translation."""
    found = []

    def fn(v):
        if SKIP.match(v) or v in SAME or not re.search(r"[A-Za-zÀ-ÿ]", v):
            return None
        if v not in found:
            found.append(v)
        return None
    walk(s, fn)
    return found


if __name__ == "__main__" and "--keys" in sys.argv:
    sys.path.insert(0, str(WEB))
    import build as Bm
    B = Bm
    Bm.home_module_ready = True
    langs = {p.stem: json.loads(p.read_text()) for p in sorted((WEB / "content").glob("*.json"))}
    L = Bm.Lang("pt", langs["pt"], langs)
    ks = keys(page_home(L))
    out = {}
    for code in langs:
        if code == "pt":
            continue
        t = table(code)
        out[code] = [k for k in ks if k not in t]
    json.dump({"all": ks, "missing": out}, sys.stdout, ensure_ascii=False, indent=1)
