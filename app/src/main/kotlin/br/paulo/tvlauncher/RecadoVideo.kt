package br.paulo.tvlauncher

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Bundle
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream

/**
 * Recado em vídeo (degrau 4, 17/11/2026): a família conectada (FamiliaConectada.kt) ganha "Gravar vídeo" na página do
 * link. Até [MAX_SEGUNDOS] s e [MAX_BYTES]; no máximo [MAX_POR_DIA] por dia; tudo junto até [MAX_TOTAL] (apaga os mais
 * antigos) e cada um fica [DIAS] dias. Toca no NOSSO player (VodActivity, arquivo local).
 *
 * Opt-in: desligado de fábrica; quem mora na casa liga em Configurações › Família › Família conectada ("Aceitar
 * vídeos"). Desligado, a página nem mostra o botão e o servidor recusa.
 *
 * Nunca toca sozinho: chega um cartão "Vídeo de Ana · OK para assistir" (só com a HOME na frente, a tela acesa e fora
 * da madrugada; senão só o aviso de passagem e o mural). O arquivo vai direto para o disco (sem passar pela memória) e
 * é conferido ANTES de entrar na lista: cabeçalho de vídeo (MP4/MOV ou WebM) e duração lida pelo Android; o que não
 * passa é apagado.
 *
 * WebM além de MP4: o Chrome do Android grava WebM quando a página usa o gravador do navegador; com o seletor de
 * arquivo/câmera (o que a página usa) vem MP4/MOV. Os dois tocam no Media3 [inferência: não testado num aparelho].
 *
 * Os arquivos ficam em getExternalFilesDir("familia-video") (privado do app no Android 11+): o player só abre file://
 * de /storage/ (PastaArquivos.arquivoLocalPermitido).
 */
object RecadoVideo {
    const val MAX_SEGUNDOS = 30
    const val MAX_BYTES = 20L * 1024 * 1024
    const val MAX_POR_DIA = 5
    const val MAX_TOTAL = 200L * 1024 * 1024
    const val DIAS = 30
    /** Tempo para receber o arquivo todo pelo túnel (20 MB a ~1 Mbit/s levariam 160 s). */
    const val PRAZO_ENVIO_MS = 240_000L
    private const val TAG = "TvLauncher.Video"
    private const val PREFS = "familia_video"

    /** Regras puras (RecadoVideoTest). */
    object Regras {
        enum class Tipo(val extensao: String) { MP4(".mp4"), MOV(".mov"), WEBM(".webm") }

        private val MARCAS_MP4 = setOf("isom", "iso2", "iso4", "iso5", "iso6", "mp41", "mp42", "avc1", "M4V ", "MSNV", "3gp4", "3gp5", "3g2a", "mmp4")

        /** O tipo pelo cabeçalho (os primeiros 12 bytes), ou null se não for vídeo que aceitamos. */
        fun tipo(cab: ByteArray): Tipo? {
            if (cab.size >= 4 && cab[0] == 0x1A.toByte() && cab[1] == 0x45.toByte() && cab[2] == 0xDF.toByte() && cab[3] == 0xA3.toByte()) return Tipo.WEBM
            if (cab.size < 12 || String(cab, 4, 4, Charsets.ISO_8859_1) != "ftyp") return null
            val marca = String(cab, 8, 4, Charsets.ISO_8859_1)
            return when {
                marca == "qt  " -> Tipo.MOV
                marca in MARCAS_MP4 -> Tipo.MP4
                else -> null
            }
        }

        /** O tamanho anunciado cabe? (0 ou negativo = sem corpo / chunked: recusa.) */
        fun tamanhoOk(bytes: Long) = bytes in 1..MAX_BYTES

        /** A duração lida pelo Android cabe? 1 s de folga (o celular grava 30,4 s quando a pessoa para no 30). */
        fun duracaoOk(ms: Long?) = ms != null && ms in 1..(MAX_SEGUNDOS * 1000L + 1000L)

        /** Contador do dia (como FamiliaConectada.Regras.contar), com o teto de vídeos. */
        fun contar(dia: String, ultimoDia: String?, quantos: Int): Pair<Boolean, Int> {
            val n = if (dia == ultimoDia) quantos else 0
            return if (n >= MAX_POR_DIA) false to n else true to n + 1
        }

