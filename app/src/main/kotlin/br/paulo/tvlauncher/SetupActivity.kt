package br.paulo.tvlauncher

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.LinearLayout
import android.widget.TextView

/**
 * "Terminar a instalação" (21/11/2026). Só existe no APK de instalação (`-Psetup`, BuildConfig.SETUP).
 *
 * Por quê: numa TV no Brasil, o Play Protect bloqueia sem saída o APK que vem do Downloader/navegador e declara
 * acessibilidade ou ouvinte de notificações. O APK de instalação sai sem esses dois serviços (src/setup/AndroidManifest.xml)
 * e passa. Esta tela, a primeira coisa que ele mostra, traz a versão completa do mesmo Release pelo Atualizador
 * (mesmo pacote, mesma chave, versionCode maior). Sem Device Owner o Android pede um "Instalar" na tela: é o único toque.
 *
 * Fluxo: [Instalar] -> se a TV ainda não deixa a Sofaly instalar apps, abre essa permissão e, ao voltar com ela dada,
 * segue sozinho -> o Atualizador baixa, confere (SHA, pacote, chave) e entrega ao PackageInstaller -> o Android mostra
 * "Instalar". [Depois] fecha; a versão leve segue funcionando e a tela volta na próxima abertura da Sofaly.
 */
class SetupActivity : Activity() {
    private lateinit var estado: TextView
    private var pediuPermissao = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val raiz = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = Estilo.fundoTela(context)
            setPaddingRelative(dp(Estilo.MARGEM_LATERAL), dp(Estilo.MARGEM_VERTICAL + 9), dp(Estilo.MARGEM_LATERAL), dp(Estilo.MARGEM_VERTICAL))
        }
        raiz.addView(Estilo.tituloTela(this, getString(R.string.setup_titulo)))
        raiz.addView(Estilo.texto(this, Estilo.Papel.CORPO, getString(R.string.setup_texto)),
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
        raiz.addView(Estilo.texto(this, Estilo.Papel.CORPO, getString(R.string.setup_passos)),
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
        estado = Estilo.texto(this, Estilo.Papel.LEGENDA, "")
        raiz.addView(estado, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })

        val instalar = linha(getString(R.string.setup_instalar), null) { instalar() }
        raiz.addView(instalar, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(16); bottomMargin = dp(8) })
        raiz.addView(linha(getString(R.string.setup_depois), getString(R.string.setup_depois_det)) { finish() },
            LinearLayout.LayoutParams(-1, -2))
        setContentView(raiz)
        instalar.requestFocus()
    }

    override fun onResume() {
        super.onResume()
        // Voltou da tela "Instalar apps desconhecidos" com a permissão dada: segue sem pedir outro toque.
        if (pediuPermissao && podeInstalar()) { pediuPermissao = false; baixar() }
    }

    private fun instalar() {
        if (!podeInstalar()) {
            // A tela da permissão só para a Sofaly. Uma TV que não a tem (ActivityNotFound) segue direto: o próprio
            // instalador do Android pede a permissão no lugar dela.
            try {
                startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
                pediuPermissao = true
                estado.text = getString(R.string.setup_permitir)
                return
            } catch (_: Exception) {}
        }
        baixar()
    }

    private fun baixar() {
        estado.text = getString(R.string.setup_baixando)
        Atualizador.verificar(this, instalar = true) { r -> runOnUiThread { if (!isDestroyed) estado.text = r } }
    }

    private fun podeInstalar() = try { packageManager.canRequestPackageInstalls() } catch (_: Exception) { true }

    private fun linha(titulo: String, detalhe: String?, aoClicar: () -> Unit) =
        Estilo.linhaOpcao(this, titulo, detalhe).apply { setOnClickListener { aoClicar() } }

    private fun dp(v: Int) = (v * resources.displayMetrics.density + 0.5f).toInt()

    companion object {
        /** Uma vez por processo: a HOME chama a cada criação, e a tela não pode voltar a cada giro de configuração. */
        @Volatile private var mostrou = false

        /** Chamado pela HOME. Fora do APK de instalação não faz nada. */
        fun talvezAbrir(a: Activity) {
            if (!BuildConfig.SETUP || mostrou) return
            mostrou = true
            try { a.startActivity(Intent(a, SetupActivity::class.java)) } catch (_: Exception) {}
        }
    }
}
