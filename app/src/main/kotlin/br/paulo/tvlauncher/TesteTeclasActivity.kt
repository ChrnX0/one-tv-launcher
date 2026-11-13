package br.paulo.tvlauncher

import android.app.Activity
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.TextView

/**
 * Tela do testador de teclas (ver [TesteTeclas]): mostra, ao vivo, cada tecla que o serviço de acessibilidade recebe.
 * Enquanto esta tela está aberta, o testador fica armado (e é desarmado ao sair). VOLTAR também aparece na lista antes
 * de fechar a tela; para sair sem registrar nada, use INÍCIO.
 */
class TesteTeclasActivity : Activity() {
    private val ui = Handler(Looper.getMainLooper())
    private lateinit var lista: TextView
    private lateinit var situacao: TextView

    private val rearmar = object : Runnable {
        override fun run() { TesteTeclas.armar(); ui.postDelayed(this, TesteTeclas.JANELA_MS / 2) }
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        if (VigiaAtualizacao.fechada(this)) return   // modo seguro: tela que derrubou esta versão (VigiaAtualizacao)
        // Molde único de tela (Estilo.telaLista): fundo, título e margens iguais às outras telas.
        val (raiz, coluna) = Estilo.telaLista(this, getString(R.string.teclas_titulo))
        situacao = Estilo.texto(this, Estilo.Papel.CORPO, getString(R.string.teclas_instrucao))
        // Lista em fonte de largura fixa: os códigos das teclas ficam alinhados em coluna.
        lista = Estilo.texto(this, Estilo.Papel.LEGENDA).apply { typeface = Typeface.MONOSPACE; setPadding(0, Estilo.dp(context, Estilo.E2), 0, 0) }
        coluna.addView(situacao)
        coluna.addView(lista)
        setContentView(raiz)
    }

    override fun onResume() {
        super.onResume()
        TesteTeclas.aoRegistrar = { ui.post { mostrar() } }
        ui.post(rearmar)
        mostrar()
    }

    override fun onPause() {
        ui.removeCallbacks(rearmar)
        TesteTeclas.aoRegistrar = null
        TesteTeclas.desarmar()   // fora desta tela, nada de registro de teclas
        super.onPause()
    }

    private fun mostrar() {
        val itens = TesteTeclas.lista().takeLast(18).reversed()
        lista.text = if (itens.isEmpty()) getString(R.string.teclas_vazio) else itens.joinToString("\n") { it.texto() }
    }
}
