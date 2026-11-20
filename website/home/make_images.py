"""Makes the home page images in website/assets/home/ (WebP).

  python3 website/home/make_images.py <screens folder> <panel folder>

<screens folder>: the PNGs of the "Telas" workflow artifact (<lang>-<screen>.png, 1920x1080, app drawn without data).
<panel folder>: the PNGs of home/render_panel.js. Needs Pillow (only here, not in build.py).
"""
import sys
from pathlib import Path
from PIL import Image

OUT = Path(__file__).resolve().parent.parent / "assets" / "home"
SCREENS = ["configuracoes", "home", "radio", "jogos", "despertador", "boas-vindas", "entrar-celular", "mudar-tv", "ajuda"]


def webp(src, dst, width, crop=None):
    im = Image.open(src).convert("RGB")
    if crop:
        im = im.crop(crop)
    im = im.resize((width, round(im.height * width / im.width)), Image.LANCZOS)
    im.save(dst, "WEBP", quality=80, method=6)


def main(screens, panel):
    OUT.mkdir(parents=True, exist_ok=True)
    for lang in ("pt", "en", "ar"):
        for n in SCREENS:
            webp(Path(screens) / f"{lang}-{n}.png", OUT / f"tela-{lang}-{n}.webp", 1600)
    for f in sorted(Path(panel).glob("painel-*-pc.png")):
        webp(f, OUT / (f.stem + ".webp"), 1500, (0, 0, 2880, 1660))   # without the empty numbers box at the end
    for f in sorted(Path(panel).glob("painel-*-cel.png")):
        webp(f, OUT / (f.stem + ".webp"), 560)


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2])
