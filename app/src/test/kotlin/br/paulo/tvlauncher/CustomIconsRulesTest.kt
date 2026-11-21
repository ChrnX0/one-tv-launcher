package br.paulo.tvlauncher

import br.paulo.tvlauncher.home.CustomIcons
import br.paulo.tvlauncher.home.CustomIconsRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** "Trocar ícone": formato guardado e a ordem da grade (o do app, os parecidos, o resto). */
class CustomIconsRulesTest {
    @Test fun lerEEscrever() {
        assertEquals("com.pack" to "netflix", CustomIconsRules.ler(CustomIconsRules.escrever("com.pack", "netflix")))
        assertNull(CustomIconsRules.ler(CustomIcons.ORIGINAL))
        assertNull(CustomIconsRules.ler(null))
        assertNull(CustomIconsRules.ler("|netflix"))
        assertNull(CustomIconsRules.ler("com.pack|"))
        assertNull(CustomIconsRules.ler("com.pack|../x"))
        assertNull(CustomIconsRules.ler("com pack|x"))
    }

    @Test fun ordemDaGrade() {
        val todos = listOf("zebra", "globoplay_alt", "netflix", "globoplay", "abc", "netflix")
        val l = CustomIconsRules.ordenar(todos, "globoplay", "Globoplay", "com.globo.globotv")
        assertEquals(listOf("globoplay", "globoplay_alt", "abc", "netflix", "zebra"), l)
        assertEquals(2, CustomIconsRules.sugeridos(l, "globoplay", "Globoplay", "com.globo.globotv"))
    }

    @Test fun semIconeDoAppAindaAchaParecidos() {
        val l = CustomIconsRules.ordenar(listOf("b", "youtube_kids", "a"), null, "YouTube Kids", "com.google.android.youtube.tvkids")
        assertEquals("youtube_kids", l.first())
    }

    @Test fun palavrasCurtasNaoContam() {
        // "TV" não pode puxar todo ícone com "tv" no nome para cima.
        val l = CustomIconsRules.ordenar(listOf("atv_home", "b"), null, "TV", "x.tv")
        assertEquals(listOf("atv_home", "b"), l)
        assertEquals(0, CustomIconsRules.sugeridos(l, null, "TV", "x.tv"))
    }

    @Test fun nomesInvalidosSaem() {
        val l = CustomIconsRules.ordenar(listOf("ok_1", "não-ok", ""), null, "X", "a.b")
        assertEquals(listOf("ok_1"), l)
    }

    @Test fun legenda() {
        assertEquals("Youtube tv", CustomIconsRules.legenda("youtube_tv"))
        assertTrue(CustomIconsRules.MAX >= 1000)
    }
}
