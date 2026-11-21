package br.paulo.tvlauncher.player

import android.content.Context
import android.util.Log
import br.paulo.tvlauncher.BuildConfig
import br.paulo.tvlauncher.Http
import br.paulo.tvlauncher.R
import br.paulo.tvlauncher.family.ModoVisita
import br.paulo.tvlauncher.family.Perfil
import br.paulo.tvlauncher.family.PerfilCriancaTv
import br.paulo.tvlauncher.player.ServicosFilmes.Servico
import br.paulo.tvlauncher.player.SincronizarFilmes.Ep
import br.paulo.tvlauncher.player.SincronizarFilmes.Item
import br.paulo.tvlauncher.player.SincronizarFilmes.ListaFora
import br.paulo.tvlauncher.player.SincronizarFilmes.Opcoes
import br.paulo.tvlauncher.player.SincronizarFilmes.Tipo
import br.paulo.tvlauncher.player.SincronizarFilmes.Titulo
import java.io.File
import java.net.URLEncoder
import org.json.JSONArray
import org.json.JSONObject

/**
 * A parte com rede e disco da fatia 3 (regras em SincronizarFilmes, testadas na JVM).
 *
 * Onde fica cada coisa:
 *  - prefs "sincronizar_filmes" (vai no backup completo): as opções de cada conta, o perfil a que a sincronização
 *    pertence e as listas escolhidas;
 *  - files/player/sincronizar.json (não vai: refaz sozinho): as fotos da última sincronização (atividades, base dos
 *    Interesses), as pausas e os episódios vistos lá fora, e a data da última vez;
 *  - files/player/listas_fora/<serviço>_<id>.json: os itens de cada lista escolhida (12 h de validade).
 *
 * A sincronização pertence a UM perfil: o que estava ativo quando o dono ligou a primeira opção. Com outro perfil, em
 * Modo visita ou no perfil infantil, nada sobe nem desce. Quem chama: Filmes e Séries ao abrir (no máximo a cada 20 min,
 * ou na hora pelo "Sincronizar agora"), o player (scrobble) e a tela/painel das contas.
 */
object ServicosSync {
    private const val TAG = "TvLauncher.Sync"
    private const val PREFS = "sincronizar_filmes"
    private const val ARQ_ESTADO = "sincronizar.json"
    private const val ARQ_SIMKL = "simkl_biblioteca.json"
    private const val PASTA_LISTAS = "listas_fora"
    private const val VALIDADE_LISTA_MS = 12 * 3_600_000L
    private const val MAX_POR_ENVIO = 100
    private val versao get() = BuildConfig.VERSION_CODE
    private val trava = Any()

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // ================================================================ opções e listas escolhidas

    fun opcoes(ctx: Context, s: Servico): Opcoes =
        SincronizarFilmes.opcoesDeJson(runCatching { JSONObject(prefs(ctx).getString("opcoes_${s.id}", null) ?: "") }.getOrNull())

    /**
     * Grava as opções. Ligar a primeira opção amarra a sincronização ao perfil ativo agora. Recusa (false) no Modo
     * visita e no perfil infantil: o dono liga isso no perfil dele.
     */
    fun definirOpcoes(ctx: Context, s: Servico, o: Opcoes): Boolean {
        if (bloqueado(ctx)) return false
        val e = prefs(ctx).edit().putString("opcoes_${s.id}", SincronizarFilmes.opcoesParaJson(o).toString())
        val antigo = prefs(ctx).getString("perfil", null)
        if (antigo == null || nenhumaLigada(ctx)) {
            val novo = Perfil.chaveDados(ctx)
            // Outro perfil assume a sincronização: as fotos (base dos Interesses, atividades, cópia do Simkl) eram do
            // outro. Mantê-las faria a mescla de três vias "tirar lá fora" o que só este perfil não tem.
            if (antigo != null && antigo != novo) listOf(Servico.TRAKT, Servico.SIMKL).forEach { esquecer(ctx, it) }
            e.putString("perfil", novo)
        }
        e.apply()
        return true
    }

    private fun nenhumaLigada(ctx: Context) = listOf(Servico.TRAKT, Servico.SIMKL).none { opcoes(ctx, it).let { o -> o.interesses || o.assistidos || o.continuar || o.contar } }

    fun listas(ctx: Context): List<ListaFora> =
        SincronizarFilmes.listasDeJson(runCatching { JSONArray(prefs(ctx).getString("listas", null) ?: "[]") }.getOrNull())

    fun definirListas(ctx: Context, l: List<ListaFora>) {
        prefs(ctx).edit().putString("listas", SincronizarFilmes.listasParaJson(l.take(SincronizarFilmes.MAX_LISTAS)).toString()).apply()
    }

    /** Modo visita ou perfil infantil: nada da conta do dono entra nem sai. */
    fun bloqueado(ctx: Context): Boolean =
        ModoVisita.ativo(ctx) || runCatching { PerfilCriancaTv.eCrianca(ctx, Perfil.ativa(ctx)) }.getOrDefault(false)

    /** O perfil ativo é o dono da sincronização? */
    fun perfilCerto(ctx: Context): Boolean =
        prefs(ctx).getString("perfil", null)?.let { it == Perfil.chaveDados(ctx) } ?: true

