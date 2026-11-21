package br.paulo.tvlauncher.family

import android.content.Context
import br.paulo.tvlauncher.Prefs

/**
 * "Como esta TV vai ser usada" (degrau 2, 16/11/2026: modo "só HOME" na 1ª abertura). Responde ao medo de launcher
 * pesado: quem só quer uma tela inicial bonita não vê shell, rede, casa e servidor nas Configurações.
 *
 *  - [SIMPLES]: só a tela inicial. As abas técnicas (Casa, Remoto, Avançado) somem do menu das Configurações e o
 *    assistente não oferece parear o shell nem virar dono. Nada é desligado: só não aparece.
 *  - [COMPLETO]: tudo, como sempre foi (padrão de quem já usava o launcher antes desta escolha existir).
 *  - [CUIDA]: a TV de alguém que você cuida de longe (pais, avós). Menu enxuto como o Simples para quem mora lá, e o
 *    assistente começa pelo que dá acesso de longe: configurar pelo celular (PIN e painel), cuidar de longe e cartões grandes.
 *
 * A escolha muda a qualquer hora em Configurações > Sistema. Guardado em prefs "launcher" (entra no backup).
 */
enum class UsoTv {
    SIMPLES, COMPLETO, CUIDA;

    companion object {
        private const val CHAVE = "uso_tv"

        /** Índices das abas das Configurações que somem fora do Completo (ver ConfigLauncherActivity.abas). */
        val ABAS_TECNICAS = setOf(4, 5, 8)   // Casa, Remoto, Avançado

        fun escolhido(ctx: Context): UsoTv? =
            ctx.getSharedPreferences(Prefs.LAUNCHER, Context.MODE_PRIVATE).getString(CHAVE, null)
                ?.let { n -> entries.firstOrNull { it.name == n } }

        /** O que vale agora: sem escolha (instalação antiga ou TV com Device Owner) = Completo. */
        fun atual(ctx: Context): UsoTv = escolhido(ctx) ?: COMPLETO

        fun escolher(ctx: Context, uso: UsoTv) =
            ctx.getSharedPreferences(Prefs.LAUNCHER, Context.MODE_PRIVATE).edit().putString(CHAVE, uso.name).apply()

        /** Apaga a escolha: o assistente volta a perguntar. */
        fun limpar(ctx: Context) =
            ctx.getSharedPreferences(Prefs.LAUNCHER, Context.MODE_PRIVATE).edit().remove(CHAVE).apply()

        /** A aba [indice] aparece no menu das Configurações? */
        fun mostraAba(uso: UsoTv, indice: Int) = uso == COMPLETO || indice !in ABAS_TECNICAS

        /** O assistente oferece os passos técnicos (parear o shell, virar dono)? */
        fun ofereceTecnico(uso: UsoTv) = uso == COMPLETO

        fun proximo(uso: UsoTv): UsoTv = entries[(uso.ordinal + 1) % entries.size]
    }
}
