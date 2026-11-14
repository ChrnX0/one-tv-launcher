package br.paulo.tvlauncher

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.Xml
import java.io.File
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Player universal, passo 2 (06/11/2026): baixa a lista de canais e o guia de cada conta, com cache em arquivo.
 *
 * Tudo aqui faz rede ou disco: chamar FORA da thread da tela. Regras:
 * - Lista: vale 12 h no cache. Sem rede, usa o cache velho (melhor um canal que talvez mudou do que tela vazia).
 * - Guia: vale 6 h, só os canais da lista e só de 1 h atrás até 14 h à frente, num banco SQLite consultado por canal
 *   (GuiaBanco; o arquivo inteiro não cabe na memória).
 * - Tetos de tamanho: 30 MB para a lista, 150 MB para o guia (lido em fluxo, nunca inteiro na memória).
 * - O endereço nunca vai para o log (no Xtream ele leva a senha): só o nome do servidor.
 */
object PlayerCarga {
    private const val TAG = "TvLauncher.Player"
    private const val VALIDADE_LISTA = 12 * 3_600_000L
    private const val VALIDADE_GUIA = 6 * 3_600_000L
    private const val MAX_LISTA = 30L * 1024 * 1024
    private const val MAX_GUIA = 150L * 1024 * 1024
    private const val AGENTE = "TvLauncher-Player/1.0"

    private fun arquivoCanais(ctx: Context, id: String) = File(PlayerArmazem.pasta(ctx), "$id.canais.json")
    /** Desde 10/11/2026 o guia fica num banco SQLite (GuiaBanco). O guia.json antigo é só apagado (migração). */
    private fun arquivoGuia(ctx: Context) = File(PlayerArmazem.pasta(ctx), "guia.db")
    private fun guiaJsonAntigo(ctx: Context) = File(PlayerArmazem.pasta(ctx), "guia.json")
    /** "Meu time" com alcance de dias (ideia 16): as partidas achadas além da janela do guia, gravadas junto com ele. */
    private fun arquivoAdiante(ctx: Context) = File(PlayerArmazem.pasta(ctx), "meutime.json")

    /** As partidas do "Meu time" guardadas no último download do guia (até 7 dias à frente). Pequena; nunca baixa. */
    fun partidasAdiante(ctx: Context): List<MeuTimeAdiante.Partida> =
        MeuTimeAdiante.deJson(runCatching { arquivoAdiante(ctx).readText() }.getOrNull())

    fun apagarCache(ctx: Context, id: String) {
        arquivoCanais(ctx, id).delete()
        File(PlayerArmazem.pasta(ctx), "$id.filmes.json").delete()
        File(PlayerArmazem.pasta(ctx), "$id.series.json").delete()
        // O guia é de todas as contas juntas: refaz na próxima abertura.
        synchronized(this) { ultimaTentativaGuia = 0L }
        GuiaBanco.apagar(arquivoGuia(ctx)); guiaJsonAntigo(ctx).delete(); arquivoAdiante(ctx).delete()
    }

    /** Canais de todas as contas, juntos e sem número repetido. */
    fun canais(ctx: Context, forcar: Boolean = false): List<Canal> =
        PlayerDados.juntar(PlayerArmazem.fontes(ctx).map { it to canaisDaFonte(ctx, it, forcar) })

    /**
     * Canais para a voz e o Claude ("coloca na Globo", CanalTv): só o cache, MESMO vencido. NUNCA baixa: baixar uma lista
     * de 30 MB no processo da HOME travaria a fila dos pedidos; conta sem cache fica de fora (o CanalBusca responde "abra
     * a TV ao vivo uma vez", e o player grava o cache quando abre). Ids e números iguais aos do player (mesmo juntar).
     */
    fun canaisDoCache(ctx: Context): List<Canal> =
        PlayerDados.juntar(PlayerArmazem.fontes(ctx).map { f ->
            val cache = arquivoCanais(ctx, f.id)
            f to (if (cache.exists()) runCatching { PlayerDados.canaisDeJson(cache.readText(), f) }.getOrDefault(emptyList()) else emptyList())
        })

