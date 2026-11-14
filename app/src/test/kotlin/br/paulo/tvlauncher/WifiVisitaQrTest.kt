package br.paulo.tvlauncher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WifiVisitaQrTest {
    @Test fun textoDoQr() {
        assertEquals("WIFI:T:WPA;S:Visitas;P:senha123;;", WifiVisitaQr.texto("Visitas", "senha123"))
        assertEquals("WIFI:T:nopass;S:Casa aberta;;", WifiVisitaQr.texto("Casa aberta", ""))
        assertEquals("WIFI:T:WPA;S:a\\;b;P:x\\:y\\\\z\\\"w\\,1;;", WifiVisitaQr.texto("a;b", "x:y\\z\"w,1"))
    }

    @Test fun validacao() {
        assertTrue(WifiVisitaQr.valido("Visitas", "12345678"))
        assertTrue(WifiVisitaQr.valido("Visitas", ""))
        assertFalse(WifiVisitaQr.valido("", "12345678"))
        assertFalse(WifiVisitaQr.valido("Visitas", "1234567"))
        assertFalse(WifiVisitaQr.valido("x".repeat(33), "12345678"))
        assertFalse(WifiVisitaQr.valido("Visitas", "x".repeat(64)))
        assertFalse(WifiVisitaQr.valido("Vi\nsitas", "12345678"))
    }
}
