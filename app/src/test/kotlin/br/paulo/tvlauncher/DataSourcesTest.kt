package br.paulo.tvlauncher

import br.paulo.tvlauncher.settings.Planos.Plano
import br.paulo.tvlauncher.weather.DataSources
import br.paulo.tvlauncher.weather.DataSources.Weather
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Regra do Paulo (19/11/2026): grátis no Básico, pago a partir do Pro, o mais especial no Ultra; pago só com licença. */
class DataSourcesTest {
    @Test fun semLicencaNadaPagoLiga() {
        assertFalse("contrato Open-Meteo é decisão do Paulo", DataSources.OPEN_METEO_LICENSED)
        assertFalse("contrato TMDB é decisão do Paulo", DataSources.TMDB_LICENSED)
        assertFalse("licença Simkl é decisão do Paulo", DataSources.SIMKL_LICENSED)
        assertFalse(DataSources.tmdb(Plano.ULTRA, temChave = true))
        assertFalse(DataSources.simklSemLogin(Plano.ULTRA))
    }

    @Test fun climaPorPlano() {
        assertEquals(Weather.MET_NORWAY, DataSources.weather(Plano.BASICO, openMeteoLicensed = true, weatherApiEnabled = true))
        assertEquals(Weather.WEATHERAPI, DataSources.weather(Plano.PRO, openMeteoLicensed = true, weatherApiEnabled = true))
        assertEquals(Weather.OPEN_METEO, DataSources.weather(Plano.ULTRA, openMeteoLicensed = true, weatherApiEnabled = true))
        // Ultra sem contrato da Open-Meteo: fica com a do Pro.
        assertEquals(Weather.WEATHERAPI, DataSources.weather(Plano.ULTRA, openMeteoLicensed = false, weatherApiEnabled = true))
        // Servidor sem chave (em pausa): volta ao MET.
        assertEquals(Weather.MET_NORWAY, DataSources.weather(Plano.ULTRA, openMeteoLicensed = false, weatherApiEnabled = true, weatherApiDown = true))
        assertEquals(Weather.MET_NORWAY, DataSources.weather(Plano.PRO, openMeteoLicensed = false, weatherApiEnabled = false))
    }

    @Test fun fichasESimklComLicenca() {
        assertFalse(DataSources.tmdb(Plano.BASICO, temChave = true, licensed = true))
        assertTrue(DataSources.tmdb(Plano.PRO, temChave = true, licensed = true))
        assertFalse(DataSources.tmdb(Plano.PRO, temChave = false, licensed = true))
        assertFalse(DataSources.simklSemLogin(Plano.BASICO, licensed = true))
        assertTrue(DataSources.simklSemLogin(Plano.PRO, licensed = true))
    }
}
