package br.paulo.tvlauncher.games

import org.json.JSONArray
import org.json.JSONObject

/**
 * "Sua turma" na tela Jogos (hub de jogos, 17/11/2026): quem do servidor do Discord da pessoa está online, jogando o
 * quê e quem está na sala de voz. Só a regra mora aqui, pura (sem Android), testada em TurmaDiscordTest.
 *
 * Por que o widget do servidor e não o login do Discord: o widget (`/api/guilds/<id>/widget.json`) é público, não pede
 * conta nem aprovação, e o dono do servidor liga ele em Configurações do servidor > Widget. Ele traz até 100 membros
 * online com status, o nome do jogo e o canal de voz. O kit oficial com login (Social SDK) só mostraria o que a pessoa
 * faz no NOSSO app, não o jogo dos amigos (docs do Discord, 17/11/2026), e o pedido de uso ainda não teve resposta.
 *
 * "Chamar a turma" manda uma mensagem num canal pelo webhook que a própria pessoa cria (Editar canal > Integrações >
 * Webhooks). A TV nunca lê mensagens.
 */
object TurmaDiscord {
    enum class Status { ONLINE, AUSENTE, OCUPADO }

    /** Um membro como o widget mostra: nome (o apelido do servidor), status, jogo (null = nenhum) e canal de voz. */
    data class Membro(val nome: String, val status: Status, val jogo: String?, val canalVoz: String?, val avatar: String?)

    /** Uma sala de voz com gente dentro. */
    data class Sala(val id: String, val nome: String, val membros: List<Membro>)

    /**
     * O que a tela desenha: nome do servidor, quantos online (o número do servidor, não só os 100 que vêm na lista),
     * as salas de voz com gente (a mais cheia primeiro) e os outros online (quem joga primeiro).
     */
    data class Turma(val servidor: String, val online: Int, val salas: List<Sala>, val outros: List<Membro>)

    /** O widget está desligado no servidor (o dono precisa ligar). */
    class WidgetDesligado : Exception("widget desligado")

    /** ID do servidor: só dígitos (snowflake do Discord, 17 a 20). */
    fun idValido(id: String): Boolean = Regex("^\\d{17,20}$").matches(id.trim())

    /** Webhook de canal do Discord, e só ele (nada de URL qualquer: a TV faz POST nela). */
    fun webhookValido(url: String): Boolean =
        Regex("^https://(?:(?:ptb|canary)\\.)?discord(?:app)?\\.com/api(?:/v\\d+)?/webhooks/\\d{17,20}/[A-Za-z0-9_-]{20,128}$").matches(url.trim())

    fun urlWidget(id: String) = "https://discord.com/api/guilds/${id.trim()}/widget.json"

    /** Link que abre a sala de voz no app do Discord do celular (o QR da tela). A conversa vai pelo celular. */
    fun linkSala(servidor: String, canal: String) = "https://discord.com/channels/${servidor.trim()}/$canal"

    /**
     * Lê o JSON do widget. Lança [WidgetDesligado] para o código 50004 do Discord e IllegalArgumentException para
     * qualquer outra coisa que não seja um widget.
     */
    fun ler(json: String): Turma {
        val o = JSONObject(json)
        if (o.optInt("code") == 50004) throw WidgetDesligado()
        if (!o.has("members") && !o.has("name")) throw IllegalArgumentException("não é um widget do Discord")
        val canais = HashMap<String, Pair<String, Int>>()   // id -> (nome, posição)
        val ca = o.optJSONArray("channels") ?: JSONArray()
        for (i in 0 until ca.length()) ca.optJSONObject(i)?.let { c ->
            canais[c.optString("id")] = c.optString("name") to c.optInt("position", i)
        }
        val membros = ArrayList<Membro>()
        var bots = 0   // o Discord conta os bots no presence_count: tiramos do número também (visto na Sala, 19/11/2026)
        val ma = o.optJSONArray("members") ?: JSONArray()
        for (i in 0 until ma.length()) {
            val m = ma.optJSONObject(i) ?: continue
            val nome = limpar(m.optString("username"), 32)
            if (nome.isEmpty()) continue
            if (pareceBot(nome)) { bots++; continue }
            val jogo = (m.optJSONObject("activity") ?: m.optJSONObject("game"))?.optString("name")?.let { limpar(it, 40) }?.takeIf { it.isNotEmpty() }
            val status = when (m.optString("status")) { "idle" -> Status.AUSENTE; "dnd" -> Status.OCUPADO; else -> Status.ONLINE }
            val canal = m.optString("channel_id").takeIf { it.isNotEmpty() && it != "null" }
            membros += Membro(nome, status, jogo, canal, m.optString("avatar_url").takeIf { it.startsWith("https://") })
        }
        val salas = membros.filter { it.canalVoz != null }.groupBy { it.canalVoz!! }
            .map { (id, ms) -> Sala(id, canais[id]?.first?.let { limpar(it, 32) } ?: "", ordenar(ms)) }
            .sortedWith(compareByDescending<Sala> { it.membros.size }.thenBy { canais[it.id]?.second ?: Int.MAX_VALUE })
        val outros = ordenar(membros.filter { it.canalVoz == null })
        val online = (o.optInt("presence_count", membros.size + bots) - bots).coerceAtLeast(membros.size)
        return Turma(limpar(o.optString("name"), 40), online, salas, outros)
    }

