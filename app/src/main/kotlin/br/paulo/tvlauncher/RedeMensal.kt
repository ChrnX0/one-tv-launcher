package br.paulo.tvlauncher

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Ideias 36 e 37 do estudo (14/11/2026), as contas sem Android (testadas no PC em RedeMensalTest).
 *
 * 37. Relatório mensal da internet: o [HistoricoRede] guarda as medições cruas por 7 dias. Para cobrar o provedor
 *     ("em outubro: 14 quedas, 3 h 12 fora") guardamos também UMA linha por dia, por [GUARDAR_DIAS] dias, num arquivo
 *     à parte (~40 bytes por dia: ~16 KB por ano). Dia só entra depois de terminado, e uma vez só.
 *     Formato da linha, separado por TAB: `aaaa-mm-dd  quedasInternet  quedasDns  foraMs  coberturaMs`.
 * 36. Aviso no celular quando a internet volta: [quedaQueAcabou] diz se a amostra boa de agora fechou uma queda de
 *     internet de pelo menos [MIN_QUEDA_AVISO_MS], e [causa] dá o palpite que vai junto no aviso.
 */
object RedeMensal {
    const val GUARDAR_DIAS = 400
    /** Queda menor que isso não vira aviso no celular (piscada de roteador). */
    const val MIN_QUEDA_AVISO_MS = 3 * 60_000L

    class Dia(val data: LocalDate, val quedas: Int, val quedasDns: Int, val foraMs: Long, val coberturaMs: Long)
    class Mes(val mes: String, val dias: Int, val quedas: Int, val quedasDns: Int, val foraMs: Long, val coberturaMs: Long)

    fun linha(d: Dia) = listOf(d.data, d.quedas, d.quedasDns, d.foraMs, d.coberturaMs).joinToString("\t")

    /** Linhas do arquivo de dias -> dias em ordem (o último gravado vence se repetir). Linha estragada é pulada. */
    fun ler(linhas: List<String>): List<Dia> {
        val m = sortedMapOf<LocalDate, Dia>()
        for (l in linhas) {
            val p = l.split('\t')
            if (p.size < 5) continue
            val data = runCatching { LocalDate.parse(p[0]) }.getOrNull() ?: continue
            val n = p.drop(1).take(4).map { it.toLongOrNull() }
            if (n.any { it == null || it < 0 }) continue
            m[data] = Dia(data, n[0]!!.toInt(), n[1]!!.toInt(), n[2]!!, n[3]!!)
        }
        return m.values.toList()
    }

    /**
     * Os dias JÁ TERMINADOS (antes de hoje em [zona]) que têm amostra e ainda não estão em [jaGravados]. Cada dia é
     * resumido sozinho, de 0h a 0h: uma queda que atravessa a meia-noite conta nos dois dias, cada um com o seu pedaço.
     */
    fun diasNovos(amostras: List<ResumoRede.Amostra>, jaGravados: Set<LocalDate>, agora: Long, zona: ZoneId): List<Dia> {
        val hoje = Instant.ofEpochMilli(agora).atZone(zona).toLocalDate()
        val porDia = amostras.groupBy { Instant.ofEpochMilli(it.t).atZone(zona).toLocalDate() }
        return porDia.keys.filter { it < hoje && it !in jaGravados }.sorted().map { d ->
            val ini = d.atStartOfDay(zona).toInstant().toEpochMilli()
            val fim = d.plusDays(1).atStartOfDay(zona).toInstant().toEpochMilli()
            // A amostra boa logo depois da meia-noite fecha uma queda da noite: entra na conta, sem passar do fim do dia.
            val doDia = amostras.filter { it.t in ini until fim }
            val r = ResumoRede.resumir(doDia + listOfNotNull(amostras.firstOrNull { it.t >= fim }), ini, fim)
            val quedas = r.quedas.filter { it.inicio < fim }   // a que começou na amostra extra é do dia seguinte
            Dia(d, quedas.count { it.tipo == "internet" }, quedas.count { it.tipo == "dns" },
                quedas.filter { it.tipo == "internet" }.sumOf { (minOf(it.fim, fim) - it.inicio).coerceAtLeast(0) }, r.coberturaMs)
        }
    }

    /** Junta os dias por mês ("aaaa-mm"), do mais novo para o mais antigo, no máximo [quantos] meses. */
    fun meses(dias: List<Dia>, quantos: Int = 12): List<Mes> =
        dias.groupBy { "%04d-%02d".format(it.data.year, it.data.monthValue) }.toSortedMap(reverseOrder()).entries.take(quantos)
            .map { (m, l) -> Mes(m, l.size, l.sumOf { it.quedas }, l.sumOf { it.quedasDns }, l.sumOf { it.foraMs }, l.sumOf { it.coberturaMs }) }

    /** Só os dias que ainda cabem na janela de [GUARDAR_DIAS] (para reescrever o arquivo). */
    fun aparar(dias: List<Dia>, agora: Long, zona: ZoneId): List<Dia> {
        val limite = Instant.ofEpochMilli(agora).atZone(zona).toLocalDate().minusDays(GUARDAR_DIAS.toLong())
        return dias.filter { it.data >= limite }
    }

    // ---------------------------------------------------------------- 36. a internet voltou

    enum class Causa { WIFI_DA_TV, PROVEDOR_OU_ROTEADOR }

    /**
     * A queda de internet que a ÚLTIMA amostra (boa) acabou de fechar, se durou pelo menos [MIN_QUEDA_AVISO_MS].
     * null se a última amostra não é boa, se não havia queda logo antes, ou se havia um buraco sem dados no meio
     * (TV desligada: aí não sabemos se a internet caiu).
     */
    fun quedaQueAcabou(amostras: List<ResumoRede.Amostra>): ResumoRede.Queda? {
        val ultima = amostras.lastOrNull() ?: return null
        if (!ultima.internet) return null
        val r = ResumoRede.resumir(amostras, amostras.first().t, ultima.t)
        val q = r.quedas.lastOrNull { it.tipo == "internet" } ?: return null
        if (q.aberta || q.fim != ultima.t) return null
        return q.takeIf { it.duracao >= MIN_QUEDA_AVISO_MS }
    }

    /**
     * Palpite da causa, pelas amostras de dentro da queda. A TV estava no Wi-Fi antes e, durante a queda, ficou sem
     * Wi-Fi (dBm 0) na maior parte do tempo = o problema é o Wi-Fi da TV (ou o roteador desligou o rádio). Wi-Fi de pé
     * (ou TV no cabo) e mesmo assim sem internet = provedor ou roteador; sem os dados do roteador não dá para separar.
     */
    fun causa(amostras: List<ResumoRede.Amostra>, q: ResumoRede.Queda): Causa {
        val antes = amostras.lastOrNull { it.t < q.inicio }
        val dentro = amostras.filter { it.t >= q.inicio && it.t < q.fim }
        val semWifi = dentro.count { it.dbm == 0 }
        return if (antes != null && antes.dbm != 0 && dentro.isNotEmpty() && semWifi * 2 > dentro.size) Causa.WIFI_DA_TV
        else Causa.PROVEDOR_OU_ROTEADOR
    }
}