    /** Esta conta sincroniza agora (conectada, alguma opção ligada, perfil certo, sem visita nem infantil)? */
    fun ativo(ctx: Context, s: Servico): Boolean {
        val o = opcoes(ctx, s)
        return (o.interesses || o.assistidos || o.continuar || o.contar) && ServicosContas.conta(ctx, s) != null &&
            ServicosContas.disponivel(s) && !bloqueado(ctx) && perfilCerto(ctx)
    }

    /** Mandar "visto"/"vendo agora" é permitido agora? */
    fun podeContar(ctx: Context, s: Servico): Boolean = ativo(ctx, s) && SincronizarFilmes.podeEnviar(opcoes(ctx, s).contar, ModoVisita.ativo(ctx), bloqueado(ctx))

    // ================================================================ estado (o arquivo que refaz sozinho)

    private fun lerEstado(ctx: Context): JSONObject = synchronized(trava) {
        runCatching { JSONObject(File(PlayerArmazem.pasta(ctx), ARQ_ESTADO).readText()) }.getOrDefault(JSONObject())
    }

    private fun gravarEstado(ctx: Context, o: JSONObject) = synchronized(trava) {
        runCatching {
            val arq = File(PlayerArmazem.pasta(ctx), ARQ_ESTADO)
            val tmp = File(arq.path + ".tmp"); tmp.writeText(o.toString()); tmp.renameTo(arq)
        }
    }

    private fun mudarEstado(ctx: Context, f: (JSONObject) -> Unit) = synchronized(trava) { val o = lerEstado(ctx); f(o); gravarEstado(ctx, o) }

    private fun mapa(o: JSONObject?): Map<String, String> = o?.keys()?.asSequence()?.associateWith { o.optString(it) }.orEmpty()
    private fun conjunto(a: JSONArray?): Set<Int>? = a?.let { (0 until it.length()).mapNotNull { i -> it.optInt(i, 0).takeIf { v -> v > 0 } }.toSet() }
    private fun arranjo(s: Set<Int>) = JSONArray().apply { s.sorted().forEach { put(it) } }

    fun ultima(ctx: Context, s: Servico): Long = lerEstado(ctx).optLong("ultima_${s.id}", 0)

    /** Aviso para a tela da conta ("Sua lista no Trakt está cheia"), ou null. */
    fun aviso(ctx: Context, s: Servico): String? = lerEstado(ctx).optString("aviso_${s.id}").takeIf { it.isNotBlank() }

    /** Esquece tudo da conta (ao sair dela): a próxima vez começa do zero, só unindo. */
    fun esquecer(ctx: Context, s: Servico) = mudarEstado(ctx) { o ->
        val tira = o.keys().asSequence().filter { it.endsWith("_${s.id}") || it.contains("_${s.id}_") }.toList()
        tira.forEach { o.remove(it) }
        if (s == Servico.SIMKL) arquivoBibliotecaSimkl(ctx).delete()
    }

    // ================================================================ rede

    private class Http(val codigo: Int, val corpo: String)

    /** Uma chamada com o token da conta; 401 renova uma vez (só no processo do launcher) e tenta de novo. */
    private fun chamar(ctx: Context, s: Servico, caminho: String, corpo: String? = null, extra: String = "", renovar: Boolean = true): Http {
        val c = ServicosContas.conta(ctx, s) ?: return Http(401, "")
        val k = ServicosContas.chavesApp()
        val (url, cab) = if (s == Servico.TRAKT) "${ServicosFilmes.TRAKT_API}$caminho" + (if (extra.isEmpty()) "" else "?$extra") to
            ServicosFilmes.traktCabecalhos(k.traktId, versao, c.acesso)
        else ServicosFilmes.simklUrl(caminho, k.simklId, versao, extra) to ServicosFilmes.simklCabecalhos(k.simklId, versao, c.acesso)
        var r = ServicosContas.pedir(url, cab, corpo).let { Http(it.codigo, it.corpo) }
        // Simkl: 400 {"error":"rate_limit"} numa escrita NÃO é cota: é a trava de 20 s por usuário (outra escrita nossa,
        // por exemplo o scrobble do player, ainda corre). A doc manda esperar uns segundos e tentar UMA vez.
        if (s == Servico.SIMKL && corpo != null && r.codigo == 400 && r.corpo.contains("rate_limit", ignoreCase = true)) {
            Thread.sleep(5_000)
            r = ServicosContas.pedir(url, cab, corpo).let { Http(it.codigo, it.corpo) }
        }
        if (r.codigo == 401 && renovar && processoPrincipal(ctx) && ServicosContas.renovar(ctx, s, forcar = true) != null)
            return chamar(ctx, s, caminho, corpo, extra, renovar = false)
        if (r.codigo == 429) Log.i(TAG, "${s.id}: devagar (429)")
        // A doc do Trakt: POST no máximo 1 por segundo. As escritas daqui são poucas, mas em sequência.
        if (corpo != null) Thread.sleep(1_100)
        return r
    }

    /** Renovar token só no processo do launcher (o player é outro processo; dois renovando derrubam um ao outro). */
    private fun processoPrincipal(ctx: Context): Boolean = runCatching {
        val nome = if (android.os.Build.VERSION.SDK_INT >= 28) android.app.Application.getProcessName() else null
        nome == null || !nome.contains(':')
    }.getOrDefault(false)

    // ================================================================ sincronizar (Filmes e Séries ao abrir)

    /** O que mudou aqui (para a tela recarregar). */
    data class Mudou(val interesses: Boolean, val posicoes: Boolean)

