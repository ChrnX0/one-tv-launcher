package br.paulo.tvlauncher

import android.media.session.PlaybackState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Trailer no YouTube (18/11/2026): formas do link, qual trailer abrir e quando o trailer acabou. */
class TrailerTvTest {
    private val id = "n9xhJrPXop4"

    @Test fun aceitaAsFormasDoYoutube() {
        for (v in listOf(id, " $id ", "https://www.youtube.com/watch?v=$id", "http://youtube.com/watch?feature=x&v=$id",
            "www.youtube.com/watch?v=$id&t=10", "https://youtu.be/$id", "https://www.youtube.com/embed/$id?autoplay=1",
            "https://m.youtube.com/watch?v=$id", "https://www.youtube.com/shorts/$id", "https://www.youtube-nocookie.com/embed/$id",
            "youtube.com/v/$id"))
            assertEquals(v, id, TrailerLink.codigo(v))
    }

    @Test fun recusaOQueNaoEYoutube() {
        for (v in listOf(null, "", "abc", "https://evil.example/watch?v=$id", "https://www.youtube.com/watch?v=${id}X",
            "javascript:alert(1)", "https://www.youtube.com/channel/UCabcdefghijk"))
            assertNull(v, TrailerLink.codigo(v))
        assertEquals("", TrailerLink.normalizar("nada"))
        assertEquals("https://www.youtube.com/watch?v=$id", Vod.linkTrailer("https://www.youtube.com/embed/$id"))
    }

    @Test fun tmdbAntesDoServidor() {
        assertEquals("https://www.youtube.com/watch?v=AAAAAAAAAAA", TrailerLink.escolher("https://www.youtube.com/watch?v=AAAAAAAAAAA", "BBBBBBBBBBB"))
        assertEquals("https://www.youtube.com/watch?v=BBBBBBBBBBB", TrailerLink.escolher("", "BBBBBBBBBBB"))
        assertNull(TrailerLink.escolher(null, "lixo"))
    }

    @Test fun fimDoTrailer() {
        assertFalse(TrailerLink.acabou(false, null, PlaybackState.STATE_STOPPED, null))   // nem começou
        assertFalse(TrailerLink.acabou(true, "Trailer", PlaybackState.STATE_PLAYING, "Trailer"))
        assertFalse(TrailerLink.acabou(true, "Trailer", PlaybackState.STATE_PAUSED, "Trailer"))   // pausar não volta
        assertTrue(TrailerLink.acabou(true, "Trailer", PlaybackState.STATE_STOPPED, "Trailer"))
        assertTrue(TrailerLink.acabou(true, "Trailer", PlaybackState.STATE_PLAYING, "Outro vídeo"))   // o YouTube emendou outro
        assertFalse(TrailerLink.acabou(true, "Trailer", PlaybackState.STATE_BUFFERING, null))
    }
}
