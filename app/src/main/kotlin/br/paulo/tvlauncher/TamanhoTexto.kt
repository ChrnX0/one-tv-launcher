package br.paulo.tvlauncher

import android.app.Activity
import android.content.Context

/**
 * Ideia 47 (14/11/2026): um tamanho de texto só, para todas as telas do launcher (antes só Filmes, Séries e TV ao vivo
 * tinham a letra maior, Estilo.Leitura). O LauncherApp aplica [aplicar] em cada tela antes do onCreate dela.
 *
 * Como: todo texto do launcher é em sp, e sp = dp × scaledDensity. Mudar o scaledDensity da tela muda tudo de uma vez,
 * sem mexer em cada arquivo. Medidas em dp (alturas de linha, cards) não mudam, por isso o máximo é 120%: acima disso
 * o texto começa a não caber nos blocos de altura fixa (painel rápido, grade de pôsteres). Se o Android já estiver com
 * letra aumentada na acessibilidade, ele usa uma conversão própria (não linear) e esta escolha não se soma a ela.
 *
 * Decisão anterior que isto toca: a letra maior de Filmes/Séries/TV (13/11). Ela continua; o tamanho escolhido aqui
 * vale por cima dela também. Padrão = 100% (nada muda).
 */
object TamanhoTexto {
    val OPCOES = listOf(100, 110, 120)
    const val CHAVE = "tamanho_texto"

    /** Próxima opção (dá a volta); valor fora da lista volta ao normal. */
    fun proximo(atual: Int): Int = OPCOES[(OPCOES.indexOf(atual) + 1).mod(OPCOES.size)]

    fun valido(v: Int): Int = if (v in OPCOES) v else 100

    fun atual(ctx: Context): Int = valido(Extras.numero(ctx, CHAVE, 100))
    fun definir(ctx: Context, v: Int) = Extras.definirNumero(ctx, CHAVE, valido(v))

    /** Ajusta o scaledDensity da tela [a] ao tamanho escolhido (respeitando a escala de letra do sistema). */
    fun aplicar(a: Activity) {
        val pct = atual(a)
        val dm = a.resources.displayMetrics
        val sistema = a.resources.configuration.fontScale
        @Suppress("DEPRECATION")
        dm.scaledDensity = dm.density * sistema * pct / 100f
    }
}
