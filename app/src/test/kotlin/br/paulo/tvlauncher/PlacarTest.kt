package br.paulo.tvlauncher

import br.paulo.tvlauncher.alarm.AcertoAlarme
import br.paulo.tvlauncher.observer.Placar
import br.paulo.tvlauncher.observer.Placar.Numero
import br.paulo.tvlauncher.observer.Placar.Pilar
import br.paulo.tvlauncher.observer.Placar.Ponto
import br.paulo.tvlauncher.observer.Placar.Serie
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Elevação 2.1: placar dos 7 pilares, cada um com número, alvo e data; sem medida = "ainda não medido". */
class PlacarTest {
    private fun serie(vararg v: Long) = Serie(v.mapIndexed { i, x -> Ponto(x, 1_000L + i) })

    @Test fun seteLinhasNaOrdemDosPilares() {
        val l = Placar.linhas(Placar.Entradas())
        assertEquals(Pilar.entries.toList(), l.map { it.pilar })
    }

    @Test fun semMedidaNuncaMostraNumeroNemVeredito() {
        for (x in Placar.linhas(Placar.Entradas())) {
            assertNull(x.pilar.name, x.numero); assertNull(x.dentro); assertNull(x.medidoEm)
        }
    }

    @Test fun trocaDeCanalUsaMedianaEAlvoDe1500() {
        val l = Placar.linhas(Placar.Entradas(canal = serie(900, 1_200, 9_000)))   // uma troca lenta por acaso
        val c = l.first { it.pilar == Pilar.AO_VIVO }
        assertEquals(Numero.Tempo(1_200), c.numero)
        assertEquals(Numero.Tempo(1_500), c.alvo)
        assertEquals(true, c.dentro)
        assertEquals(1_002L, c.medidoEm)   // a data é a da última medida
        assertEquals(3, c.amostras)
        assertEquals(false, Placar.linhas(Placar.Entradas(canal = serie(1_600, 2_000))).first { it.pilar == Pilar.AO_VIVO }.dentro)
    }

    @Test fun homeSoFicaNoAlvoSemQuedaEm30Dias() {
        val rapida = serie(400, 500, 600)
        assertEquals(true, Placar.linhas(Placar.Entradas(home = rapida, quedas30 = 0)).first().dentro)
        assertEquals(false, Placar.linhas(Placar.Entradas(home = rapida, quedas30 = 1)).first().dentro)
        assertEquals(true, Placar.linhas(Placar.Entradas(home = rapida, quedas30 = null)).first().dentro)
        assertEquals(false, Placar.linhas(Placar.Entradas(home = serie(1_100, 1_300), quedas30 = 0)).first().dentro)
    }

    @Test fun alarmeContaPerdidos() {
        val r = AcertoAlarme.Resumo(naHora = 211, atrasados = 0, perdidos = 1, ultimoEm = 5L)
        val c = Placar.linhas(Placar.Entradas(alarme = r)).first { it.pilar == Pilar.CASA }
        assertEquals(Numero.Contagem(211, 212), c.numero)
        assertEquals(false, c.dentro)
        val ok = Placar.linhas(Placar.Entradas(alarme = r.copy(naHora = 212, perdidos = 0))).first { it.pilar == Pilar.CASA }
        assertEquals(true, ok.dentro)
        assertNull(Placar.linhas(Placar.Entradas(alarme = AcertoAlarme.Resumo(0, 0, 0, null))).first { it.pilar == Pilar.CASA }.numero)
    }

    @Test fun instalacaoAlvoCincoMinutos() {
        val c = Placar.linhas(Placar.Entradas(instalacao = serie(4 * 60_000L))).first { it.pilar == Pilar.INSTALA }
        assertEquals(true, c.dentro)
        assertEquals(Numero.Tempo(5 * 60_000L), c.alvo)
    }

    @Test fun serieGuardaSoOsUltimos() {
        var s = Serie()
        repeat(Placar.GUARDAR + 5) { s = s.somar(Ponto(it.toLong(), it + 1L)) }
        assertEquals(Placar.GUARDAR, s.pontos.size)
        assertEquals(5L, s.pontos.first().valor)
        assertEquals(s.pontos, Placar.ler(Placar.texto(s)).pontos)
    }

    @Test fun leituraDefeituosaNaoQuebra() {
        assertTrue(Placar.ler("lixo").pontos.isEmpty())
        assertTrue(Placar.ler(null).pontos.isEmpty())
        assertEquals(1, Placar.ler("[[5,0],[7,9],[1]]").pontos.size)   // em=0 e ponto incompleto caem fora
    }

    @Test fun tempoEmTexto() {
        assertEquals("0,8 s", Placar.tempoTexto(800))
        assertEquals("1,4 s", Placar.tempoTexto(1_449)); assertEquals("1,5 s", Placar.tempoTexto(1_450))
        assertEquals("1.2 s", Placar.tempoTexto(1_200, '.'))
        assertEquals("5 min", Placar.tempoTexto(300_000))
        assertEquals("3 min 20 s", Placar.tempoTexto(200_000))
    }

    @Test fun jsonDizMedidoOuNao() {
        val j = Placar.json(Placar.linhas(Placar.Entradas(canal = serie(1_000))))
        val p = j.getJSONArray("pilares")
        assertEquals(7, p.length())
        assertFalse(p.getJSONObject(0).getBoolean("medido"))
        assertTrue(p.getJSONObject(1).getBoolean("medido"))
        assertEquals(1_000L, p.getJSONObject(1).getJSONObject("valor").getLong("ms"))
    }
}
