package br.paulo.tvlauncher

import br.paulo.tvlauncher.weather.CidadesOffline
import java.io.File
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A lista de cidades de todo build (src/main/assets/cidades.tsv.gz, GeoNames): lê, acha por nome, por CEP e por fuso. */
class CidadesOfflineTest {
    private val lista by lazy {
        val f = listOf(File("src/main/assets/cidades.tsv.gz"), File("app/src/main/assets/cidades.tsv.gz")).first { it.exists() }
        f.inputStream().use { CidadesOffline.ler(it) }
    }

    @Test fun leATabelaInteira() {
        assertTrue("dezenas de milhares de cidades", lista.size > 30_000)
        assertTrue("da mais populosa para a menos", lista[0].populacao >= lista[100].populacao)
    }

    @Test fun achaSemAcentoEComFiltro() {
        assertEquals("BR", CidadesOffline.buscar(lista, "sao paulo")?.pais)
        assertEquals("Ontario", CidadesOffline.buscar(lista, "London, Canada", Locale.ENGLISH)?.estado)
        assertEquals("GB", CidadesOffline.buscar(lista, "London")?.pais)   // a mais populosa vence
        assertEquals("PT", CidadesOffline.buscar(lista, "Lisboa")?.pais)   // apelido em português
        assertNull(CidadesOffline.buscar(lista, "xq"))
    }

    @Test fun cepViraCidadePequena() {
        val c = CidadesOffline.noBrasil(lista, "Brasília", "DF")
        assertNotNull(c); assertEquals(-15.8, c!!.lat, 0.3)
        assertNotNull("cidade com menos de 15 mil (lista do Brasil vai até mil)", CidadesOffline.noBrasil(lista, "Ritapolis", "MG"))
    }

    @Test fun fusoDaMaiorCidade() {
        assertEquals("São Paulo", CidadesOffline.doFuso(lista, "America/Sao_Paulo")?.nome)
        assertNull(CidadesOffline.doFuso(lista, "Nada/Nada"))
    }

    @Test fun normaliza() {
        assertEquals("ribeirao pires", CidadesOffline.normalizar("  Ribeirão-Pires "))
    }
}
