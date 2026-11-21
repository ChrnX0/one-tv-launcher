package br.paulo.tvlauncher.home

import br.paulo.tvlauncher.Fundo
import android.content.Context
import android.util.Log
import br.paulo.tvlauncher.AvisoTv
import br.paulo.tvlauncher.Http
import br.paulo.tvlauncher.R
import br.paulo.tvlauncher.alarm.FeriadosTv
import br.paulo.tvlauncher.family.ModoVisita
import br.paulo.tvlauncher.settings.Extras
import br.paulo.tvlauncher.settings.Planos
import br.paulo.tvlauncher.weather.AvisoRegras
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDate
import java.time.LocalTime
import java.util.Calendar
import java.util.Currency
import java.util.Locale

/**
 * A parte Android dos cartões próprios da tela inicial ([HomeCards]): onde as listas moram, o texto de cada pílula, a
 * busca da cotação e o teste dos serviços. Tudo que bloqueia (rede, disco) roda FORA da thread da tela (Fundo.rodar).
 *
 * Listas nas prefs "extras" (já vão no backup completo), editadas pelo painel (cartão "Cartões da tela inicial") ou pelo
 * adb (`EXTRAS --es o cartoes`). As chaves liga/desliga são [Extras.Chave].CARTAO_*. Modo visita: nada aparece.
 */
object HomeCardsTv {
    private const val TAG = "TvLauncher.Cartoes"
    const val CHAVE_CONTAGENS = "cartao_contagens"
    const val CHAVE_LEMBRETES = "cartao_lembretes_casa"
    const val CHAVE_SERVICOS = "cartao_servicos"
    const val CHAVE_MOEDAS = "cartao_moedas"
    private const val CHAVE_COTACOES = "cartao_cotacoes_cache"
    private const val CHAVE_COTACOES_EM = "cartao_cotacoes_em"
    private const val CHAVE_AVISADOS = "cartao_lembretes_avisados"
    /** Intervalo do teste dos serviços (a cada volta à HOME, no máximo uma vez neste tempo). */
    const val TESTE_SERVICOS_MS = 5 * 60_000L

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("cartoes_cache", Context.MODE_PRIVATE)

    /** A chave está ligada e o plano deixa (o Pro). */
    fun ligado(ctx: Context, c: Extras.Chave): Boolean =
        Extras.ligado(ctx, c) && Planos.permite(ctx, Planos.Recurso.CARTOES_HOME) && !ModoVisita.ativo(ctx)

    private fun hoje() = LocalDate.now()

    // ---------------------------------------------------------------- listas

    fun contagens(ctx: Context) = HomeCards.contagensDeJson(Extras.texto(ctx, CHAVE_CONTAGENS, "[]"))
    fun lembretes(ctx: Context) = HomeCards.lembretesDeJson(Extras.texto(ctx, CHAVE_LEMBRETES, "[]"))
    fun servicos(ctx: Context) = HomeCards.servicosDeJson(Extras.texto(ctx, CHAVE_SERVICOS, "[]"))

    @Synchronized fun editarContagens(ctx: Context, f: (List<HomeCards.Contagem>) -> List<HomeCards.Contagem>) =
        f(contagens(ctx)).also { Extras.definirTexto(ctx, CHAVE_CONTAGENS, HomeCards.contagensJson(it).toString()) }
    @Synchronized fun editarLembretes(ctx: Context, f: (List<HomeCards.LembreteCasa>) -> List<HomeCards.LembreteCasa>) =
        f(lembretes(ctx)).also { Extras.definirTexto(ctx, CHAVE_LEMBRETES, HomeCards.lembretesJson(it).toString()) }
    @Synchronized fun editarServicos(ctx: Context, f: (List<HomeCards.Servico>) -> List<HomeCards.Servico>) =
        f(servicos(ctx)).also { l ->
            Extras.definirTexto(ctx, CHAVE_SERVICOS, HomeCards.servicosJson(l).toString())
            // tirou um serviço: as falhas dele saem junto
            prefs(ctx).edit().apply { prefs(ctx).all.keys.filter { it.startsWith("falhas:") && it.removePrefix("falhas:") !in l.map { s -> s.nome } }.forEach { remove(it) } }.apply()
        }

    fun moedaDaCasa(): String = HomeCards.moedaDaCasa(runCatching { Currency.getInstance(Locale.getDefault()).currencyCode }.getOrNull())
    fun moedas(ctx: Context): List<String> = HomeCards.escolhidas(Extras.texto(ctx, CHAVE_MOEDAS), moedaDaCasa())
    fun definirMoedas(ctx: Context, texto: String) {
        Extras.definirTexto(ctx, CHAVE_MOEDAS, HomeCards.escolhidas(texto, moedaDaCasa()).joinToString(","))
        prefs(ctx).edit().remove(CHAVE_COTACOES_EM).apply()   // busca de novo na próxima volta à HOME
    }

