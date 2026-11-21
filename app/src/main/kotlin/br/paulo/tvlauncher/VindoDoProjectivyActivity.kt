package br.paulo.tvlauncher

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import br.paulo.tvlauncher.home.FundoMeuDados
import br.paulo.tvlauncher.home.Organizacao
import br.paulo.tvlauncher.home.PacoteIcones
import br.paulo.tvlauncher.home.abrir
import br.paulo.tvlauncher.settings.Tema
import java.util.concurrent.Executors

/**
 * "Vindo do Projectivy?" (degrau 1, item 1.1, 15/11/2026): migração guiada em ~1 minuto.
 *
 * Por que guiada e não lendo o backup dele: o arquivo .plbackup do Projectivy é cifrado com uma chave dele, e o Paulo
 * decidiu (15/11 17:30Z) que o One TV não decifra o arquivo de outro app. Então refazemos o que mais dá trabalho, com
 * as peças que já existem: apps ocultos, pacote de ícones (mesmo formato appfilter que ele usa), "Meu fundo" com a
 * foto ou o vídeo que a pessoa já tem no aparelho e, por último, tirar o serviço de acessibilidade dele, que senão
 * briga com o nosso pela tecla HOME.
 *
 * Aparece UMA vez, sozinha, na 1ª HOME depois de instalar se o Projectivy estiver no aparelho; depois fica em
 * Configurações > Aparência enquanto ele estiver instalado.
 */
class VindoDoProjectivyActivity : Activity() {
    companion object {
        const val PACOTE = "com.spocky.projengmenu"
        private const val CHAVE = "vindo_projectivy_visto"

        fun instalado(ctx: Context) = runCatching { ctx.packageManager.getPackageInfo(PACOTE, 0); true }.getOrDefault(false)

        /** Na HOME: mostra uma vez se o Projectivy estiver instalado. Barato: uma leitura de prefs (e só 1 IPC na 1ª vez). */
        fun talvezMostrar(ctx: Context) {
            val prefs = ctx.getSharedPreferences(Prefs.LAUNCHER, Context.MODE_PRIVATE)
            if (prefs.getBoolean(CHAVE, false)) return
            prefs.edit().putBoolean(CHAVE, true).apply()   // antes de abrir: se cair, não reabre em laço
            if (instalado(ctx)) ctx.startActivity(Intent(ctx, VindoDoProjectivyActivity::class.java))
        }
    }

    private val trabalho = Executors.newSingleThreadExecutor()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (VigiaAtualizacao.fechada(this)) return
        montar()
    }

    override fun onRestart() { super.onRestart(); montar() }   // voltou das Configurações do Android ou da lista de apps
    override fun onDestroy() { super.onDestroy(); trabalho.shutdown() }

    private fun montar() {
        val raiz = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = Estilo.fundoTela(context)
            setPaddingRelative(dp(Estilo.MARGEM_LATERAL), dp(Estilo.MARGEM_VERTICAL + 9), dp(Estilo.MARGEM_LATERAL), dp(Estilo.MARGEM_VERTICAL))
        }
        raiz.addView(Estilo.tituloTela(this, getString(R.string.vp_titulo)))
        raiz.addView(Estilo.texto(this, Estilo.Papel.LEGENDA, getString(R.string.vp_texto)),
            LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(4) })
        val lista = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPaddingRelative(dp(8), dp(8), dp(8), dp(8)); clipChildren = false; clipToPadding = false }
        raiz.addView(ScrollView(this).apply { isVerticalScrollBarEnabled = false; clipChildren = false; addView(lista) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = dp(16) })

        val nOcultos = Organizacao.ocultos(this).size
        lista.addView(linha(getString(R.string.vp_1), if (nOcultos == 0) getString(R.string.vp_1_det) else getString(R.string.oc_quantos, nOcultos)) {
            startActivity(Intent(this, AppsEscolhaActivity::class.java).putExtra(AppsEscolhaActivity.EXTRA_MODO, AppsEscolhaActivity.MODO_OCULTOS))
        })
        val pacote = PacoteIcones.escolhido(this)
        lista.addView(linha(getString(R.string.vp_2), if (pacote.isEmpty()) getString(R.string.vp_2_det) else PacoteIcones.nome(this, pacote)) {
            escolherPacote()
        })
        lista.addView(linha(getString(R.string.vp_3), FundoMeuDados.arquivo(this)?.name ?: getString(R.string.vp_3_det)) { escolherFundo() })
        lista.addView(linha(getString(R.string.vp_4), getString(R.string.vp_4_det)) { abrir(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) })
        val pronto = linha(getString(R.string.vp_pronto), getString(R.string.vp_pronto_det)) { finish() }
        lista.addView(pronto)
        setContentView(raiz)
        lista.getChildAt(0)?.requestFocus()
    }

    private fun escolherPacote() = trabalho.execute {
        val l = PacoteIcones.instalados(this)
        runOnUiThread {
            if (isDestroyed) return@runOnUiThread
            if (l.isEmpty()) { aviso(getString(R.string.pi_det_vazio)); return@runOnUiThread }
            val atual = PacoteIcones.escolhido(this)
            Estilo.lista(this, getString(R.string.pi_titulo), listOf(getString(R.string.pi_nenhum)) + l.map { it.second },
                marcadas = listOf(l.indexOfFirst { it.first == atual } + 1)) { i ->
                PacoteIcones.escolher(this, l.getOrNull(i - 1)?.first ?: ""); montar()
            }
        }
    }

    private fun escolherFundo() = trabalho.execute {
        val arquivos = FundoMeuDados.candidatos()
        runOnUiThread {
            if (isDestroyed) return@runOnUiThread
            if (arquivos.isEmpty()) { aviso(getString(R.string.fm_vazio)); return@runOnUiThread }
            val atual = FundoMeuDados.arquivo(this)
            Estilo.lista(this, getString(R.string.fm_titulo), listOf(getString(R.string.fm_nenhum)) + arquivos.map { it.name },
                marcadas = listOf(arquivos.indexOfFirst { it == atual } + 1)) { i ->
                FundoMeuDados.definirArquivo(this, arquivos.getOrNull(i - 1))
                Tema.definir(this, if (i == 0) Tema.ESCURO else Tema.MEU)
                montar()
            }
        }
    }

    private fun aviso(t: String) = AvisoTv.rapido(this, getString(R.string.vp_titulo), t, 4)

    private fun abrir(i: Intent) = try { startActivity(i) } catch (e: Exception) {
        try { startActivity(Intent(Settings.ACTION_SETTINGS)) } catch (_: Exception) {}
    }

    private fun linha(titulo: String, detalhe: String, aoClicar: () -> Unit) = Estilo.linhaOpcao(this, titulo, detalhe).apply {
        setOnClickListener { aoClicar() }
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(8) }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density + 0.5f).toInt()
}
