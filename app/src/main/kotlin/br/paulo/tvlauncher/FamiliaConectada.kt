package br.paulo.tvlauncher

import android.app.Activity
import android.content.Context
import android.os.Bundle
import android.view.Gravity
import android.widget.ImageView
import android.widget.LinearLayout
import br.paulo.tvlauncher.family.Mural
import br.paulo.tvlauncher.family.Recado
import br.paulo.tvlauncher.family.RecadoImagem
import br.paulo.tvlauncher.mcp.McpServidor
import br.paulo.tvlauncher.mcp.TunelTailscale
import br.paulo.tvlauncher.network.Cenas
import br.paulo.tvlauncher.network.EntreTvs
import br.paulo.tvlauncher.panel.QrCodigo
import br.paulo.tvlauncher.screensaver.FotosProtetor
import br.paulo.tvlauncher.weather.AvisoRegras
import java.security.MessageDigest

/**
 * Família conectada (degrau 3, 16/11/2026): filhos e netos mandam foto e recado para a TV por um LINK, sem conta e
 * sem app. O link é https://<nome da TV>.ts.net/familia/<token>, servido pelo túnel do Tailscale (o mesmo do MCP:
 * tunel/main.go repassa o :443 para o McpServidor). Nada passa por servidor nosso.
 *  - recado: aparece na tela por 1 minuto (cartão de recado) e fica no mural do protetor, com o nome de quem mandou;
 *  - foto: aparece na tela com o recado e entra nas fotos do protetor (FotosProtetor), com o nome "familia-...".
 * Nada de alerta de ausência nem de "a TV está ligada?": só aproxima (decisão do Paulo, 14/11).
 *
 * Segurança: token de 128 bits; a TV guarda o token (para mostrar o QR de novo) em prefs FORA do backup; comparação
 * em tempo constante; no máximo [MAX_POR_DIA] envios por dia; foto só JPEG/PNG/WEBP até [MAX_FOTO]; texto passa pelas
 * mesmas limpezas do recado. Trocar o link invalida o antigo na hora. Regras puras em [Regras] (FamiliaConectadaTest).
 */
object FamiliaConectada {
    const val MAX_POR_DIA = 40
    const val MAX_FOTO = 3 * 1024 * 1024
    const val MAX_NOME = 30
    private const val PREFS = "familia_link"

    object Regras {
        private val TOKEN = Regex("^[0-9a-f]{32}$")
        /** Ações do link. Degrau 4: "video" (RecadoVideo) e "estado" (a página pergunta se os vídeos estão aceitos). */
        val ACOES = setOf("recado", "foto", "video", "estado")

        fun tokenValido(t: String?) = t != null && TOKEN.matches(t)

        /** O token pedido bate com o guardado? Tempo constante; sem link ligado, nunca. */
        fun confere(pedido: String?, guardado: String?): Boolean =
            tokenValido(pedido) && tokenValido(guardado) && MessageDigest.isEqual(pedido!!.toByteArray(), guardado!!.toByteArray())

        /** "/familia/<token>" e "/familia/<token>/recado|foto|video|estado" -> (token, ação); "" = a página. Null = não é nosso. */
        fun rota(caminho: String): Pair<String, String>? {
            val p = caminho.removePrefix("/familia/").takeIf { it != caminho } ?: return null
            val partes = p.trimEnd('/').split('/')
            return when {
                partes.size == 1 -> partes[0] to ""
                partes.size == 2 && partes[1] in ACOES -> partes[0] to partes[1]
                else -> null
            }
        }

        /** Nome de quem manda: letras, números, espaço, . - ', até [MAX_NOME]. Vazio = "Família" (quem chama decide). */
        fun nome(bruto: String?): String = (bruto ?: "").filter { it.isLetterOrDigit() || it in " .-'" }
            .replace(Regex("\\s+"), " ").trim().take(MAX_NOME)

