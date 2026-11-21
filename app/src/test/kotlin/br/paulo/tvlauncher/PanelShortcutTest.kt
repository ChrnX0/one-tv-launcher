package br.paulo.tvlauncher

import br.paulo.tvlauncher.panel.Mtp
import br.paulo.tvlauncher.panel.MtpArmazem
import br.paulo.tvlauncher.panel.MtpLoja
import br.paulo.tvlauncher.panel.PainelArquivos
import br.paulo.tvlauncher.panel.PanelShortcut
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PanelShortcutTest {
    @Test fun nomeDoArquivo() {
        assertEquals("Abrir o painel da TV.url", PanelShortcut.fileName("Abrir o painel da TV"))
        assertEquals("テレビのパネルを開く.url", PanelShortcut.fileName("テレビのパネルを開く"))
        // o que o Windows não aceita em nome de arquivo sai; ponto no fim também
        assertEquals("ab.url", PanelShortcut.fileName("a/b:"))
        assertEquals("Painel.url", PanelShortcut.fileName("Painel..."))
        assertNull(PanelShortcut.fileName("  ?*  "))
    }

    @Test fun conteudoComOIpDeAgora() {
        val t = String(PanelShortcut.content("192.168.1.153", 8080), Charsets.US_ASCII)
        assertEquals("[InternetShortcut]\r\nURL=http://192.168.1.153:8080/\r\nIconFile=http://192.168.1.153:8080/upnp/sofaly.ico\r\nIconIndex=0\r\n", t)
        assertTrue(String(PanelShortcut.content("fe80::1", 8080), Charsets.US_ASCII).contains("URL=http://[fe80::1]:8080/"))
        assertEquals("http://10.0.0.5:8080/", PanelShortcut.panelUrl("10.0.0.5", 8080))
    }

    @Test(expected = IllegalArgumentException::class) fun recusaTextoQueNaoEIp() {
        PanelShortcut.content("1.2.3.4\r\nURL=file:///C:/x", 8080)
    }

    @Test fun soNaRaizENomeExato() {
        assertTrue(PanelShortcut.isShortcut("Abrir o painel da TV.url", "Abrir o painel da TV.url"))
        assertFalse(PanelShortcut.isShortcut("Download/Abrir o painel da TV.url", "Abrir o painel da TV.url"))
        assertFalse(PanelShortcut.isShortcut("abrir o painel da tv.url", "Abrir o painel da TV.url"))
        assertFalse(PanelShortcut.isShortcut("x.url", null))
    }

    /** Na loja MTP: listado na raiz da memória interna, lido, mas não apagado nem movido. */
    @Test fun lojaMtpMostraOAtalho() {
        val raiz = Files.createTempDirectory("mtp-atalho").toFile()
        val fora = Files.createTempDirectory("mtp-atalho-fora").toFile()
        PainelArquivos.raizTeste = raiz
        try {
            File(raiz, "Download").mkdir()
            val at = File(fora, "Abrir o painel da TV.url").apply { writeBytes(PanelShortcut.content("192.168.1.153", 8080)) }
            val loja = MtpLoja({ listOf(MtpArmazem.interno()) }, atalho = { at }) {}
            val raizes = loja.filhos(Mtp.ARMAZEM, 0)!!
            val o = raizes.single { it.nome == at.name }
            assertEquals(at.length(), o.tamanho)
            assertEquals(at.canonicalPath, loja.arquivo(o.handle)!!.canonicalPath)
            assertTrue(loja.todos(Mtp.ARMAZEM).any { it.nome == at.name })
            val dl = raizes.single { it.nome == "Download" }
            assertTrue(loja.filhos(Mtp.ARMAZEM, dl.handle)!!.none { it.nome == at.name })
            assertEquals(Mtp.NEGADO, loja.apagar(o.handle))
            assertEquals(Mtp.NEGADO, loja.mover(o.handle, Mtp.ARMAZEM, dl.handle, null))
            assertTrue(at.exists())
            // arquivo de verdade com o mesmo nome: aparece uma vez só, e é ele
            File(raiz, at.name).writeText("meu")
            val de = loja.filhos(Mtp.ARMAZEM, 0)!!.filter { it.nome == at.name }
            assertEquals(1, de.size)
            assertEquals(3L, de[0].tamanho)
            // sem atalho (sem rede): nada a mais
            val sem = MtpLoja({ listOf(MtpArmazem.interno()) }) {}
            assertEquals(listOf("Download", at.name), sem.filhos(Mtp.ARMAZEM, 0)!!.map { it.nome })
        } finally {
            PainelArquivos.raizTeste = null
            raiz.deleteRecursively(); fora.deleteRecursively()
        }
    }
}
