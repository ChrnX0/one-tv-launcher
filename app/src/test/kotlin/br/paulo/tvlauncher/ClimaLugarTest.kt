package br.paulo.tvlauncher

import br.paulo.tvlauncher.alarm.FaixaDias
import br.paulo.tvlauncher.network.Endereco
import br.paulo.tvlauncher.weather.LocalClima
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Testes do endereço do clima (Endereco/LocalClima) e da faixa de 9 dias (FaixaDias): só a lógica, sem Android. */
class ClimaLugarTest {
    // ---- endereço (CEP + número)

    @Test fun cepENumero() {
        assertEquals("01001000", Endereco.limparCep("01001-000"))
        assertNull(Endereco.limparCep("0100100"))
        assertNull(Endereco.limparCep(null))
        assertEquals("100", Endereco.limparNumero(" 100 "))
        assertEquals("", Endereco.limparNumero(""))
        assertNull(Endereco.limparNumero("100A"))
    }

    private val viaCep = """{"cep":"01001-000","logradouro":"Praça da Sé","bairro":"Centro","localidade":"São Paulo","uf":"SP"}"""

    @Test fun leViaCep() {
        assertEquals(Endereco.Cep("01001-000", "Praça da Sé", "Centro", "São Paulo", "SP"), Endereco.lerViaCep(viaCep))
        assertNull(Endereco.lerViaCep("""{"erro": true}"""))
        assertNull(Endereco.lerViaCep("""{"erro": "true"}"""))
        assertNull(Endereco.lerViaCep("lixo"))
    }

    @Test fun leNominatim() {
        assertEquals(-21.18 to -47.80, Endereco.lerNominatim("""[{"lat":"-21.18","lon":"-47.80"}]"""))
        assertNull(Endereco.lerNominatim("[]"))
        assertNull(Endereco.lerNominatim("""[{"lat":"0","lon":"0"}]"""))
    }

    @Test fun buscaDoMaisPrecisoParaOMenos() {
        val c = Endereco.lerViaCep(viaCep)!!
        assertEquals(listOf("número", "rua", "bairro", "cidade"), Endereco.consultas(c, "100").map { it.second })
        assertEquals(listOf("rua", "bairro", "cidade"), Endereco.consultas(c, "").map { it.second })
        // O número não achou nada no mapa: cai na rua.
        val pedidos = ArrayList<String>()
        val r = Endereco.buscar("01001-000", "100") { url ->
            pedidos += url
            when {
                "viacep" in url -> viaCep
                "street=100" in url -> "[]"
                "street=" in url -> """[{"lat":"-23.550","lon":"-46.633"}]"""
                else -> "[]"
            }
        }.getOrThrow()
        assertEquals(Endereco.Achado("Centro", -23.550, -46.633, "rua"), r)
        assertEquals(3, pedidos.size)   // ViaCEP + 2 buscas no mapa, e para
    }

    @Test fun erros() {
        assertEquals("CEP inválido: são 8 números", Endereco.buscar("123", "") { null }.exceptionOrNull()?.message)
        assertEquals("CEP não encontrado (ou sem internet)", Endereco.buscar("01001000", "") { null }.exceptionOrNull()?.message)
        assertEquals("não achei esse endereço no mapa",
            Endereco.buscar("01001000", "") { if ("viacep" in it) viaCep else "[]" }.exceptionOrNull()?.message)
    }

    @Test fun descricao() {
        assertEquals("Centro · CEP 01001-000, nº 100", LocalClima.descrever(LocalClima.Lugar("Centro", 1.0, 1.0, "01001000", "100")))
        assertEquals("Centro · CEP 01001-000", LocalClima.descrever(LocalClima.Lugar("Centro", 1.0, 1.0, "01001000", "")))
    }

