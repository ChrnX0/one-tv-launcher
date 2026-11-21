package br.paulo.tvlauncher

import android.content.pm.ApplicationInfo
import br.paulo.tvlauncher.home.CategoriaApps
import br.paulo.tvlauncher.home.CruzLogica.Categoria
import org.junit.Assert.assertEquals
import org.junit.Test

class CategoriaAppsTest {
    @Test fun automaticoPeloTipoDoAppOuPelaLista() {
        assertEquals(Categoria.ASSISTIR, CategoriaApps.destino("com.google.android.youtube.tv", -1, false, null))
        assertEquals(Categoria.OUVIR, CategoriaApps.destino("com.spotify.tv.android", -1, false, null))
        assertEquals(Categoria.ASSISTIR, CategoriaApps.destino("x.video", ApplicationInfo.CATEGORY_VIDEO, false, null))
        assertEquals(Categoria.OUVIR, CategoriaApps.destino("x.audio", ApplicationInfo.CATEGORY_AUDIO, false, null))
        assertEquals(Categoria.APLICATIVOS, CategoriaApps.destino("x.outro", ApplicationInfo.CATEGORY_PRODUCTIVITY, false, null))
        assertEquals(Categoria.APLICATIVOS, CategoriaApps.destino("x.nada", -1, false, null))
    }

    @Test fun escolhaManualVenceOAutomaticoEJogoVaiParaJogar() {
        assertEquals(Categoria.APLICATIVOS, CategoriaApps.destino("com.google.android.youtube.tv", -1, false, "aplicativos"))
        assertEquals(Categoria.OUVIR, CategoriaApps.destino("x.nada", -1, false, "ouvir"))
        assertEquals(Categoria.JOGAR, CategoriaApps.destino("x.jogo", -1, true, "ouvir"))   // jogo decide a tela Jogos
        // "jogar" guardado aqui não vale (quem guarda jogo é a escolha dos Jogos) e lixo volta ao automático
        assertEquals(Categoria.APLICATIVOS, CategoriaApps.destino("x.nada", -1, false, "jogar"))
        assertEquals(Categoria.ASSISTIR, CategoriaApps.destino("com.netflix.ninja", -1, false, "continuar"))
        assertEquals(Categoria.ASSISTIR, CategoriaApps.destino("com.netflix.ninja", -1, false, "???"))
    }

    @Test fun jsonIdaEVolta() {
        val m = mapOf("a.b" to "ouvir", "c.d" to "assistir")
        assertEquals(m, CategoriaApps.deJson(CategoriaApps.paraJson(m)))
        assertEquals(emptyMap<String, String>(), CategoriaApps.deJson("lixo"))
        assertEquals(emptyMap<String, String>(), CategoriaApps.deJson(null))
    }
}
