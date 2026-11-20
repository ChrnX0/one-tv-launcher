# Gera site/visual.html: página que lê um código de visual (#sofaly-visual:1:...) e galeria dos visuais prontos.
import json, base64, io, sys, qrcode, qrcode.image.svg, html
PAG = "https://chrnx0.github.io/sofaly/visual.html#"
PREF = "sofaly-visual:1:"
# Mesma ordem e valores de VisualCodigo.PRONTOS (o teste VisualCodigoTest confere).
P = [
 ("classico", "Classic", "Clássico", dict(t="ESCURO", x=100, c=False, s=100, v=False, g=False)),
 ("leitura", "Easy reading", "Leitura fácil", dict(t="ESCURO", x=120, c=False, s=115, v=False, g=False)),
 ("galeria", "Gallery", "Galeria", dict(t="GALERIA", x=100, c=False, s=100, v=True, g=False)),
 ("galeria_grande", "Big gallery", "Galeria grande", dict(t="GALERIA", x=110, c=False, s=115, v=True, g=False)),
 ("grade", "Grid", "Grade", dict(t="ESCURO", x=100, c=False, s=100, v=False, g=True)),
 ("grade_grande", "Big grid", "Grade grande", dict(t="ESCURO", x=110, c=False, s=115, v=False, g=True)),
 ("cores", "App colors", "Cores do app", dict(t="ESCURO", x=100, c=True, s=100, v=True, g=False)),
 ("cinema", "Cinema", "Cinema", dict(t="GALERIA", x=100, c=False, s=100, v=True, g=True)),
]
def cod(d): return PREF + base64.urlsafe_b64encode(json.dumps(d, separators=(",", ":")).encode()).decode().rstrip("=")
def qr(txt):
    q = qrcode.QRCode(border=2, error_correction=qrcode.constants.ERROR_CORRECT_M); q.add_data(txt)
    img = q.make_image(image_factory=qrcode.image.svg.SvgPathImage)
    b = io.BytesIO(); img.save(b); s = b.getvalue().decode()
    s = s[s.index("<svg"):]
    return s.replace("<svg ", '<svg class="qr" role="img" aria-label="QR" ', 1)
cards = []
for i, (id_, en, pt, d) in enumerate(P):
    c = cod(d)
    cards.append(f'''<article class="cartao" id="{id_}">
<div class="mini {d['t'].lower()}{' grade' if d['g'] else ''}{' grande' if d['s'] == 115 else ''}{' transp' if d['v'] else ''}{' cores' if d['c'] else ''}" aria-hidden="true"><i></i><i></i><i></i><i></i><i></i><i></i></div>
<div class="info"><h2 data-en="{html.escape(en)}" data-pt="{html.escape(pt)}">{html.escape(en)}</h2>
<ul class="muda" data-codigo="{c}"></ul>
<button class="copiar" data-codigo="{c}" data-t="copiar">Copy code</button></div>
{qr(PAG + c)}
</article>''')
tpl = open(sys.argv[1]).read()
open(sys.argv[2], "w").write(tpl.replace("<!--CARTOES-->", "\n".join(cards)))
print("ok", len(cards))