    /**
     * Sincroniza as contas ligadas para [tipo] (filmes ou séries), com o [catalogo] da tela (chave, id TMDB e nome+ano).
     * Bloqueia (rede): fora da thread da tela. Devolve o que mudou no disco daqui.
     */
    fun sincronizar(ctx: Context, tipo: Tipo, catalogo: List<Titulo>, forcar: Boolean = false): Mudou {
        var interesses = false; var posicoes = false
        for (s in listOf(Servico.TRAKT, Servico.SIMKL)) {
            if (!ativo(ctx, s)) continue
            val chaveUltima = "ultima_${s.id}_${tipo.name}"
            if (!SincronizarFilmes.deveSincronizar(lerEstado(ctx).optLong(chaveUltima, 0), System.currentTimeMillis(), forcar)) continue
            runCatching {
                val m = if (s == Servico.TRAKT) trakt(ctx, tipo, catalogo) else simkl(ctx, tipo, catalogo, forcar)
                interesses = interesses || m.interesses; posicoes = posicoes || m.posicoes
                val agora = System.currentTimeMillis()
                mudarEstado(ctx) { it.put(chaveUltima, agora).put("ultima_${s.id}", agora) }
            }.onFailure { Log.w(TAG, "${s.id}: sincronização falhou: ${it.javaClass.simpleName}") }
        }
        return Mudou(interesses, posicoes)
    }

    /** Ids TMDB de cada chave do catálogo, e o caminho de volta. */
    private class Indice(private val catalogo: List<Titulo>) {
        val porChave = HashMap<String, Int>(); val porId = HashMap<Int, String>()
        init { for (t in catalogo) t.tmdb?.let { porChave[t.chave] = it; porId.putIfAbsent(it, t.chave) } }

        /** Os itens de fora ensinam o id aos títulos do catálogo que vieram sem (casando por nome + ano). */
        fun ensinar(remoto: List<Item>) {
            for ((ch, id) in SincronizarFilmes.idsPorNome(catalogo, remoto)) {
                if (porChave.putIfAbsent(ch, id) == null) porId.putIfAbsent(id, ch)
            }
        }
    }

    private fun tmdbDe(ctx: Context, chave: String, ind: Indice): Int? = ind.porChave[chave] ?: InfoExternaCache.guardado(ctx, chave)?.tmdb

    // ---------------------------------------------------------------- Trakt

    private fun trakt(ctx: Context, tipo: Tipo, catalogo: List<Titulo>): Mudou {
        val s = Servico.TRAKT
        val o = opcoes(ctx, s)
        val r = chamar(ctx, s, "/sync/last_activities")
        if (r.codigo !in 200..299) error("last_activities ${r.codigo}")
        val ativ = SincronizarFilmes.traktLerAtividades(r.corpo)
        val chaveAtiv = "ativ_${s.id}_${tipo.name}"
        val antes = lerEstado(ctx).optJSONObject(chaveAtiv)?.let { mapa(it) }
        val ind = Indice(catalogo)
        val grupo = if (tipo == Tipo.FILME) "movies" else "shows"
        var mi = false; var mp = false
        val enviar = SincronizarFilmes.podeEnviar(true, ModoVisita.ativo(ctx), bloqueado(ctx))

        if (o.interesses) {
            val wl = chamar(ctx, s, "/sync/watchlist/$grupo")
            if (wl.codigo in 200..299) {
                val remoto = SincronizarFilmes.traktLerItens(wl.corpo)?.filter { it.tipo == tipo }
                if (remoto != null) mi = aplicarInteresses(ctx, s, tipo, ind, remoto, enviar) { add, tirar ->
                    var addOk = emptyList<Item>(); var tirarOk = emptyList<Item>()
                    if (add.isNotEmpty()) {
                        val lote = add.take(MAX_POR_ENVIO)
                        val a = chamar(ctx, s, "/sync/watchlist", SincronizarFilmes.corpoItens(lote))
                        if (a.codigo == 420) avisar(ctx, s, ctx.getString(R.string.sync_trakt_cheia)) else if (ok(a)) { avisar(ctx, s, null); addOk = lote }
                    }
                    if (tirar.isNotEmpty()) {
                        val lote = tirar.take(MAX_POR_ENVIO)
                        if (ok(chamar(ctx, s, "/sync/watchlist/remove", SincronizarFilmes.corpoItens(lote)))) tirarOk = lote
                    }
                    addOk to tirarOk
                }
            }
        }
        if (o.assistidos && (SincronizarFilmes.mudou(antes, ativ, "movies.watched_at", "episodes.watched_at") || o.contar)) {
            val w = chamar(ctx, s, "/sync/watched/$grupo")
            if (w.codigo in 200..299) {
                val vistos = SincronizarFilmes.traktLerVistos(w.corpo, tipo)
                if (vistos != null) {
                    if (tipo == Tipo.FILME) mp = aplicarVistosFilmes(ctx, s, ind, vistos).first || mp
                    else guardarEpisodiosVistos(ctx, s, vistos)
                }
            }
        }
        if (o.continuar && SincronizarFilmes.mudou(antes, ativ, "movies.paused_at", "episodes.paused_at")) {
            val p = chamar(ctx, s, "/sync/playback/" + if (tipo == Tipo.FILME) "movies" else "episodes")
            if (p.codigo in 200..299) mp = aplicarPausas(ctx, s, tipo, ind, SincronizarFilmes.traktLerPausas(p.corpo)) || mp
        }
        mudarEstado(ctx) { it.put(chaveAtiv, JSONObject(ativ as Map<*, *>)) }
        return Mudou(mi, mp)
    }

