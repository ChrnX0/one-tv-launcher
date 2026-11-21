package br.paulo.tvlauncher

import android.app.Activity
import android.app.SearchManager
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognizerIntent
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.Log
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import br.paulo.tvlauncher.alarm.Lembretes
import br.paulo.tvlauncher.home.BibliotecaIndice
import br.paulo.tvlauncher.home.BibliotecaUnica
import br.paulo.tvlauncher.home.abrir
import br.paulo.tvlauncher.listen.Radios
import br.paulo.tvlauncher.mcp.CaixaComando
import br.paulo.tvlauncher.mcp.CaixaComandoTv
import br.paulo.tvlauncher.mcp.ComandosIa
import br.paulo.tvlauncher.mcp.PedidoIa
import br.paulo.tvlauncher.network.WifiVisita
import br.paulo.tvlauncher.player.Canal
import br.paulo.tvlauncher.player.CanalBusca
import br.paulo.tvlauncher.player.CanalTv
import br.paulo.tvlauncher.player.ContaMidia
import br.paulo.tvlauncher.player.Filme
import br.paulo.tvlauncher.player.FontesMidia
import br.paulo.tvlauncher.player.Idioma
import br.paulo.tvlauncher.player.IdiomaPlayer
import br.paulo.tvlauncher.player.InfoExternaCache
import br.paulo.tvlauncher.player.MidiaArmazem
import br.paulo.tvlauncher.player.OndeAssistir
import br.paulo.tvlauncher.player.PlayerArmazem
import br.paulo.tvlauncher.player.PlayerCarga
import br.paulo.tvlauncher.player.PlayerFiltro
import br.paulo.tvlauncher.player.Serie
import br.paulo.tvlauncher.player.Vod
import br.paulo.tvlauncher.player.VodFiltro
import br.paulo.tvlauncher.search.BuscaFaz
import br.paulo.tvlauncher.search.BuscaOutrosApps
import br.paulo.tvlauncher.search.BuscaRecentes
import br.paulo.tvlauncher.search.BuscaUnica
import br.paulo.tvlauncher.settings.Extras
import br.paulo.tvlauncher.settings.Manual
import br.paulo.tvlauncher.settings.TamanhoTexto
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Busca única (12/11/2026, ideia 2), aberta pelo card "Buscar" da HOME. Uma caixa procura em canais, programas do guia,
 * filmes, séries e apps, tudo do que já está guardado na TV (nada é baixado aqui). A regra (ordem, limite) é a
 * [BuscaUnica]; esta tela só lê os caches e desenha.
 *
 * Caixa vazia: "Hoje na TV" (ideia 8), quando a chave estiver ligada.
 *
 * Roda no processo da HOME. Filmes e Séries abrem as telas do player (processo :player) já com o termo buscado.
 * Toda leitura de disco vai numa thread; [geracao] descarta resultado velho quando a pessoa já digitou outra coisa.
 */
class BuscaActivity : Activity() {
    private val ui = Handler(Looper.getMainLooper())
    private lateinit var campo: EditText
    private lateinit var lista: LinearLayout
    @Volatile private var geracao = 0

