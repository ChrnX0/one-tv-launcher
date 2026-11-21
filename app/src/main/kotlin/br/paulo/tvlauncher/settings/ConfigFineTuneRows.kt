package br.paulo.tvlauncher.settings

import android.widget.LinearLayout
import br.paulo.tvlauncher.ConfigLauncherActivity
import br.paulo.tvlauncher.ConfigLauncherActivity.LinhaCfg
import br.paulo.tvlauncher.Estilo
import br.paulo.tvlauncher.R

/**
 * "Ajuste fino" na aba Aparência (21/11/2026): letra, formato e cor da hora. Ficam logo abaixo do Tema porque mudam a
 * mesma coisa que ele (a cara da tela). A tela inicial se recria sozinha ao voltar (MainActivity.assinaturaDoLayout).
 */
internal fun ConfigLauncherActivity.linhasAjusteFino(c: LinearLayout) {
    // Painel com prévia ao vivo: tudo abaixo e mais cor de destaque, cantos, brilho e tamanho da cruz, vendo a HOME.
    c.addView(linha(getString(R.string.ft_abrir), getString(R.string.ft_abrir_det), seta = true) {
        br.paulo.tvlauncher.FineTuneActivity.abrir(this)
    }, lp())
    lateinit var linhaFonte: LinhaCfg
    linhaFonte = linha(getString(R.string.fc_titulo), getString(R.string.fc_det), valor = FontChoice.current(this).name) {
        val fontes = FontChoice.FONTS
        val atual = fontes.indexOfFirst { it.id == FontChoice.currentId(this) }
        Estilo.lista(this, getString(R.string.fc_titulo), fontes.map { it.name }, marcadas = listOf(atual)) { i ->
            linhaFonte.valor(getString(R.string.fc_baixando))
            FontChoice.choose(this, fontes[i].id) { ok ->
                if (isDestroyed) return@choose
                if (ok) recreate()   // a própria tela de opções já aparece na letra nova
                else { linhaFonte.valor(FontChoice.current(this).name); aviso(getString(R.string.fc_falhou)) }
            }
        }
    }
    c.addView(linhaFonte, lp())

    fun nomeFormato(f: ClockStyle.Format) = getString(when (f) {
        ClockStyle.Format.AUTO -> R.string.ck_auto
        ClockStyle.Format.H24 -> R.string.ck_24
        ClockStyle.Format.H12 -> R.string.ck_12
        ClockStyle.Format.H12_AMPM -> R.string.ck_12ampm
        ClockStyle.Format.SECONDS -> R.string.ck_seg
    })
    lateinit var linhaFormato: LinhaCfg
    linhaFormato = linha(getString(R.string.ck_formato), getString(R.string.ck_formato_det), valor = nomeFormato(ClockStyle.format(this))) {
        linhaFormato.valor(nomeFormato(ClockStyle.nextFormat(this)))
    }
    c.addView(linhaFormato, lp())

    fun nomeCor(k: ClockStyle.Color) = getString(when (k) {
        ClockStyle.Color.DEFAULT -> R.string.ck_padrao
        ClockStyle.Color.ORANGE -> R.string.ck_laranja
        ClockStyle.Color.CREAM -> R.string.ck_creme
        ClockStyle.Color.AMBER -> R.string.ck_ambar
        ClockStyle.Color.TEAL -> R.string.ck_verde
    })
    lateinit var linhaCor: LinhaCfg
    linhaCor = linha(getString(R.string.ck_cor), getString(R.string.ck_cor_det), valor = nomeCor(ClockStyle.color(this))) {
        linhaCor.valor(nomeCor(ClockStyle.nextColor(this)))
    }
    c.addView(linhaCor, lp())
}
