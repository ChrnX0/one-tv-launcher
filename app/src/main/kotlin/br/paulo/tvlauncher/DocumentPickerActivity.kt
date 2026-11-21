package br.paulo.tvlauncher

import android.app.Activity
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.text.format.Formatter
import android.view.KeyEvent
import android.view.View
import android.widget.LinearLayout
import br.paulo.tvlauncher.family.Diario
import br.paulo.tvlauncher.player.MidiaContas

/**
 * O seletor de pastas e arquivos da TV (21/11/2026), no lugar do seletor do Android que o Google TV não traz.
 *
 * Porquê: apps como o RetroArch da Play pedem "escolha uma pasta" (OPEN_DOCUMENT_TREE) e recebiam "nenhum app
 * compatível". Para quê: escolher, com o controle e em letra grande, uma pasta ou arquivo da memória da TV, do pen
 * drive ou do "Na rede". Como: a tela navega pelos lugares do [TvDocumentsProvider] e devolve ao app o endereço do que
 * foi escolhido, com permissão só para aquilo. Atende OPEN_DOCUMENT_TREE, OPEN_DOCUMENT, GET_CONTENT e CREATE_DOCUMENT;
 * sendo Device Owner, o Sofaly se fixa como o padrão desses pedidos (PrivilegioDO.fixarSeletor).
 * Liga com: lembra a última pasta de cada app (abre direto nela da próxima vez) e anota no diário da TV o que cada app
 * recebeu (o painel e o Claude veem "RetroArch recebeu a pasta Games/Retro-Arch").
 */
class DocumentPickerActivity : Activity() {
    private lateinit var modo: DocumentIds.Modo
    private var filtros: List<String> = emptyList()
    private var app: String = ""
    private var nomeApp: String = ""
    /** Onde estamos; null = a lista de lugares. */
    private var atual: DocumentIds.Id? = null
    /** Cada ida à rede ganha um número; uma resposta antiga (a pessoa já voltou) é descartada. */
    private var pedido = 0
    private var ocupado = false

    private fun prefs() = getSharedPreferences("seletor_docs", Context.MODE_PRIVATE)

    override fun onCreate(estado: Bundle?) {
        super.onCreate(estado)
        if (VigiaAtualizacao.fechada(this)) return
        modo = DocumentIds.modo(intent.action) ?: return finish()
        filtros = (intent.getStringArrayExtra(Intent.EXTRA_MIME_TYPES)?.toList().orEmpty() + listOfNotNull(intent.type)).distinct()
        app = callingActivity?.packageName ?: callingPackage.orEmpty()
        nomeApp = runCatching { packageManager.getApplicationLabel(packageManager.getApplicationInfo(app, 0)).toString() }.getOrDefault(app)
        Estilo.tirarCorDaJanelaSeCoberta(this)
        // Começa onde este app parou da última vez (se ainda existir); senão, na lista de lugares.
        val ultimo = DocumentIds.ler(prefs().getString("ultimo:$app", null))
        abrir(ultimo, voltarAosLugaresSeFalhar = true)
    }

    private fun titulo(): String = getString(when (modo) {
        DocumentIds.Modo.PASTA -> R.string.dp_titulo_pasta
        DocumentIds.Modo.ABRIR -> R.string.dp_titulo_arquivo
        DocumentIds.Modo.CRIAR -> R.string.dp_titulo_salvar
    })

    /** Subtítulo: para qual app e onde estamos ("Para RetroArch · Pen drive › Games"). */
    private fun subtitulo(lugar: String?): String {
        val para = if (nomeApp.isNotEmpty()) getString(R.string.dp_para, nomeApp) else null
        val onde = atual?.let { a -> listOfNotNull(lugar, a.caminho.takeIf { it.isNotEmpty() }?.replace("/", " › ")).joinToString(" › ") }
        return listOfNotNull(para, onde).joinToString("  ·  ")
    }