    @Test fun nomeLimpo() {
        assertEquals("Centro", LocalClima.limparNome("  Centro \n"))
        assertEquals("Local escolhido", LocalClima.limparNome(""))
        assertEquals("Local escolhido", LocalClima.limparNome(null))
        assertEquals(LocalClima.MAX_NOME, LocalClima.limparNome("x".repeat(100)).length)
    }

    // ---- faixa de 9 dias

    private fun daily(n: Int = 9) = JSONObject()
        .put("time", JSONArray((0 until n).map { "2026-11-%02d".format(5 + it) }))
        .put("weather_code", JSONArray((0 until n).map { if (it % 2 == 0) 61 else 2 }))
        .put("temperature_2m_max", JSONArray((0 until n).map { 29.4 + it }))
        .put("temperature_2m_min", JSONArray((0 until n).map { 20.6 }))
        .put("precipitation_probability_max", JSONArray((0 until n).map { 80 }))

    @Test fun leNoveDias() {
        val dias = FaixaDias.ler(daily(), tempAgora = 25)
        assertEquals(9, dias.size)
        assertEquals(FaixaDias.Dia("2026-11-05", 61, 29, 21, 80), dias[0])
        assertEquals(FaixaDias.Dia("2026-11-13", 61, 37, 21, 80), dias[8])
    }

    @Test fun hojeSeAjustaATemperaturaMedida() {
        // O aeroporto mediu 32° com o modelo dizendo máxima 29°: a de hoje vira 32 (igual ao topo), as outras não.
        val dias = FaixaDias.ler(daily(), tempAgora = 32)
        assertEquals(32, dias[0].max)
        assertEquals(30, dias[1].max)
        assertEquals(18, FaixaDias.ler(daily(), tempAgora = 18)[0].min)
    }

    @Test fun chanceDeChuvaPelaMedianaQuandoHa() {
        val mediana = listOf(55, 15, 55, 40, 10, 10, 15, 50, 15)
        assertEquals(mediana, FaixaDias.ler(daily(), null, diasChuva = mediana).map { it.chuva })
        // Mediana mais curta que os dias: o resto cai no best_match.
        assertEquals(listOf(55, 80, 80), FaixaDias.ler(daily(3), null, diasChuva = listOf(55)).map { it.chuva })
    }

    @Test fun chanceDoDiaEOMeioTermoEntrePicoEMedia() {
        // Números de 05/11/2026 (mediana ECMWF/ICON/GFS): pico 76/43/71, média 21/13/27 -> 49/28/49 (Google: 55/15/55).
        assertEquals(listOf(49, 28, 49), FaixaDias.chanceDoDia(listOf(76, 43, 71), listOf(21, 13, 27)))
        assertEquals(listOf(76, 43), FaixaDias.chanceDoDia(listOf(76, 43), null))          // sem média: o pico
        assertEquals(listOf(49, 43), FaixaDias.chanceDoDia(listOf(76, 43), listOf(21)))    // média mais curta
        assertNull(FaixaDias.chanceDoDia(null, listOf(21)))
    }

    @Test fun pulaDiaSemDadoENaoPassaDoPedido() {
        val d = daily().put("temperature_2m_max", JSONArray(listOf(30.0, JSONObject.NULL, 31.0)))
        assertEquals(listOf("2026-11-05", "2026-11-07"), FaixaDias.ler(d, null).map { it.data })
        assertEquals(3, FaixaDias.ler(daily(), null, dias = 3).size)
        assertEquals(emptyList<FaixaDias.Dia>(), FaixaDias.ler(JSONObject(), null))
    }

    @Test fun nomesDosDias() {
        val d = FaixaDias.Dia("2026-11-06", 2, 33, 23, 15)   // sexta-feira
        assertEquals("Hoje", FaixaDias.rotulo(d, 0, "Hoje"))
        assertEquals("sex.", FaixaDias.rotulo(d, 1, "Hoje", Locale.forLanguageTag("pt-BR")))
        assertEquals("Fri", FaixaDias.rotulo(d, 1, "Today", Locale.US))
    }
}
