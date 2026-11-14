package br.paulo.tvlauncher

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FichasComunidadeTest {
    @Test fun validaFichaPorFicha() {
        val j = """{
          "com.bom.app": {"r":"seguro","d":"Faz algo.","q":"Nada.","en":"Does something","dep":["com.x.y","não pacote"]},
          "com.risco.estranho": {"r":"talvez","d":"a","q":"b"},
          "sem-ponto": {"r":"seguro","d":"a","q":"b"},
          "com.sem.texto": {"r":"seguro","d":"","q":"b"},
          "com.quebra.linha": {"r":"seguro","d":"a\nb","q":"c"},
          "com.nao.objeto": 3
        }"""
        val o = FichasComunidade.validarFichas(j)!!
        assertEquals(setOf("com.bom.app"), o.keys().asSequence().toSet())
        assertEquals(1, o.getJSONObject("com.bom.app").getJSONArray("dep").length())
        assertNull(FichasComunidade.validarFichas("[1]"))
        assertNull(FichasComunidade.validarFichas("{" + " ".repeat(FichasComunidade.MAX_BYTES) + "}"))
    }

    @Test fun deForaNuncaBaixaORisco() {
        val apk = JSONObject("""{"com.a":{"r":"essencial","d":"A","q":"qa"},"com.b":{"r":"seguro","d":"B","q":"qb"}}""")
        val fora = JSONObject("""{"com.a":{"r":"seguro","d":"outro","q":"x","en":"An"},"com.b":{"r":"cuidado","d":"B2","q":"y"},
            "com.c":{"r":"cuidado","d":"C","q":"qc"}}""")
        val r = FichasComunidade.juntar(apk, fora)
        assertEquals("essencial", r.getJSONObject("com.a").getString("r"))
        assertEquals("A", r.getJSONObject("com.a").getString("d"))
        assertEquals("An", r.getJSONObject("com.a").getString("en"))
        assertEquals("cuidado", r.getJSONObject("com.b").getString("r"))
        assertEquals("B", r.getJSONObject("com.b").getString("d"))
        assertEquals("C", r.getJSONObject("com.c").getString("d"))
        assertFalse("o original não muda", apk.getJSONObject("com.a").has("en"))
    }

    @Test fun limpezaSoTestadoEForaDoNunca() {
        val j = """[
          {"pacote":"com.bloat.um","nome":"Um","perde":"nada","testadoEm":"14/11/2026","perdeEn":"nothing"},
          {"pacote":"com.bloat.dois","nome":"Dois","perde":"nada"},
          {"pacote":"com.google.android.gms","nome":"GMS","perde":"tudo","testadoEm":"hoje"},
          {"pacote":"com.ja.tem","nome":"X","perde":"y","testadoEm":"z"},
          {"pacote":"com.bloat.um","nome":"Repetido","perde":"nada","testadoEm":"x"}
        ]"""
        val l = FichasComunidade.validarLimpeza(j, Debloat.NUNCA, setOf("com.ja.tem"))
        assertEquals(listOf("com.bloat.um"), l.map { it.pacote })
        assertEquals("nothing", l[0].perdeEn)
        assertTrue(Debloat.permitido(l[0]))
        assertEquals(emptyList<Debloat.Item>(), FichasComunidade.validarLimpeza("{}", Debloat.NUNCA, emptySet()))
    }

    @Test fun umaVezPorSemana() {
        val semana = 7 * 24 * 3_600_000L
        assertTrue(FichasComunidade.deveBuscar(0L, semana * 100))
        assertFalse(FichasComunidade.deveBuscar(1000L, 1000L + semana - 1))
        assertTrue(FichasComunidade.deveBuscar(1000L, 1000L + semana))
        assertTrue("relógio voltou", FichasComunidade.deveBuscar(5000L, 1000L))
    }
}
