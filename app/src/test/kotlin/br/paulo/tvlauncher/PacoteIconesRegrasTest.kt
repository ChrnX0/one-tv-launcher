package br.paulo.tvlauncher

import br.paulo.tvlauncher.home.PacoteIconesRegras
import java.io.StringReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.kxml2.io.KXmlParser

class PacoteIconesRegrasTest {
    private fun ler(xml: String) = PacoteIconesRegras.ler(KXmlParser().apply { setInput(StringReader(xml)) })

    @Test fun leItensEIgnoraMascaras() {
        val m = ler("""<?xml version="1.0" encoding="utf-8"?>
            <resources>
              <iconback img1="fundo"/><iconmask img1="mascara"/><scale factor="0.8"/>
              <item component="ComponentInfo{com.netflix.ninja/com.netflix.ninja.MainActivity}" drawable="netflix"/>
              <item component="ComponentInfo{com.google.android.youtube.tv/.Shell}" drawable="youtube"/>
              <item component="ComponentInfo{com.netflix.ninja/com.netflix.Outra}" drawable="netflix_2"/>
              <item component="ComponentInfo{quebrado}" drawable="x"/>
              <item component="ComponentInfo{a.b/c.D}" drawable="../../segredo"/>
            </resources>""")
        assertEquals(3, m.tamanho)
        assertEquals("netflix", m.drawable("com.netflix.ninja", "com.netflix.ninja.MainActivity"))
        assertEquals("youtube", m.drawable("com.google.android.youtube.tv", "com.google.android.youtube.tv.Shell"))
        assertEquals("youtube", m.drawable("com.google.android.youtube.tv", ".Shell"))
        // Atividade renomeada numa versão nova do app: cai no 1º ícone do pacote.
        assertEquals("netflix", m.drawable("com.netflix.ninja", "com.netflix.Nova"))
        assertNull(m.drawable("a.b", "c.D"))
        assertNull(m.drawable("nao.existe", null))
    }

    @Test fun componente() {
        assertEquals("p" to "p.A", PacoteIconesRegras.componente("ComponentInfo{p/.A}"))
        assertEquals("p" to "x.A", PacoteIconesRegras.componente(" ComponentInfo{p/x.A} "))
        assertNull(PacoteIconesRegras.componente("ComponentInfo{p/}"))
        assertNull(PacoteIconesRegras.componente("p/x.A"))
    }

    @Test fun proximoDaVoltaDaOpcao() {
        val l = listOf("a", "b")
        assertEquals("a", PacoteIconesRegras.proximo("", l))
        assertEquals("b", PacoteIconesRegras.proximo("a", l))
        assertEquals("", PacoteIconesRegras.proximo("b", l))
        assertEquals("a", PacoteIconesRegras.proximo("desinstalado", l))
        assertEquals("", PacoteIconesRegras.proximo("a", emptyList()))
    }
}
