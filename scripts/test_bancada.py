"""Testes da bancada (elevação 2.2): python3 -m unittest scripts/test_bancada.py (da raiz do repo)."""
import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(__file__))
import bancada  # noqa: E402


class TesteBancada(unittest.TestCase):
    def test_total_time(self):
        s = "Starting: Intent { cmp=x/.M }\r\nStatus: ok\r\nTotalTime: 812\r\nWaitTime: 830\r\nComplete\r\n"
        self.assertEqual(bancada.total_time(s), 812)
        self.assertIsNone(bancada.total_time("Error: Activity not started"))
        self.assertIsNone(bancada.total_time(""))

    def test_jank(self):
        self.assertEqual(bancada.jank_pct("Total frames rendered: 352\nJanky frames: 12 (3.40%)\n"), 3.4)
        self.assertIsNone(bancada.jank_pct("No process found"))

    def test_pss(self):
        self.assertEqual(bancada.pss_mb("  TOTAL PSS:    56320            TOTAL RSS:   120000"), 55.0)
        self.assertEqual(bancada.pss_mb("        TOTAL    51200    20000"), 50.0)
        self.assertIsNone(bancada.pss_mb("No process found for: x"))

    def test_piora_contra_mediana(self):
        antes = [{"canal_ms": 1000}, {"canal_ms": 1100}, {"canal_ms": 900}]
        self.assertEqual(bancada.pioras({"canal_ms": 1100}, antes), [])           # +10%: dentro do limite
        p = bancada.pioras({"canal_ms": 1200}, antes)                             # +20%
        self.assertEqual(p, [("canal_ms", 1200, 1000)])
        self.assertEqual(bancada.pioras({"canal_ms": 5000}, []), [])              # primeira noite: nada a comparar
        self.assertEqual(bancada.pioras({"canal_ms": None}, antes), [])           # não medido não é piora

    def test_so_compara_ultimas_noites(self):
        antigas = [{"home_ms": 100}] * 10 + [{"home_ms": 1000}] * 7
        self.assertEqual(bancada.pioras({"home_ms": 1100}, antigas), [])

    def test_resultado_do_broadcast(self):
        s = 'Broadcasting: Intent { ... }\nBroadcast completed: result=0, data="sim"\n'
        self.assertEqual(bancada.resultado_broadcast(s), "sim")
        self.assertEqual(bancada.resultado_broadcast("nada"), "")

    def test_grafico(self):
        h = bancada.grafico_html([{"home_ms": 500}, {"home_ms": 700}])
        self.assertIn("<polyline", h)
        self.assertIn("ainda não medido", h)   # canal_ms sem pontos


if __name__ == "__main__":
    unittest.main()