    /** Os programas guardados de alguns canais, lidos em fluxo do cache do guia (nunca baixa). Sem cache: guia vazio. */
    fun guiaDoCache(ctx: Context, guiaIds: Set<String>): Guia {
        // O guia virou SQLite (guia.db): abre só para leitura, pega os canais pedidos e fecha na hora (não prende o banco
        // no processo da HOME). Antes lia o arquivo como texto JSON e o "o que passa" voltava sempre vazio.
        if (guiaIds.isEmpty() || !arquivoGuia(ctx).exists()) return Guia(emptyList())
        val banco = GuiaBanco.abrir(arquivoGuia(ctx)) ?: return Guia(emptyList())
        return try { Guia(banco.doCanais(guiaIds)?.values?.flatten().orEmpty()) } finally { banco.fechar() }
    }

    fun canaisDaFonte(ctx: Context, fonte: FonteTv, forcar: Boolean = false): List<Canal> {
        val cache = arquivoCanais(ctx, fonte.id)
        val fresco = cache.exists() && System.currentTimeMillis() - cache.lastModified() < VALIDADE_LISTA
        if (fresco && !forcar) PlayerDados.canaisDeJson(cache.readText(), fonte).takeIf { it.isNotEmpty() }?.let { return it }
        val baixados = runCatching { baixarCanais(ctx, fonte) }
            .onFailure { Log.w(TAG, "lista de ${fonte.servidorVisivel}: ${it.javaClass.simpleName} ${it.message?.take(80)}") }
            .getOrNull()
        if (!baixados.isNullOrEmpty()) {
            gravarAtomico(cache, PlayerDados.canaisParaJson(baixados, fonte))
            return baixados
        }
        return if (cache.exists()) PlayerDados.canaisDeJson(cache.readText(), fonte) else emptyList()
    }

