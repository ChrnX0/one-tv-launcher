package br.paulo.tvlauncher

import org.junit.Assert.assertEquals
import org.junit.Test

class PainelBlocosTest {
    @Test fun semNadaGravadoEhOPainelDeAntes() {
        assertEquals(listOf("wifi", "bluetooth", "timer", "dormir", "protetor", "audio", "despertador", "visita", "casa", "cenas"),
            PainelBlocos.ler(null))
    }

    @Test fun lerIgnoraDesconhecidoERepetido() =
        assertEquals(listOf("dns", "wifi"), PainelBlocos.ler("dns, xyz,wifi,dns"))

    @Test fun listaVaziaGravadaEhVaziaDeVerdade() = assertEquals(emptyList<String>(), PainelBlocos.ler(""))

    @Test fun idaEVoltaPeloTexto() {
        val l = listOf("filtro", "wifi", "timer")
        assertEquals(l, PainelBlocos.ler(PainelBlocos.gravar(l)))
    }

    @Test fun alternarTiraEPoeNoFim() {
        val l = listOf("wifi", "timer")
        assertEquals(listOf("timer"), PainelBlocos.alternar(l, "wifi"))
        assertEquals(listOf("wifi", "timer", "dns"), PainelBlocos.alternar(l, "dns"))
        assertEquals(l, PainelBlocos.alternar(l, "nao-existe"))
    }

    @Test fun moverTrocaComOVizinhoENaoPassaDasPontas() {
        val l = listOf("wifi", "timer", "dns")
        assertEquals(listOf("timer", "wifi", "dns"), PainelBlocos.mover(l, "timer", -1))
        assertEquals(listOf("wifi", "dns", "timer"), PainelBlocos.mover(l, "timer", 1))
        assertEquals(l, PainelBlocos.mover(l, "wifi", -1))
        assertEquals(l, PainelBlocos.mover(l, "dns", 1))
        assertEquals(l, PainelBlocos.mover(l, "casa", 1))
    }

    @Test fun escolhaMostraOsEscolhidosPrimeiro() {
        val o = PainelBlocos.ordemDaEscolha(listOf("dns", "wifi"))
        assertEquals(listOf("dns", "wifi", "bluetooth"), o.take(3))
        assertEquals(PainelBlocos.TODOS.size, o.size)
    }

    @Test fun indisponivelSomeEParesComSobra() {
        val v = PainelBlocos.visiveis(listOf("wifi", "casa", "timer", "cenas", "dns")) { it != "casa" }
        assertEquals(listOf("wifi", "timer", "cenas", "dns"), v)
        assertEquals(listOf(listOf("wifi", "timer"), listOf("cenas", "dns")), PainelBlocos.linhas(v))
        assertEquals(listOf(listOf("wifi", "timer"), listOf("dns")), PainelBlocos.linhas(listOf("wifi", "timer", "dns")))
    }
}
