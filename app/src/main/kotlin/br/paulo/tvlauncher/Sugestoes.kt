package br.paulo.tvlauncher

import java.util.Calendar
import java.util.TimeZone

/**
 * Ideia 44 (14/11/2026): sugestão na hora certa, sem IA. Regras locais olham o uso da TV na última semana e, quando
 * uma opção desligada resolveria o que aconteceu, a HOME pergunta uma vez se quer ligar. Objeto puro (testado na JVM);
 * quem lê o uso e mostra a pergunta é o [SugestoesTv]. Nada sai da TV.
 *
 * Freio: no máximo uma sugestão a cada [INTERVALO_MS]; "Não sugerir mais" vale para sempre para aquela regra.
 */
object Sugestoes {
    const val INTERVALO_MS = 3 * 24 * 60 * 60_000L
    /** Quantas noites (de 7) com a TV em uso disparam cada regra. */
    const val NOITES_MADRUGADA = 3
    const val NOITES_TARDE = 4

    enum class Tipo {
        /** A TV ficou em uso depois da meia-noite em 3 noites: ligar "Desligar a TV sem uso". */
        DESLIGAR_SOZINHA,
        /** A TV foi usada depois das 22 h em 4 noites: ligar o filtro noturno. */
        FILTRO_NOTURNO,
    }

    data class Sinais(
        val noitesMadrugada: Int,       // noites da semana com uso entre 0 h e 5 h
        val noitesTarde: Int,           // noites da semana com uso entre 22 h e 5 h
        val desligarSozinhaMin: Int,    // AutoDesligar.minutos (0 = nunca)
        val filtroLigado: Boolean,
        val dispensadas: Set<String>,   // nomes de Tipo com "Não sugerir mais"
        val ultimaEm: Long,             // quando a última sugestão apareceu (0 = nunca)
        val agora: Long,
    )

    /** A sugestão de agora, ou null. Ordem = prioridade: desligar sozinha economiza energia e acorda menos gente. */
    fun escolher(s: Sinais): Tipo? {
        if (s.ultimaEm > 0 && s.agora - s.ultimaEm < INTERVALO_MS) return null
        fun pode(t: Tipo) = t.name !in s.dispensadas
        return when {
            pode(Tipo.DESLIGAR_SOZINHA) && s.desligarSozinhaMin == 0 && s.noitesMadrugada >= NOITES_MADRUGADA -> Tipo.DESLIGAR_SOZINHA
            pode(Tipo.FILTRO_NOTURNO) && !s.filtroLigado && s.noitesTarde >= NOITES_TARDE -> Tipo.FILTRO_NOTURNO
            else -> null
        }
    }

    /**
     * Quantas noites diferentes têm algum momento de uso ([momentos], em ms) entre [deHora] e 5 h da manhã seguinte.
     * A "noite" é o dia de quem foi dormir: 1 h de sábado conta como a noite de sexta.
     */
    fun noites(momentos: Collection<Long>, fuso: TimeZone, deHora: Int): Int {
        val cal = Calendar.getInstance(fuso)
        val noites = HashSet<Int>()
        for (t in momentos) {
            cal.timeInMillis = t
            val h = cal.get(Calendar.HOUR_OF_DAY)
            val dentro = if (deHora == 0) h < 5 else h >= deHora || h < 5
            if (!dentro) continue
            if (h < 5) cal.add(Calendar.DAY_OF_YEAR, -1)
            noites += cal.get(Calendar.YEAR) * 1000 + cal.get(Calendar.DAY_OF_YEAR)
        }
        return noites.size
    }
}
