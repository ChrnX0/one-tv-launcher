package br.paulo.tvlauncher

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Caixa de comando do painel sem IA (ideia 10): frase em português ou inglês -> uma ação do catálogo fechado. */
class CaixaComandoTest {
    private val apps = mapOf(
        "com.google.android.youtube.tv" to "YouTube",
        "com.netflix.ninja" to "Netflix",
        "com.amazon.amazonvideo.livingroom" to "Prime Video",
        "com.globo.globotv" to "Globoplay",
    )

    /** O leitor de canais da voz (CanalBusca.interpretarFrase), simplificado: "coloca/assistir ... X" e "o que passa ... X". */
    private val canalPt: (String) -> CaixaComando.Pedido? = { f ->
        val n = CaixaComando.normalizar(f)
        Regex("""^o que (?:esta )?passa(?:ndo)? (?:no |na |em )?(.+)$""").find(n)?.let { CaixaComando.Pedido("o_que_passa", alvo = it.groupValues[1]) }
            ?: Regex("""^(?:coloca|assistir|ver|poe|bota|muda) (?:(?:no|na|o|a|pra|para) )*(.+)$""").find(n)
                ?.let { CaixaComando.Pedido("tocar_canal", alvo = it.groupValues[1]) }
    }

    private fun ler(f: String?) = CaixaComando.interpretar(f, apps, canalPt)

    @Test fun canalEmPortugues() {
        assertEquals(CaixaComando.Pedido("tocar_canal", alvo = "globo"), ler("Coloca na Globo"))
        assertEquals(CaixaComando.Pedido("o_que_passa", alvo = "sbt"), ler("o que está passando no SBT?"))
    }

    @Test fun abrirApp() {
        assertEquals(CaixaComando.Pedido("abrir_app", alvo = "com.google.android.youtube.tv"), ler("abre o YouTube"))
        assertEquals(CaixaComando.Pedido("abrir_app", alvo = "com.netflix.ninja"), ler("Open Netflix"))
        // O começo do nome basta quando só um app combina.
        assertEquals(CaixaComando.Pedido("abrir_app", alvo = "com.amazon.amazonvideo.livingroom"), ler("abrir prime"))
        // O verbo de canal com o nome de um app abre o app, não procura canal.
        assertEquals(CaixaComando.Pedido("abrir_app", alvo = "com.netflix.ninja"), ler("coloca a Netflix"))
        // Só o nome.
        assertEquals(CaixaComando.Pedido("abrir_app", alvo = "com.globo.globotv"), ler("Globoplay"))
    }

    @Test fun abrirSemAppViraCanal() {
        assertEquals(CaixaComando.Pedido("tocar_canal", alvo = "globo"), ler("abre a Globo"))
    }

    @Test fun abrirTelaDoLauncher() {
        assertEquals(CaixaComando.Pedido("abrir_tela", alvo = "previsao"), ler("abre a previsão do tempo"))
        assertEquals(CaixaComando.Pedido("abrir_tela", alvo = "despertador"), ler("abre o alarme"))
        assertEquals(CaixaComando.Pedido("abrir_tela", alvo = "configuracoes"), ler("open settings"))
    }

    @Test fun timer() {
        assertEquals(CaixaComando.Pedido("timer", numero = 7), ler("avisa em 7 minutos"))
        assertEquals(CaixaComando.Pedido("timer", numero = 25), ler("me lembra daqui a vinte e cinco minutos"))
        assertEquals(CaixaComando.Pedido("timer", numero = 30), ler("timer de meia hora"))
        assertEquals(CaixaComando.Pedido("timer", numero = 10), ler("Remind me in 10 min"))
        assertEquals(CaixaComando.Pedido("timer", numero = 60), ler("set a timer for an hour"))
        assertNull("acima do teto do catálogo", ler("avisa em 300 minutos"))
    }

    @Test fun desligar() {
        assertEquals(CaixaComando.Pedido("desligar_em", numero = 30), ler("desliga em 30 minutos"))
        assertEquals(CaixaComando.Pedido("desligar_em", numero = 60), ler("desligar a TV daqui a 1 hora"))
        assertEquals(CaixaComando.Pedido("desligar_em", numero = 45), ler("turn off in 45 minutes"))
        assertEquals(CaixaComando.Pedido("desligar_em", numero = 0), ler("cancela o desligamento"))
        assertEquals(CaixaComando.Pedido("desligar_em", numero = 0), ler("cancel the sleep timer"))
    }

    @Test fun volumeEMidia() {
        assertEquals(CaixaComando.Pedido("volume", acao = "subir", numero = 2), ler("aumenta o volume"))
        assertEquals(CaixaComando.Pedido("volume", acao = "descer", numero = 5), ler("volume down 5"))
        assertEquals(CaixaComando.Pedido("volume", acao = "mudo", numero = 1), ler("mudo"))
        assertEquals(CaixaComando.Pedido("midia", acao = "pausar"), ler("pausa"))
        assertEquals(CaixaComando.Pedido("midia", acao = "tocar"), ler("play"))
    }

    @Test fun canalEmIngles() {
        assertEquals(CaixaComando.Pedido("tocar_canal", alvo = "globo"), ler("put on Globo"))
        assertEquals(CaixaComando.Pedido("tocar_canal", alvo = "5"), ler("watch channel 5"))
        assertEquals(CaixaComando.Pedido("o_que_passa", alvo = "espn"), ler("What's on ESPN?"))
    }

    @Test fun naoEntendeu() {
        assertNull(ler(""))
        assertNull(ler("   "))
        assertNull(ler(null))
        assertNull(ler("qual a capital da França"))
        assertNull(ler("desliga"))
    }

    @Test fun jsonDoCatalogo() {
        val o = JSONObject(CaixaComando.Pedido("timer", numero = 7).json())
        val a = o.getJSONArray("acoes").getJSONObject(0)
        assertEquals("timer", a.getString("tipo"))
        assertEquals(7, a.getInt("minutos"))
        assertEquals("", o.getString("resposta"))
        val c = JSONObject(CaixaComando.Pedido("tocar_canal", alvo = "globo").json()).getJSONArray("acoes").getJSONObject(0)
        assertEquals("globo", c.getString("canal"))
        val app = JSONObject(CaixaComando.Pedido("abrir_app", alvo = "com.netflix.ninja").json()).getJSONArray("acoes").getJSONObject(0)
        assertEquals("com.netflix.ninja", app.getString("pacote"))
    }

    @Test fun minutosPorExtenso() {
        assertEquals(7, CaixaComando.minutos("sete minutos"))
        assertEquals(90, CaixaComando.minutos("noventa minutos"))
        assertEquals(120, CaixaComando.minutos("2h"))
        assertEquals(30, CaixaComando.minutos("half an hour"))
        assertNull(CaixaComando.minutos("um dia"))
    }
}
