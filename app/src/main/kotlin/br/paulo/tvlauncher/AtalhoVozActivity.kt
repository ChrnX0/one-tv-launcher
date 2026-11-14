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
        when (AtalhosVoz.destino(alias)) {   // tabela pura e testada (AtalhosVoz)
            AtalhosVoz.Destino.DESLIGAR_30 -> desligarEm(30)
            AtalhosVoz.Destino.DESLIGAR_60 -> desligarEm(60)
            AtalhosVoz.Destino.NAO_DESLIGAR -> avisar(getString(if (Desligamento.cancelar(this)) R.string.dl_cancelado else R.string.dl_nenhum))
            AtalhosVoz.Destino.CENAS -> abrir(CenasActivity::class.java)   // ideia 4: "abrir Cenas"
            AtalhosVoz.Destino.DIAGNOSTICO -> abrir(DiagnosticoActivity::class.java)
            // No modo visita a agenda (do perfil ativo) não abre, igual ao OK na data da HOME.
            AtalhosVoz.Destino.AGENDA -> if (ModoVisita.ativo(this)) avisar(getString(R.string.dl_agenda_visita))
                else abrir(CalendarioActivity::class.java)
            // Ideia 8: as telas que a HOME já abre pelos cards. Sem catálogo/sem casa configurada, cada tela mostra o
            // próprio aviso de vazio (como pelo card).
            AtalhosVoz.Destino.FILMES -> abrir(FilmesActivity::class.java)
            AtalhosVoz.Destino.SERIES -> abrir(SeriesActivity::class.java)
            AtalhosVoz.Destino.NA_REDE -> abrir(MidiaRedeActivity::class.java)
            AtalhosVoz.Destino.CASA -> abrir(CasaActivity::class.java)
            AtalhosVoz.Destino.BUSCA -> abrir(BuscaActivity::class.java)
            // O mural na TV é a lista de compras do painel rápido (ideia 39); desligado ou visita em casa: só avisa.
            AtalhosVoz.Destino.MURAL -> if (!Extras.ligado(this, Extras.Chave.MURAL) || ModoVisita.ativo(this))
                avisar(getString(R.string.voz_mural_desligado)) else abrir(PainelRapidoActivity::class.java)
            null -> {}
        }
        finish()
    }

    private fun abrir(tela: Class<out Activity>) {
        try { startActivity(Intent(this, tela)) }
        catch (e: Exception) { Log.w("TvLauncher.Voz", "não abriu ${tela.simpleName}: ${e.javaClass.simpleName}") }
    }

    private fun desligarEm(minutos: Int) {
        Desligamento.agendar(this, minutos)
        avisar(getString(R.string.dl_desliga_em, Desligamento.duracaoTexto(minutos)))
    }

    private fun avisar(texto: String) = AvisoTv.rapido(this, getString(R.string.dl_atalho_voz), texto)
}
