package br.paulo.tvlauncher

import br.paulo.tvlauncher.PistaAdb.Pedido
import br.paulo.tvlauncher.PistaAdb.Pista
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Melhoria 10/11/2026: fila do adb com duas pistas (rápida e lenta). */
class PistaAdbTest {
    @Test fun teclasEConsultasCurtasVaoNaRapida() {
        assertEquals(Pista.RAPIDA, PistaAdb.escolher(Pedido.COMANDO, "input keyevent 26"))
        assertEquals(Pista.RAPIDA, PistaAdb.escolher(Pedido.COMANDO, "appops set --uid x CAMERA allow", 20_000))
        assertEquals(Pista.RAPIDA, PistaAdb.escolher(Pedido.COMANDO, "dumpsys account"))
        assertEquals(Pista.RAPIDA, PistaAdb.escolher(Pedido.COMANDO, "settings get global device_name", 5_000))
    }

    @Test fun comandoLongoOuPrazoGrandeVaiNaLenta() {
        assertEquals(Pista.LENTA, PistaAdb.escolher(Pedido.COMANDO, "pm trim-caches 1000G"))
        assertEquals(Pista.LENTA, PistaAdb.escolher(Pedido.COMANDO, "dumpsys meminfo"))
        assertEquals(Pista.LENTA, PistaAdb.escolher(Pedido.COMANDO, "am kill-all"))
        assertEquals(Pista.LENTA, PistaAdb.escolher(Pedido.COMANDO, "input keyevent 3", 60_000))   // executar_shell com 60 s
        assertEquals(Pista.LENTA, PistaAdb.escolher(Pedido.COMANDO, "PM   Install /sdcard/a.apk"))
    }

    @Test fun pedacoLentoNoComandoCompostoVaiNaLenta() {
        assertEquals(Pista.LENTA, PistaAdb.escolher(Pedido.COMANDO, "cd /sdcard && pm install a.apk"))
        assertEquals(Pista.LENTA, PistaAdb.escolher(Pedido.COMANDO, "echo oi; sleep 15"))
        assertEquals(Pista.LENTA, PistaAdb.escolher(Pedido.COMANDO, "logcat -d | grep x"))
        // "dumpsys power" não é "dumpsys package": não confunde começo de palavra.
        assertEquals(Pista.RAPIDA, PistaAdb.escolher(Pedido.COMANDO, "dumpsys power | grep Wakefulness"))
        assertFalse(PistaAdb.ehLento("findx"))   // "find" só como palavra inteira
        assertTrue(PistaAdb.ehLento("find /sdcard -name x"))
    }

    @Test fun comandoEmbrulhadoNaoEnganaAEscolha() {
        assertTrue(PistaAdb.ehLento("sh -c 'pm install /sdcard/a.apk'"))
        assertTrue(PistaAdb.ehLento("timeout 60 find / -name x"))
        assertTrue(PistaAdb.ehLento("nice -n 10 du -s /sdcard"))
        assertTrue(PistaAdb.ehLento("env A=1 B=2 logcat -d"))
        assertTrue(PistaAdb.ehLento("(sleep 5; echo x)"))
        assertTrue(PistaAdb.ehLento("echo $(find /sdcard)"))
        assertTrue(PistaAdb.ehLento("dumpsys package"))           // o dumpsys inteiro de pacotes demora
        assertFalse(PistaAdb.ehLento("dumpsys package com.x.y"))  // o de um pacote só é curto
        assertFalse(PistaAdb.ehLento("sh -c 'input keyevent 3'"))
        assertFalse(PistaAdb.ehLento("timeout 5 settings get global x"))
    }

    @Test fun semConexaoSoALentaResolve() {
        assertEquals(Pista.LENTA, PistaAdb.escolher(Pedido.COMANDO, "input keyevent 26", conectado = false))
    }

    @Test fun parearNaRapidaEManutencaoNaLenta() {
        assertEquals(Pista.RAPIDA, PistaAdb.escolher(Pedido.PAREAR, conectado = false))
        assertEquals(Pista.LENTA, PistaAdb.escolher(Pedido.MANUTENCAO))
    }

    @Test fun rapidaNaoEsperaALentaPresa() {
        val lenta = Executors.newSingleThreadExecutor(); val rapida = Executors.newSingleThreadExecutor()
        val solta = CountDownLatch(1)
        lenta.execute { solta.await() }   // um comando de 2 min segurando a lenta
        val f = FilaDuasPistas(lenta, rapida)
        val r = f.rodar<String>(Pista.RAPIDA, 2_000, naRapida = { FilaDuasPistas.Rapida.Feito("tecla") }, naLenta = { "errado" })
        assertEquals("tecla", r)
        solta.countDown(); lenta.shutdown(); rapida.shutdown()
    }

    @Test fun desvioVaiParaALentaComOQueSobrouDoPrazo() {
        val lenta = Executors.newSingleThreadExecutor(); val rapida = Executors.newSingleThreadExecutor()
        val desviou = AtomicBoolean(false)
        val r = FilaDuasPistas(lenta, rapida).rodar<String>(Pista.RAPIDA, 2_000, aoDesviar = { desviou.set(true) },
            naRapida = { FilaDuasPistas.Rapida.Desviar }, naLenta = { "pela lenta" })
        assertEquals("pela lenta", r)
        assertTrue(desviou.get())
        lenta.shutdown(); rapida.shutdown()
    }

    @Test fun desvioSemPrazoSobrandoNaoRodaNaLenta() {
        // Relógio falso: o pedido começa em 0 e a rápida devolve já em 5 s, com prazo total de 5 s.
        var agora = 0L
        val lenta = Executors.newSingleThreadExecutor(); val rapida = Executors.newSingleThreadExecutor()
        val rodou = AtomicBoolean(false)
        val r = FilaDuasPistas(lenta, rapida) { agora }.rodar<String>(Pista.RAPIDA, 5_000,
            naRapida = { agora = 5_000; FilaDuasPistas.Rapida.Desviar }, naLenta = { rodou.set(true); "x" })
        assertNull(r)
        lenta.shutdown(); lenta.awaitTermination(2, TimeUnit.SECONDS); rapida.shutdown()
        assertFalse(rodou.get())
    }

    @Test fun lentaUsaSoALenta() {
        val lenta = Executors.newSingleThreadExecutor(); val rapida = Executors.newSingleThreadExecutor()
        val solta = CountDownLatch(1)
        rapida.execute { solta.await() }   // a rápida presa não atrapalha a lenta
        assertEquals("ok", FilaDuasPistas(lenta, rapida).rodar<String>(Pista.LENTA, 2_000,
            naRapida = { FilaDuasPistas.Rapida.Feito("errado") }, naLenta = { "ok" }))
        solta.countDown(); lenta.shutdown(); rapida.shutdown()
    }
}