    private fun montar(lugar: String?, linhas: (LinearLayout) -> View?) {
        val (raiz, coluna) = Estilo.telaLista(this, titulo(), subtitulo(lugar))
        val foco = linhas(coluna)
        (raiz as LinearLayout).addView(Estilo.dicasDeTexto(this, getString(R.string.dp_dicas)),
            LinearLayout.LayoutParams(-2, -2).apply { bottomMargin = Estilo.dp(this@DocumentPickerActivity, Estilo.MARGEM_VERTICAL) })
        setContentView(raiz)
        (foco ?: coluna.getChildAt(0))?.requestFocus()
    }

    private fun linha(c: LinearLayout, titulo: String, explicacao: String?, seta: Boolean, aoClicar: () -> Unit): View =
        Estilo.linhaOpcao(this, titulo, explicacao, if (seta) Estilo.valor(this, "", true) else null).apply {
            setOnClickListener { if (!ocupado) aoClicar() }
            c.addView(this, Estilo.lpLinha(this@DocumentPickerActivity))
        }

    private fun legenda(c: LinearLayout, texto: String) {
        c.addView(Estilo.texto(this, Estilo.Papel.LEGENDA, texto), Estilo.lpLinha(this).apply { topMargin = Estilo.dp(this@DocumentPickerActivity, Estilo.E2) })
    }

    /** A primeira tela: memória da TV, pen drives e pastas do "Na rede". */
    private fun mostrarLugares() {
        atual = null; pedido++; ocupado = false
        val lugares = TvDocumentsProvider.lugares(this)
        montar(null) { c ->
            for (l in lugares) {
                val expl = when {
                    l.resumo != null -> l.resumo
                    l.livre != null -> getString(R.string.dp_livre, Formatter.formatShortFileSize(this, l.livre))
                    else -> null
                }
                linha(c, l.titulo, expl, true) { abrir(l.raiz) }
            }
            if (lugares.isEmpty()) legenda(c, getString(R.string.dp_erro))
            null
        }
    }

    /** Lista a pasta fora da tela (a rede demora) e desenha quando chegar. */
    private fun abrir(id: DocumentIds.Id?, voltarAosLugaresSeFalhar: Boolean = false) {
        if (id == null) return mostrarLugares()
        val meu = ++pedido
        ocupado = true
        val lugar = TvDocumentsProvider.lugares(this).firstOrNull { it.raiz.raiz == id.raiz }?.titulo
        if (lugar == null) return mostrarLugares()   // o pen drive saiu ou a conta foi apagada
        atual = id
        montar(lugar) { c -> legenda(c, getString(R.string.dp_abrindo)); null }
        Fundo.rodar("seletor-listar") {
            val r = runCatching { TvDocumentsProvider.listar(applicationContext, id) }
            runOnUiThread {
                if (meu != pedido || isFinishing || isDestroyed) return@runOnUiThread
                ocupado = false
                r.onSuccess { desenhar(id, lugar, it) }.onFailure {
                    if (voltarAosLugaresSeFalhar) mostrarLugares()
                    else montar(lugar) { c -> legenda(c, getString(R.string.dp_erro)); null }
                }
            }
        }
    }

