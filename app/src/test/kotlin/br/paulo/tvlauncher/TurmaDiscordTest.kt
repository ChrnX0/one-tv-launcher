package br.paulo.tvlauncher

import br.paulo.tvlauncher.games.TurmaDiscord
import br.paulo.tvlauncher.games.TurmaDiscord.Status
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TurmaDiscordTest {
    // Formato real do widget (conferido em 17/11/2026 contra um servidor público), com nomes inventados.
    private val widget = """
        {"id":"81384788765712384","name":"Os Parças","instant_invite":null,
         "channels":[{"id":"111111111111111111","name":"Squad","position":0},{"id":"222222222222222222","name":"Papo","position":1}],
         "members":[
           {"id":"0","username":"Lucas","discriminator":"0000","avatar":null,"status":"online","avatar_url":"https://cdn.discordapp.com/widget-avatars/a","activity":{"name":"Fortnite"},"channel_id":"111111111111111111"},
           {"id":"1","username":"Mari","discriminator":"0000","avatar":null,"status":"online","avatar_url":"https://cdn.discordapp.com/widget-avatars/b","activity":{"name":"Fortnite"},"channel_id":"111111111111111111"},
           {"id":"2","username":"Ju","discriminator":"0000","avatar":null,"status":"idle","avatar_url":"https://cdn.discordapp.com/widget-avatars/c"},
           {"id":"3","username":"Tio Beto","discriminator":"0000","avatar":null,"status":"dnd","avatar_url":"https://cdn.discordapp.com/widget-avatars/d","game":{"name":"EA FC 26"}},
           {"id":"4","username":"Rafa","discriminator":"0000","avatar":null,"status":"online","avatar_url":"javascript:x"},
           {"id":"5","username":"Bia","discriminator":"0000","avatar":null,"status":"online","avatar_url":"https://x","channel_id":"222222222222222222"}
         ],
         "presence_count":9}
    """.trimIndent()

    @Test fun leSalasEOutros() {
        val t = TurmaDiscord.ler(widget)
        assertEquals("Os Parças", t.servidor)
        assertEquals(9, t.online)
        assertEquals(listOf("Squad", "Papo"), t.salas.map { it.nome })   // a mais cheia primeiro
        assertEquals(listOf("Lucas", "Mari"), t.salas[0].membros.map { it.nome })
        // quem joga primeiro (Tio Beto, mesmo ocupado), depois online, ausente
        assertEquals(listOf("Tio Beto", "Rafa", "Ju"), t.outros.map { it.nome })
        assertEquals("EA FC 26", t.outros[0].jogo)   // campo antigo "game" também vale
        assertEquals(Status.OCUPADO, t.outros[0].status)
        assertEquals(Status.AUSENTE, t.outros[2].status)
        assertNull(t.outros[1].avatar)   // só https
    }

    @Test fun onlineNuncaMenorQueALista() {
        val t = TurmaDiscord.ler("""{"name":"X","members":[{"username":"A","status":"online"},{"username":"B","status":"online"}],"presence_count":1}""")
        assertEquals(2, t.online)
    }

    @Test(expected = TurmaDiscord.WidgetDesligado::class) fun widgetDesligado() {
        TurmaDiscord.ler("""{"message": "Widget Disabled", "code": 50004}""")
    }

    @Test(expected = IllegalArgumentException::class) fun naoEhWidget() {
        TurmaDiscord.ler("""{"message": "Unknown Guild", "code": 10004}""")
    }

    @Test fun jogandoAgora() {
        val j = TurmaDiscord.jogandoAgora(TurmaDiscord.ler(widget))
        assertEquals("Fortnite" to listOf("Lucas", "Mari"), j[0])
        assertEquals("EA FC 26", j[1].first)
    }

    @Test fun noAppSoComNomeIgual() {
        val t = TurmaDiscord.ler(widget)
        assertEquals(listOf("Lucas", "Mari"), TurmaDiscord.noApp(t, " fortnite "))
        assertEquals(emptyList<String>(), TurmaDiscord.noApp(t, "GeForce NOW"))
        assertEquals(emptyList<String>(), TurmaDiscord.noApp(t, ""))
    }

    @Test fun limparTextoDeOutraPessoa() {
        assertEquals("a b", TurmaDiscord.limpar("a\n\u0000 b ", 10))
        assertEquals("abcd…", TurmaDiscord.limpar("abcdefgh", 5))
    }

    @Test fun validaId() {
        assertTrue(TurmaDiscord.idValido("81384788765712384"))
        assertTrue(TurmaDiscord.idValido(" 1572075603115515944 "))
        assertFalse(TurmaDiscord.idValido("123"))
        assertFalse(TurmaDiscord.idValido("81384788765712384/../x"))
    }

    @Test fun validaWebhookSoDoDiscord() {
        assertTrue(TurmaDiscord.webhookValido("https://discord.com/api/webhooks/123456789012345678/abcDEF_123-xyzabcDEF_123-xyzabcDEF_123-xyz"))
        assertTrue(TurmaDiscord.webhookValido("https://canary.discordapp.com/api/v10/webhooks/123456789012345678/abcDEF_123-xyzabcDEF_123"))
        assertFalse(TurmaDiscord.webhookValido("http://discord.com/api/webhooks/123456789012345678/abcDEF_123-xyzabcDEF_123"))
        assertFalse(TurmaDiscord.webhookValido("https://discord.com.evil.io/api/webhooks/123456789012345678/abcDEF_123-xyzabcDEF_123"))
        assertFalse(TurmaDiscord.webhookValido("https://evil.io/?u=https://discord.com/api/webhooks/123456789012345678/abcDEF_123-xyzabcDEF_123"))
    }

    @Test fun chamadoSemMencao() {
        val o = JSONObject(TurmaDiscord.corpoChamado("@everyone bora jogar Fortnite", "Sofaly · Sala"))
        assertEquals(0, o.getJSONObject("allowed_mentions").getJSONArray("parse").length())
        assertEquals("Sofaly · Sala", o.getString("username"))
        assertEquals("Sofaly", JSONObject(TurmaDiscord.corpoChamado("oi", "Discord da Sala")).getString("username"))
        assertEquals("Sofaly", JSONObject(TurmaDiscord.corpoChamado("oi", "")).getString("username"))
    }

    @Test fun linkDaSala() {
        assertEquals("https://discord.com/channels/81384788765712384/111111111111111111",
            TurmaDiscord.linkSala("81384788765712384", "111111111111111111"))
    }

    @Test fun botsSaemDaTurma() {
        for (n in listOf("Midjourney Bot", "MEE6 bot", "Music-Bot", "Bot")) assertTrue(n, TurmaDiscord.pareceBot(n))
        for (n in listOf("Botafogo", "Robot", "Lucas", "Abbott")) assertFalse(n, TurmaDiscord.pareceBot(n))
        val t = TurmaDiscord.ler("""{"name":"S","members":[{"username":"Midjourney Bot","status":"online"},{"username":"Paulo","status":"online"}]}""")
        assertEquals(listOf("Paulo"), t.outros.map { it.nome })
        // O número do servidor também não conta o bot ("2 online" com só o Paulo de gente).
        val n = TurmaDiscord.ler("""{"name":"S","presence_count":2,"members":[{"username":"Midjourney Bot","status":"online"},{"username":"Paulo","status":"idle"}]}""")
        assertEquals(1, n.online)
    }
}
