package br.paulo.tvlauncher.privilege

import org.json.JSONObject

/**
 * Pareamento guiado pelo celular (degrau 2, item 6, 16/11/2026). O assistente da primeira configuração (painel,
 * primeira.js, passo "Modo completo") mostra no celular, um por vez, os passos que ligam os poderes da TV:
 *   1. TECLAS: o serviço de acessibilidade (HOME sempre volta para cá; e a janela que lê o código do pareamento);
 *   2. DEV: as Opções do desenvolvedor (Build 7 vezes);
 *   3. PAREAR: depuração sem fio + "Parear com código"; PareamentoNaTv lê o código da tela sozinho;
 *   4. DONO (opcional): abre na TV as boas-vindas, onde a pergunta do dono do aparelho fica (o OK é na TV).
 * Aqui só a REGRA de qual é o próximo (PrimeiraPoderesTest); quem abre as telas na TV é o PainelWeb.
 */
object PrimeiraPoderes {
    enum class Passo(val id: String) { TECLAS("teclas"), DEV("dev"), PAREAR("parear"), DONO("dono");
        companion object { fun de(id: String?) = entries.firstOrNull { it.id == id } }
    }

    data class Estado(val teclas: Boolean, val dev: Boolean, val shell: Boolean, val dono: Boolean)

    /** O passo que falta agora, ou null se está tudo feito. Shell funcionando dispensa olhar as Opções do desenvolvedor. */
    fun proximo(e: Estado): Passo? = when {
        !e.teclas -> Passo.TECLAS
        !e.shell && !e.dev -> Passo.DEV
        !e.shell -> Passo.PAREAR
        !e.dono -> Passo.DONO
        else -> null
    }

    fun json(e: Estado): JSONObject = JSONObject()
        .put("teclas", e.teclas).put("dev", e.dev || e.shell).put("shell", e.shell).put("dono", e.dono)
        .put("proximo", proximo(e)?.id ?: JSONObject.NULL)
}
