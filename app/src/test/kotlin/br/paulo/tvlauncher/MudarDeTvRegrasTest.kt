package br.paulo.tvlauncher

import br.paulo.tvlauncher.backup.MudarDeTvRegras
import br.paulo.tvlauncher.backup.MudarDeTvRegras.App
import br.paulo.tvlauncher.backup.MudarDeTvRegras.Conferencia
import br.paulo.tvlauncher.home.abrir
import org.json.JSONArray
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Elevação 19.7: o código do QR, a porta de uso único, o envelope cifrado e a fila de apps. */
class MudarDeTvRegrasTest {
    @Test fun codigoDoQrIdaEVolta() {
        val par = MudarDeTvRegras.novoPar()
        val c = MudarDeTvRegras.Convite("192.168.1.50", 8080, MudarDeTvRegras.novoSegredo(), MudarDeTvRegras.digital(par.public.encoded))
        assertEquals(c, MudarDeTvRegras.ler(c.texto(), 8080))
        // Digitado à mão: minúsculas, espaços, sem a porta, com O no lugar de 0.
        val digitado = "192.168.1.50  " + c.codigo().lowercase().replace('0', 'o').replace("-", " ")
        assertEquals(c, MudarDeTvRegras.ler(digitado, 8080))
        assertEquals(19, c.codigo().length)   // 4 grupos de 4 + 3 hífens
    }

    @Test fun recusaForaDeCasaECodigoErrado() {
        val cod = "ABCD-EFGH-JKMN-PQRS"
        assertNull(MudarDeTvRegras.ler("8.8.8.8 $cod", 8080))         // IP público: nunca
        assertNull(MudarDeTvRegras.ler("100.64.0.9 $cod", 8080))      // Tailscale/CGNAT: não é a rede de casa
        assertNull(MudarDeTvRegras.ler("192.168.1.5:80 $cod", 8080))  // porta < 1024
        assertNull(MudarDeTvRegras.ler("192.168.1.5 ABCD-EFGH", 8080))
        assertNull(MudarDeTvRegras.ler("192.168.1.5 ABCD-EFGH-JKMN-PQRU", 8080))   // U não existe no alfabeto
        assertNotNull(MudarDeTvRegras.ler("SOFALY-TV 10.0.0.2:8080 $cod", 8080))
        assertNotNull(MudarDeTvRegras.ler("172.20.1.1 $cod", 8080))
        assertFalse(MudarDeTvRegras.ipDeCasa("172.32.0.1"))
        assertFalse(MudarDeTvRegras.ipDeCasa("192.168.1.300"))
    }

    @Test fun portaDeUsoUnicoComPrazoELimiteDeErros() {
        val r = MudarDeTvRegras.Recepcao("ABCDEFGH", 1_000)
        assertEquals(Conferencia.ERRADO, r.conferir(MudarDeTvRegras.prova("ABCDEFGX"), 2_000))
        assertEquals(Conferencia.OK, r.conferir(MudarDeTvRegras.prova("abcd-efgh"), 3_000))
        assertEquals(Conferencia.VENCIDO, r.conferir(MudarDeTvRegras.prova("ABCDEFGH"), 4_000))   // uso único

        val v = MudarDeTvRegras.Recepcao("ABCDEFGH", 0)
        assertEquals(Conferencia.VENCIDO, v.conferir(MudarDeTvRegras.prova("ABCDEFGH"), MudarDeTvRegras.VALIDADE_MS + 1))

        val e = MudarDeTvRegras.Recepcao("ABCDEFGH", 0)
        repeat(MudarDeTvRegras.MAX_ERROS) { assertEquals(Conferencia.ERRADO, e.conferir(MudarDeTvRegras.prova("ZZZZZZZZ"), 10)) }
        assertEquals(Conferencia.VENCIDO, e.conferir(MudarDeTvRegras.prova("ABCDEFGH"), 20))   // chutar não adianta: cai depois de 5
        assertFalse(e.vale(20))
    }