    private fun desenhar(id: DocumentIds.Id, lugar: String, itens: List<TvDocumentsProvider.Companion.Item>) {
        val pastas = itens.filter { it.pasta }
        val arquivos = itens.filter { !it.pasta }
        montar(lugar) { c ->
            var foco: View? = null
            when (modo) {
                DocumentIds.Modo.PASTA -> foco = Estilo.botao(this, getString(R.string.dp_usar_pasta)).apply {
                    setOnClickListener { if (!ocupado) devolverPasta(id) }
                    c.addView(this, LinearLayout.LayoutParams(-2, -2).apply { bottomMargin = Estilo.dp(this@DocumentPickerActivity, Estilo.E1) })
                }
                DocumentIds.Modo.CRIAR -> foco = Estilo.botao(this, getString(R.string.dp_salvar_aqui)).apply {
                    setOnClickListener { if (!ocupado) criarAqui(id) }
                    c.addView(this, LinearLayout.LayoutParams(-2, -2).apply { bottomMargin = Estilo.dp(this@DocumentPickerActivity, Estilo.E1) })
                }
                DocumentIds.Modo.ABRIR -> {}
            }
            for (p in pastas) linha(c, p.nome, null, true) { abrir(p.id) }
            if (modo == DocumentIds.Modo.ABRIR) {
                for (a in arquivos.filter { DocumentIds.tipoServe(TvDocumentsProvider.tipoPorNome(it.nome), filtros) })
                    linha(c, a.nome, Formatter.formatShortFileSize(this, a.tamanho), false) { devolverArquivo(a.id) }
            } else if (arquivos.isNotEmpty()) {
                // Escolhendo pasta, os arquivos não se escolhem; a contagem mostra que é a pasta certa (ex.: as ROMs).
                legenda(c, getString(R.string.dp_arquivos, arquivos.size))
            }
            if (pastas.isEmpty() && (modo != DocumentIds.Modo.ABRIR || arquivos.isEmpty())) legenda(c, getString(R.string.dp_vazia))
            foco
        }
    }

    private fun uriDoc(id: DocumentIds.Id): Uri = DocumentsContract.buildDocumentUri(TvDocumentsProvider.autoridade(this), id.toString())

    private fun lembrar(pasta: DocumentIds.Id) {
        if (app.isNotEmpty()) prefs().edit().putString("ultimo:$app", pasta.toString()).apply()
    }

    private fun anotar(o: String) {
        runCatching { Diario.anotar(this, "tv", getString(R.string.dp_diario, nomeApp.ifEmpty { "?" }, o), "ok") }
    }

    private fun devolverPasta(id: DocumentIds.Id) {
        val uri = DocumentsContract.buildTreeDocumentUri(TvDocumentsProvider.autoridade(this), id.toString())
        lembrar(id)
        anotar(id.caminho.ifEmpty { "/" })
        setResult(RESULT_OK, Intent().setData(uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or
            Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION))
        finish()
    }

    private fun devolverArquivo(id: DocumentIds.Id) {
        val uri = uriDoc(id)
        id.pai?.let { lembrar(it) }
        anotar(id.caminho)
        var flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
        // OPEN_DOCUMENT: o app pode guardar o acesso e gravar; GET_CONTENT: só uma leitura agora (regra do Android).
        if (intent.action == Intent.ACTION_OPEN_DOCUMENT) flags = flags or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
        val r = Intent().setData(uri).addFlags(flags)
        if (intent.getBooleanExtra(Intent.EXTRA_ALLOW_MULTIPLE, false)) r.clipData = ClipData.newRawUri(null, uri)
        setResult(RESULT_OK, r)
        finish()
    }

    /** CREATE_DOCUMENT: cria o arquivo com o nome que o app sugeriu (sem apagar um que já exista) e devolve. */
    private fun criarAqui(pasta: DocumentIds.Id) {
        ocupado = true
        val nome = intent.getStringExtra(Intent.EXTRA_TITLE)?.trim()?.takeIf { DocumentIds.nomeValido(it) } ?: getString(R.string.dp_sem_nome)
        val tipo = intent.type ?: "application/octet-stream"
        Fundo.rodar("seletor-criar") {
            val r = runCatching {
                DocumentsContract.createDocument(contentResolver, uriDoc(pasta), tipo, nome) ?: error("não criou")
            }
            runOnUiThread {
                ocupado = false
                r.onSuccess { uri ->
                    lembrar(pasta)
                    anotar(MidiaContas.juntar(pasta.caminho, nome))
                    setResult(RESULT_OK, Intent().setData(uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION))
                    finish()
                }.onFailure { android.widget.Toast.makeText(this, R.string.dp_erro, android.widget.Toast.LENGTH_LONG).show() }
            }
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            val a = atual
            if (a != null) { abrir(a.pai); return true }   // sobe uma pasta; da raiz de um lugar, volta aos lugares
            setResult(RESULT_CANCELED)
            finish()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }
}