        data class Guardado(val arquivo: String, val bytes: Long, val quando: Long)

        /**
         * O que apagar para caber um novo de [novo] bytes: primeiro os de mais de [DIAS] dias, depois os mais antigos,
         * até o total (com o novo) ficar em [MAX_TOTAL]. Devolve os nomes, do mais antigo ao mais novo.
         */
        fun apagar(lista: List<Guardado>, novo: Long, agora: Long): List<String> {
            val limite = agora - DIAS * 86_400_000L
            val ordem = lista.sortedBy { it.quando }
            val fora = ordem.filter { it.quando < limite }.toMutableList()
            var total = ordem.filter { it.quando >= limite }.sumOf { it.bytes } + novo
            for (g in ordem) {
                if (total <= MAX_TOTAL) break
                if (g in fora) continue
                fora += g; total -= g.bytes
            }
            return fora.sortedBy { it.quando }.map { it.arquivo }
        }

        /** O cartão grande só com a HOME na frente, a tela acesa e fora da madrugada (antes das 7h, depois das 22h). */
        fun podeAbrirCartao(hora: Int, naHome: Boolean, telaAcesa: Boolean) = naHome && telaAcesa && hora in 7..21

        /** Nome do arquivo: só o que nós geramos (data + sorteio + extensão). Defesa contra caminho vindo das prefs. */
        private val NOME = Regex("^v-\\d{8}-\\d{6}-[0-9a-f]{8}\\.(mp4|mov|webm)$")
        fun nomeValido(n: String?) = n != null && NOME.matches(n)
    }

    data class Video(val arquivo: String, val quem: String, val texto: String, val quando: Long, val bytes: Long, val visto: Boolean)

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    fun aceita(ctx: Context) = prefs(ctx).getBoolean("aceita", false)
    fun definirAceita(ctx: Context, sim: Boolean) = prefs(ctx).edit().putBoolean("aceita", sim).apply()

    fun pasta(ctx: Context): File? = ctx.getExternalFilesDir("familia-video")?.also { it.mkdirs() }

    /** A lista (mais novo primeiro), sem os que sumiram do disco. */
    @Synchronized fun lista(ctx: Context): List<Video> {
        val dir = pasta(ctx) ?: return emptyList()
        val a = try { JSONArray(prefs(ctx).getString("lista", "[]")) } catch (_: Exception) { JSONArray() }
        return (0 until a.length()).mapNotNull { i ->
            val o = a.optJSONObject(i) ?: return@mapNotNull null
            val n = o.optString("a")
            if (!Regras.nomeValido(n) || !File(dir, n).isFile) null
            else Video(n, o.optString("q"), o.optString("t"), o.optLong("w"), o.optLong("b"), o.optBoolean("v"))
        }.sortedByDescending { it.quando }
    }

    private fun gravarLista(ctx: Context, l: List<Video>) {
        val a = JSONArray()
        l.forEach { a.put(JSONObject().put("a", it.arquivo).put("q", it.quem).put("t", it.texto).put("w", it.quando).put("b", it.bytes).put("v", it.visto)) }
        prefs(ctx).edit().putString("lista", a.toString()).apply()
    }

    fun naoVistos(ctx: Context) = lista(ctx).count { !it.visto }

