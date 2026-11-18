"""Testes da matriz "toca tudo" (elevação 6.1): python3 -m unittest scripts/test_amostras.py (da raiz do repo)."""
import os
import re
import sys
import unittest

sys.path.insert(0, os.path.dirname(__file__))
import amostras  # noqa: E402

# A mesma regra do launcher (MatrizAmostras.arquivoSeguro): o que não passa nela é ignorado na TV.
SEGURO = re.compile(r"[A-Za-z0-9_\-]+(/[A-Za-z0-9_\-]+)?\.[a-z0-9]{2,5}$")


class TesteAmostras(unittest.TestCase):
    def test_cerca_de_60_com_licenca(self):
        m = amostras.matriz()
        self.assertGreaterEqual(len(m), 60)
        self.assertEqual(len({a["id"] for a in m}), len(m))
        self.assertEqual(len({a["arquivo"] for a in m}), len(m))
        for a in m:
            self.assertRegex(a["id"], r"^[a-z]\d{2}$")
            self.assertTrue(SEGURO.match(a["arquivo"]), a["arquivo"])
            self.assertIn("CC0", a["licenca"])
            if a["legenda_externa"]:
                self.assertTrue(SEGURO.match(a["legenda_externa"]))

    def test_cobre_cada_categoria(self):
        cats = {}
        for a in amostras.matriz():
            cats[a["categoria"]] = cats.get(a["categoria"], 0) + 1
        for c in ["video", "audio", "conteiner", "legenda", "hdr", "stream"]:
            self.assertGreaterEqual(cats.get(c, 0), 4, c)

    def test_comando(self):
        a = next(x for x in amostras.matriz() if x["id"] == "l01")
        c = amostras.comando(a, "saida", {"pt.srt": "/tmp/pt.srt"})
        self.assertEqual(c[0], "ffmpeg")
        self.assertIn("/tmp/pt.srt", c)
        self.assertEqual(c[-1], os.path.join("saida", "l01_srt_embutida.mkv"))
        h = next(x for x in amostras.matriz() if x["id"] == "t01")
        self.assertIn(os.path.join("saida", "t01_hls") + "/seg%03d.ts", amostras.comando(h, "saida", {}))

    def test_so_conteudo_sintetico(self):
        # Nenhuma entrada de fora: só os geradores do ffmpeg (lavfi) e as legendas escritas no próprio script.
        for a in amostras.matriz():
            ent = a["entradas"]
            arquivos = [ent[i + 1] for i, x in enumerate(ent) if x == "-i"]
            for x in arquivos:
                self.assertTrue(x.startswith(("testsrc2=", "smptehdbars=", "sine=")) or x.startswith("{"), x)


if __name__ == "__main__":
    unittest.main()
