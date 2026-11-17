package br.paulo.tvlauncher

import android.app.Activity
import android.app.Dialog
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.Window

/**
 * A parte de tela das travas de plano (revisão dos degraus, T2, 17/11/2026). Quem vai abrir uma função paga pergunta
 * [pedir]: liberado, devolve true e segue; senão mostra "Isto faz parte do plano X" com "Ver os planos" (ConhecerPro) e
 * devolve false. Fora do build da Play o plano é sempre Ultra ([Planos.atual]), então a Sala nunca vê este aviso.
 */
object PlanosTv {
    fun pedir(a: Activity, r: Planos.Recurso): Boolean {
        if (Planos.permite(a, r)) return true
        val plano = a.getString(if (Planos.MINIMO[r] == Planos.Plano.ULTRA) R.string.pl_ultra else R.string.pl_pro)
        val d = Dialog(a)
        val agoraNao = Estilo.botao(a, a.getString(R.string.plt_agora_nao), primario = false).apply { setOnClickListener { d.dismiss() } }
        val ver = Estilo.botao(a, a.getString(R.string.plt_ver)).apply {
            setOnClickListener { d.dismiss(); a.startActivity(Intent(a, ConhecerProActivity::class.java)) }
        }
        d.requestWindowFeature(Window.FEATURE_NO_TITLE)
        d.setContentView(Estilo.dialogo(a, null, a.getString(R.string.plt_titulo, plano), a.getString(R.string.plt_texto, plano),
            listOf(agoraNao, ver)))
        d.window?.apply { setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT)); setLayout(-1, -1) }
        d.show()
        ver.requestFocus()
        return false
    }
}