    @Synchronized private fun podeMandar(ctx: Context): Boolean {
        val dia = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date())
        val p = prefs(ctx)
        val (ok, n) = Regras.contar(dia, p.getString("dia", null), p.getInt("quantos", 0))
        p.edit().putString("dia", dia).putInt("quantos", n).apply()
        return ok
    }

    /**
     * Um vídeo que está chegando: [entrada] com [tamanho] bytes (Content-Length). Copia para o disco com teto, confere
     * e guarda. Devolve null se deu certo, ou o código do erro para a página ("desligado", "tamanho", "limite",
     * "video", "duracao", "disco").
     */
    fun receber(ctx: Context, nomeBruto: String?, textoBruto: String?, entrada: InputStream, tamanho: Long, rid: String? = null): String? {
        val app = ctx.applicationContext
        if (!aceita(app) || !Planos.permite(app, Planos.Recurso.RECADO_VIDEO)) return "desligado"   // plano: Pro
        if (!Regras.tamanhoOk(tamanho)) return "tamanho"
        val dir = pasta(app) ?: return "disco"
        if (dir.usableSpace < tamanho + 50L * 1024 * 1024) return "disco"
        if (!podeMandar(app)) return "limite"
        val quem = FamiliaConectada.Regras.nome(nomeBruto).ifEmpty { app.getString(R.string.famc_alguem) }
        val texto = textoBruto?.let { Recado.limpar(it) } ?: ""
        val tmp = File(dir, ".chegando-" + System.nanoTime())
        try {
            var lidos = 0L
            val cab = ByteArray(12)
            tmp.outputStream().buffered(64 * 1024).use { out ->
                val buf = ByteArray(64 * 1024)
                while (lidos < tamanho) {
                    val n = entrada.read(buf, 0, minOf(buf.size.toLong(), tamanho - lidos).toInt())
                    if (n < 0) return "video"
                    if (lidos < 12) System.arraycopy(buf, 0, cab, lidos.toInt(), minOf(n.toLong(), 12 - lidos).toInt())
                    // Cabeçalho errado: para de receber já nos primeiros bytes (não gasta disco com o que não é vídeo).
                    if (lidos < 12 && lidos + n >= 12 && Regras.tipo(cab) == null) return "video"
                    out.write(buf, 0, n)
                    lidos += n
                }
            }
            val tipo = Regras.tipo(cab) ?: return "video"
            val ms = duracao(tmp) ?: return "video"
            if (!Regras.duracaoOk(ms)) return "duracao"
            val agora = System.currentTimeMillis()
            val nome = "v-" + java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US).format(java.util.Date(agora)) +
                "-" + Cenas.gerarToken().take(8) + tipo.extensao
            synchronized(this) {
                val atuais = lista(app)
                val fora = Regras.apagar(atuais.map { Regras.Guardado(it.arquivo, it.bytes, it.quando) }, tamanho, agora).toSet()
                fora.forEach { File(dir, it).delete() }
                if (!tmp.renameTo(File(dir, nome))) return "disco"
                gravarLista(app, listOf(Video(nome, quem, texto, agora, tamanho, false)) + atuais.filter { it.arquivo !in fora })
            }
            FamiliaVistos.registrar(app, rid, quem, "🎬 " + texto, false, idFixo = nome)   // "❤ Vi": visto quando assistir
            avisar(app, quem, texto, nome)
            Log.i(TAG, "vídeo recebido: ${tamanho / 1024} KB, ${ms / 1000} s")
            return null
        } catch (e: Exception) {
            Log.w(TAG, "vídeo: ${e.javaClass.simpleName}")
            return "video"
        } finally { tmp.delete() }
    }

    /** Duração em ms, e só se houver trilha de vídeo; null = o Android não reconhece como vídeo. */
    private fun duracao(f: File): Long? {
        val m = MediaMetadataRetriever()
        return try {
            m.setDataSource(f.absolutePath)
            if (m.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO) != "yes") null
            else m.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
        } catch (_: Exception) { null } finally { try { m.release() } catch (_: Exception) {} }
    }

    private fun avisar(app: Context, quem: String, texto: String, arquivo: String) {
        val titulo = app.getString(R.string.vid_de, quem)
        Mural.editar(app) { Mural.adicionar(it, Mural.Lista.RECADOS, "$quem: 🎬 " + texto.ifEmpty { app.getString(R.string.vid_mural) }) }
        val hora = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        val acesa = app.getSystemService(android.os.PowerManager::class.java)?.isInteractive == true
        if (Regras.podeAbrirCartao(hora, SaudeAcesso.naHome(app), acesa)) {
            try {
                app.startActivity(Intent(app, RecadoVideoActivity::class.java).putExtra(RecadoVideoActivity.EXTRA_ARQUIVO, arquivo)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return
            } catch (_: Exception) {}
        }
        // Vendo um filme, de madrugada ou com a tela apagada: só o aviso de passagem (que já respeita a tela apagada).
        AvisoTv.mostrar(app, titulo, app.getString(R.string.vid_onde), 20, som = true, guardarSeApagada = true, fica = AvisoRegras.Fica.ATENCAO)
    }

    fun marcarVisto(ctx: Context, arquivo: String) {
        synchronized(this) { gravarLista(ctx, lista(ctx).map { if (it.arquivo == arquivo) it.copy(visto = true) else it }) }
        FamiliaVistos.marcar(ctx, arquivo)   // quem mandou vê "❤ Visto" na página
    }

    fun apagar(ctx: Context, arquivo: String) {
        if (!Regras.nomeValido(arquivo)) return
        synchronized(this) {
            pasta(ctx)?.let { File(it, arquivo).delete() }
            gravarLista(ctx, lista(ctx).filter { it.arquivo != arquivo })
        }
    }

    /** Toca no nosso player (nunca em app de fora). */
    fun tocar(a: Activity, v: Video) {
        val f = File(pasta(a) ?: return, v.arquivo)
        marcarVisto(a, v.arquivo)
        VodActivity.abrirUrl(a, Uri.fromFile(f).toString(), a.getString(R.string.vid_de, v.quem))
    }

    /** Para o adb: "3 vídeos (1 não visto), 12 MB; aceita=true". */
    fun estado(ctx: Context): String {
        val l = lista(ctx)
        return "aceita=${aceita(ctx)} videos=${l.size} naoVistos=${l.count { !it.visto }} total=${l.sumOf { it.bytes } / 1024 / 1024}MB"
    }
}

