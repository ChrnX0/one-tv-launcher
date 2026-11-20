#!/usr/bin/env python3
"""Design tokens (20/11/2026): design/tokens.json is the ONE place where the Sofaly look lives.

Why: the TV, the web panel and the phone app each had their own colours, fonts and timings, so the same brand looked
like 4 products. What for: change a value once and every surface follows. How: this script writes
  - app/src/main/kotlin/br/paulo/tvlauncher/DesignTokens.kt   (TV app and screensaver)
  - celular/src/main/kotlin/br/paulo/onetv/celular/DesignTokens.kt   (phone app)
  - the block between "design-tokens:start" and "design-tokens:end" in app/src/main/assets/painel/style.css
DesignTokensTest (JVM) fails when one of them is older than tokens.json.

Usage: python3 scripts/design-tokens.py
"""
import json
import re
from pathlib import Path

RAIZ = Path(__file__).resolve().parent.parent
TOKENS = json.loads((RAIZ / "design/tokens.json").read_text(encoding="utf-8"))


def snake(nome: str) -> str:
    """worldWatch -> WORLD_WATCH (Kotlin constant)."""
    return re.sub(r"(?<!^)(?=[A-Z])", "_", nome).upper()


def kebab(nome: str) -> str:
    """worldWatch -> world-watch (CSS variable)."""
    return re.sub(r"(?<!^)(?=[A-Z])", "-", nome).lower()


def argb(hexa: str) -> str:
    """#FF6A2B -> 0xFFFF6A2B.toInt() (Android colour: opaque)."""
    return f"0xFF{hexa.lstrip('#').upper()}.toInt()"


def kotlin(pacote: str) -> str:
    linhas = [
        "// GERADO por scripts/design-tokens.py a partir de design/tokens.json. Não edite à mão: mude o JSON e rode o script.",
        f"package {pacote}",
        "",
        "/** Os tokens da marca, iguais na TV, no painel e no celular (o porquê de cada um está em design/tokens.json). */",
        "object DesignTokens {",
    ]
    for nome, t in TOKENS["color"].items():
        linhas.append(f"    /** {t['why']} */")
        linhas.append(f"    const val {snake(nome)} = {argb(t['value'])}")
    for nome, t in TOKENS["font"].items():
        linhas.append(f"    /** {t['why']} */")
        linhas.append(f"    const val FONT_{snake(nome)} = \"{t['value']}\"")
    for nome, t in TOKENS["radius"].items():
        linhas.append(f"    /** {t['why']} */")
        linhas.append(f"    const val RADIUS_{snake(nome)} = {t['value']}")
    linhas.append(f"    /** {TOKENS['space']['unit']['why']} */")
    linhas.append(f"    const val SPACE = {TOKENS['space']['unit']['value']}")
    for nome, t in TOKENS["motion"].items():
        if nome == "easeOut":
            a, b, c, d = t["value"]
            linhas.append(f"    /** {t['why']} */")
            linhas.append(f"    val EASE_OUT = floatArrayOf({a}f, {b}f, {c}f, {d}f)")
        else:
            linhas.append(f"    /** {t['why']} */")
            linhas.append(f"    const val MS_{snake(nome)} = {t['value']}L")
    linhas.append("}")
    return "\n".join(linhas) + "\n"


def css() -> str:
    linhas = ["/* design-tokens:start (GERADO por scripts/design-tokens.py a partir de design/tokens.json; não edite à mão) */", ":root {"]
    for nome, t in TOKENS["color"].items():
        linhas.append(f"  --t-{kebab(nome)}: {t['value'].lower()};")
    for nome, t in TOKENS["font"].items():
        linhas.append(f"  --t-font-{kebab(nome)}: \"{t['value']}\";")
    for nome, t in TOKENS["radius"].items():
        linhas.append(f"  --t-radius-{kebab(nome)}: {t['value']}px;")
    linhas.append(f"  --t-space: {TOKENS['space']['unit']['value']}px;")
    for nome, t in TOKENS["motion"].items():
        if nome == "easeOut":
            linhas.append(f"  --t-ease-out: cubic-bezier({', '.join(str(v) for v in t['value'])});")
        else:
            linhas.append(f"  --t-ms-{kebab(nome)}: {t['value']}ms;")
    linhas.append("}")
    linhas.append("/* design-tokens:end */")
    return "\n".join(linhas)


def main() -> None:
    (RAIZ / "app/src/main/kotlin/br/paulo/tvlauncher/DesignTokens.kt").write_text(kotlin("br.paulo.tvlauncher"), encoding="utf-8")
    (RAIZ / "celular/src/main/kotlin/br/paulo/onetv/celular/DesignTokens.kt").write_text(kotlin("br.paulo.onetv.celular"), encoding="utf-8")
    estilo = RAIZ / "app/src/main/assets/painel/style.css"
    texto = estilo.read_text(encoding="utf-8")
    bloco = css()
    padrao = re.compile(r"/\* design-tokens:start.*?/\* design-tokens:end \*/", re.S)
    if padrao.search(texto):
        texto = padrao.sub(lambda _: bloco, texto)
    else:
        # Primeira vez: logo depois das fontes, antes do tema escuro.
        texto = texto.replace("/* Tema escuro", bloco + "\n\n/* Tema escuro", 1)
    estilo.write_text(texto, encoding="utf-8")
    print("tokens gravados: DesignTokens.kt (app, celular) e style.css")


if __name__ == "__main__":
    main()