    // ---------------------------------------------------------------- Simkl

    /**
     * O Simkl em duas partes, como pede o guia /sync deles (api.simkl.org/guides/sync):
     *  1. rede ([atualizarBibliotecaSimkl]): uma cópia local da biblioteca, posta em dia por /sync/activities + só o
     *     que mudou (date_from). Filmes e Séries usam a mesma cópia: abrir os dois em seguida faz UMA ida ao Simkl;
     *  2. aqui, sem rede para ler: a mescla dos Interesses, os vistos e as pausas saem da cópia. Só as escritas
     *     (pôr/tirar da lista, marcar visto) vão lá, e já entram na cópia.
     */
    private fun simkl(ctx: Context, tipo: Tipo, catalogo: List<Titulo>, forcar: Boolean): Mudou {
        val s = Servico.SIMKL
        val o = opcoes(ctx, s)
        atualizarBibliotecaSimkl(ctx, s, o, forcar)
        var bib = lerBibliotecaSimkl(ctx) ?: return Mudou(false, false)
        val antesDasEscritas = bib
        val ind = Indice(catalogo)
        var mi = false; var mp = false; var tirou = false
        val enviar = SincronizarFilmes.podeEnviar(true, ModoVisita.ativo(ctx), bloqueado(ctx))
        val doTipo = bib.values.filter { it.item.tipo == tipo }
        if (o.interesses) {
            val remoto = doTipo.filter { it.status == "plantowatch" }.map { it.item }
            mi = aplicarInteresses(ctx, s, tipo, ind, remoto, enviar) { add, tirar ->
                val agora = System.currentTimeMillis()
                var addOk = emptyList<Item>(); var tirarOk = emptyList<Item>()
                val loteAdd = add.take(MAX_POR_ENVIO)
                if (loteAdd.isNotEmpty() && ok(chamar(ctx, s, "/sync/add-to-list", SincronizarFilmes.corpoItens(loteAdd, "plantowatch")))) {
                    loteAdd.forEach { bib = SincronizarFilmes.simklMarcar(bib, it, "plantowatch", agora) }
                    addOk = loteAdd
                }
                // O Simkl não tem "tirar da lista para ver" separado: /sync/history/remove tira o título da lista.
                val loteTirar = tirar.take(MAX_POR_ENVIO)
                if (loteTirar.isNotEmpty() && ok(chamar(ctx, s, "/sync/history/remove", SincronizarFilmes.corpoItens(loteTirar)))) {
                    loteTirar.forEach { bib = SincronizarFilmes.simklMarcar(bib, it, null, agora) }
                    tirarOk = loteTirar; tirou = true
                }
                addOk to tirarOk
            }
        }
        if (o.assistidos && tipo == Tipo.FILME) {
            val vistos = doTipo.filter { it.status == "completed" }.map { SincronizarFilmes.Visto(it.item, it.quandoMs) }
            val r = aplicarVistosFilmes(ctx, s, ind, vistos)
            mp = r.first || mp
            r.second.forEach { (item, q) -> bib = SincronizarFilmes.simklMarcar(bib, item, "completed", q) }
        }
        if (o.continuar) {
            val bruto = lerEstado(ctx).optString("pausas_${s.id}")
            val pausas = SincronizarFilmes.simklLerPausas(bruto).filter { it.item.tipo == tipo }
            mp = aplicarPausas(ctx, s, tipo, ind, pausas) || mp
        }
        if (bib !== antesDasEscritas) gravarBibliotecaSimkl(ctx, bib)
        // Tiramos algo: a foto passa a ter os removed_from_list novos (sem isso a próxima vez baixaria a lista de ids
        // por causa da nossa própria remoção). Ler /sync/activities não passa pela trava de escrita do Simkl.
        if (tirou) {
            val r = chamar(ctx, s, "/sync/activities")
            if (ok(r)) mudarEstado(ctx) { e ->
                val salva = mapa(e.optJSONObject("ativ_${s.id}"))
                if (salva.isNotEmpty()) e.put("ativ_${s.id}", JSONObject(SincronizarFilmes.simklFotoAposTirar(salva, SincronizarFilmes.simklLerAtividades(r.corpo)) as Map<*, *>))
            }
        }
        return Mudou(mi, mp)
    }

    private fun ok(r: Http) = r.codigo in 200..299

    private fun arquivoBibliotecaSimkl(ctx: Context) = File(PlayerArmazem.pasta(ctx), ARQ_SIMKL)

    /** A cópia local da biblioteca do Simkl; null = ainda não houve a fase 1. */
    /** Ids do Simkl da biblioteca guardada da pessoa, por id TMDB (vazio sem conta ou sem cópia). Sem rede. */
    fun idsSimklDaBiblioteca(ctx: Context): Map<String, Int> =
        lerBibliotecaSimkl(ctx)?.let { SincronizarFilmes.simklIdsPorTmdb(it.values) } ?: emptyMap()

    private fun lerBibliotecaSimkl(ctx: Context): Map<String, SincronizarFilmes.EntradaSimkl>? = synchronized(trava) {
        val f = arquivoBibliotecaSimkl(ctx)
        if (!f.exists()) null else runCatching { SincronizarFilmes.simklBibliotecaDeJson(JSONArray(f.readText())) }.getOrNull()
    }