    private fun baixarCanais(ctx: Context, fonte: FonteTv): List<Canal> {
        // Lista M3U lida linha a linha direto da rede: o texto inteiro (até 30 MB, o dobro na memória) nunca é montado.
        val principal: Xtream.Conta = fonte.xtream
            ?: return abrir(fonte.endereco, MAX_LISTA).bufferedReader(Charsets.UTF_8).use { M3u.ler(it.lineSequence()) }
        // Servidor reserva (11/11/2026): o principal primeiro; se ele não responder como Xtream, os reservas, em ordem.
        // O 1º que responde vira o principal (PlayerArmazem.promoverServidor). Conta recusada NÃO troca de servidor:
        // é a mesma conta em todos, e a recusa é dela.
        var conta: Xtream.Conta = principal
        var situacao: Xtream.Situacao? = null
        var falha: Throwable? = null
        for (servidor in listOf(fonte.endereco) + fonte.reservas) {
            val tentativa = principal.copy(servidor = servidor)
            val s = runCatching { Xtream.lerSituacao(baixarTexto(Xtream.urlApi(tentativa), 1024 * 1024)) }
                .onFailure { falha = it }.getOrNull() ?: continue
            conta = tentativa; situacao = s
            if (servidor != fonte.endereco && PlayerArmazem.promoverServidor(ctx, fonte.id, servidor))
                Log.i(TAG, "servidor reserva em uso: ${servidor.substringAfter("://").substringBefore('/')}")
            break
        }
        if (situacao == null) throw (falha as? IOException ?: IOException("o servidor não respondeu como Xtream"))
        PlayerArmazem.definirVencimento(ctx, fonte.id, fonte.nome, situacao.expiraEm)   // pílula "vence em N dias" da HOME
        if (!situacao.ativa) throw IOException("conta ${situacao.status.ifEmpty { "recusada" }}")
        val categorias = runCatching { Xtream.lerCategorias(baixarTexto(Xtream.urlCategoriasAoVivo(conta), 5L * 1024 * 1024)) }.getOrDefault(emptyList())
        return Xtream.lerCanais(baixarTexto(Xtream.urlCanaisAoVivo(conta), MAX_LISTA), conta, categorias)
    }

    /**
     * Situação da conta (área "Conta" das Configurações): o login Xtream de novo, que devolve user_info. Lista M3U
     * comum = null sem rede nenhuma. Chamar fora da thread da tela. O endereço leva a senha: nunca vai para o log.
     */
    fun situacao(fonte: FonteTv): Xtream.Situacao? {
        val conta = ContaIptv.contaConsultavel(fonte) ?: return null
        return runCatching { Xtream.lerSituacao(baixarTexto(Xtream.urlApi(conta), 1024 * 1024)) }
            .onFailure { Log.w(TAG, "conta de ${fonte.servidorVisivel}: ${it.javaClass.simpleName}") }.getOrNull()
    }

    /**
     * TV de volta: os programas do canal que o servidor guardou e o fuso dele (o endereço do arquivo vai no horário do
     * servidor). Chamar fora da thread da tela. null = sem resposta (rede, servidor fora). Os endereços levam a senha:
     * nunca vão para o log.
     */
    fun tvDeVolta(o: TvDeVolta.Origem): Pair<List<TvDeVolta.Programa>, java.util.TimeZone>? = runCatching {
        val fuso = TvDeVolta.fuso(runCatching { baixarTexto(Xtream.urlApi(o.conta), 1024 * 1024) }.getOrNull())
        TvDeVolta.lerTabela(baixarTexto(TvDeVolta.urlTabela(o), 8L * 1024 * 1024)) to fuso
    }.onFailure { Log.w(TAG, "TV de volta: ${it.javaClass.simpleName}") }.getOrNull()

    // ---------------------------------------------------------------- filmes (só contas Xtream)

    private fun arquivoFilmes(ctx: Context, id: String) = File(PlayerArmazem.pasta(ctx), "$id.filmes.json")

    /**
     * O catálogo de filmes ou séries no disco precisa ser baixado de novo (algum arquivo falta ou passou da validade)?
     * Barato (só a data dos arquivos). As telas de Filmes e Séries abrem pelo cache e, se for o caso, atualizam em segundo
     * plano (12/11/2026: com o cache vencido a tela esperava o download inteiro, ~10 s na Sala).
     */
    fun catalogoVelho(ctx: Context, series: Boolean): Boolean {
        val agora = System.currentTimeMillis()
        return PlayerArmazem.fontes(ctx).filter { it.xtream != null }.any { f ->
            val a = if (series) File(PlayerArmazem.pasta(ctx), "${f.id}.series.json") else arquivoFilmes(ctx, f.id)
            !a.exists() || agora - a.lastModified() >= VALIDADE_LISTA
        }
    }

    /** Há alguma conta Xtream? (só essas têm filmes e séries pelo protocolo.) */
    fun temVod(ctx: Context): Boolean = PlayerArmazem.fontes(ctx).any { it.xtream != null || vodM3u(ctx, it.id) > 0 }

    // ---------------------------------------------------------------- filmes e séries de lista M3U (ideia 26)

    private const val PREFS_VOD_M3U = "player_vod_m3u"
    /** Quantos filmes e episódios a lista M3U [fonteId] trazia da última vez que foi lida (barato: a HOME pergunta). */
    fun vodM3u(ctx: Context, fonteId: String): Int =
        ctx.getSharedPreferences(PREFS_VOD_M3U, Context.MODE_PRIVATE).getInt(fonteId, 0)

    private fun catalogoM3u(ctx: Context, fonte: FonteTv, soCache: Boolean, forcar: Boolean = false): M3uVod.Catalogo {
        val canais = if (soCache) {
            val cache = arquivoCanais(ctx, fonte.id)
            if (cache.exists()) runCatching { PlayerDados.canaisDeJson(cache.readText(), fonte) }.getOrDefault(emptyList()) else emptyList()
        } else canaisDaFonte(ctx, fonte, forcar)
        return M3uVod.montar(canais, fonte.id).also { cat ->
            if (canais.isNotEmpty()) ctx.getSharedPreferences(PREFS_VOD_M3U, Context.MODE_PRIVATE).edit()
                .putInt(fonte.id, cat.filmes.size + cat.episodios.values.sumOf { t -> t.values.sumOf { it.size } }).apply()
        }
    }

    /**
     * Endereço (e User-Agent, se a lista pedir) de um filme ou episódio de lista M3U, pelo id estável da entrada.
     * Vem do cache da lista (que já guarda os endereços dos canais M3U). Fora da thread da tela.
     */
    fun urlM3u(ctx: Context, fonteId: String, id: String): Pair<String, String>? {
        val fonte = PlayerArmazem.fontes(ctx).firstOrNull { it.id == fonteId && it.xtream == null } ?: return null
        return canaisDaFonte(ctx, fonte).firstOrNull { it.id == id }?.let { it.url to it.agenteUsuario }
    }

    /** Filmes de todas as contas Xtream. Cache de 12 h por conta; sem rede, o cache velho. */
    fun filmes(ctx: Context, forcar: Boolean = false, soCache: Boolean = false): List<Filme> = PlayerArmazem.fontes(ctx).flatMap { fonte ->
        val conta = fonte.xtream ?: return@flatMap catalogoM3u(ctx, fonte, soCache, forcar).filmes
        val cache = arquivoFilmes(ctx, fonte.id)
        // soCache: quem chama não pode baixar (ex.: a HOME montando a lista de categorias infantis)
        if (soCache) return@flatMap if (cache.exists()) Vod.filmesDeJson(cache.readText(), fonte.id) else emptyList()
        val fresco = cache.exists() && System.currentTimeMillis() - cache.lastModified() < VALIDADE_LISTA
        if (fresco && !forcar) Vod.filmesDeJson(cache.readText(), fonte.id).takeIf { it.isNotEmpty() }?.let { return@flatMap it }
        val baixados = runCatching {
            val cats = runCatching { Xtream.lerCategorias(baixarTexto(Vod.urlCategoriasFilmes(conta), 5L * 1024 * 1024)) }.getOrDefault(emptyList())
            Vod.lerFilmes(baixarTexto(Vod.urlFilmes(conta), MAX_LISTA), fonte.id, cats)
        }.onFailure { Log.w(TAG, "filmes de ${fonte.servidorVisivel}: ${it.javaClass.simpleName}") }.getOrNull()
        if (!baixados.isNullOrEmpty()) {
            gravarAtomico(cache, Vod.filmesParaJson(baixados))
            baixados
        } else if (cache.exists()) Vod.filmesDeJson(cache.readText(), fonte.id) else emptyList()
    }

    /**
     * A ficha (sinopse, elenco...) de um filme. Vem da rede; sem rede, a última que deu certo (ideia 22, 14/11/2026:
     * antes, sem rede, sem ficha).
     */
    fun fichaFilme(ctx: Context, f: Filme): FichaFilme? {
        val conta = PlayerArmazem.fontes(ctx).firstOrNull { it.id == f.fonteId }?.xtream ?: return null
        return fichaComDisco(ctx, FimDoVideo.nomeFicha(f.fonteId, false, f.id), { Vod.lerFicha(it) }) {
            baixarTexto(Vod.urlFichaFilme(conta, f.id), 2L * 1024 * 1024)
        }
    }

    /**
     * Rede primeiro (episódio novo aparece na hora); a resposta boa fica em cacheDir/fichas. Se a rede falhar, lê o disco.
     * cacheDir: o Android pode limpar quando falta espaço, e o backup não leva. No máximo FimDoVideo.FICHAS_MAX arquivos.
     */
    private fun <T> fichaComDisco(ctx: Context, nome: String, ler: (String) -> T?, baixar: () -> String): T? {
        val pasta = File(ctx.cacheDir, "fichas")
        val arq = File(pasta, nome)
        val daRede = runCatching { baixar() }.onFailure { Log.w(TAG, "ficha: ${it.javaClass.simpleName}") }.getOrNull()
        val lida = daRede?.let { t -> runCatching { ler(t) }.getOrNull() }
        if (lida != null && daRede != null) {
            runCatching {
                pasta.mkdirs()
                gravarAtomico(arq, daRede)
                val todos = pasta.listFiles().orEmpty().associate { it.name to it.lastModified() }
                FimDoVideo.podar(todos).forEach { File(pasta, it).delete() }
            }
            return lida
        }
        return if (arq.exists()) runCatching { ler(arq.readText()) }.getOrNull() else null
    }

    // ---------------------------------------------------------------- séries (só contas Xtream)

    /** Séries de todas as contas Xtream. Cache de 12 h por conta; sem rede, o cache velho. */
    fun series(ctx: Context, forcar: Boolean = false, soCache: Boolean = false): List<Serie> = PlayerArmazem.fontes(ctx).flatMap { fonte ->
        val conta = fonte.xtream ?: return@flatMap catalogoM3u(ctx, fonte, soCache, forcar).series
        val cache = File(PlayerArmazem.pasta(ctx), "${fonte.id}.series.json")
        if (soCache) return@flatMap if (cache.exists()) Series.seriesDeJson(cache.readText(), fonte.id) else emptyList()
        val fresco = cache.exists() && System.currentTimeMillis() - cache.lastModified() < VALIDADE_LISTA
        if (fresco && !forcar) Series.seriesDeJson(cache.readText(), fonte.id).takeIf { it.isNotEmpty() }?.let { return@flatMap it }
        val baixadas = runCatching {
            val cats = runCatching { Xtream.lerCategorias(baixarTexto(Series.urlCategorias(conta), 5L * 1024 * 1024)) }.getOrDefault(emptyList())
            Series.lerSeries(baixarTexto(Series.urlSeries(conta), MAX_LISTA), fonte.id, cats)
        }.onFailure { Log.w(TAG, "séries de ${fonte.servidorVisivel}: ${it.javaClass.simpleName}") }.getOrNull()
        if (!baixadas.isNullOrEmpty()) {
            gravarAtomico(cache, Series.seriesParaJson(baixadas))
            baixadas
        } else if (cache.exists()) Series.seriesDeJson(cache.readText(), fonte.id) else emptyList()
    }

    /** Ficha e episódios de uma série. Rede primeiro (episódio novo aparece na hora); sem rede, a do disco (ideia 22). */
    fun fichaSerie(ctx: Context, s: Serie): FichaSerie? {
        val fonte = PlayerArmazem.fontes(ctx).firstOrNull { it.id == s.fonteId } ?: return null
        // Lista M3U (ideia 26): os episódios saem da própria lista; não há sinopse nem elenco.
        val conta = fonte.xtream ?: return catalogoM3u(ctx, fonte, soCache = false).episodios[s.id]?.let { FichaSerie(temporadas = it) }
        return fichaComDisco(ctx, FimDoVideo.nomeFicha(s.fonteId, true, s.id), { Series.lerFicha(it) }) {
            baixarTexto(Series.urlFicha(conta, s.id), 10L * 1024 * 1024)
        }
    }

    /** O endereço para tocar (com a senha: não guardar, não logar). */
    fun urlFilme(ctx: Context, f: Filme): String? =
        PlayerArmazem.fontes(ctx).firstOrNull { it.id == f.fonteId }?.xtream?.let { Vod.urlFilme(it, f) } ?: urlM3u(ctx, f.fonteId, f.id)?.first

    /** Link do guia de uma conta: o cadastrado, senão o do Xtream, senão o que a lista anuncia. */
    private fun linkGuia(fonte: FonteTv): String? {
        if (fonte.guia.isNotEmpty()) return fonte.guia
        fonte.xtream?.let { return Xtream.urlGuiaXmltv(it) }
        // O link do guia está só na 1ª linha da lista: lê essa linha e fecha (antes baixava a lista inteira para isso).
        return runCatching {
            abrir(fonte.endereco, MAX_LISTA).bufferedReader(Charsets.UTF_8).use { it.readLine() }?.let { M3u.guiaDaLista(it) }
        }.getOrNull()
    }

    // O guia aberto neste processo (o player roda no processo ":player", a HOME no principal: cada um tem o seu).
    // Reaproveitado enquanto o arquivo não mudar, para não abrir o banco de novo a cada chamada.
    private var guiaAberto: Guia? = null
    private var chaveAberto = ""
    // Última vez que este processo tentou baixar o guia. Sem guia nenhum (conta sem EPG), evita baixar a cada volta ao player.
    @Volatile private var ultimaTentativaGuia = 0L

    /**
     * Percorre o guia guardado (nunca baixa): cada programa que começa entre [desde] e [ate]. Abre só para leitura e
     * fecha no fim. false = sem guia. Para os lembretes do "Meu time" (LembretesTv). Disco: FORA da thread da tela.
     */
    /** Os títulos diferentes do guia guardado entre [desde] e [ate] que ainda não acabaram em [agora]. null = sem guia. */
    fun titulosDoGuia(ctx: Context, desde: Long, ate: Long, agora: Long): Set<String>? {
        val banco = GuiaBanco.abrir(arquivoGuia(ctx)) ?: return null
        return try { banco.titulosEntre(desde, ate, agora) } finally { banco.fechar() }
    }

    /** Os programas do guia guardado com um destes títulos, entre [desde] e [ate]. */
    fun programasDoGuia(ctx: Context, titulos: Collection<String>, desde: Long, ate: Long, aoLer: (Programa) -> Unit): Boolean {
        if (titulos.isEmpty()) return true
        val banco = GuiaBanco.abrir(arquivoGuia(ctx)) ?: return false
        return try { banco.programasComTitulos(titulos, desde, ate, aoLer) } finally { banco.fechar() }
    }

    fun varrerGuia(ctx: Context, desde: Long, ate: Long, aoLer: (Programa) -> Unit): Boolean {
        val banco = GuiaBanco.abrir(arquivoGuia(ctx)) ?: return false
        return try { banco.programasEntre(desde, ate, aoLer) } finally { banco.fechar() }
    }

    /**
     * O guia está velho (passou da validade ou nem existe) e já dá para tentar de novo? Barato (só a data do arquivo):
     * a PlayerActivity chama ao voltar para a frente e, se for o caso, recarrega o guia fora da thread da tela.
     */
    fun guiaVelho(ctx: Context): Boolean {
        val agora = System.currentTimeMillis()
        val arquivo = arquivoGuia(ctx)
        val velho = !arquivo.exists() || agora - arquivo.lastModified() >= VALIDADE_GUIA
        return velho && agora - ultimaTentativaGuia >= 3_600_000L
    }

    /**
     * Abre (ou reaproveita) o guia do arquivo. null = arquivo ausente ou que não presta.
     * `guardar = false` (chamadas com forcar, que vêm da HOME: Configurações > Testar, Painel, diagnóstico): abre um Guia
     * avulso que NÃO fica preso no processo; quem chama usa e solta (ou o coletor de lixo solta). Antes ficava aberto na
     * HOME para sempre, com o cache do SQLite e até 300 canais na memória.
     */
    private fun abrirGuia(arquivo: File, guardar: Boolean = true): Guia? = synchronized(this) {
        if (!guardar) return GuiaBanco.abrir(arquivo)?.let { Guia(it) }
        val chave = "${arquivo.lastModified()}:${arquivo.length()}"
        guiaAberto?.let { if (chave == chaveAberto && arquivo.exists()) return it }
        val fonte = GuiaBanco.abrir(arquivo) ?: return null
        val novo = Guia(fonte)
        val velho = guiaAberto
        guiaAberto = novo; chaveAberto = chave
        // O guia antigo ainda pode estar na tela por um instante (a troca chega pela thread da tela): fecha só daqui a
        // 1 min. Mesmo fechado antes da hora, a consulta não quebra: devolve vazio (FonteSqlite usa runCatching).
        velho?.let { v -> Handler(Looper.getMainLooper()).postDelayed({ v.fechar() }, 60_000L) }
        novo
    }

    /**
     * O guia dos canais da lista. Sem guia nenhum: Guia vazio (o player mostra só o nome do canal).
     *
     * Desde 10/11/2026: o XMLTV vai direto para um banco SQLite em fluxo (Xmltv.lerEmFluxo + GuiaBanco), sem montar a
     * lista de programas na memória; as telas consultam o banco canal por canal. Rede e disco: chamar FORA da thread da tela.
     */
    fun guia(ctx: Context, canais: List<Canal>, forcar: Boolean = false): Guia {
        val pasta = PlayerArmazem.pasta(ctx)
        val arquivo = arquivoGuia(ctx)
        val agora = System.currentTimeMillis()
        guiaJsonAntigo(ctx).delete()   // migração: o cache antigo (JSON) é só descartado; o guia é baixado de novo
        // Restos de uma gravação que não terminou (TV desligada no meio): apaga os de mais de 1 h.
        pasta.listFiles { f -> f.name.startsWith("guia.db.") && f.name.contains(".tmp") }?.forEach { f ->
            if (agora - f.lastModified() > 3_600_000L) f.delete()
        }
        if (!forcar && arquivo.exists() && agora - arquivo.lastModified() < VALIDADE_GUIA) {
            // Banco vazio, cortado ou de outra versão (abrirGuia = null) não vale como "fresco": cai para baixar de novo.
            abrirGuia(arquivo)?.let { return it }
        }
        val guardar = !forcar
        ultimaTentativaGuia = agora   // antes do "sem tvg-id" abaixo: senão cada zapping abria uma thread à toa
        val ids = canais.mapNotNull { it.guiaId.ifEmpty { null } }.toSet()
        if (ids.isEmpty()) return Guia(emptyList())
        // Grava num arquivo temporário com nome único (o player e a HOME são processos diferentes e podem baixar ao
        // mesmo tempo) e só no fim troca o nome: quem estiver lendo o guia antigo continua lendo, sem ver nada pela metade.
        val tmp = File(pasta, "guia.db.${android.os.Process.myPid()}.${System.nanoTime()}.tmp")
        var total = 0
        val gravador = runCatching { GuiaBanco.criar(tmp) }
            .onFailure { Log.w(TAG, "guia: não criou o banco (${it.javaClass.simpleName})") }.getOrNull()
        if (gravador != null) {
            val (desde, ate) = GuiaConsulta.janela(agora)
            var discoFalhou = false
            // Segundo guia (ideia 15): os canais (guiaId) que receberam programa do guia principal. Só os ids (texto curto),
            // do tamanho da lista de canais com guia; some no fim do download.
            val comPrograma = HashSet<String>()
            val fontes = PlayerArmazem.fontes(ctx)
            // "Meu time" com alcance de dias (ideia 16): além da janela, só as partidas que casam com as palavras.
            val palavras = LembretesTv.palavrasParaGuia(ctx)
            val adiante = palavras.takeIf { it.isNotEmpty() }?.let { MeuTimeAdiante.Coletor(it, agora) }
            val alem = if (adiante != null) agora + MeuTimeAdiante.JANELA_MS else Long.MIN_VALUE
            val aoLerAlem: ((Programa) -> Unit)? = adiante?.let { c -> { p -> c.oferecer(p.guiaId, p.inicio, p.fim, p.titulo) } }
            try {
                for (fonte in fontes) {
                    if (total >= Xmltv.MAX_PROGRAMAS_BANCO || discoFalhou) break
                    val link = linkGuia(fonte) ?: continue
                    // O xmltv.php do Xtream monta o guia inteiro antes de mandar o 1º byte: com milhares de canais isso passa de
                    // 30 s (visto na Sala: SocketTimeoutException). Por isso 120 s de espera e até 3 tentativas (5 s e 15 s entre elas).
                    for (tentativa in 0 until 3) {
                        if (tentativa > 0) Thread.sleep(if (tentativa == 1) 5_000L else 15_000L)
                        val r = runCatching {
                            abrir(link, MAX_GUIA, leitura = 120_000).use { entrada ->
                                // Cada tentativa é um lote: se a rede cair no meio, o lote é desfeito (sem programa repetido).
                                val vistos = HashSet<String>()   // desta tentativa: só valem se o lote for gravado
                                gravador.lote { inserir ->
                                    Xmltv.lerEmFluxo(Xml.newPullParser(), Xmltv.abrir(entrada), ids, desde, ate,
                                        Xmltv.MAX_PROGRAMAS_BANCO - total, alem, aoLerAlem) { p -> inserir(p); vistos += p.guiaId }
                                }.also { comPrograma += vistos }
                            }
                        }.onFailure { Log.w(TAG, "guia de ${fonte.servidorVisivel} (tentativa ${tentativa + 1}): ${it.javaClass.simpleName}") }
                        val lidos = r.getOrNull()
                        if (lidos != null) { total += lidos; break }
                        // Erro do banco (disco cheio, por exemplo) não é da rede: baixar de novo (até 150 MB) não resolve.
                        if (r.exceptionOrNull() is android.database.SQLException) { discoFalhou = true; break }
                        // XML com defeito é erro fixo: baixar de novo (até 150 MB) daria o mesmo erro. Só a rede merece nova tentativa.
                        // Mas tenta UMA vez mais: servidor de IPTV às vezes devolve uma página de erro (HTML) com status 200.
                        if (r.exceptionOrNull() is org.xmlpull.v1.XmlPullParserException && tentativa >= 1) break
                    }
                }
                // Segundo guia (ideia 15): depois do principal de TODAS as contas, o extra de cada conta preenche só os
                // canais dela que ficaram sem nenhum programa. Uma tentativa só: é complemento, não vale segurar o player.
                for (fonte in fontes) {
                    if (fonte.guiaExtra.isEmpty() || total >= Xmltv.MAX_PROGRAMAS_BANCO || discoFalhou) continue
                    val prefixo = "${fonte.id}:"
                    val mescla = GuiaExtra.Mescla(GuiaExtra.faltando(
                        canais.filter { it.id.startsWith(prefixo) }.map { it.guiaId to it.nome }, comPrograma))
                    if (mescla.vazia) continue
                    val r = runCatching {
                        abrir(fonte.guiaExtra, MAX_GUIA, leitura = 120_000).use { entrada ->
                            val vistos = HashSet<String>()
                            gravador.lote { inserir ->
                                Xmltv.lerExtraEmFluxo(Xml.newPullParser(), Xmltv.abrir(entrada), mescla, desde, ate,
                                    Xmltv.MAX_PROGRAMAS_BANCO - total) { p -> inserir(p); vistos += p.guiaId }
                            }.also { comPrograma += vistos; Log.i(TAG, "guia extra de ${fonte.servidorVisivel}: ${vistos.size} canais preenchidos") }
                        }
                    }.onFailure { Log.w(TAG, "guia extra de ${fonte.servidorVisivel}: ${it.javaClass.simpleName}") }
                    r.getOrNull()?.let { total += it }
                    if (r.exceptionOrNull() is android.database.SQLException) discoFalhou = true
                }
                // Grava a lista à parte já agora (antes de trocar o banco): o varrerDepois logo abaixo a usa.
                if (total > 0) gravarAtomico(arquivoAdiante(ctx), MeuTimeAdiante.paraJson(adiante?.partidas().orEmpty()))
            } finally {
                gravador.concluir(indexar = total > 0)
            }
        }
        if (total > 0) {
            // Trocar o nome é tudo ou nada: ou fica o guia antigo inteiro, ou o novo inteiro.
            for (fim in listOf("-journal", "-wal", "-shm")) File(arquivo.path + fim).delete()
            if (tmp.renameTo(arquivo)) {
                GuiaBanco.apagar(tmp)
                LembretesTv.varrerDepois(ctx)   // guia novo: os lembretes do "Meu time" se refazem com ele
                abrirGuia(arquivo, guardar)?.let { return it }
            }
        }
        GuiaBanco.apagar(tmp)
        if (arquivo.exists()) {
            // Ficou com o guia antigo: marca o arquivo como de 5 h atrás, para a próxima abertura do player não repetir
            // os 3 downloads na hora (tenta de novo daqui a 1 h).
            runCatching { arquivo.setLastModified(agora - VALIDADE_GUIA + 3_600_000L) }
            return abrirGuia(arquivo, guardar) ?: Guia(emptyList())
        }
        return Guia(emptyList())
    }

    /**
     * Escrita atômica do cache: grava num arquivo ao lado e troca o nome. Antes gravava direto por cima; se a TV
     * desligasse no meio, ficava um arquivo cortado com data de agora (e o guia cortado valia como "fresco" por 6 h).
     * Trocar o nome é tudo ou nada: ou fica o arquivo antigo inteiro, ou o novo inteiro.
     */
    internal fun gravarAtomico(arquivo: File, texto: String) {
        val tmp = File(arquivo.path + ".tmp")
        runCatching {
            tmp.writeText(texto)
            if (!tmp.renameTo(arquivo)) tmp.delete()
        }.onFailure { tmp.delete() }
    }

    // ---------------------------------------------------------------- rede

    private fun baixarTexto(url: String, max: Long): String = abrir(url, max).use { it.readBytes().toString(Charsets.UTF_8) }

    /**
     * Abre o endereço seguindo até 4 redirecionamentos (o HttpURLConnection não segue de http para https sozinho).
     * A verificação de TLS é a padrão do Android: nunca desligada.
     */
    private fun abrir(endereco: String, max: Long, leitura: Int = 30_000): InputStream {
        var url = endereco
        repeat(5) {
            if (!M3u.urlAceita(url)) throw IOException("endereço recusado")
            val c = URL(url).openConnection() as HttpURLConnection
            c.connectTimeout = 10_000
            c.readTimeout = leitura
            c.instanceFollowRedirects = false
            c.setRequestProperty("User-Agent", AGENTE)
            c.setRequestProperty("Accept-Encoding", "gzip")
            val codigo = c.responseCode
            if (codigo in 300..399) {
                val destino = c.getHeaderField("Location")
                c.disconnect()
                url = URL(URL(url), destino ?: throw IOException("redirecionamento sem destino")).toString()
                return@repeat
            }
            if (codigo != 200) { c.disconnect(); throw IOException("HTTP $codigo") }
            if (c.contentLengthLong > max) { c.disconnect(); throw IOException(Xmltv.TETO) }
            val bruto = c.inputStream
            val corpo = if (c.contentEncoding.equals("gzip", ignoreCase = true)) java.util.zip.GZIPInputStream(bruto) else bruto
            return Teto(corpo, max)
        }
        throw IOException("redirecionamentos demais")
    }

    /** Fluxo que dá erro se passar de `max` bytes: servidor que manda sem fim não enche a memória nem o disco. */
    private class Teto(entrada: InputStream, private val max: Long) : FilterInputStream(entrada) {
        private var lidos = 0L
        override fun read(): Int = super.read().also { if (it >= 0) somar(1) }
        override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, len).also { if (it > 0) somar(it.toLong()) }
        private fun somar(n: Long) { lidos += n; if (lidos > max) throw IOException(Xmltv.TETO) }
    }
}
