package br.paulo.tvlauncher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CompartilharTest {
    private val W = "https://www.youtube.com/watch?v=dQw4w9WgXcQ"

    @Test fun youtubeDeTodoJeito() {
        for (l in listOf("https://youtu.be/dQw4w9WgXcQ?si=abc", "https://www.youtube.com/watch?v=dQw4w9WgXcQ&t=10",
            "https://m.youtube.com/watch?feature=x&v=dQw4w9WgXcQ", "https://youtube.com/shorts/dQw4w9WgXcQ",
            "https://www.youtube.com/live/dQw4w9WgXcQ?x=1"))
            assertEquals(l, W, Compartilhar.youtube(l))
        assertNull(Compartilhar.youtube("https://www.youtube.com/@canal"))
        assertNull(Compartilhar.youtube("https://evil.com/watch?v=dQw4w9WgXcQ"))
        assertNull(Compartilhar.youtube("https://youtu.be/curto"))
    }

    @Test fun linkDentroDoTexto() {
        val d = Compartilhar.decidir("Vídeo", "Olha isso https://youtu.be/dQw4w9WgXcQ.", "")!!
        assertEquals(Compartilhar.Tipo.YOUTUBE, d.tipo); assertEquals(W, d.valor)
        val l = Compartilhar.decidir("", "", "https://g1.globo.com/noticia")!!
        assertEquals(Compartilhar.Tipo.LINK, l.tipo); assertEquals("https://g1.globo.com/noticia", l.valor)
    }

    @Test fun textoViraRecado() {
        val d = Compartilhar.decidir("Lista", "pão\nleite", null)!!
        assertEquals(Compartilhar.Tipo.RECADO, d.tipo); assertEquals("Lista\npão\nleite", d.valor)
        assertEquals(Recado.MAX_CARACTERES, Compartilhar.decidir("", "x".repeat(500), "")!!.valor.length)
        assertNull(Compartilhar.decidir("", " ", null))
    }

    @Test fun esquemaPerigosoNaoAbre() {
        val d = Compartilhar.decidir("", "javascript:alert(1)", "")!!
        assertEquals(Compartilhar.Tipo.RECADO, d.tipo)
    }
}
