package br.paulo.tvlauncher

import br.paulo.tvlauncher.observer.ObserverCore.Signal
import br.paulo.tvlauncher.observer.ObserverMorning
import br.paulo.tvlauncher.observer.ObserverMorning.Change
import br.paulo.tvlauncher.player.item
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ObserverMorningTest {

    private val dia = 86_400_000L
    private val agora = 30 * dia + 7 * 3_600_000L   // 7h da manhã

    /** Noite [d] dias atrás (às 4h), com memória, disco, latência e Wi-Fi. */
    private fun noite(d: Int, mem: Long = 700, disco: Long = 5000, lat: Long = 40, wifi: Long = -55) =
        ObserverMorning.Night(agora - 3 * 3_600_000L - d * dia,
            mapOf(Signal.MEMORY_AVAILABLE_MB to mem, Signal.DISK_FREE_MB to disco, Signal.LATENCY_MS to lat, Signal.WIFI_DBM to wifi))

    private fun item(r: ObserverMorning.Report, s: Signal) = r.items.first { it.signal == s }

    @Test fun primeiraNoiteNaoCompara() {
        val r = ObserverMorning.report(listOf(noite(0)), JSONArray(), agora)
        assertEquals(0, r.compared)
        assertEquals(0, r.items.size)
    }

    @Test fun semSondaRecenteNaoTemManha() {
        val r = ObserverMorning.report(listOf(noite(1), noite(2)), JSONArray(), agora)
        assertNull(r.night)
    }

    @Test fun pioraDevagarAparece() {
        val antes = (1..5).map { noite(it, disco = 5000 + it * 10L) }
        val r = ObserverMorning.report(listOf(noite(0, disco = 4300, mem = 520, wifi = -62, lat = 41)) + antes, JSONArray(), agora)
        assertEquals(5, r.compared)
        assertEquals(Change.WORSE, item(r, Signal.DISK_FREE_MB).change)        // 4300 contra 5030
        assertEquals(Change.WORSE, item(r, Signal.MEMORY_AVAILABLE_MB).change)  // 520 contra 700
        assertEquals(Change.WORSE, item(r, Signal.WIFI_DBM).change)             // -62 contra -55
        assertEquals(Change.SAME, item(r, Signal.LATENCY_MS).change)            // 41 contra 40
        assertEquals(3, r.worse)
    }

    @Test fun ruidoPequenoNaoContaEMelhoraContaComoMelhora() {
        val antes = (1..4).map { noite(it) }
        val r = ObserverMorning.report(listOf(noite(0, mem = 660, disco = 5150, lat = 20, wifi = -53)) + antes, JSONArray(), agora)
        assertEquals(Change.SAME, item(r, Signal.MEMORY_AVAILABLE_MB).change)   // -40 MB: abaixo do mínimo de 60
        assertEquals(Change.SAME, item(r, Signal.DISK_FREE_MB).change)          // +150 MB: abaixo de 200
        assertEquals(Change.BETTER, item(r, Signal.LATENCY_MS).change)          // 20 contra 40 ms
        assertEquals(Change.SAME, item(r, Signal.WIFI_DBM).change)              // +2 dB
    }

    @Test fun medianaIgnoraUmaNoiteEstranha() {
        val antes = listOf(noite(1, mem = 100), noite(2), noite(3), noite(4))
        val r = ObserverMorning.report(listOf(noite(0)) + antes, JSONArray(), agora)
        assertEquals(700L, item(r, Signal.MEMORY_AVAILABLE_MB).usual)
        assertEquals(Change.SAME, item(r, Signal.MEMORY_AVAILABLE_MB).change)
        assertEquals(400L, ObserverMorning.median(listOf(100, 700)))
    }

    @Test fun semWifiNaoEntraNaComparacao() {
        // Hoje no cabo (0): Wi-Fi fora do relatório, não "melhora".
        val r = ObserverMorning.report(listOf(noite(0, wifi = 0)) + (1..3).map { noite(it) }, JSONArray(), agora)
        assertEquals(0, r.items.count { it.signal == Signal.WIFI_DBM })
        // Noites antigas sem Wi-Fi (0 e -127) não puxam a mediana: -62 contra -55 continua pior.
        val r2 = ObserverMorning.report(listOf(noite(0, wifi = -62), noite(1, wifi = 0), noite(2, wifi = -127), noite(3), noite(4)), JSONArray(), agora)
        assertEquals(-55L, item(r2, Signal.WIFI_DBM).usual)
        assertEquals(Change.WORSE, item(r2, Signal.WIFI_DBM).change)
    }

    @Test fun achadosDas24hPorTipo() {
        val log = JSONArray()
            .put(JSONObject().put("at", agora - 3_600_000L).put("event", "appeared").put("kind", "NO_INTERNET"))
            .put(JSONObject().put("at", agora - 5_000_000L).put("event", "cleared").put("kind", "NO_INTERNET"))
            .put(JSONObject().put("at", agora - 7_000_000L).put("event", "appeared").put("kind", "NO_INTERNET"))
            .put(JSONObject().put("at", agora - 3 * 3_600_000L).put("event", "night").put("kind", JSONObject.NULL))
            .put(JSONObject().put("at", agora - 2 * dia).put("event", "appeared").put("kind", "LOW_MEMORY"))
        val r = ObserverMorning.report(listOf(noite(0)), log, agora)
        assertEquals(mapOf("NO_INTERNET" to 2), r.events24h)
        assertEquals(2, r.toJson().getJSONObject("events_24h").getInt("NO_INTERNET"))
    }

    @Test fun listaDeNoitesIdaEVoltaECorte() {
        var s: String? = null
        repeat(ObserverMorning.MAX_NIGHTS + 3) { s = ObserverMorning.appendNight(s, noite(20 - it)) }
        val n = ObserverMorning.readNights(s)
        assertEquals(ObserverMorning.MAX_NIGHTS, n.size)
        assertEquals(noite(20 - (ObserverMorning.MAX_NIGHTS + 2)), n.first())
        assertEquals(0, ObserverMorning.readNights("estragado").size)
    }
}