    @Test fun envelopeSoAbreNaTvNova() {
        val nova = MudarDeTvRegras.novoPar()
        val outra = MudarDeTvRegras.novoPar()
        val dados = ByteArray(200_000) { (it * 31).toByte() }
        val env = MudarDeTvRegras.selar(nova.public.encoded, dados, "abcd-efgh")
        assertArrayEquals(dados, MudarDeTvRegras.abrir(nova.private, env, "ABCDEFGH"))
        try { MudarDeTvRegras.abrir(outra.private, env, "ABCDEFGH"); fail("abriu com outra chave") } catch (_: IllegalArgumentException) {}
        // Rodada 15: envelope montado por quem só viu o cabeçalho (não sabe o segredo) não abre.
        try { MudarDeTvRegras.abrir(nova.private, MudarDeTvRegras.selar(nova.public.encoded, dados, "ZZZZZZZZ"), "ABCDEFGH"); fail("aceitou envelope de outro segredo") }
        catch (_: IllegalArgumentException) {}
        // Um byte trocado no meio do caminho: o GCM recusa.
        val b = java.util.Base64.getDecoder().decode(env.getString("dados")); b[100] = (b[100].toInt() xor 1).toByte()
        env.put("dados", java.util.Base64.getEncoder().encodeToString(b))
        try { MudarDeTvRegras.abrir(nova.private, env, "ABCDEFGH"); fail("aceitou conteúdo alterado") } catch (_: IllegalArgumentException) {}
        // Lixo no lugar do envelope: recusa com IllegalArgumentException, nunca outra exceção.
        try { MudarDeTvRegras.abrir(nova.private, org.json.JSONObject().put("v", 1).put("efemera", "!!").put("iv", "x").put("dados", "y"), "ABCDEFGH"); fail() }
        catch (_: IllegalArgumentException) {}
    }

    @Test fun pacoteEFilaDeApps() {
        val apps = listOf(App("com.netflix.ninja", "Netflix"), App("br.paulo.tvlauncher", "Sofaly"), App("com.globo.globotv", "Globoplay"),
            App("com.netflix.ninja", "Netflix"), App("nao valido", "x"), App("com.spotify.tv.android", "Spotify"))
        val p = MudarDeTvRegras.lerPacote(MudarDeTvRegras.pacote("s".repeat(32), "{cofre}", apps, "Sala"))
        assertEquals("{cofre}", p.backup); assertEquals("Sala", p.de)
        assertFalse(p.apps.any { it.pacote == "nao valido" })
        val fila = MudarDeTvRegras.fila(p.apps, setOf("com.spotify.tv.android"), "br.paulo.tvlauncher")
        assertEquals(listOf("com.netflix.ninja", "com.globo.globotv"), fila.map { it.pacote })
        assertEquals("com.netflix.ninja", MudarDeTvRegras.proximo(fila, emptySet(), emptySet())?.pacote)
        assertEquals("com.globo.globotv", MudarDeTvRegras.proximo(fila, setOf("com.netflix.ninja"), emptySet())?.pacote)
        assertEquals("com.globo.globotv", MudarDeTvRegras.proximo(fila, emptySet(), setOf("com.netflix.ninja"))?.pacote)
        assertNull(MudarDeTvRegras.proximo(fila, setOf("com.netflix.ninja", "com.globo.globotv"), emptySet()))
        assertEquals(fila, MudarDeTvRegras.appsDeJson(JSONArray(MudarDeTvRegras.appsParaJson(fila))))
        try { MudarDeTvRegras.lerPacote("{\"v\":1,\"senha\":\"curta\",\"backup\":\"x\"}".toByteArray()); fail() } catch (_: IllegalArgumentException) {}
        assertTrue(MudarDeTvRegras.pacoteValido("com.a.b"))
    }
}
