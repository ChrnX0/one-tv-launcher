package br.paulo.tvlauncher

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.Log
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
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
                        val iApps: List<BuscaUnica.Indexado<Pair<String, String>>>, val titulos: Map<String, List<String>>)
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
            setPadding(dp(22), dp(14), dp(22), dp(14))
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
            Dados(canais, BuscaUnica.indexar(canais) { it.nome }, BuscaUnica.indexar(filmes) { it.nome },
                BuscaUnica.indexar(series) { it.nome }, BuscaUnica.indexar(apps()) { it.second }, titulosDoGuia(canais))
                .also { Log.i(TAG, "índice: ${canais.size} canais, ${filmes.size} filmes, ${series.size} séries, " +
                    "${it.titulos.size} títulos do guia em ${System.currentTimeMillis() - t0} ms") }
        } catch (e: Exception) {
            Log.w(TAG, "carregar: $e")
            Dados(emptyList(), emptyList(), emptyList(), emptyList(), BuscaUnica.indexar(apps()) { it.second }, emptyMap())
        }
        dados = d
        ui.post { if (!isFinishing) buscarJa() }
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
            val r = try { if (termo.length < 2) hoje(d) else resultados(d, termo) } catch (e: Exception) { Log.w(TAG, "busca: $e"); emptyList() }
            if (termo.length >= 2) Log.i(TAG, "busca (${termo.length} letras): ${System.currentTimeMillis() - t0} ms")
            ui.post { if (minha == geracao && !isFinishing) desenhar(termo, r) }
        }
    }

    /** Uma linha do resultado: título, explicação e o que o OK faz. */
    private class Item(val titulo: String, val sub: String, val abrir: () -> Unit)
    private class Grupo(val nome: String, val itens: List<Item>)

    private fun resultados(d: Dados, termo: String): List<Grupo> {
        val grupos = ArrayList<Grupo>()
        val canais = BuscaUnica.filtrarIndice(d.iCanais, termo).first
        if (canais.isNotEmpty()) grupos += Grupo(getString(R.string.bu_canais), canais.map { c -> Item(c.nome, getString(R.string.bu_canal_n, c.numero)) { tocar(c.id, c.nome) } })
        val progs = programas(d, termo)
        if (progs.isNotEmpty()) grupos += Grupo(getString(R.string.bu_no_guia), progs)
        val (filmes, total) = BuscaUnica.filtrarIndice(d.iFilmes, termo)
        if (filmes.isNotEmpty()) {
            grupos += Grupo(getString(R.string.home_card_filmes), filmes.map { f ->
                Item(f.nome, listOfNotNull(f.ano?.toString(), f.grupo.takeIf { it.isNotBlank() }).joinToString(" · ")) { catalogo(FilmesActivity::class.java, f.nome) }
            } + Item(getString(R.string.bu_ver_filmes, total), getString(R.string.bu_ver_filmes_det)) { catalogo(FilmesActivity::class.java, termo) })
        }
        val (series, totalSeries) = BuscaUnica.filtrarIndice(d.iSeries, termo)
        if (series.isNotEmpty()) {
            grupos += Grupo(getString(R.string.home_card_series), series.map { s ->
                Item(s.nome, listOfNotNull(s.ano?.toString(), s.grupo.takeIf { it.isNotBlank() }).joinToString(" · ")) { catalogo(SeriesActivity::class.java, s.nome) }
            } + Item(getString(R.string.bu_ver_series, totalSeries), getString(R.string.bu_ver_series_det)) { catalogo(SeriesActivity::class.java, termo) })
        }
        val apps = BuscaUnica.filtrarIndice(d.iApps, termo).first
        if (apps.isNotEmpty()) grupos += Grupo("Apps", apps.map { (pacote, nome) -> Item(nome, "App") { abrirApp(pacote) } })
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
        val quero = d.titulos.entries.mapNotNull { (n, t) -> BuscaUnica.pontosNorm(n, p)?.let { pts -> Triple(t, pts, n.length) } }
            .sortedWith(compareBy({ it.second }, { it.third })).take(100).flatMap { it.first }
        if (quero.isEmpty()) return emptyList()
        val porGuia = d.canais.filter { it.guiaId.isNotEmpty() }.groupBy { it.guiaId }
        val achados = ArrayList<Lembretes.Candidato>()
        PlayerCarga.programasDoGuia(this, quero, agora - 6 * 3_600_000L, agora + BuscaUnica.HORIZONTE_PROGRAMAS_MS) { prog ->
            if (prog.fim <= agora || achados.size >= 400) return@programasDoGuia
            porGuia[prog.guiaId]?.minByOrNull { it.numero }?.let { c -> achados += Lembretes.Candidato(c.id, c.nome, c.numero, prog) }
        }
        val melhores = BuscaUnica.filtrar(BuscaUnica.agrupar(achados), termo, max = BuscaUnica.MAX_POR_GRUPO) { it.first.programa.titulo }
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
        if (grupos.isEmpty()) {
            val msg = when {
                termo.length >= 2 -> getString(R.string.bu_nada, termo)
                Extras.ligado(this, Extras.Chave.HOJE_NA_TV) -> getString(R.string.bu_digite) + " " + getString(R.string.bu_sem_sugestoes)
                else -> getString(R.string.bu_digite)
            }
            lista.addView(Estilo.texto(this, Estilo.Papel.LEGENDA, msg).apply { setPadding(dp(4), dp(Estilo.E3), 0, 0) })
            return
        }
        for (g in grupos) {
            lista.addView(Estilo.secao(this, g.nome))
            for (i in g.itens) lista.addView(Estilo.linhaOpcao(this, i.titulo, i.sub.ifEmpty { null }).apply {
                setOnClickListener { i.abrir() }
            }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(6) })
        }
    }

    private fun tocar(canalId: String, nome: String) {
        CanalTv.tocar(applicationContext, ComandosIa.Acao.TocarCanal(nome, canalId = canalId, nomeCanal = nome))
    }

    private fun catalogo(tela: Class<out Activity>, termo: String) {
        try { startActivity(Intent(this, tela).putExtra(BuscaUnica.EXTRA_TERMO, termo)) }
        catch (e: Exception) { Log.w(TAG, "não abriu ${tela.simpleName}: ${e.javaClass.simpleName}") }
    }

    private fun abrirApp(pacote: String) {
        val i = packageManager.getLeanbackLaunchIntentForPackage(pacote) ?: packageManager.getLaunchIntentForPackage(pacote) ?: return
        try { startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (e: Exception) { Log.w(TAG, "app: $e") }
    }

    private fun hora(ms: Long) = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(ms))

    companion object { private const val TAG = "TvLauncher.Busca" }
}