    private fun gravarBibliotecaSimkl(ctx: Context, b: Map<String, SincronizarFilmes.EntradaSimkl>) = synchronized(trava) {
        runCatching {
            val f = arquivoBibliotecaSimkl(ctx)
            val tmp = File(f.path + ".tmp"); tmp.writeText(SincronizarFilmes.simklBibliotecaParaJson(b).toString()); tmp.renameTo(f)
        }
    }

    /**
     * Põe a cópia local em dia. No máximo uma ida a cada 20 min (Filmes e Séries juntos; "Sincronizar agora" fura, mas
     * não duas vezes no mesmo minuto). A foto de /sync/activities é lida ANTES de buscar e só é salva depois que
     * tudo deu certo (se algo falhar, a próxima vez pede o mesmo date_from de novo).
     */
    private fun atualizarBibliotecaSimkl(ctx: Context, s: Servico, o: Opcoes, forcar: Boolean) {
        val est = lerEstado(ctx)
        val agoraMs = System.currentTimeMillis()
        val ultima = est.optLong("rede_${s.id}", 0)
        val espera = if (forcar) 60_000L else SincronizarFilmes.INTERVALO_MS
        if (ultima in 1..agoraMs && agoraMs - ultima < espera && lerBibliotecaSimkl(ctx) != null) return
        val antes = est.optJSONObject("ativ_${s.id}")?.let { mapa(it) }?.takeIf { it.isNotEmpty() }
        val r = chamar(ctx, s, "/sync/activities")
        if (!ok(r)) error("activities ${r.codigo}")
        val ativ = SincronizarFilmes.simklLerAtividades(r.corpo)
        // Sem o "all" a resposta é ilegível: parar aqui (seguir faria a fase 1 inteira de novo).
        if (ativ["all"] == null) error("activities ilegível")
        var bib = lerBibliotecaSimkl(ctx)
        val plano = SincronizarFilmes.simklPlano(antes, ativ, bib != null, o.continuar, est.has("pausas_${s.id}"))
        if (plano.completo) {
            // Fase 1: a biblioteca inteira, um tipo de cada vez, em sequência (nunca em paralelo).
            val tudo = ArrayList<SincronizarFilmes.EntradaSimkl>()
            for (t in listOf("movies", "shows", "anime")) tudo += lerTudoSimkl(ctx, s, "/sync/all-items/$t") ?: error("all-items $t")
            bib = SincronizarFilmes.simklMesclar(emptyMap(), tudo)
        } else {
            var b = bib ?: emptyMap()
            if (plano.conferirTirados) {
                val ids = chamar(ctx, s, "/sync/all-items", extra = "extended=simkl_ids_only")
                b = SincronizarFilmes.simklTirarSumidos(b, ids.takeIf { ok(it) }?.let { SincronizarFilmes.simklLerIds(it.corpo) } ?: error("ids ${ids.codigo}"))
            }
            plano.desde?.let { d ->
                // Fase 2: os três tipos numa chamada só, só o que mudou desde a foto.
                b = SincronizarFilmes.simklMesclar(b, lerTudoSimkl(ctx, s, "/sync/all-items", "date_from=" + URLEncoder.encode(d, "UTF-8")) ?: error("delta"))
            }
            bib = b
        }
        var pausas: String? = null
        if (plano.pausas) {
            val p = chamar(ctx, s, "/sync/playback")
            if (ok(p)) pausas = p.corpo
        }
        if (plano.completo || plano.desde != null || plano.conferirTirados) gravarBibliotecaSimkl(ctx, bib)
        mudarEstado(ctx) { e ->
            e.put("ativ_${s.id}", JSONObject(ativ as Map<*, *>)).put("rede_${s.id}", agoraMs)
            pausas?.let { e.put("pausas_${s.id}", it) }
            // Fotos por tipo do jeito antigo (antes de 18/11/2026): não servem mais.
            e.remove("ativ_${s.id}_${Tipo.FILME.name}"); e.remove("ativ_${s.id}_${Tipo.SERIE.name}")
        }
    }

    private fun lerTudoSimkl(ctx: Context, s: Servico, caminho: String, extra: String = ""): List<SincronizarFilmes.EntradaSimkl>? {
        val r = chamar(ctx, s, caminho, extra = extra)
        return if (ok(r)) SincronizarFilmes.simklLerTodos(r.corpo) else null
    }

    // ---------------------------------------------------------------- aplicar aqui

    private fun idsLocaisInteresses(ctx: Context, tipo: Tipo, ind: Indice): Map<Int, String> {
        val r = LinkedHashMap<Int, String>()
        for (ch in PlayerArmazem.interesses(ctx, tipo == Tipo.SERIE)) tmdbDe(ctx, ch, ind)?.let { r.putIfAbsent(it, ch) }
        return r
    }