    // ---------------------------------------------------------------- contagem

    /** O próximo feriado nacional a partir de hoje (tabela do APK), dentro da janela da contagem. */
    private fun proximoFeriado(ctx: Context, hoje: LocalDate): LocalDate? =
        (0..HomeCards.JANELA_CONTAGEM).map { hoje.plusDays(it) }.firstOrNull { d ->
            FeriadosTv.hoje(ctx, Calendar.getInstance().apply { set(d.year, d.monthValue - 1, d.dayOfMonth, 12, 0, 0) }.timeInMillis)
        }

    fun textoContagem(ctx: Context): String? {
        if (!ligado(ctx, Extras.Chave.CARTAO_CONTAGEM)) return null
        val h = hoje()
        val f = HomeCards.contagemDaPilula(contagens(ctx), h, proximoFeriado(ctx, h)) ?: return null
        val r = ctx.resources
        return when {
            f.nome == null && f.dias == 0L -> r.getString(R.string.hc_feriado_hoje)
            f.nome == null -> r.getQuantityString(R.plurals.hc_feriado_em, f.dias.toInt(), f.dias.toInt())
            f.dias == 0L -> r.getString(R.string.hc_hoje, f.nome)
            else -> r.getQuantityString(R.plurals.hc_faltam, f.dias.toInt(), f.dias.toInt(), f.nome)
        }
    }

    // ---------------------------------------------------------------- lembrete da casa

    fun textosLembretes(ctx: Context): List<String> {
        if (!ligado(ctx, Extras.Chave.CARTAO_LEMBRETE_CASA)) return emptyList()
        return HomeCards.lembretesDeHoje(lembretes(ctx), hoje()).map { l ->
            ctx.getString(R.string.hc_hoje, l.texto) + (l.minuto?.let { " · %02d:%02d".format(Locale.ROOT, it / 60, it % 60) } ?: "")
        }
    }

    /** Mostra na tela os lembretes com hora que chegaram agora (uma vez por dia cada). Chamado na volta à HOME e a cada minuto do relógio. */
    fun avisarLembretes(ctx: Context) {
        if (!ligado(ctx, Extras.Chave.CARTAO_LEMBRETE_CASA)) return
        val h = hoje()
        val agora = LocalTime.now().let { it.hour * 60 + it.minute }
        val avisados = prefs(ctx).getStringSet(CHAVE_AVISADOS, emptySet())!!.filter { it.startsWith("$h|") }.toSet()
        val novos = HomeCards.lembretesParaAvisar(lembretes(ctx), h, agora, avisados)
        if (novos.isEmpty()) return
        prefs(ctx).edit().putStringSet(CHAVE_AVISADOS, avisados + novos.map { HomeCards.chaveAviso(h, it) }).apply()
        // Lembrete de verdade: com os bipes, na central de avisos e guardado se a tela estiver apagada (o não perturbe segura).
        AvisoTv.mostrar(ctx, ctx.getString(R.string.hc_lembrete_rotulo), novos.joinToString("\n") { it.texto }, 15,
            som = true, guardarSeApagada = true, fica = AvisoRegras.Fica.ATENCAO)
    }

    // ---------------------------------------------------------------- status do serviço

    private fun falhasDe(ctx: Context): Map<String, Int> = servicos(ctx).associate { it.nome to prefs(ctx).getInt("falhas:" + it.nome, 0) }

    fun textoServicoFora(ctx: Context): String? {
        if (!ligado(ctx, Extras.Chave.CARTAO_SERVICO)) return null
        val fora = HomeCards.foraDoAr(servicos(ctx), falhasDe(ctx))
        if (fora.isEmpty()) return null
        return ctx.getString(R.string.hc_servico_fora, fora.first()) + if (fora.size > 1) "  +${fora.size - 1}" else ""
    }

    fun foraDoArTodos(ctx: Context): List<String> = HomeCards.foraDoAr(servicos(ctx), falhasDe(ctx))

    /** Testa cada serviço (bloqueia até ~5 s por serviço: chamar fora da tela). Devolve true se o "fora do ar" mudou. */
    fun testarServicos(ctx: Context, forcar: Boolean = false): Boolean {
        if (!ligado(ctx, Extras.Chave.CARTAO_SERVICO)) return false
        val lista = servicos(ctx)
        if (lista.isEmpty()) return false
        val p = prefs(ctx)
        val agora = System.currentTimeMillis()
        if (!forcar && agora - p.getLong("servicos_em", 0) < TESTE_SERVICOS_MS) return false
        p.edit().putLong("servicos_em", agora).apply()
        val antes = HomeCards.foraDoAr(lista, falhasDe(ctx))
        val e = p.edit()
        for (s in lista) {
            val ok = HomeCards.respondeu(codigoHttp(s.url))
            e.putInt("falhas:" + s.nome, HomeCards.falhas(p.getInt("falhas:" + s.nome, 0), ok))
        }
        e.commit()
        return HomeCards.foraDoAr(lista, falhasDe(ctx)) != antes
    }