        /** Contador do dia: (dia, quantos). Novo dia zera. True = ainda pode mandar (e já conta este). */
        fun contar(dia: String, ultimoDia: String?, quantos: Int): Pair<Boolean, Int> {
            val n = if (dia == ultimoDia) quantos else 0
            return if (n >= MAX_POR_DIA) false to n else true to n + 1
        }
    }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    fun token(ctx: Context): String? = prefs(ctx).getString("token", null)?.takeIf { Regras.tokenValido(it) }
    fun ligado(ctx: Context) = token(ctx) != null

    /** Liga (ou troca) o link: um token novo; o antigo deixa de valer na hora. */
    fun novoLink(ctx: Context): String = Cenas.gerarToken().also { prefs(ctx).edit().putString("token", it).commit(); ajustarServidor(ctx) }
    fun desligar(ctx: Context) { prefs(ctx).edit().remove("token").commit(); ajustarServidor(ctx) }

    /** O servidor e o túnel sobem também só pelo link (McpServidor.precisaServidor), sem ligar o acesso do Claude. */
    private fun ajustarServidor(ctx: Context) = Fundo.rodar("familia-tunel") { McpServidor.ajustar(ctx); TunelTailscale.ajustar(ctx) }

    /** O endereço para o QR, ou null se o acesso de fora (túnel) ainda não tem nome. */
    fun link(ctx: Context): String? {
        val t = token(ctx) ?: return null
        val nome = TunelTailscale.nome(ctx) ?: return null
        return "https://$nome/familia/$t"
    }

    @Synchronized fun podeMandar(ctx: Context): Boolean {
        val dia = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date())
        val p = prefs(ctx)
        val (ok, n) = Regras.contar(dia, p.getString("dia", null), p.getInt("quantos", 0))
        p.edit().putString("dia", dia).putInt("quantos", n).apply()
        return ok
    }

    /** Recado (e foto opcional) que chegou. Devolve null se deu certo, ou o código do erro para a página. */
    fun receber(ctx: Context, nomeBruto: String?, texto: String?, foto: ByteArray?): String? {
        val app = ctx.applicationContext
        val quem = Regras.nome(nomeBruto).ifEmpty { app.getString(R.string.famc_alguem) }
        val t = texto?.let { Recado.limpar(it) }
        if (foto == null && t == null) return "vazio"
        if (foto != null && (foto.size > MAX_FOTO || RecadoImagem.tipo(foto) == null)) return "foto"
        if (!podeMandar(app)) return "limite"
        val titulo = app.getString(R.string.famc_de, quem)
        if (foto != null) {
            val bmp = RecadoImagem.decodificar(foto) ?: return "foto"
            // Também nas fotos do protetor (a pasta tem limite: cheia, só não guarda; o cartão aparece mesmo assim).
            val nome = "familia-" + java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US).format(java.util.Date()) +
                when (RecadoImagem.tipo(foto)) { RecadoImagem.Tipo.PNG -> ".png"; RecadoImagem.Tipo.WEBP -> ".webp"; else -> ".jpg" }
            FotosProtetor.receber(app, nome, foto.size.toLong(), foto.inputStream())
            AvisoTv.mostrar(app, titulo, t ?: "📷", 60, som = true, guardarSeApagada = true, imagem = bmp, fica = AvisoRegras.Fica.ATENCAO)
        } else {
            AvisoTv.mostrar(app, titulo, t!!, 60, som = true, guardarSeApagada = true, fica = AvisoRegras.Fica.ATENCAO)
        }
        Gatilhos.disparar(app, Gatilhos.Evento.RECADO_CHEGOU)   // elevação 10.6
        if (t != null) Mural.editar(app) { Mural.adicionar(it, Mural.Lista.RECADOS, "$quem: $t") }
        return null
    }
}

