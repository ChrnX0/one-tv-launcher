package br.paulo.tvlauncher

import android.view.KeyEvent
import org.json.JSONArray
import org.json.JSONObject

/**
 * Testador de teclas ao vivo (10/11/2026, ideia F; li no tv-remote: "Every key the TV gets, live: name, code, which
 * remote, press or hold, remapped or not").
 *
 * Por que importa aqui: `input keyevent` pelo adb NÃO passa pelo filtro de teclas da acessibilidade (CLAUDE.md, "Limites
 * de teste"). Então o que depende do serviço de teclas só se testa com o controle de verdade. Com o testador armado, o
 * dono aperta o controle e quem está do outro lado (adb, tela da TV) lê exatamente o que o serviço recebeu.
 *
 * Segurança: um registro de teclas é um keylogger. Por isso só grava enquanto ARMADO, por no máximo [JANELA_MS]
 * (2 min), só na memória (some ao reiniciar o processo), e no máximo [CAPACIDADE] teclas. Armar exige adb (receiver
 * protegido por DUMP) ou abrir a tela do testador na própria TV.
 */
object TesteTeclas {
    const val JANELA_MS = 120_000L
    const val CAPACIDADE = 60

    class Registro(
        val hora: Long, val nome: String, val codigo: Int, val scan: Int, val aparelho: String,
        val desce: Boolean, val repete: Int, val longo: Boolean, val remapeada: Boolean,
    ) {
        fun json(): JSONObject = JSONObject().put("hora", hora).put("nome", nome).put("codigo", codigo).put("scan", scan)
            .put("aparelho", aparelho).put("acao", if (desce) "desce" else "sobe").put("repete", repete)
            .put("longo", longo).put("remapeada", remapeada)

        fun texto(): String = "$nome ($codigo) ${if (desce) "↓" else "↑"}" + (if (repete > 0) " ×$repete" else "") +
            (if (longo) " longo" else "") + (if (remapeada) " remapeada" else "") + " · $aparelho"
    }

    private val fila = ArrayDeque<Registro>()
    @Volatile private var armadoAte = 0L
    @Volatile var aoRegistrar: ((Registro) -> Unit)? = null   // a tela do testador, se aberta

    fun armar(agora: Long = System.currentTimeMillis()) { armadoAte = agora + JANELA_MS }
    fun desarmar() { armadoAte = 0L; synchronized(fila) { fila.clear() } }
    fun armado(agora: Long = System.currentTimeMillis()): Boolean = agora < armadoAte

    /** Chamado pelo serviço de acessibilidade a CADA tecla. Custo quando desarmado: uma comparação. */
    fun registrar(e: KeyEvent) {
        if (!armado()) return
        val r = Registro(
            hora = System.currentTimeMillis(), nome = KeyEvent.keyCodeToString(e.keyCode).removePrefix("KEYCODE_"),
            codigo = e.keyCode, scan = e.scanCode, aparelho = e.device?.name ?: "?",
            desce = e.action == KeyEvent.ACTION_DOWN, repete = e.repeatCount,
            longo = (e.flags and KeyEvent.FLAG_LONG_PRESS) != 0, remapeada = BotoesControle.botao(e.keyCode) != null,
        )
        adicionar(r)
        aoRegistrar?.invoke(r)
    }

    fun adicionar(r: Registro) = synchronized(fila) {
        fila.addLast(r)
        while (fila.size > CAPACIDADE) fila.removeFirst()
    }

    fun lista(): List<Registro> = synchronized(fila) { fila.toList() }

    fun json(agora: Long = System.currentTimeMillis()): JSONObject = JSONObject()
        .put("armado", armado(agora)).put("restante_ms", (armadoAte - agora).coerceAtLeast(0))
        .put("teclas", JSONArray().apply { lista().forEach { put(it.json()) } })
}