    /**
     * O widget não diz quem é bot (pedido do Paulo, 19/11/2026: "Midjourney Bot" aparecia como da turma). Pelo nome,
     * só a palavra "bot" inteira no fim ("Midjourney Bot", "MEE6 bot", "Music-Bot"): "Botafogo" e "Robot" ficam.
     */
    fun pareceBot(nome: String): Boolean = Regex("(^|[\\s._-])bot$", RegexOption.IGNORE_CASE).containsMatchIn(nome.trim())

    /** Quem joga primeiro; depois online, ausente, ocupado; dentro de cada um, por nome. */
    private fun ordenar(ms: List<Membro>) = ms.sortedWith(compareBy<Membro> { if (it.jogo != null) 0 else 1 }
        .thenBy { it.status.ordinal }.thenBy { it.nome.lowercase() })

    /** Texto que vem do Discord: sem quebras nem controles, cortado com "…" (é dado de outra pessoa, nunca formatação). */
    fun limpar(s: String, max: Int): String {
        val t = s.filter { !it.isISOControl() }.replace(Regex("\\s+"), " ").trim()
        return if (t.length > max) t.take(max - 1).trimEnd() + "…" else t
    }

    /**
     * Quem da turma está jogando [jogo] agora (para "Lucas e Mari estão no Fortnite"). O widget só diz o nome do jogo,
     * não por qual app; a tela usa isso só como frase, nunca para abrir nada.
     */
    fun jogandoAgora(t: Turma): List<Pair<String, List<String>>> =
        (t.salas.flatMap { it.membros } + t.outros).filter { it.jogo != null }
            .groupBy { it.jogo!! }.map { (j, ms) -> j to ms.map { it.nome } }
            .sortedByDescending { it.second.size }

    /**
     * Quem da turma está jogando o app [nomeApp] (o selo "2 da turma aqui" no card). Só nome igual, sem adivinhar: o
     * widget diz "Fortnite", e o app GeForce NOW não diz qual jogo roda dentro dele.
     */
    fun noApp(t: Turma, nomeApp: String): List<String> {
        val alvo = nomeApp.trim().lowercase()
        if (alvo.isEmpty()) return emptyList()
        return (t.salas.flatMap { it.membros } + t.outros).filter { it.jogo?.lowercase() == alvo }.map { it.nome }
    }

    /**
     * Corpo JSON do webhook "Chamar a turma". `allowed_mentions` vazio: nada de @everyone, @here ou menção, mesmo que
     * o texto (nome do jogo, nome da TV) traga um "@". O nome que aparece no Discord é o da TV.
     */
    fun corpoChamado(texto: String, nomeTv: String): String = JSONObject()
        .put("content", limpar(texto, 300))
        // O Discord recusa webhook com "discord" ou "clyde" no nome: aí vai "Sofaly".
        .put("username", limpar(nomeTv, 32).takeUnless { it.isEmpty() || Regex("(?i)discord|clyde").containsMatchIn(it) } ?: "Sofaly")
        .put("allowed_mentions", JSONObject().put("parse", JSONArray()))
        .toString()
}