/** Configurações › Família › Família conectada: o QR do link (para mandar aos parentes) e trocar ou desligar. */
class FamiliaConectadaActivity : Activity() {
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        montar()
    }

    private fun montar() {
        if (!FamiliaConectada.ligado(this)) FamiliaConectada.novoLink(this)
        val link = FamiliaConectada.link(this)
        val raiz = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = Estilo.fundoTela(context)
            setPaddingRelative(dp(Estilo.MARGEM_LATERAL), dp(Estilo.MARGEM_VERTICAL + 9), dp(Estilo.MARGEM_LATERAL), dp(Estilo.MARGEM_VERTICAL))
        }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(Estilo.tituloTela(this, getString(R.string.famc_titulo)))
        col.addView(Estilo.texto(this, Estilo.Papel.CORPO, getString(if (link != null) R.string.famc_texto else R.string.famc_sem_tunel)).apply { textSize = 20f },
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(16) })
        val pronto = Estilo.linhaOpcao(this, getString(R.string.fp_fechar)).apply { setOnClickListener { finish() } }   // era "Começar"
        col.addView(pronto, LinearLayout.LayoutParams(dp(300), -2).apply { topMargin = dp(20) })
        // Degrau 4: recado em vídeo. Opt-in de quem mora aqui (desligado de fábrica) e a lista dos que chegaram.
        col.addView(Estilo.linhaOpcao(this, getString(R.string.vid_aceitar), getString(R.string.vid_aceitar_det),
            Estilo.valor(this, getString(if (RecadoVideo.aceita(this)) R.string.rec_ligado else R.string.rec_desligado), seta = false)).apply {
            setOnClickListener { RecadoVideo.definirAceita(this@FamiliaConectadaActivity, !RecadoVideo.aceita(this@FamiliaConectadaActivity)); recreate() }
        }, LinearLayout.LayoutParams(dp(300), -2).apply { topMargin = dp(8) })
        if (RecadoVideo.lista(this).isNotEmpty()) col.addView(Estilo.linhaOpcao(this, getString(R.string.vid_lista),
            getString(R.string.vid_quantos, RecadoVideo.lista(this).size)).apply {
            setOnClickListener { startActivity(android.content.Intent(this@FamiliaConectadaActivity, RecadoVideoActivity::class.java)) }
        }, LinearLayout.LayoutParams(dp(300), -2).apply { topMargin = dp(8) })
        // Degrau 4: as TVs da mesma conta conversam (Sessão junto, Continuar em outra TV). Opt-in, desligado de fábrica.
        col.addView(Estilo.linhaOpcao(this, getString(R.string.junto_tvs), getString(R.string.junto_tvs_det),
            Estilo.valor(this, getString(if (EntreTvs.ligado(this)) R.string.rec_ligado else R.string.rec_desligado), seta = false)).apply {
            setOnClickListener { EntreTvs.definir(this@FamiliaConectadaActivity, !EntreTvs.ligado(this@FamiliaConectadaActivity)); recreate() }
        }, LinearLayout.LayoutParams(dp(300), -2).apply { topMargin = dp(8) })
        col.addView(Estilo.linhaOpcao(this, getString(R.string.famc_trocar), getString(R.string.famc_trocar_det)).apply {
            setOnClickListener { FamiliaConectada.novoLink(this@FamiliaConectadaActivity); recreate() }
        }, LinearLayout.LayoutParams(dp(300), -2).apply { topMargin = dp(8) })
        col.addView(Estilo.linhaOpcao(this, getString(R.string.famc_desligar)).apply {
            setOnClickListener { FamiliaConectada.desligar(this@FamiliaConectadaActivity); finish() }
        }, LinearLayout.LayoutParams(dp(300), -2).apply { topMargin = dp(8) })
        raiz.addView(col, LinearLayout.LayoutParams(0, -2, 1f))
        if (link != null) {
            val lado = (resources.displayMetrics.heightPixels * 0.55f).toInt()
            raiz.addView(ImageView(this).apply { setImageBitmap(QrCodigo.bitmap(link, lado)) },
                LinearLayout.LayoutParams(lado, lado).apply { marginStart = dp(40) })
        }
        setContentView(raiz)
        pronto.requestFocus()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density + 0.5f).toInt()
}
