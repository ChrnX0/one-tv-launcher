package br.paulo.tvlauncher

import org.json.JSONArray
import org.json.JSONObject

/**
 * Relatório de compatibilidade por aparelho (degrau 1 contra o Projectivy, item 1.2, 15/11/2026).
 *
 * Para que serve: a página pública "Funciona no seu aparelho?" mostra, por modelo, se o launcher passou nos 4 testes
 * da matriz. Este objeto junta os fatos que a própria TV sabe dizer e dá o veredito de cada teste; a página só lê o
 * JSON que sai daqui (um por aparelho), então o número publicado é o que a TV mediu, não o que alguém achou.
 *
 * Os 4 testes (mesmos do plano):
 *  1. HOME sozinha: a tecla HOME cai na nossa tela (por preferência do Android OU pelo serviço de acessibilidade, que
 *     é o caminho no Google TV, onde o launcher do Google tem priority=2).
 *  2. Acordar rápido: do "tela acendeu" ao 1º quadro da HOME, mediana < [LIMITE_ACORDAR_MS] (2 s).
 *  3. 24 h sem cair: nenhuma queda registrada nas últimas 24 h, com o aparelho ligado há pelo menos 24 h.
 *  4. Telas: o teste de telas (Robolectric) roda no PC, não na TV; aqui só entra como "não medido".
 *
 * Só a conta pura (testada em CompatibilidadeTest). A coleta no Android é [CompatibilidadeTv].
 */
object Compatibilidade {
    const val LIMITE_ACORDAR_MS = 2000
    const val HORAS_MINIMAS = 24
    /** Mínimo de medidas de "acordar" para dar veredito (uma só pode ser sorte). */
    const val MIN_ACORDAR = 3
    /** Quantas medidas de "acordar" guardar (as mais recentes). */
    const val MAX_ACORDAR = 20

    enum class Veredito { PASSOU, FALHOU, MEDINDO, NAO_MEDIDO }

    /** Fatos do aparelho. Tudo que vem do Android chega pronto aqui; nada aqui toca no Android. */
    data class Fatos(
        val fabricante: String,
        val modelo: String,
        val aparelho: String,          // Build.DEVICE (codinome, ex.: "sabrina")
        val android: String,           // Build.VERSION.RELEASE
        val sdk: Int,
        val abis: List<String>,
        val ramMb: Long,
        val processo64: Boolean,
        val googleTv: Boolean,         // tem o launcher do Google TV (launcherx)
        val fireOs: Boolean,
        val versaoApp: String,
        val versaoCode: Long,
        val dono: Boolean,             // Device Owner
        val acessibilidade: Boolean,   // nosso serviço de acessibilidade ligado
        val homePreferida: Boolean,    // o Android resolve HOME para nós
        val aberturaMs: Int?,          // mediana da abertura da HOME (Desempenho)
        val acordarMs: List<Int>,      // medidas de "tela acendeu" -> 1º quadro da HOME
        val quedas24h: Int,
        val ligadoHoras: Long,         // desde o último boot
        val watchNext: Int = -1,       // programas do Watch Next legíveis (-1 = erro/sem acesso); decide a fileira
        val entradas: Int = 0,         // entradas HDMI/AV
    )

    data class Teste(val id: String, val veredito: Veredito, val detalhe: String)

    fun mediana(l: List<Int>): Int? = if (l.isEmpty()) null else l.sorted()[l.size / 2]

