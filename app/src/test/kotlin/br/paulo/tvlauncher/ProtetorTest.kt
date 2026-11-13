package br.paulo.tvlauncher

import org.junit.Assert.assertEquals
import org.junit.Test

class ProtetorTest {
    @Test fun padraoEGaleria() {
        assertEquals(Protetor.GALERIA, Protetor.normalizar(null))
        assertEquals(Protetor.GALERIA, Protetor.normalizar(""))
        assertEquals(Protetor.GALERIA, Protetor.normalizar("qualquer"))
    }

    @Test fun classicoSeMantem() = assertEquals(Protetor.CLASSICO, Protetor.normalizar("classico"))
}
