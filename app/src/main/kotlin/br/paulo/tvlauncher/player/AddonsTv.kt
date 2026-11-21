package br.paulo.tvlauncher.player

import android.content.Context
import br.paulo.tvlauncher.BuildConfig
import br.paulo.tvlauncher.Http
import br.paulo.tvlauncher.PlayPolicy
import br.paulo.tvlauncher.R
import java.net.HttpURLConnection
import java.net.URL

/**
 * A parte Android dos addons (16/11/2026; regras e protocolo em [AddonsProtocolo]): onde a lista mora, como se fala com
 * um addon pela rede e as perguntas que as telas fazem (catálogo, ficha, fontes). Tudo aqui bloqueia: chamar fora da
 * thread da tela.
 *
 * A lista fica no prefs "addons" (vai no backup completo: é configuração), com MODE_MULTI_PROCESS porque o painel grava
 * no processo da HOME e a tela Addons lê no ":player". O aceite dos termos ("quem instala responde pelo conteúdo") fica
 * no mesmo prefs e também viaja no backup: foi o dono quem aceitou.
 */
object AddonsTv {
    const val PREFS = "addons"
    private const val LISTA = "lista"
    private const val ACEITE = "aceite_termos"
    private const val TEMPO_MS = 12_000
    private const val MAX_BYTES = 3_000_000

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_MULTI_PROCESS)

    fun instalados(ctx: Context): List<AddonsProtocolo.Instalado> = AddonsProtocolo.deJson(prefs(ctx).getString(LISTA, null))

    private fun gravar(ctx: Context, l: List<AddonsProtocolo.Instalado>) =
        prefs(ctx).edit().putString(LISTA, AddonsProtocolo.paraJson(l)).apply()

    fun aceitou(ctx: Context): Boolean = prefs(ctx).getBoolean(ACEITE, false)
    fun aceitar(ctx: Context) = prefs(ctx).edit().putBoolean(ACEITE, true).apply()

    fun remover(ctx: Context, base: String) = gravar(ctx, instalados(ctx).filter { it.base != base })

    sealed class Resultado {
        data class Ok(val i: AddonsProtocolo.Instalado) : Resultado()
        data class Recusado(val motivo: AddonsProtocolo.Motivo) : Resultado()
        object Cheio : Resultado()
        object SemRede : Resultado()
        object SemAceite : Resultado()
    }

    /** Lê o manifesto do endereço colado e instala (ou atualiza). Precisa do aceite dos termos antes. */
    fun instalar(ctx: Context, colado: String): Resultado {
        if (!aceitou(ctx)) return Resultado.SemAceite
        val base = AddonsProtocolo.base(colado) ?: return Resultado.Recusado(AddonsProtocolo.Motivo.ENDERECO)
        val texto = baixar(AddonsProtocolo.urlManifesto(base)) ?: return Resultado.SemRede
        return when (val l = AddonsProtocolo.lerManifesto(texto)) {
            is AddonsProtocolo.Leitura.Recusado -> Resultado.Recusado(l.motivo)
            is AddonsProtocolo.Leitura.Ok -> {
                val novo = AddonsProtocolo.Instalado(base, l.m)
                val lista = AddonsProtocolo.instalar(instalados(ctx), novo) ?: return Resultado.Cheio
                gravar(ctx, lista)
                Resultado.Ok(novo)
            }
        }
    }

    /**
     * GET de um addon. http e https (há addon caseiro na rede de casa sem certificado); segue até 3 redirecionamentos,
     * mas nunca de https para http. Sem cookie, sem senha. Null = falhou.
     */
    fun baixar(url: String): String? {
        var u = url
        repeat(4) {
            if (!(u.startsWith("https://") || u.startsWith("http://"))) return null
            val c = runCatching { URL(u).openConnection() as HttpURLConnection }.getOrNull() ?: return null
            try {
                c.connectTimeout = TEMPO_MS; c.readTimeout = TEMPO_MS; c.instanceFollowRedirects = false
                c.setRequestProperty("User-Agent", Http.USER_AGENT); c.setRequestProperty("Accept", "application/json")
                val codigo = runCatching { c.responseCode }.getOrNull() ?: return null
                if (codigo in 300..399) {
                    val prox = c.getHeaderField("Location") ?: return null
                    val abs = runCatching { URL(URL(u), prox).toString() }.getOrNull() ?: return null
                    if (u.startsWith("https://") && !abs.startsWith("https://")) return null
                    u = abs; return@repeat
                }
                if (codigo !in 200..299) return null
                return c.inputStream.use { e ->
                    val saida = java.io.ByteArrayOutputStream(); val buf = ByteArray(16_384)
                    while (saida.size() < MAX_BYTES) { val n = e.read(buf); if (n < 0) break; saida.write(buf, 0, n) }
                    saida.toString("UTF-8")
                }
            } catch (_: Exception) { return null } finally { c.disconnect() }
        }
        return null
    }

    // ---------------------------------------------------------------- lista curada (18/11/2026, RecommendedAddons)

    private const val REC_JSON = "recomendados_json"
    private const val REC_QUANDO = "recomendados_quando"

    /** Um add-on da lista curada, pronto para a tela: nome e texto já no idioma da TV. */
    data class Sugerido(val e: RecommendedAddons.Entry, val nome: String, val texto: String)

    /** Nome traduzido de uma seção ([RecommendedAddons.GROUPS]). */
    fun nomeGrupo(ctx: Context, g: String): String = ctx.getString(when (g) {
        "movies_series" -> R.string.ad_grupo_filmes_series
        "anime" -> R.string.ad_grupo_anime
        "tv" -> R.string.ad_grupo_tv
        "subtitles" -> R.string.ad_grupo_legendas
        "music" -> R.string.ad_grupo_musica
        else -> R.string.ad_grupo_outros
    })

    /** Seção de um addon instalado, pelo manifesto (tipos + recursos). */
    fun grupoDe(a: AddonsProtocolo.Instalado): String =
        RecommendedAddons.groupOf(a.m.tipos, a.m.recursos.map { it.nome }.distinct())

    /** A lista atual (do servidor, se já veio; senão a embutida), sem o que já está instalado. Não acessa a rede. */
    fun sugeridos(ctx: Context): List<Sugerido> {
        val lista = RecommendedAddons.parse(prefs(ctx).getString(REC_JSON, null)) ?: RecommendedAddons.BUILT_IN
        val inst = instalados(ctx)
        return RecommendedAddons.toShow(lista, inst.map { it.m.id }.toSet(), inst.map { it.base }.toSet(), BuildConfig.PLAY).map { e ->
            // Referência direta ao R (não getIdentifier): o encolhedor de recursos do Release apagaria o texto.
            val res = when (RecommendedAddons.textKey(e.id)) {
                "streaming" -> R.string.ad_sug_streaming
                "tmdb" -> R.string.ad_sug_tmdb
                "mubi" -> R.string.ad_sug_mubi
                "kitsu" -> R.string.ad_sug_kitsu
                "animeseason" -> R.string.ad_sug_animeseason
                "opensubtitles" -> R.string.ad_sug_opensubtitles
                "livechannels" -> R.string.ad_sug_livechannels
                "publicdomain" -> R.string.ad_sug_publicdomain
                else -> 0
            }
            Sugerido(e, RecommendedAddons.name(e.id) ?: e.id, if (res != 0) ctx.getString(res) else "")
        }
    }

    /** Pergunta ao servidor no máximo 1 vez por dia (fora da thread da tela). Falhou: fica a lista que já havia. */
    fun atualizarSugeridos(ctx: Context) {
        val p = prefs(ctx)
        if (System.currentTimeMillis() - p.getLong(REC_QUANDO, 0) < RecommendedAddons.REFRESH_MS) return
        p.edit().putLong(REC_QUANDO, System.currentTimeMillis()).apply()
        val texto = baixar(RecommendedAddons.URL) ?: return
        if (RecommendedAddons.parse(texto) != null) p.edit().putString(REC_JSON, texto).apply()
    }

    // ---------------------------------------------------------------- perguntas das telas

    data class Linha(val addon: AddonsProtocolo.Instalado, val cat: AddonsProtocolo.CatalogoDecl)

    /** As linhas de catálogo de todos os addons (só as que não exigem extra que não sabemos dar). */
    fun linhas(ctx: Context): List<Linha> = instalados(ctx).flatMap { a ->
        if (!a.m.tem("catalog")) emptyList() else a.m.catalogos.filter { it.listavel }.map { Linha(a, it) }
    }

    fun catalogo(l: Linha, skip: Int = 0, genero: String? = null, busca: String? = null): List<AddonsProtocolo.Item> {
        val ex = buildList {
            if (busca != null) add("search" to busca)
            (genero ?: l.cat.generoPadrao())?.let { add("genre" to it) }
            if (skip > 0) add("skip" to skip.toString())
        }
        return baixar(AddonsProtocolo.url(l.addon.base, "catalog", l.cat.tipo, l.cat.id, ex))
            ?.let { AddonsProtocolo.lerCatalogo(it, l.cat.tipo) }.orEmpty()
    }

    /** Busca em todos os catálogos que aceitam busca. */
    fun buscar(ctx: Context, termo: String): List<Pair<Linha, List<AddonsProtocolo.Item>>> =
        instalados(ctx).flatMap { a -> a.m.catalogos.filter { it.temBusca }.map { Linha(a, it) } }
            .map { l -> l to catalogo(l, busca = termo) }.filter { it.second.isNotEmpty() }

    /**
     * A ficha: primeiro o addon que mostrou o item (se ele tem `meta` para esse id), depois os outros com `meta`, e para
     * ids `tt…` o TMDB. Null = ninguém sabe nada além do que o catálogo já deu.
     */
    fun ficha(ctx: Context, item: AddonsProtocolo.Item, origem: AddonsProtocolo.Instalado?): AddonsProtocolo.Ficha? {
        val candidatos = (listOfNotNull(origem) + instalados(ctx).filter { it.base != origem?.base })
            .filter { it.m.atende("meta", item.tipo, item.id) }
        for (a in candidatos) {
            val f = baixar(AddonsProtocolo.url(a.base, "meta", item.tipo, item.id))?.let { AddonsProtocolo.lerFicha(it, item.tipo) }
            if (f != null && (item.tipo != "series" || f.videos.isNotEmpty())) return f
        }
        return if (InfoExterna.idImdbValido(item.id)) fichaTmdb(item) else null
    }

    private fun fichaTmdb(item: AddonsProtocolo.Item): AddonsProtocolo.Ficha? {
        if (!InfoExternaCache.temTmdb()) return null   // TMDB só no Pro com contrato (DataSources, 19/11/2026)
        val k = ServicosContas.chavesApp().tmdb.takeIf { it.isNotBlank() } ?: return null
        val idioma = InfoExterna.idiomaTmdb()
        val cab = InfoExterna.tmdbCabecalhos(k, ServicosFilmes.agente(BuildConfig.VERSION_CODE))
        fun pedir(u: String) = runCatching { ServicosContas.pedir(u, cab) }.getOrNull()?.takeIf { it.codigo == 200 }?.corpo
        val achado = AddonsTmdb.urlFind(k, item.id, idioma)?.let(::pedir)?.let { AddonsTmdb.lerFind(it, item.id, item.tipo) } ?: return null
        val base = achado.item.copy(
            poster = item.poster.ifEmpty { achado.item.poster }, fundo = item.fundo.ifEmpty { achado.item.fundo },
            sinopse = achado.item.sinopse.ifEmpty { item.sinopse })
        if (!achado.serie) return AddonsProtocolo.Ficha(base, emptyList(), "")
        val temporadas = pedir(AddonsTmdb.urlSerie(k, achado.tmdb, idioma))?.let(AddonsTmdb::lerTemporadas).orEmpty()
        val videos = temporadas.take(40).flatMap { t ->
            pedir(AddonsTmdb.urlTemporada(k, achado.tmdb, t, idioma))?.let { AddonsTmdb.lerEpisodios(it, item.id, t) }.orEmpty()
        }
        return AddonsProtocolo.Ficha(base, videos, "")
    }

    /** Fontes HTTP de todos os addons que atendem esse vídeo, com o nome do addon. */
    fun fontes(ctx: Context, tipo: String, videoId: String): List<Pair<AddonsProtocolo.Instalado, AddonsProtocolo.Fonte>> =
        // Versão da Play enxuta (PlayPolicy.Power.ADDON_STREAMS): add-on não diz de onde tocar; catálogo e ficha ficam.
        if (!PlayPolicy.allowed(PlayPolicy.Power.ADDON_STREAMS, BuildConfig.PLAY)) emptyList()
        else instalados(ctx).filter { it.m.atende("stream", tipo, videoId) }.flatMap { a ->
            baixar(AddonsProtocolo.url(a.base, "stream", tipo, videoId))?.let { AddonsProtocolo.lerFontes(it) }.orEmpty().map { a to it }
        }
}