    fun testes(f: Fatos): List<Teste> {
        val home = when {
            f.homePreferida || f.acessibilidade -> Teste("home", Veredito.PASSOU,
                if (f.homePreferida) "preferred" else "accessibility")
            else -> Teste("home", Veredito.FALHOU, "none")
        }
        val m = mediana(f.acordarMs)
        val acordar = when {
            m == null || f.acordarMs.size < MIN_ACORDAR -> Teste("acordar", Veredito.MEDINDO, "${f.acordarMs.size}/$MIN_ACORDAR")
            m < LIMITE_ACORDAR_MS -> Teste("acordar", Veredito.PASSOU, "$m ms")
            else -> Teste("acordar", Veredito.FALHOU, "$m ms")
        }
        val estavel = when {
            f.quedas24h > 0 -> Teste("estavel", Veredito.FALHOU, "${f.quedas24h}")
            f.ligadoHoras < HORAS_MINIMAS -> Teste("estavel", Veredito.MEDINDO, "${f.ligadoHoras} h")
            else -> Teste("estavel", Veredito.PASSOU, "${f.ligadoHoras} h")
        }
        return listOf(home, acordar, estavel, Teste("telas", Veredito.NAO_MEDIDO, "pc"))
    }

    /** Resultado geral: falhou se algum falhou; passou só se os medidos na TV passaram todos. */
    fun geral(t: List<Teste>): Veredito {
        val naTv = t.filter { it.veredito != Veredito.NAO_MEDIDO }
        return when {
            naTv.any { it.veredito == Veredito.FALHOU } -> Veredito.FALHOU
            naTv.any { it.veredito == Veredito.MEDINDO } -> Veredito.MEDINDO
            else -> Veredito.PASSOU
        }
    }

    /** Guarda uma medida de "acordar" na lista (as [MAX_ACORDAR] mais recentes). Descarta absurdos (> 60 s). */
    fun anotarAcordar(lista: List<Int>, ms: Int): List<Int> =
        if (ms < 0 || ms > 60_000) lista else (lista + ms).takeLast(MAX_ACORDAR)

    /**
     * O JSON que a página pública lê. Sem nada que identifique a pessoa: nem número de série, nem conta, nem rede, nem
     * nome que o dono deu ao aparelho. Só modelo, sistema e as medidas.
     */
    fun json(f: Fatos, agora: Long): JSONObject {
        val t = testes(f)
        return JSONObject()
            .put("formato", 1)
            .put("medidoEm", agora)
            .put("aparelho", JSONObject()
                .put("fabricante", f.fabricante).put("modelo", f.modelo).put("codinome", f.aparelho)
                .put("android", f.android).put("sdk", f.sdk).put("abis", JSONArray(f.abis))
                .put("ramMb", f.ramMb).put("processo64", f.processo64)
                .put("googleTv", f.googleTv).put("fireOs", f.fireOs).put("entradas", f.entradas))
            .put("app", JSONObject().put("versao", f.versaoApp).put("versionCode", f.versaoCode))
            .put("poderes", JSONObject().put("dono", f.dono).put("acessibilidade", f.acessibilidade).put("homePreferida", f.homePreferida))
            .put("medidas", JSONObject()
                .put("aberturaHomeMs", f.aberturaMs ?: JSONObject.NULL)
                .put("acordarMs", mediana(f.acordarMs) ?: JSONObject.NULL)
                .put("acordarAmostras", f.acordarMs.size)
                .put("quedas24h", f.quedas24h)
                .put("ligadoHoras", f.ligadoHoras)
                .put("watchNext", f.watchNext))
            .put("testes", JSONArray().apply {
                t.forEach { put(JSONObject().put("id", it.id).put("veredito", it.veredito.name).put("detalhe", it.detalhe)) }
            })
            .put("geral", geral(t).name)
    }

    /** Uma linha por teste, para o adb (EXTRAS --es o compat_texto) e a tela de Diagnóstico. */
    fun texto(f: Fatos): String {
        val t = testes(f)
        return buildString {
            append("${f.fabricante} ${f.modelo} (${f.aparelho}) · Android ${f.android} · ${f.abis.joinToString(",")} · ${f.ramMb} MB\n")
            t.forEach { append("${it.id}: ${it.veredito} (${it.detalhe})\n") }
            append("geral: ${geral(t)}")
        }
    }
}