    /** O código HTTP do endereço, ou null (sem resposta, certificado recusado, tempo esgotado). Nunca afrouxa o TLS. */
    private fun codigoHttp(url: String): Int? = runCatching {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 4_000; c.readTimeout = 4_000
            c.instanceFollowRedirects = false
            c.requestMethod = "GET"
            c.setRequestProperty("User-Agent", Http.USER_AGENT)
            c.responseCode
        } finally { c.disconnect() }
    }.onFailure { Log.i(TAG, "serviço sem resposta: ${it.javaClass.simpleName}") }.getOrNull()

    // ---------------------------------------------------------------- cotação

    fun cotacoesGuardadas(ctx: Context): List<HomeCards.Cotacao> {
        val quero = moedas(ctx)
        return HomeCards.cotacoesDeTexto(prefs(ctx).getString(CHAVE_COTACOES, null)).filter { it.codigo in quero }
            .sortedBy { quero.indexOf(it.codigo) }
    }

    /** A pílula ao lado do clima: as duas primeiras ("US$ 5,78 · ₿ 373 mil"). */
    fun textoCotacao(ctx: Context): String? {
        if (!ligado(ctx, Extras.Chave.CARTAO_COTACAO)) return null
        val l = cotacoesGuardadas(ctx).take(2)
        if (l.isEmpty()) return null
        return l.joinToString("  ·  ") { linhaCotacao(ctx, it) }
    }

    fun linhaCotacao(ctx: Context, c: HomeCards.Cotacao): String =
        HomeCards.simbolo(c.codigo) + " " + HomeCards.valorCurto(c.valor, Locale.getDefault(), ctx.getString(R.string.hc_mil), ctx.getString(R.string.hc_milhao))

    /** O aviso do OK na pílula: todas as escolhidas, a moeda da casa e a data de cada uma. */
    fun textoCotacoesCompleto(ctx: Context): String = cotacoesGuardadas(ctx).joinToString("\n") { c ->
        "${linhaCotacao(ctx, c)}  (${c.codigo} → ${moedaDaCasa()}, ${c.data})"
    } + "\n" + ctx.getString(R.string.hc_cotacao_fonte)

    /** Busca na Frankfurter se o cache passou de 3 h (bloqueia até ~8 s: fora da tela). Devolve true se mudou. */
    fun atualizarCotacoes(ctx: Context): Boolean {
        if (!ligado(ctx, Extras.Chave.CARTAO_COTACAO)) return false
        val p = prefs(ctx)
        val agora = System.currentTimeMillis()
        if (agora - p.getLong(CHAVE_COTACOES_EM, 0) < HomeCards.VALIDADE_COTACAO_MS) return false
        p.edit().putLong(CHAVE_COTACOES_EM, agora).apply()   // falhou também espera: nada de martelar a fonte sem rede
        val casa = moedaDaCasa()
        val json = runCatching {
            val c = URL("https://api.frankfurter.dev/v2/rates?base=$casa&quotes=${moedas(ctx).joinToString(",")}").openConnection() as HttpURLConnection
            try {
                c.connectTimeout = 4_000; c.readTimeout = 4_000
                c.setRequestProperty("User-Agent", Http.USER_AGENT)
                if (c.responseCode != 200) null else c.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
            } finally { c.disconnect() }
        }.onFailure { Log.i(TAG, "cotação falhou: ${it.javaClass.simpleName}") }.getOrNull()
        val l = HomeCards.lerCotacoes(json, casa)
        if (l.isEmpty()) return false
        val antes = p.getString(CHAVE_COTACOES, null)
        val novo = HomeCards.cotacoesParaTexto(l)
        p.edit().putString(CHAVE_COTACOES, novo).apply()
        return novo != antes
    }

    // ---------------------------------------------------------------- painel / adb

    fun json(ctx: Context): org.json.JSONObject = org.json.JSONObject()
        .put("permitido", Planos.permite(ctx, Planos.Recurso.CARTOES_HOME))
        .put("contagens", HomeCards.contagensJson(contagens(ctx)))
        .put("lembretes", HomeCards.lembretesJson(lembretes(ctx)))
        .put("servicos", HomeCards.servicosJson(servicos(ctx), falhasDe(ctx)))
        .put("moedas", org.json.JSONArray(moedas(ctx)))
        .put("moedaCasa", moedaDaCasa())
        .put("ativos", org.json.JSONArray(HomeCards.ATIVOS))
        .put("cotacoes", org.json.JSONArray().apply { cotacoesGuardadas(ctx).forEach { put(org.json.JSONObject().put("codigo", it.codigo).put("valor", it.valor).put("data", it.data)) } })
}
