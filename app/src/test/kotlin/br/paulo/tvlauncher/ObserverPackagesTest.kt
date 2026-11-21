package br.paulo.tvlauncher

import br.paulo.tvlauncher.observer.ObserverPackages
import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ObserverPackagesTest {

    private val fichas = JSONObject()
        .put("android", JSONObject().put("r", "essencial"))
        .put("com.netflix.ninja", JSONObject().put("r", "seguro"))
        .put("com.google.android.katniss", JSONObject().put("r", "cuidado"))

    @Test fun separaConhecidosEDesconhecidos() {
        val inv = ObserverPackages.inventory(listOf(
            ObserverPackages.Pkg("android", true, false),
            ObserverPackages.Pkg("com.netflix.ninja", false, false),
            ObserverPackages.Pkg("com.google.android.katniss", true, true),
            ObserverPackages.Pkg("zz.app.novo", false, false),
            ObserverPackages.Pkg("com.google.android.coisa.nova", true, false),
        ), fichas)
        assertEquals(5, inv.total)
        assertEquals(3, inv.system)
        assertEquals(listOf("com.google.android.katniss"), inv.frozen)
        assertEquals(mapOf("cuidado" to 1, "essencial" to 1, "seguro" to 1), inv.byRisk)
        // Desconhecido de sistema vem primeiro: é o que pede mais cuidado.
        assertEquals(listOf("com.google.android.coisa.nova", "zz.app.novo"), inv.unknown.map { it.name })
        assertEquals(2, inv.toJson().getInt("unknown_total"))
        assertTrue(inv.toJson().getBoolean("complete"))
        assertEquals(false, ObserverPackages.inventory(emptyList(), fichas, complete = false).toJson().getBoolean("complete"))
    }

    /** As fichas do APK: todo pacote com risco válido, texto curto e fonte (as novas de 20/11 trazem "src"). */
    @Test fun fichasDoApkSaoValidas() {
        val f = listOf(File("src/main/assets/fichas-apps.json"), File("app/src/main/assets/fichas-apps.json")).first { it.exists() }
        val o = JSONObject(f.readText())
        val validas = FichasComunidade.validarFichas(f.readText())!!
        // "android" (sem ponto) só existe no APK: a conferência das fichas de fora não aceita nome sem ponto.
        assertEquals(o.length() - 1, validas.length())
        assertTrue(o.length() >= 140)
        for (p in listOf("com.google.android.tvrecommendations", "com.android.tv.feedbackconsent", "tv.sofaly.launcher"))
            assertTrue(p, o.getJSONObject(p).optString("src").isNotEmpty())
        assertEquals("essencial", o.getJSONObject("tv.sofaly.launcher").getString("r"))
    }
}