    /**
     * Os catálogos guardados, lidos e normalizados uma vez ao abrir a tela (12/11/2026: normalizar dezenas de milhares
     * de nomes a cada tecla levava ~20 s na Sala). [titulos]: título normalizado do guia -> títulos como estão no banco.
     */
    private class Dados(val canais: List<Canal>, val iCanais: List<BuscaUnica.Indexado<Canal>>,
                        val iFilmes: List<BuscaUnica.Indexado<Filme>>, val iSeries: List<BuscaUnica.Indexado<Serie>>,
                        val iApps: List<BuscaUnica.Indexado<Pair<String, String>>>, val titulos: Map<String, List<String>>,
                        val instalados: Set<String> = emptySet(),
                        val idsFilmes: Set<Int> = emptySet(), val idsSeries: Set<Int> = emptySet(),
                        val radios: List<Radios.Radio> = emptyList(), val ajuda: List<Pair<Int, String>> = emptyList(),
                        val temServidorMidia: Boolean = false, val appsMapa: Map<String, String> = emptyMap(),
                        /** Elevação 7.1: o que a TV viu em pastas, servidores, nuvens e addons; e a versão preferida. */
                        val iBiblioteca: List<BuscaUnica.Indexado<BibliotecaUnica.Fonte>> = emptyList(),
                        val preferida: Idioma.Versao = Idioma.Versao.DUBLADA)
    @Volatile private var dados: Dados? = null

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        if (VigiaAtualizacao.fechada(this)) return
        val (raiz, coluna) = Estilo.telaLista(this, getString(R.string.bu_buscar))
        campo = EditText(this).apply {
            hint = getString(R.string.bu_dica)
            setSingleLine()
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            setTextColor(Estilo.TEXTO); setHintTextColor(Estilo.TEXTO_3)
            textSize = 20f
            typeface = Estilo.fonte(500)
            background = Estilo.fundo(context, Estilo.SUPERFICIE_ALTA, Estilo.RAIO_PILULA, Estilo.BORDA)
            setPaddingRelative(dp(22), dp(14), dp(22), dp(14))
            setOnEditorActionListener { _, acao, _ ->
                if (acao == EditorInfo.IME_ACTION_SEARCH || acao == EditorInfo.IME_ACTION_DONE) { buscarJa(); true } else false
            }
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                // Espera a pessoa parar de digitar: o guia pode ter dezenas de milhares de programas.
                override fun afterTextChanged(s: Editable?) { ui.removeCallbacks(buscaAdiada); ui.postDelayed(buscaAdiada, 700) }
            })
        }
        coluna.addView(campo, LinearLayout.LayoutParams(-1, -2))
        lista = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; clipChildren = false; clipToPadding = false }
        coluna.addView(lista, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(Estilo.E1) })
        setContentView(raiz)
        campo.requestFocus()
        // Visto na Sala (20/11): a busca abria vazia por uns 4 s, até o índice (canais, filmes, guia) ficar pronto.
        // Falar e as buscas recentes não dependem dele: aparecem na hora; o "Hoje na TV" chega quando o índice fica pronto.
        desenhar("", inicio())
        Fundo.rodar("busca-dados") { carregar() }
    }

    override fun onDestroy() { super.onDestroy(); ui.removeCallbacksAndMessages(null); geracao++ }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private val buscaAdiada = Runnable { buscarJa() }

    private fun carregar() {
        val d = try {
            val parental = PlayerArmazem.parental(this)
            val todos = runCatching { PlayerCarga.canaisDoCache(this) }.getOrDefault(emptyList())
            val ocultas = PlayerFiltro.ocultasEfetivas(todos, PlayerArmazem.gruposEscondidos(this), PlayerArmazem.gruposLiberados(this))
            val canais = todos.filter { PlayerFiltro.podeIrParaHome(it, ocultas, parental) }
            fun <T> semOcultas(l: List<T>, grupo: (T) -> String) = VodFiltro.visiveis(l, VodFiltro.ocultas(l.map(grupo),
                PlayerArmazem.gruposEscondidos(this), PlayerArmazem.gruposLiberados(this), parental), grupo)
            val filmes = semOcultas(runCatching { PlayerCarga.filmes(this, soCache = true) }.getOrDefault(emptyList())) { it.grupo }
            val series = semOcultas(runCatching { PlayerCarga.series(this, soCache = true) }.getOrDefault(emptyList())) { it.grupo }
            val t0 = System.currentTimeMillis()
            val listaApps = apps()
            Dados(canais, BuscaUnica.indexar(canais) { it.nome }, BuscaUnica.indexar(filmes) { it.nome },
                BuscaUnica.indexar(series) { it.nome }, BuscaUnica.indexar(listaApps) { it.second }, titulosDoGuia(canais),
                comGoogleTv(this, listaApps.map { it.first }.toSet()),
                // Ideia 82: ids TMDB do catálogo inteiro (com as ocultas: o que está escondido não vira "fora da sua lista").
                idsDoCatalogo(false), idsDoCatalogo(true), radiosFavoritas(), topicosAjuda(), temServidorMidia(),
                runCatching { PedidoIa.appsDaHome(this) }.getOrDefault(emptyMap()),
                // Modo infantil: o que veio de pasta, servidor ou addon não tem classificação conferida, então não entra.
                BuscaUnica.indexar(if (parental.infantil) emptyList() else runCatching { BibliotecaIndice.todas(this) }.getOrDefault(emptyList())) { it.titulo },
                runCatching { Idioma.versaoDesejada(IdiomaPlayer.prefs(this).padrao) }.getOrDefault(Idioma.Versao.DUBLADA))
                .also { Log.i(TAG, "índice: ${canais.size} canais, ${filmes.size} filmes, ${series.size} séries, " +
                    "${it.titulos.size} títulos do guia em ${System.currentTimeMillis() - t0} ms") }
        } catch (e: Exception) {
            Log.w(TAG, "carregar: $e")
            val listaApps = apps()
            Dados(emptyList(), emptyList(), emptyList(), emptyList(), BuscaUnica.indexar(listaApps) { it.second }, emptyMap(),
                comGoogleTv(this, listaApps.map { it.first }.toSet()), ajuda = topicosAjuda(),
                appsMapa = runCatching { PedidoIa.appsDaHome(this) }.getOrDefault(emptyMap()))
        }
        dados = d
        ui.post { if (!isFinishing) buscarJa() }
    }

    private fun radiosFavoritas(): List<Radios.Radio> = runCatching { Radios.favoritas(this) }.getOrDefault(emptyList())

    /** Os assuntos da Ajuda: (índice, "título resumo"), para achar "como pareio o controle" pelo resumo também. */
    private fun topicosAjuda(): List<Pair<Int, String>> = Manual.TOPICOS.mapIndexed { i, id ->
        i to AjudaActivity.texto(this, id, Manual.Parte.TITULO)
    }

    private fun temServidorMidia(): Boolean = runCatching {
        MidiaArmazem.contas(this).any { it.ehJellyOuEmby || it.tipo == ContaMidia.PLEX }
    }.getOrDefault(false)

    /** Elevação 3.11: a linha "Fazer", se a frase pede algo. */
    private fun fazer(d: Dados, termo: String): Item? {
        val f = BuscaFaz.entender(termo, d.appsMapa) { t ->
            when (val p = CanalBusca.interpretarFrase(t)) {
                is CanalBusca.PedidoVoz.OQuePassa -> CaixaComando.Pedido("o_que_passa", alvo = p.canal)
                is CanalBusca.PedidoVoz.Tocar -> CaixaComando.Pedido("tocar_canal", alvo = p.canal)
                null -> null
            }
        } ?: return null
        f.local?.let { l ->
            return when (l) {
                BuscaFaz.Local.WIFI_VISITA -> Item(getString(R.string.elv_bf_wifi), getString(
                    if (WifiVisita.configurado(this)) R.string.elv_bf_ok_faz else R.string.elv_bf_wifi_configurar)) {
                    if (WifiVisita.configurado(this)) WifiVisita.mostrarQr(this)
                    else startActivity(Intent(this, ConfigLauncherActivity::class.java))
                }
                BuscaFaz.Local.LETRA_MAIOR, BuscaFaz.Local.LETRA_MENOR -> {
                    val atual = TamanhoTexto.atual(this)
                    val i = TamanhoTexto.OPCOES.indexOf(atual).coerceAtLeast(0)
                    val novo = TamanhoTexto.OPCOES[(if (l == BuscaFaz.Local.LETRA_MAIOR) i + 1 else i - 1).coerceIn(0, TamanhoTexto.OPCOES.lastIndex)]
                    Item(getString(if (l == BuscaFaz.Local.LETRA_MAIOR) R.string.elv_bf_letra_maior else R.string.elv_bf_letra_menor, novo),
                        getString(if (novo == atual) R.string.elv_bf_letra_limite else R.string.elv_bf_ok_faz)) {
                        if (novo != atual) { TamanhoTexto.definir(this, novo); recreate() }
                    }
                }
            }
        }
        val p = f.pedido ?: return null
        val texto = CaixaComandoTv.entendido(applicationContext, p, d.appsMapa)
        return Item(texto, getString(R.string.elv_bf_ok_faz)) {
            PedidoIa.propor(applicationContext, termo, ORIGEM, p.json())
            if (p.tipo != "volume" && p.tipo != "midia") finish()   // volume/mídia: a pessoa pode querer de novo
        }
    }

    private fun abrirRadio(termo: String) {
        try { startActivity(Intent(this, RadioActivity::class.java).putExtra(RadioActivity.EXTRA_BUSCA, termo)) } catch (_: Exception) {}
    }

    private fun idsDoCatalogo(serie: Boolean): Set<Int> = runCatching {
        if (serie) PlayerCarga.series(this, soCache = true).mapNotNullTo(HashSet()) { it.tmdb ?: InfoExternaCache.guardado(this, it.chave)?.tmdb }
        else PlayerCarga.filmes(this, soCache = true).mapNotNullTo(HashSet()) { it.tmdb ?: InfoExternaCache.guardado(this, it.chave)?.tmdb }
    }.getOrDefault(emptySet())

    /**
     * Ideia 82: o que o TMDB acha com o termo e NÃO está na lista, com onde assistir (assinatura ou grátis). OK abre a
     * busca do app quando ele está instalado. Pede à rede: só quando a pessoa para de digitar (a busca já espera 700 ms).
     */
    private fun foraDaLista(d: Dados, termo: String): List<Item> {
        if (!OndeAssistir.JUSTWATCH_LIBERADO || termo.length < 3 || !InfoExternaCache.temTmdb() || PlayerArmazem.parental(this).infantil) return emptyList()
        val fora = OndeAssistir.foraDaLista(InfoExternaCache.buscarFora(termo), d.idsFilmes, d.idsSeries)
        return fora.mapNotNull { a ->
            val onde = InfoExternaCache.provedores(a.tmdb, a.serie).takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val app = onde.firstNotNullOfOrNull { n -> OndeAssistir.pacote(n)?.takeIf { it in d.instalados }?.let { n to it } }
            Item(listOfNotNull(a.nome, a.ano?.let { "($it)" }).joinToString(" "), getString(R.string.onde_fora, onde.joinToString(" · "))) {
                if (app != null) BuscaOutrosApps.DESTINOS.firstOrNull { it.pacote == app.second }?.let { procurarEm(BuscaOutrosApps.Pedido(it, a.nome)) } ?: abrirApp(app.second)
            }
        }
    }

    /** Apps que abrem pela HOME (pacote, nome), sem o próprio launcher. */
    private fun apps(): List<Pair<String, String>> = try {
        val pm = packageManager
        listOf(Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER).flatMap { cat ->
            pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(cat), 0)
        }.map { it.activityInfo.packageName to it.loadLabel(pm).toString() }
            .filter { it.first != packageName }.distinctBy { it.first }
    } catch (_: Exception) { emptyList() }

    /** Os títulos diferentes do guia (próximas 24 h), agrupados pela forma normalizada. Uma consulta ao banco só. */
    private fun titulosDoGuia(canais: List<Canal>): Map<String, List<String>> {
        if (canais.none { it.guiaId.isNotEmpty() }) return emptyMap()
        val agora = System.currentTimeMillis()
        val t = PlayerCarga.titulosDoGuia(this, agora - 6 * 3_600_000L, agora + BuscaUnica.HORIZONTE_PROGRAMAS_MS, agora) ?: return emptyMap()
        return t.groupBy { PlayerFiltro.normalizar(it) }
    }

    private fun buscarJa() {
        ui.removeCallbacks(buscaAdiada)
        val d = dados ?: return
        val termo = campo.text.toString().trim()
        val minha = ++geracao
        Fundo.rodar("busca") {
            val t0 = System.currentTimeMillis()
            val r = try { if (termo.length < 2) inicio() + hoje(d) else resultados(d, termo) } catch (e: Exception) { Log.w(TAG, "busca: $e"); emptyList() }
            if (termo.length >= 2) Log.i(TAG, "busca (${termo.length} letras): ${System.currentTimeMillis() - t0} ms")
            ui.post { if (minha == geracao && !isFinishing) desenhar(termo, r) }
            // Ideia 82: depois, sem atrasar o resto, o que não está na lista e onde assistir.
            if (termo.length >= 3 && minha == geracao) {
                val fora = try { foraDaLista(d, termo) } catch (e: Exception) { Log.w(TAG, "fora: ${e.javaClass.simpleName}"); emptyList() }
                if (fora.isNotEmpty()) ui.post { if (minha == geracao && !isFinishing) desenhar(termo, r + Grupo(getString(R.string.onde_grupo), fora)) }
            }
        }
    }

    /** Uma linha do resultado: título, explicação e o que o OK faz. */
    private class Item(val titulo: String, val sub: String, val abrir: () -> Unit)
    private class Grupo(val nome: String, val itens: List<Item>)

    private fun resultados(d: Dados, termo: String): List<Grupo> {
        val grupos = ArrayList<Grupo>()
        // Elevação 3.11: o que a frase pede vem primeiro ("desligar em 30", "wifi visita", "letra maior"). Só com o OK.
        fazer(d, termo)?.let { grupos += Grupo(getString(R.string.elv_bf_fazer), listOf(it)) }
        val canais = BuscaUnica.filtrarIndice(d.iCanais, termo, tolerante = true).first
        if (canais.isNotEmpty()) grupos += Grupo(getString(R.string.bu_canais), canais.map { c -> Item(c.nome, getString(R.string.bu_canal_n, c.numero)) { tocar(c.id, c.nome) } })
        val progs = programas(d, termo)
        if (progs.isNotEmpty()) grupos += Grupo(getString(R.string.bu_no_guia), progs)
        // Elevação 7.1: biblioteca única. Lista, pastas, servidores, nuvens e addons juntos: cada título uma vez, com as
        // fontes embaixo (a melhor primeiro, com ▶) e o OK abrindo a melhor. Só junta o que bateu com o termo (rápido).
        val (filmes, total) = BuscaUnica.filtrarIndice(d.iFilmes, termo, max = 40, tolerante = true)
        val (series, totalSeries) = BuscaUnica.filtrarIndice(d.iSeries, termo, max = 40, tolerante = true)
        val daCasa = BuscaUnica.filtrarIndice(d.iBiblioteca, termo, max = 60, tolerante = true).first
        val unicos = BibliotecaUnica.juntar(filmes.map { f ->
            BibliotecaUnica.Fonte(BibliotecaUnica.Origem.LISTA, f.grupo, f.nome, f.ano, false, f.tmdb,
                qualidade = BibliotecaUnica.qualidadeDoNome(f.nome + " " + f.grupo), versao = Idioma.versao(f.nome, f.grupo), abrir = f.nome)
        } + series.map { s ->
            BibliotecaUnica.Fonte(BibliotecaUnica.Origem.LISTA, s.grupo, s.nome, s.ano, true, s.tmdb,
                qualidade = BibliotecaUnica.qualidadeDoNome(s.nome + " " + s.grupo), versao = Idioma.versao(s.nome, s.grupo), abrir = s.nome)
        } + daCasa, d.preferida)
        val uFilmes = unicos.filter { !it.serie }.take(BuscaUnica.MAX_POR_GRUPO)
        if (uFilmes.isNotEmpty()) {
            grupos += Grupo(getString(R.string.home_card_filmes), uFilmes.map { itemUnico(it) } +
                if (total > 0) listOf(Item(getString(R.string.bu_ver_filmes, total), getString(R.string.bu_ver_filmes_det)) { catalogo(FilmesActivity::class.java, termo) }) else emptyList())
        }
        val uSeries = unicos.filter { it.serie }.take(BuscaUnica.MAX_POR_GRUPO)
        if (uSeries.isNotEmpty()) {
            grupos += Grupo(getString(R.string.home_card_series), uSeries.map { itemUnico(it) } +
                if (totalSeries > 0) listOf(Item(getString(R.string.bu_ver_series, totalSeries), getString(R.string.bu_ver_series_det)) { catalogo(SeriesActivity::class.java, termo) }) else emptyList())
        }
        val apps = BuscaUnica.filtrarIndice(d.iApps, termo, tolerante = true).first
        if (apps.isNotEmpty()) grupos += Grupo("Apps", apps.map { (pacote, nome) -> Item(nome, "App") { abrirApp(pacote) } })
        // Elevação 3.11: rádios favoritas, ajuda e (com Jellyfin/Plex) os arquivos da rede.
        val radios = BuscaUnica.filtrar(d.radios, termo, max = 4, tolerante = true) { it.nome }
        if (radios.isNotEmpty()) grupos += Grupo(getString(R.string.elv_bf_radios), radios.map { r ->
            Item(r.nome, getString(R.string.elv_bf_radio_fav)) { abrirRadio(r.nome) }
        })
        val ajuda = BuscaUnica.filtrar(d.ajuda, termo, max = 3, tolerante = true) { it.second }
        if (ajuda.isNotEmpty()) grupos += Grupo(getString(R.string.elv_bf_ajuda), ajuda.map { (i, nome) ->
            Item(nome, getString(R.string.elv_bf_ajuda_det)) {
                try { startActivity(Intent(this, AjudaActivity::class.java).putExtra(AjudaActivity.EXTRA_TOPICO, i)) } catch (_: Exception) {}
            }
        })
        val maisLonge = ArrayList<Item>()
        if (termo.length >= 3) maisLonge += Item(getString(R.string.elv_bf_procurar_radio, termo), getString(R.string.elv_bf_procurar_radio_det)) { abrirRadio(termo) }
        if (termo.length >= 2 && d.temServidorMidia) maisLonge += Item(getString(R.string.elv_bf_procurar_rede, termo), getString(R.string.elv_bf_procurar_rede_det)) {
            try { startActivity(Intent(this, MidiaRedeActivity::class.java).putExtra(MidiaRedeActivity.EXTRA_BUSCA, termo)) } catch (_: Exception) {}
        }
        if (maisLonge.isNotEmpty()) grupos += Grupo(getString(R.string.elv_bf_procurar), maisLonge)
        // Ideia 14: no fim, continuar a busca no Google TV, YouTube, Netflix e Prime Video (só os instalados). Mesma linha dos apps.
        val outros = BuscaOutrosApps.montar(termo, d.instalados)
        if (outros.isNotEmpty()) grupos += Grupo(getString(R.string.bu_outros_apps), outros.map { p ->
            Item(getString(when (p.destino.chave) {
                "googletv" -> R.string.bu_outro_googletv
                "youtube" -> R.string.bu_outro_youtube
                "netflix" -> R.string.bu_outro_netflix
                else -> R.string.bu_outro_prime
            }, p.termo), getString(R.string.bu_outro_det)) { procurarEm(p) }
        })
        return grupos
    }

    /**
     * Programas do guia guardado (próximas 24 h) com o termo no título. Agora: OK assiste; depois: OK marca o lembrete.
     * Acha os títulos no índice já normalizado e só então pede ao banco os programas desses títulos. O mesmo programa em
     * vários canais vira uma linha ("+7 canais").
     */
    private fun programas(d: Dados, termo: String): List<Item> {
        val agora = System.currentTimeMillis()
        val p = BuscaUnica.palavras(termo)
        val quero = d.titulos.entries.mapNotNull { (n, t) -> BuscaUnica.pontosNorm(n, p, tolerante = true)?.let { pts -> Triple(t, pts, n.length) } }
            .sortedWith(compareBy({ it.second }, { it.third })).take(100).flatMap { it.first }
        if (quero.isEmpty()) return emptyList()
        val porGuia = d.canais.filter { it.guiaId.isNotEmpty() }.groupBy { it.guiaId }
        val achados = ArrayList<Lembretes.Candidato>()
        PlayerCarga.programasDoGuia(this, quero, agora - 6 * 3_600_000L, agora + BuscaUnica.HORIZONTE_PROGRAMAS_MS) { prog ->
            if (prog.fim <= agora || achados.size >= 400) return@programasDoGuia
            porGuia[prog.guiaId]?.minByOrNull { it.numero }?.let { c -> achados += Lembretes.Candidato(c.id, c.nome, c.numero, prog) }
        }
        val melhores = BuscaUnica.filtrar(BuscaUnica.agrupar(achados), termo, max = BuscaUnica.MAX_POR_GRUPO, tolerante = true) { it.first.programa.titulo }
        return melhores.sortedBy { it.first.programa.inicio }.map { (c, outros) ->
            itemPrograma(c, agora, if (outros > 0) "+" + resources.getQuantityString(R.plurals.bu_canais_n, outros, outros) else null)
        }
    }

    private fun itemPrograma(c: Lembretes.Candidato, agora: Long, motivo: String? = null): Item {
        val p = c.programa
        val quando = if (p.inicio <= agora) getString(R.string.bu_agora) else hora(p.inicio)
        val sub = listOfNotNull(quando, "${c.canalNome} (${c.numero})", motivo).joinToString(" · ")
        return Item(p.titulo, sub) {
            if (p.inicio <= System.currentTimeMillis()) tocar(c.canalId, c.canalNome)
            else Fundo.rodar("busca-lembrete") {
                val canal = dados?.canais?.firstOrNull { it.id == c.canalId } ?: return@rodar
                val sim = LembretesTv.alternar(applicationContext, canal, p)
                ui.post { AvisoTv.rapido(applicationContext, getString(if (sim) R.string.bu_lembrete_sim else R.string.bu_lembrete_nao), "${p.titulo}, ${hora(p.inicio)}") }
            }
        }
    }

    /** Caixa vazia: "Hoje na TV" (chave HOJE_NA_TV), ou só uma dica. */
    /**
     * Busca vazia (estudo global 20/11, ideia 7): o microfone em destaque e as buscas recentes antes do "Hoje na TV".
     * Por quê: digitar com a cruz é lento e o campo vazio não sugeria nada. Para quê: achar algo em 1 toque ou falando.
     */
    private fun inicio(): List<Grupo> {
        val grupos = ArrayList<Grupo>()
        if (vozDisponivel()) grupos += Grupo(getString(R.string.bu_voz_grupo),
            listOf(Item(getString(R.string.bu_falar), getString(R.string.bu_falar_det)) { ouvir() }))
        val recentes = BuscaRecentes.ler(this)
        if (recentes.isNotEmpty()) grupos += Grupo(getString(R.string.bu_recentes), recentes.map { t ->
            Item(t, getString(R.string.bu_recente_det)) { campo.setText(t); campo.setSelection(t.length); buscarJa() }
        })
        return grupos
    }

    private fun vozDisponivel(): Boolean = runCatching {
        packageManager.queryIntentActivities(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH), 0).isNotEmpty()
    }.getOrDefault(false)

    /** O reconhecimento de voz do sistema; o que a pessoa falou vai para o campo e a busca roda na hora. */
    private fun ouvir() {
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_PROMPT, getString(R.string.bu_falar))
        runCatching { startActivityForResult(i, PEDIDO_VOZ) }
    }

    @Deprecated("API antiga, ainda a única sem AndroidX para o resultado da voz")
    override fun onActivityResult(pedido: Int, resultado: Int, dados: Intent?) {
        super.onActivityResult(pedido, resultado, dados)
        if (pedido != PEDIDO_VOZ || resultado != RESULT_OK) return
        val t = dados?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.trim().orEmpty()
        if (t.isEmpty()) return
        campo.setText(t); campo.setSelection(t.length); buscarJa()
    }

    private fun hoje(d: Dados): List<Grupo> {
        if (!Extras.ligado(this, Extras.Chave.HOJE_NA_TV)) return emptyList()
        val agora = System.currentTimeMillis()
        val meuTime = LembretesTv.palavras(this)
        val favoritos = PlayerArmazem.favoritos(this)
        val recentes = PlayerArmazem.recentes(this)
        val interessa = favoritos + recentes
        // Só os canais que podem render sugestão (favoritos, recentes) ou, com o Meu time, todos.
        val porGuia = d.canais.filter { it.guiaId.isNotEmpty() && (meuTime.isNotEmpty() || it.id in interessa) }.groupBy { it.guiaId }
        if (porGuia.isEmpty()) return emptyList()
        val itens = ArrayList<Lembretes.Candidato>()
        PlayerCarga.varrerGuia(this, agora - 6 * 3_600_000L, agora + BuscaUnica.JANELA_HOJE_MS) { p ->
            if (p.fim <= agora) return@varrerGuia
            val canais = porGuia[p.guiaId] ?: return@varrerGuia
            val c = canais.minByOrNull { it.numero } ?: return@varrerGuia
            if (c.id !in interessa && meuTime.none { Lembretes.casa(p.titulo, it) }) return@varrerGuia
            itens += Lembretes.Candidato(c.id, c.nome, c.numero, p)
        }
        val sug = BuscaUnica.hojeNaTv(itens.asSequence(), agora, meuTime, favoritos, recentes)
        if (sug.isEmpty()) return emptyList()
        return listOf(Grupo(getString(R.string.bu_hoje_tv), sug.map { s ->
            itemPrograma(Lembretes.Candidato(s.canalId, s.canalNome, s.numero, s.programa), agora, when (s.motivo) {
                // BuscaUnica (lógica pura) devolve o motivo em português; aqui ele sai no idioma da TV.
                "Meu time" -> getString(R.string.bu_motivo_time)
                "Canal favorito" -> getString(R.string.bu_motivo_favorito)
                "Você assistiu" -> getString(R.string.bu_motivo_assistiu)
                else -> s.motivo
            })
        }))
    }

    private fun desenhar(termo: String, grupos: List<Grupo>) {
        lista.removeAllViews()
        // Só as linhas "Procurar no YouTube..." (ideia 14): o "nada encontrado" continua aparecendo, em cima delas.
        // "Procurar" (rádios mais longe) e "outros apps" não são resultado: só eles = nada encontrado (rodada 15).
        val soAtalhos = setOf(getString(R.string.bu_outros_apps), getString(R.string.elv_bf_procurar))
        if (grupos.isNotEmpty() && grupos.all { it.nome in soAtalhos })
            lista.addView(Estilo.texto(this, Estilo.Papel.LEGENDA, getString(R.string.bu_nada, termo)).apply { setPaddingRelative(dp(4), dp(Estilo.E3), 0, dp(Estilo.E3)) })
        if (grupos.isEmpty()) {
            val msg = when {
                termo.length >= 2 -> getString(R.string.bu_nada, termo)
                Extras.ligado(this, Extras.Chave.HOJE_NA_TV) -> getString(R.string.bu_digite) + " " + getString(R.string.bu_sem_sugestoes)
                else -> getString(R.string.bu_digite)
            }
            lista.addView(Estilo.texto(this, Estilo.Papel.LEGENDA, msg).apply { setPaddingRelative(dp(4), dp(Estilo.E3), 0, 0) })
            return
        }
        for (g in grupos) {
            lista.addView(Estilo.secao(this, g.nome))
            for (i in g.itens) lista.addView(Estilo.linhaOpcao(this, i.titulo, i.sub.ifEmpty { null }).apply {
                // Abriu um resultado: a busca valeu a pena, então entra nas recentes (o próprio item "recente" e o
                // microfone não contam, por isso só com termo de 2 letras ou mais).
                setOnClickListener { if (termo.length >= 2) BuscaRecentes.guardar(this@BuscaActivity, termo); i.abrir() }
            }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(6) })
        }
    }

    private fun tocar(canalId: String, nome: String) {
        CanalTv.tocar(applicationContext, ComandosIa.Acao.TocarCanal(nome, canalId = canalId, nomeCanal = nome))
    }

    /** "Duna: Parte Dois" / "2024 · ▶ Pasta PC 4K · Lista · Addon Cinemeta". */
    private fun itemUnico(t: BibliotecaUnica.Titulo): Item {
        fun rotulo(f: BibliotecaUnica.Fonte): String {
            val tipo = getString(when (f.origem) {
                BibliotecaUnica.Origem.LISTA -> R.string.elv_bib_lista
                BibliotecaUnica.Origem.PASTA -> R.string.elv_bib_pasta
                BibliotecaUnica.Origem.SERVIDOR -> R.string.elv_bib_servidor
                BibliotecaUnica.Origem.NUVEM -> R.string.elv_bib_nuvem
                BibliotecaUnica.Origem.ADDON -> R.string.elv_bib_addon
            })
            val q = when { f.qualidade >= 2160 -> "4K"; f.qualidade >= 1080 -> "1080p"; f.qualidade >= 720 -> "720p"; else -> null }
            return listOfNotNull(tipo, f.onde.takeIf { it.isNotBlank() && f.origem != BibliotecaUnica.Origem.LISTA }?.take(24), q).joinToString(" ")
        }
        val fontes = t.fontes.map { rotulo(it) }.distinct()
        val texto = listOfNotNull(t.ano?.toString(), "▶ " + fontes.first(), fontes.drop(1).take(2).joinToString(" · ").takeIf { it.isNotEmpty() },
            (fontes.size - 3).takeIf { it > 0 }?.let { "+$it" }).joinToString(" · ")
        return Item(Vod.nomeTela(t.nome, t.ano), texto) { abrirFonte(t.melhor, t) }   // sem o ano repetido no nome (main, 16/11)
    }

    /** Abre a melhor fonte: lista no catálogo, arquivo da casa direto no player, servidor e addon na busca deles. */
    private fun abrirFonte(f: BibliotecaUnica.Fonte, t: BibliotecaUnica.Titulo) {
        try {
            when (f.origem) {
                BibliotecaUnica.Origem.LISTA -> catalogo(if (t.serie) SeriesActivity::class.java else FilmesActivity::class.java, f.abrir.ifBlank { t.nome })
                BibliotecaUnica.Origem.PASTA, BibliotecaUnica.Origem.NUVEM ->
                    if (f.abrir.isNotBlank() && FontesMidia.aceito(f.abrir)) VodActivity.abrirUrl(this, f.abrir, t.nome)
                    else startActivity(Intent(this, MidiaRedeActivity::class.java))
                BibliotecaUnica.Origem.SERVIDOR -> startActivity(Intent(this, MidiaRedeActivity::class.java).putExtra(MidiaRedeActivity.EXTRA_BUSCA, f.titulo))
                BibliotecaUnica.Origem.ADDON -> startActivity(Intent(this, AddonsActivity::class.java).putExtra(BibliotecaUnica.EXTRA_BUSCA, f.titulo))
            }
        } catch (e: Exception) { Log.w(TAG, "fonte: ${e.javaClass.simpleName}") }
    }

    private fun catalogo(tela: Class<out Activity>, termo: String) {
        try { startActivity(Intent(this, tela).putExtra(BuscaUnica.EXTRA_TERMO, termo)) }
        catch (e: Exception) { Log.w(TAG, "não abriu ${tela.simpleName}: ${e.javaClass.simpleName}") }
    }

    private fun abrirApp(pacote: String) {
        val i = packageManager.getLeanbackLaunchIntentForPackage(pacote) ?: packageManager.getLaunchIntentForPackage(pacote) ?: return
        try { startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (e: Exception) { Log.w(TAG, "app: $e") }
    }

    /**
     * Ideia 14: a busca do próprio app (ACTION_SEARCH + SearchManager.QUERY, pacote fixo). App que não atende essa
     * busca: abre o app, e o termo fica para a pessoa digitar lá.
     */
    private fun procurarEm(p: BuscaOutrosApps.Pedido) {
        if (!procurarEm(this, p)) abrirApp(p.destino.pacote)
    }

    private fun hora(ms: Long) = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(ms))

    companion object {
        private const val PEDIDO_VOZ = 41
        private const val TAG = "TvLauncher.Busca"
        /** Origem dos pedidos que a busca faz (3.11), como "painel" na caixa do celular. */
        const val ORIGEM = "busca"

        /** A busca do Google TV (katniss) não é app de tela inicial: entra na lista de instalados à parte. */
        fun temGoogleTv(ctx: android.content.Context): Boolean =
            runCatching { ctx.packageManager.getPackageInfo(BuscaOutrosApps.PACOTE_GOOGLE_TV, 0); true }.getOrDefault(false)

        fun comGoogleTv(ctx: android.content.Context, apps: Set<String>): Set<String> =
            if (temGoogleTv(ctx)) apps + BuscaOutrosApps.PACOTE_GOOGLE_TV else apps

        /** Abre a busca do app do [p] (ação e extra do pedido, pacote fixo). false se o app não atende. */
        fun procurarEm(ctx: android.content.Context, p: BuscaOutrosApps.Pedido): Boolean {
            check(BuscaOutrosApps.ACAO == Intent.ACTION_SEARCH && BuscaOutrosApps.ACAO_GLOBAL == SearchManager.INTENT_ACTION_GLOBAL_SEARCH &&
                BuscaOutrosApps.EXTRA_TERMO == SearchManager.QUERY)
            val i = Intent(p.acao).setPackage(p.destino.pacote).putExtra(p.extra, p.termo).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (i.resolveActivity(ctx.packageManager) == null) { Log.i(TAG, "sem ${p.acao} em ${p.destino.pacote}"); return false }
            return try { ctx.startActivity(i); true } catch (e: Exception) { Log.w(TAG, "busca em ${p.destino.pacote}: ${e.javaClass.simpleName}"); false }
        }
    }
}
