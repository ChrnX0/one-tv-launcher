package br.paulo.tvlauncher

import android.app.Activity
import android.os.Bundle

/**
 * Trava do "Limpar dados" SEM Device Owner (12/11/2026).
 *
 * O manifesto declara esta tela como `android:manageSpaceActivity` do launcher. Nas Configurações da TV, ao apertar
 * "Limpar dados" de um app que declara essa tela, o Android NÃO apaga nada: abre a tela do próprio app (li no código do
 * TvSettings do Android 14, AppManagementFragment.clearData: com manageSpaceActivityName preenchido ele só chama
 * startActivityForResult). É o mecanismo que apps de galeria e e-mail usam para "gerenciar espaço".
 *
 * Aqui a tela só explica e fecha: as configurações, o pareamento do shell e os alarmes ficam. Quem quiser zerar mesmo
 * usa o adb (`pm clear`), que não passa por aqui. Não verificado na TV: o Google TV usa o TvSettings "TwoPanel" de
 * sistema (versão 1.0); se a Google tiver mudado esse trecho, o botão volta a apagar.
 *
 * Exportada porque quem abre é o app de Configurações (outro app); não faz nada além de mostrar o aviso.
 */
class GuardarDadosActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CaixaTv(this)
            .setTitle(R.string.guardar_dados_titulo)
            .setMessage(R.string.guardar_dados_texto)
            .setPositiveButton(android.R.string.ok, null)
            .setOnDismissListener { setResult(RESULT_CANCELED); finish() }
            .show()
    }
}
