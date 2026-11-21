package br.paulo.tvlauncher.weather

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.util.Log
import br.paulo.tvlauncher.BuildConfig
import br.paulo.tvlauncher.Prefs
import org.json.JSONObject

/**
 * Onde a TV está, do jeito mais preciso disponível (nesta ordem):
 *  1. A localização do próprio Android: o Google compara as redes Wi-Fi que a TV enxerga com um mapa delas.
 *     Na Sala, em 31/10/2026, deu ±57 m. Precisa de ACCESS_FINE_LOCATION (dada uma vez por adb).
 *  2. O endereço da casa (CEP + número, LocalClima.kt), se alguém cadastrou nas Configurações ou no painel.
 *  3. Pelo IP da internet (ipwho.is): acerta a cidade, mas pode cair no centro dela ou na central do provedor.
 *  4. O último ponto que deu certo (guardado pela tela inicial). Sem nada disso: null (o clima espera a rede).
 * Nada de coordenada fixa no código (pedido do dono, 05/11/2026).
 * Chamar fora da thread da tela (o passo 3 usa a rede).
 */
object Localizacao {
    /** [fonte] diz de onde veio o ponto (aparece no log TvLauncher.Local). */
    /** [nome] é o do endereço cadastrado (LocalClima), quando foi ele que valeu; null nos outros casos. */
    class Ponto(val lat: Double, val lon: Double, val fonte: String, val nome: String? = null)

    @Volatile private var guardadoIp: Ponto? = null   // o IP quase nunca muda: busca uma vez por execução

    fun atual(ctx: Context, httpGet: (String) -> String?): Ponto? {
        val ponto = doAparelho(ctx)
            ?: LocalClima.ler(ctx)?.let { Ponto(it.lat, it.lon, "endereço cadastrado", it.nome) }
            ?: (if (BuildConfig.PLAY) peloFuso(ctx) else porIp(httpGet))
            ?: ultimo(ctx)
        // Build da Play: só a localização APROXIMADA sai da TV (2 casas, ~1 km) para os serviços de clima. Assim a ficha
        // "Segurança dos dados" diz "localização aproximada" com verdade; a previsão não muda a 1 km.
        if (BuildConfig.PLAY && ponto != null) return Ponto(arredondar(ponto.lat), arredondar(ponto.lon), ponto.fonte, ponto.nome)
        // Só a fonte, nunca as coordenadas (13/11/2026): o logcat vai para relatórios de bug e o produto é público.
        Log.i("TvLauncher.Local", ponto?.let { "localização pela fonte: ${it.fonte.substringBefore(" (")}" } ?: "sem localização")
        return ponto
    }

    /** O último ponto usado pela tela inicial (prefs "clima"), para seguir funcionando sem Wi-Fi/IP por um tempo. */
    private fun ultimo(ctx: Context): Ponto? {
        val c = ctx.getSharedPreferences(Prefs.CLIMA, Context.MODE_PRIVATE)
        if (!c.contains("lat") || !c.contains("lon")) return null
        val lat = c.getFloat("lat", 0f).toDouble(); val lon = c.getFloat("lon", 0f).toDouble()
        return if (LocalClima.coordenadaValida(lat, lon)) Ponto(lat, lon, "último ponto conhecido") else null
    }

    private fun doAparelho(ctx: Context): Ponto? {
        val fina = ctx.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val grossa = ctx.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!fina && !grossa) return null
        val lm = ctx.getSystemService(LocationManager::class.java) ?: return null
        val semana = 7L * 24 * 3600 * 1000
        // "fused" junta todas as fontes; "network" é a do Wi-Fi; "passive" é a última que qualquer app recebeu.
        val melhor: Location = listOf("fused", LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)
            .mapNotNull { provedor -> try { lm.getLastKnownLocation(provedor) } catch (e: Exception) { null } }
            .filter { it.accuracy in 0f..2000f && System.currentTimeMillis() - it.time < semana }
            .minByOrNull { it.accuracy } ?: return null
        return Ponto(melhor.latitude, melhor.longitude, "Wi-Fi do Android, ±${melhor.accuracy.toInt()} m")
    }

    /**
     * Build da Play: no lugar do IP (o ipwho.is grátis é só sem fim comercial), a maior cidade do fuso horário da TV.
     * Erra de cidade com facilidade (o fuso de São Paulo cobre metade do Brasil): é só para a HOME não ficar sem clima
     * até alguém dizer a cidade. Nada sai da TV.
     */
    private fun peloFuso(ctx: Context): Ponto? =
        CidadesOffline.doFuso(CidadesOffline.lista(ctx), java.util.TimeZone.getDefault().id)
            ?.let { Ponto(it.lat, it.lon, "fuso horário", it.nome) }

    private fun porIp(httpGet: (String) -> String?): Ponto? {
        guardadoIp?.let { return it }
        return try {
            val j = JSONObject(httpGet("https://ipwho.is/") ?: return null)
            if (!j.optBoolean("success")) return null
            Ponto(j.getDouble("latitude"), j.getDouble("longitude"), "IP (${j.optString("city")})").also { guardadoIp = it }
        } catch (e: Exception) {
            null
        }
    }

    fun arredondar(v: Double): Double = Math.round(v * 100) / 100.0

    /** Distância aproximada em km entre dois pontos (boa para escolher a estação mais perto). */
    fun km(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val x = Math.toRadians(lon2 - lon1) * Math.cos(Math.toRadians((lat1 + lat2) / 2))
        val y = Math.toRadians(lat2 - lat1)
        return Math.sqrt(x * x + y * y) * 6371.0
    }
}
