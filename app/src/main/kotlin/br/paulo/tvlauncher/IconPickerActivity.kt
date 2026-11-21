package br.paulo.tvlauncher

import android.app.Activity
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.util.LruCache
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.FrameLayout
import android.widget.GridView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import br.paulo.tvlauncher.home.CustomIcons
import br.paulo.tvlauncher.home.CustomIconsRules
import br.paulo.tvlauncher.home.IconPackFreezer
import br.paulo.tvlauncher.home.PacoteIcones
import br.paulo.tvlauncher.home.abrir
import br.paulo.tvlauncher.settings.Fontes
import java.util.concurrent.Executors

/**
 * "Trocar ícone" (18/11/2026): a grade com os ícones de UM pacote para UM app. O 1º é o que o pacote já liga ao app,
 * depois os parecidos com o nome (CustomIconsRules.ordenar) e o resto em ordem alfabética. OK escolhe e fecha; a HOME
 * redesenha o app ao voltar (a troca entra na chave do cache de ícones).
 *
 * Visual da cruz: fundo grafite, título grande, cada ícone num cartão com o foco de cartão de sempre (Estilo.focoCard) e
 * a legenda em letra legível a 3 m. Os ícones são lidos fora da thread da tela, 1 por célula, com um cache pequeno.
 */
