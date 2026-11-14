package br.paulo.tvlauncher

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.util.Log

/**
 * Atalhos de voz (05/11/2026, bloco 5 do brainstorm). O Gemini da TV não conhece os comandos do launcher, mas sabe
 * "abrir <nome de app>". Então cada comando vira um "app" com nome falável no manifesto (um activity-alias que
 * aponta para ESTA tela), e "Ok Google, abrir Desligar em 30 minutos" cai aqui. A mesma ideia do "Boletim do Tempo".
 *
 * Esta tela não desenha nada: olha por qual nome foi aberta (o componente do alias), faz o comando e fecha.
 * Os aliases não aparecem na linha de apps do launcher (ela pula o nosso próprio pacote), só no menu de apps da TV.
 */
class AtalhoVozActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (VigiaAtualizacao.fechada(this)) return   // modo seguro: tela que derrubou esta versão (VigiaAtualizacao)
        val alias = intent.component?.className?.substringAfterLast('.') ?: ""
        Log.i("TvLauncher.Voz", "atalho de voz: $alias")
        when (alias) {
            "VozDesligar30" -> desligarEm(30)
            "VozDesligar60" -> desligarEm(60)
            "VozNaoDesligar" -> avisar(getString(if (Desligamento.cancelar(this)) R.string.dl_cancelado else R.string.dl_nenhum))
            "VozCenas" -> startActivity(Intent(this, CenasActivity::class.java))   // ideia 4: "abrir Cenas"
            "VozDiagnostico" -> startActivity(Intent(this, DiagnosticoActivity::class.java))
            // No modo visita a agenda (do perfil ativo) não abre, igual ao OK na data da HOME.
            "VozAgenda" -> if (ModoVisita.ativo(this)) avisar(getString(R.string.dl_agenda_visita))
                else startActivity(Intent(this, CalendarioActivity::class.java))
        }
        finish()
    }

    private fun desligarEm(minutos: Int) {
        Desligamento.agendar(this, minutos)
        avisar(getString(R.string.dl_desliga_em, Desligamento.duracaoTexto(minutos)))
    }

    private fun avisar(texto: String) = AvisoTv.rapido(this, getString(R.string.dl_atalho_voz), texto)
}