    /**
     * Interesses <-> watchlist pela mescla de três vias. [mandar] recebe o que pôr e tirar lá fora (já como itens com o
     * id TMDB). Devolve se os Interesses daqui mudaram.
     */
    private fun aplicarInteresses(ctx: Context, s: Servico, tipo: Tipo, ind: Indice, remoto: List<Item>, enviar: Boolean,
                                  mandar: (List<Item>, List<Item>) -> Pair<List<Item>, List<Item>>): Boolean {
        ind.ensinar(remoto)
        val local = idsLocaisInteresses(ctx, tipo, ind)
        val remotoIds = remoto.mapNotNull { it.ids.tmdb }.toSet()
        val chaveBase = "base_${s.id}_${tipo.name}"
        val base = conjunto(lerEstado(ctx).optJSONArray(chaveBase))
        val universo = ind.porId.keys + local.keys
        val m = SincronizarFilmes.mesclarInteresses(local.keys, remotoIds, base, enviar, universo)
        var baseNova = m.base
        if (enviar) {
            val porId = remoto.associateBy { it.ids.tmdb }
            fun item(id: Int) = porId[id] ?: Item(tipo, SincronizarFilmes.Ids(tmdb = id))
            val (addOk, tirarOk) = mandar(m.adicionarFora.map(::item), m.tirarFora.map(::item))
            baseNova = SincronizarFilmes.baseAposEnvio(m, addOk.mapNotNull { it.ids.tmdb }.toSet(), tirarOk.mapNotNull { it.ids.tmdb }.toSet())
        }
        // Os Interesses daqui: tira os que saíram, põe os novos (o mais novo primeiro, como quando se marca na TV).
        val atuais = PlayerArmazem.interesses(ctx, tipo == Tipo.SERIE)
        val tirar = (local.keys - m.local).mapNotNull { local[it] }.toSet()
        val por = (m.local - local.keys).mapNotNull { ind.porId[it] }.filter { it !in atuais }
        val novos = LinkedHashSet<String>().apply { addAll(por); addAll(atuais - tirar) }
        val mudou = novos != atuais
        if (mudou) PlayerArmazem.definirInteresses(ctx, tipo == Tipo.SERIE, LinkedHashSet(novos.take(Vod.MAX_INTERESSES)))
        val finais = idsLocaisInteresses(ctx, tipo, ind).keys
        mudarEstado(ctx) { it.put(chaveBase, arranjo(baseNova)).put("local_${s.id}_${tipo.name}", arranjo(finais)) }
        return mudou
    }

    /**
     * Filmes vistos lá fora: marca concluído aqui (união); com "contar" ligado, sobe os concluídos daqui. Devolve se
     * mudou algo aqui e o que subiu (com a data), para a cópia local do Simkl já saber.
     */
    private fun aplicarVistosFilmes(ctx: Context, s: Servico, ind: Indice, vistos: List<SincronizarFilmes.Visto>): Pair<Boolean, List<Pair<Item, Long>>> {
        ind.ensinar(vistos.map { it.item })
        val pos = PlayerArmazem.posicoes(ctx)
        val concluidosAqui = HashMap<Int, Pair<String, Long>>()
        for ((ch, p) in pos) if (Vod.finalizado(p)) ind.porChave[ch]?.let { concluidosAqui[it] = ch to p.quando }
        val remoto = vistos.mapNotNull { it.item.ids.tmdb }.toSet()
        var mudou = false
        val trazer = SincronizarFilmes.vistosParaTrazer(remoto, concluidosAqui.keys)
        for (v in vistos) {
            val id = v.item.ids.tmdb ?: continue
            if (id !in trazer) continue
            val ch = ind.porId[id] ?: continue
            if (Vod.finalizado(pos[ch])) continue
            PlayerArmazem.definirPosicoes(ctx, Vod.marcarAssistido(PlayerArmazem.posicoes(ctx), ch, v.quandoMs.takeIf { it > 0 } ?: System.currentTimeMillis()))
            mudou = true
        }
        if (podeContar(ctx, s)) {
            val subir = SincronizarFilmes.vistosParaEnviar(concluidosAqui.keys, remoto).take(MAX_POR_ENVIO)
                .map { Item(Tipo.FILME, SincronizarFilmes.Ids(tmdb = it)) to (concluidosAqui[it]?.second ?: System.currentTimeMillis()) }
            if (subir.isNotEmpty() && ok(chamar(ctx, s, "/sync/history", SincronizarFilmes.corpoHistorico(subir)))) return mudou to subir
        }
        return mudou to emptyList()
    }

    /** Episódios vistos lá fora, por série (id TMDB): a ficha da série aplica quando abre (só ela conhece os episódios). */
    private fun guardarEpisodiosVistos(ctx: Context, s: Servico, vistos: List<SincronizarFilmes.Visto>) {
        val o = JSONObject()
        for (v in vistos) {
            val id = v.item.ids.tmdb ?: continue
            if (v.eps.isEmpty()) continue
            o.put(id.toString(), JSONArray().apply { for (e in v.eps) put("${e.temporada}x${e.numero}") })
        }
        mudarEstado(ctx) { it.put("eps_${s.id}", o) }
    }

    /** Os episódios de uma série (id TMDB) vistos em qualquer conta ligada. */
    fun episodiosVistosFora(ctx: Context, tmdb: Int): Set<Ep> {
        val est = lerEstado(ctx)
        val r = HashSet<Ep>()
        for (s in listOf(Servico.TRAKT, Servico.SIMKL)) {
            if (!ativo(ctx, s) || !opcoes(ctx, s).assistidos) continue
            val a = est.optJSONObject("eps_${s.id}")?.optJSONArray(tmdb.toString()) ?: continue
            for (i in 0 until a.length()) a.optString(i).split('x').takeIf { it.size == 2 }?.let { p ->
                val t = p[0].toIntOrNull(); val n = p[1].toIntOrNull(); if (t != null && n != null) r += Ep(t, n)
            }
        }
        return r
    }