/**
 * O cartão "Vídeo de Ana · OK para assistir" (com [EXTRA_ARQUIVO]) ou a lista "Vídeos da família" (sem ele). OK
 * assiste no nosso player; na lista, cada linha tem quem mandou, quando e o recado.
 */
class RecadoVideoActivity : Activity() {
    companion object { const val EXTRA_ARQUIVO = "arquivo" }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        if (VigiaAtualizacao.fechada(this)) return
        val v = intent.getStringExtra(EXTRA_ARQUIVO)?.let { n -> RecadoVideo.lista(this).firstOrNull { it.arquivo == n } }
        if (v != null) cartao(v) else montarLista()
    }

    override fun onRestart() { super.onRestart(); if (intent.getStringExtra(EXTRA_ARQUIVO) == null) montarLista() else finish() }

    private fun cartao(v: RecadoVideo.Video) {
        val depois = Estilo.botao(this, getString(R.string.vid_depois), primario = false).apply { setOnClickListener { finish() } }
        val ver = Estilo.botao(this, getString(R.string.vid_assistir)).apply { setOnClickListener { RecadoVideo.tocar(this@RecadoVideoActivity, v) } }
        setContentView(Estilo.dialogo(this, null, getString(R.string.vid_de, v.quem),
            listOf(v.texto, getString(R.string.vid_ok_para_assistir)).filter { it.isNotEmpty() }.joinToString("\n"), listOf(depois, ver), larguraDp = 720))
        ver.post { ver.requestFocus() }   // depois do layout: senão o Android devolve o foco ao primeiro botão
    }

    private fun montarLista() {
        val (raiz, coluna) = Estilo.telaLista(this, getString(R.string.vid_lista), getString(R.string.vid_lista_det))
        val l = RecadoVideo.lista(this)
        if (l.isEmpty()) coluna.addView(Estilo.linhaTexto(this, getString(R.string.vid_nenhum)), Estilo.lpLinha(this))
        val fmt = android.text.format.DateFormat.getMediumDateFormat(this)
        for (v in l) {
            val det = fmt.format(java.util.Date(v.quando)) + (if (v.texto.isNotEmpty()) " · ${v.texto}" else "")
            coluna.addView(Estilo.linhaOpcao(this, (if (v.visto) "" else "● ") + getString(R.string.vid_de, v.quem), det).apply {
                setOnClickListener { RecadoVideo.tocar(this@RecadoVideoActivity, v) }
                setOnLongClickListener {
                    CaixaTv(this@RecadoVideoActivity).setTitle(getString(R.string.vid_apagar)).setMessage(getString(R.string.vid_de, v.quem))
                        .setPositiveButton(getString(R.string.vid_apagar)) { _, _ -> RecadoVideo.apagar(this@RecadoVideoActivity, v.arquivo); montarLista() }
                        .setNegativeButton(getString(R.string.rec_voltar), null).show()
                    true
                }
            }, Estilo.lpLinha(this))
        }
        setContentView(raiz)
        (coluna.getChildAt(0))?.requestFocus()
    }
}