class IconPickerActivity : Activity() {
    private val leitor = Executors.newSingleThreadExecutor { r -> Thread(r, "IconPicker").apply { isDaemon = true } }
    private val cache = LruCache<String, Drawable>(160)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (VigiaAtualizacao.fechada(this)) return
        val app = intent.getStringExtra(EXTRA_APP) ?: return finish()
        val classe = intent.getStringExtra(EXTRA_CLASSE)
        val pacote = intent.getStringExtra(EXTRA_PACOTE) ?: return finish()
        val nomeApp = intent.getStringExtra(EXTRA_NOME) ?: app
        val raiz = Estilo.telaBase(this)
        val coluna = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        coluna.addView(Estilo.tituloTela(this, getString(R.string.wp_icone_titulo, nomeApp)))
        val sub = Estilo.texto(this, Estilo.Papel.LEGENDA, PacoteIcones.nome(this, pacote)).apply { setPaddingRelative(0, dp(4), 0, dp(16)) }
        coluna.addView(sub)
        val grade = GridView(this).apply {
            numColumns = GridView.AUTO_FIT
            columnWidth = dp(CELULA)
            stretchMode = GridView.STRETCH_SPACING_UNIFORM
            verticalSpacing = dp(14)
            clipToPadding = false
            setPaddingRelative(dp(10), dp(10), dp(10), dp(10))
            // O foco da grade é a seleção do GridView: o mesmo anel branco do foco de cartão, por cima da célula.
            selector = Estilo.fundo(context, 0, Estilo.RAIO + 3, Estilo.FOCO, 3)
            setDrawSelectorOnTop(true)
            isFocusable = true
        }
        coluna.addView(grade, LinearLayout.LayoutParams(-1, 0, 1f))
        raiz.addView(coluna, FrameLayout.LayoutParams(-1, -1))
        setContentView(raiz)
        leitor.execute {
            // Pacote congelado que o Android não deixa ler: descongela enquanto a grade está aberta (congela de novo ao sair).
            if (IconPackFreezer.recursos(this, pacote) == null) IconPackFreezer.abrir(this, pacote)
            val mapa = PacoteIcones.mapaDeQualquer(this, pacote)
            val doApp = mapa?.drawable(app, classe)
            val todos = mapa?.let { it.porComponente.values } ?: emptyList()
            val lista = CustomIconsRules.ordenar(todos, doApp, nomeApp, app)
            val nSugeridos = CustomIconsRules.sugeridos(lista, doApp, nomeApp, app)
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                sub.text = getString(R.string.wp_icone_sub, PacoteIcones.nome(this, pacote), lista.size)
                grade.adapter = Adaptador(pacote, listOf<String?>(null) + lista, nSugeridos)
                grade.setOnItemClickListener { _, _, pos, _ ->
                    val nome = if (pos == 0) null else lista.getOrNull(pos - 1)
                    CustomIcons.definir(this, app, nome?.let { CustomIconsRules.escrever(pacote, it) } ?: CustomIcons.ORIGINAL)
                    setResult(RESULT_OK)
                    finish()
                }
                grade.requestFocus()
                // Já escolhido antes: o foco começa nele.
                CustomIconsRules.ler(CustomIcons.escolha(this, app))?.takeIf { it.first == pacote }?.let { (_, n) ->
                    lista.indexOf(n).takeIf { it >= 0 }?.let { grade.setSelection(it + 1) }
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        leitor.shutdownNow()
        // Regra do Paulo: o pacote só fica descongelado enquanto se escolhe. A cópia dos ícones é completada antes.
        intent.getStringExtra(EXTRA_PACOTE)?.let { IconPackFreezer.congelarDepois(this, it) }
    }

    private fun dp(v: Int) = Estilo.dp(this, v)

    /** Célula: o ícone (null = "original" do app) e o nome; [sugeridos] primeiros ganham a etiqueta "Sugerido". */
    private inner class Adaptador(val pacote: String, val nomes: List<String?>, val sugeridos: Int) : BaseAdapter() {
        override fun getCount() = nomes.size
        override fun getItem(p: Int) = nomes[p]
        override fun getItemId(p: Int) = p.toLong()
        override fun getView(p: Int, reuso: View?, pai: ViewGroup?): View {
            val v = (reuso as? LinearLayout) ?: celula()
            val img = v.getChildAt(0) as ImageView
            val txt = v.getChildAt(1) as TextView
            val nome = nomes[p]
            img.tag = nome ?: ORIGINAL
            txt.text = when {
                nome == null -> getString(R.string.wp_icone_original)
                p <= sugeridos -> getString(R.string.wp_icone_sugerido) + " · " + CustomIconsRules.legenda(nome)
                else -> CustomIconsRules.legenda(nome)
            }
            val chave = nome ?: ORIGINAL
            val pronto = cache.get(chave)
            img.setImageDrawable(pronto)
            if (pronto == null) leitor.execute {
                // A célula já mostra outro ícone (a grade rolou): pula. Sem isto, rolando rápido, a fila de uma thread
                // desenhava centenas de ícones que já saíram da tela antes dos que estão nela.
                if (img.tag != chave) return@execute
                val d = (if (nome == null) runCatching { packageManager.getApplicationIcon(intent.getStringExtra(EXTRA_APP)!!) }.getOrNull()
                    else PacoteIcones.desenho(this@IconPickerActivity, pacote, nome, guardar = false)) ?: return@execute
                cache.put(chave, d)
                img.post { if (img.tag == chave) img.setImageDrawable(d) }
            }
            return v
        }

        private fun celula() = LinearLayout(this@IconPickerActivity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPaddingRelative(dp(10), dp(12), dp(10), dp(10))
            background = Estilo.fundo(context, Estilo.alfa(0xFFFFFFFF.toInt(), 0x14))
            addView(ImageView(context).apply { scaleType = ImageView.ScaleType.FIT_CENTER }, LinearLayout.LayoutParams(dp(ICONE), dp(ICONE)))
            addView(TextView(context).apply {
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f); typeface = Fontes.nunito(500); setTextColor(Estilo.TEXTO)
                maxLines = 2; gravity = Gravity.CENTER; ellipsize = android.text.TextUtils.TruncateAt.END
                setPaddingRelative(0, dp(8), 0, 0)
            }, LinearLayout.LayoutParams(-1, -2))
            layoutParams = android.widget.AbsListView.LayoutParams(dp(CELULA), dp(CELULA + 34))
        }
    }

    companion object {
        const val EXTRA_APP = "app"
        const val EXTRA_CLASSE = "classe"
        const val EXTRA_PACOTE = "pacote"
        const val EXTRA_NOME = "nome"
        private const val ORIGINAL = "\u0000original"
        private const val CELULA = 150
        private const val ICONE = 84
    }
}