    /**
     * Pausas de outro aparelho. Com a duração daqui já conhecida (vídeo começado aqui antes), vira a posição na hora;
     * senão fica guardada e o player aplica ao abrir ([pausaDeFora]).
     */
    private fun aplicarPausas(ctx: Context, s: Servico, tipo: Tipo, ind: Indice, pausas: List<SincronizarFilmes.Pausa>): Boolean {
        ind.ensinar(pausas.map { it.item })
        val guardar = JSONObject()
        var mudou = false
        for (p in pausas) {
            val id = p.item.ids.tmdb ?: continue
            val k = if (p.ep == null) "$id" else "$id:${p.ep.temporada}x${p.ep.numero}"
            guardar.put(k, JSONObject().put("p", p.progresso.toDouble()).put("q", p.quandoMs))
            if (tipo != Tipo.FILME) continue
            val ch = ind.porId[id] ?: continue
            val local = PlayerArmazem.posicoes(ctx)[ch]
            if (local == null || local.duracao <= 0 || !SincronizarFilmes.pausaVale(p, local, Vod.finalizado(local))) continue
            PlayerArmazem.definirPosicao(ctx, ch, Posicao(SincronizarFilmes.posicaoDaPausa(p.progresso, local.duracao), local.duracao, p.quandoMs))
            mudou = true
        }
        mudarEstado(ctx) { it.put("pausas_${s.id}_${tipo.name}", guardar) }
        return mudou
    }

    /** Uma pausa de fora para este vídeo (filme: [ep] null), se for mais nova que a posição daqui. Para o player. */
    fun pausaDeFora(ctx: Context, tmdb: Int, ep: Ep?, local: Posicao?): Float? {
        val est = lerEstado(ctx)
        val k = if (ep == null) "$tmdb" else "$tmdb:${ep.temporada}x${ep.numero}"
        val tipo = if (ep == null) Tipo.FILME else Tipo.SERIE
        var melhor: SincronizarFilmes.Pausa? = null
        for (s in listOf(Servico.TRAKT, Servico.SIMKL)) {
            if (!ativo(ctx, s) || !opcoes(ctx, s).continuar) continue
            val o = est.optJSONObject("pausas_${s.id}_${tipo.name}")?.optJSONObject(k) ?: continue
            val p = SincronizarFilmes.Pausa(Item(tipo, SincronizarFilmes.Ids(tmdb = tmdb)), ep, o.optDouble("p", 0.0).toFloat(), o.optLong("q", 0))
            if (melhor == null || p.quandoMs > melhor.quandoMs) melhor = p
        }
        return melhor?.takeIf { SincronizarFilmes.pausaVale(it, local, Vod.finalizado(local, ep != null)) }?.progresso
    }

    private fun avisar(ctx: Context, s: Servico, texto: String?) = mudarEstado(ctx) { if (texto == null) it.remove("aviso_${s.id}") else it.put("aviso_${s.id}", texto) }

    // ================================================================ "contar o que eu assisto" (player)

    /** Momento do vídeo, para o scrobble. */
    enum class Momento { COMECOU, PAUSOU, PAROU }

    /**
     * Do player (outro processo; fora da thread da tela): "vendo agora", pausa e fim. No fim, a NOSSA régua decide
     * (SincronizarFilmes.decidirFim). Sem "contar" ligado, ou em visita/infantil, não faz nada.
     */
    fun assistindo(ctx: Context, item: Item, ep: Ep?, momento: Momento, ms: Long, duracao: Long, concluido: Boolean) {
        for (s in listOf(Servico.TRAKT, Servico.SIMKL)) {
            if (!podeContar(ctx, s)) continue
            runCatching {
                val prog = SincronizarFilmes.progresso(ms, duracao)
                when (momento) {
                    Momento.COMECOU -> if (prog < 99f) chamar(ctx, s, "/scrobble/start", SincronizarFilmes.corpoScrobble(item, ep, prog.coerceAtLeast(0.1f)))
                    Momento.PAUSOU -> if (prog >= 1f && !concluido) chamar(ctx, s, "/scrobble/pause", SincronizarFilmes.corpoScrobble(item, ep, prog.coerceAtMost(99f)))
                    Momento.PAROU -> {
                        val (acao, p) = SincronizarFilmes.decidirFim(concluido, prog)
                        when (acao) {
                            SincronizarFilmes.AcaoFim.PARAR -> chamar(ctx, s, "/scrobble/stop", SincronizarFilmes.corpoScrobble(item, ep, p))
                            SincronizarFilmes.AcaoFim.PAUSAR -> chamar(ctx, s, "/scrobble/pause", SincronizarFilmes.corpoScrobble(item, ep, p))
                            SincronizarFilmes.AcaoFim.HISTORICO -> chamar(ctx, s, "/sync/history", if (ep == null)
                                SincronizarFilmes.corpoHistorico(listOf(item to System.currentTimeMillis()))
                                else SincronizarFilmes.corpoHistorico(emptyList(), listOf(Triple(item, ep, System.currentTimeMillis()))))
                            SincronizarFilmes.AcaoFim.NADA -> Unit
                        }
                    }
                }
            }.onFailure { Log.w(TAG, "${s.id}: scrobble falhou: ${it.javaClass.simpleName}") }
        }
    }

    /** "Sincronizar agora" (tela da TV e painel): filmes e séries com os catálogos guardados, sem baixar a lista de IPTV. Bloqueia (rede). */
    fun sincronizarTudo(ctx: Context) {
        fun titulo(chave: String, tmdb: Int?, nome: String, ano: Int?) =
            Titulo(chave, tmdb ?: InfoExternaCache.guardado(ctx, chave)?.tmdb, InfoExterna.chaveNomeAno(Vod.nomeLimpo(nome), ano))
        sincronizar(ctx, Tipo.FILME, PlayerCarga.filmes(ctx, soCache = true).map { titulo(it.chave, it.tmdb, it.nome, it.ano) }, forcar = true)
        sincronizar(ctx, Tipo.SERIE, PlayerCarga.series(ctx, soCache = true).map { titulo(it.chave, it.tmdb, it.nome, it.ano) }, forcar = true)
    }

    // ================================================================ listas de fora (categorias em LISTAS)

    /** As listas que o dono pode escolher: as dele e as curtidas no Trakt; as dele, curtidas e as mais populares no MDBList. Bloqueia (rede). */
    fun listasDisponiveis(ctx: Context): List<ListaFora> {
        val r = ArrayList<ListaFora>()
        if (ServicosContas.conta(ctx, Servico.TRAKT) != null && ServicosContas.disponivel(Servico.TRAKT)) runCatching {
            chamar(ctx, Servico.TRAKT, "/users/me/lists").takeIf { it.codigo in 200..299 }?.let { r += SincronizarFilmes.traktLerListas(it.corpo) }
            chamar(ctx, Servico.TRAKT, "/users/me/likes/lists", extra = "limit=50").takeIf { it.codigo in 200..299 }?.let { r += SincronizarFilmes.traktLerCurtidas(it.corpo) }
        }
        ServicosContas.chaveMdblist(ctx)?.let { k ->
            for (c in listOf("/lists/user", "/lists/liked", "/lists/top")) runCatching {
                val x = ServicosContas.pedir("${ServicosFilmes.MDBLIST_API}$c?apikey=${java.net.URLEncoder.encode(k, "UTF-8")}" +
                    (if (c == "/lists/top") "&limit=30" else ""), mapOf("User-Agent" to ServicosFilmes.agente(versao)))
                if (x.codigo in 200..299) r += SincronizarFilmes.mdblistLerListas(x.corpo)
            }
        }
        return r.distinctBy { it.chave }
    }

    private fun arquivoLista(ctx: Context, l: ListaFora) = File(File(PlayerArmazem.pasta(ctx), PASTA_LISTAS).apply { mkdirs() }, "${l.servico}_${l.id}.json")

    /** Os itens de uma lista escolhida: do disco se tiver menos de 12 h; senão da rede (bloqueia). Vazio se falhar. */
    fun itens(ctx: Context, l: ListaFora, forcar: Boolean = false): List<Item> {
        val arq = arquivoLista(ctx, l)
        val doDisco = runCatching { JSONObject(arq.readText()) }.getOrNull()
        val emDia = doDisco != null && System.currentTimeMillis() - doDisco.optLong("em") in 0 until VALIDADE_LISTA_MS
        if (emDia && !forcar) return SincronizarFilmes.itensDeJson(doDisco!!.optJSONArray("itens"))
        val novos = runCatching { buscarItens(ctx, l) }.getOrNull()
        if (novos == null) return SincronizarFilmes.itensDeJson(doDisco?.optJSONArray("itens"))   // sem rede: o de antes
        runCatching {
            val tmp = File(arq.path + ".tmp")
            tmp.writeText(JSONObject().put("em", System.currentTimeMillis()).put("itens", SincronizarFilmes.itensParaJson(novos)).toString())
            tmp.renameTo(arq)
        }
        return novos
    }

    /** Só os do disco (sem rede): para a tela desenhar já. */
    fun itensGuardados(ctx: Context, l: ListaFora): List<Item> =
        runCatching { SincronizarFilmes.itensDeJson(JSONObject(arquivoLista(ctx, l).readText()).optJSONArray("itens")) }.getOrDefault(emptyList())

    private fun buscarItens(ctx: Context, l: ListaFora): List<Item>? = when (l.servico) {
        "trakt" -> {
            val dono = l.dono.takeIf { it.matches(Regex("[A-Za-z0-9_.-]{1,60}")) } ?: "me"
            chamar(ctx, Servico.TRAKT, "/users/$dono/lists/${l.id}/items/movie,show").takeIf { it.codigo in 200..299 }
                ?.let { SincronizarFilmes.traktLerItens(it.corpo) }   // ilegível: null, fica a lista guardada
        }
        "mdblist" -> ServicosContas.chaveMdblist(ctx)?.let { k ->
            ServicosContas.pedir("${ServicosFilmes.MDBLIST_API}/lists/${l.id}/items?limit=1000&apikey=${java.net.URLEncoder.encode(k, "UTF-8")}",
                mapOf("User-Agent" to ServicosFilmes.agente(versao))).takeIf { it.codigo in 200..299 }?.let { SincronizarFilmes.mdblistLerItens(it.corpo) }
        }
        else -> null
    }

    /** Apagar os arquivos de listas que não estão mais escolhidas. */
    fun limparListas(ctx: Context) {
        val manter = listas(ctx).map { "${it.servico}_${it.id}.json" }.toSet()
        File(PlayerArmazem.pasta(ctx), PASTA_LISTAS).listFiles()?.forEach { if (it.name !in manter) it.delete() }
    }
}
