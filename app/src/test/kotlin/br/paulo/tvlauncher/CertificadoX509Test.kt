package br.paulo.tvlauncher

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.KeyStore
import java.security.cert.CertPathValidator
import java.security.cert.CertificateFactory
import java.security.cert.PKIXParameters
import java.security.cert.TrustAnchor
import java.util.Date
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManagerFactory
import kotlin.concurrent.thread

class CertificadoX509Test {
    private val agora = 1_794_000_000_000L   // nov/2026
    private val nomes = listOf("tv", "tv.lan", "tv.local")
    private val e = CertificadoX509.emitir(nomes, "192.168.1.50", 24, "Sala", agora)

    @Test fun certificadosLegiveisEAssinadosPelaCa() {
        e.ca.verify(e.ca.publicKey)          // a CA assina a si mesma
        e.servidor.verify(e.ca.publicKey)    // e o servidor
        assertEquals(e.ca.subjectX500Principal, e.servidor.issuerX500Principal)
        assertEquals(3, e.servidor.version)
        assertTrue(e.ca.subjectX500Principal.name.contains("One TV Sala (painel)"))
    }

    @Test fun caEhCaEServidorNao() {
        assertEquals(0, e.ca.basicConstraints)        // CA, sem CA abaixo dela
        assertEquals(-1, e.servidor.basicConstraints)  // não é CA
        assertTrue(e.ca.keyUsage[5])                   // keyCertSign
        assertTrue(e.servidor.keyUsage[0])             // digitalSignature
        assertFalse(e.servidor.keyUsage[5])
        assertEquals(listOf("1.3.6.1.5.5.7.3.1"), e.servidor.extendedKeyUsage)
        assertTrue("2.5.29.30" in e.ca.criticalExtensionOIDs)   // restrição de nomes, crítica
    }

    @Test fun sanTemOsNomesEOIp() {
        assertEquals(setOf("tv", "tv.lan", "tv.local", "192.168.1.50"), CertificadoX509.nomesDo(e.servidor))
    }

    @Test fun validadeDentroDoLimiteDaApple() {
        val dias = (e.servidor.notAfter.time - e.servidor.notBefore.time) / 86_400_000L
        assertTrue("validade $dias dias", dias <= 825)
        assertTrue(e.servidor.notBefore.time < agora)
        assertTrue(e.ca.notAfter.time >= e.servidor.notAfter.time)
    }

    @Test fun cadeiaValidaNoPkixDoJava() {
        val p = PKIXParameters(setOf(TrustAnchor(e.ca, null))).apply { isRevocationEnabled = false; date = Date(agora) }
        val caminho = CertificateFactory.getInstance("X.509").generateCertPath(listOf(e.servidor))
        CertPathValidator.getInstance("PKIX").validate(caminho, p)
    }

    /**
     * A restrição de nomes da CA: o Java não aplica restrição na raiz, o OpenSSL aplica (como o Chrome). Confere com
     * o openssl quando ele existe na máquina (na nuvem e no Linux do CI existe; no Windows o teste só é pulado).
     */
    @Test fun restricaoDeNomesAceitaOsNomesDaTv() {
        val openssl = listOf("/usr/bin/openssl", "/usr/local/bin/openssl").firstOrNull { java.io.File(it).canExecute() }
        org.junit.Assume.assumeNotNull(openssl)
        val dir = java.nio.file.Files.createTempDirectory("cert").toFile()
        val ca = java.io.File(dir, "ca.pem").apply { writeText(CertificadoX509.pem(e.ca)) }
        val srv = java.io.File(dir, "srv.pem").apply { writeText(CertificadoX509.pem(e.servidor)) }
        val p = ProcessBuilder(openssl, "verify", "-attime", (agora / 1000).toString(), "-purpose", "sslserver",
            "-CAfile", ca.path, srv.path).redirectErrorStream(true).start()
        val saida = p.inputStream.bufferedReader().readText()
        assertEquals(saida, 0, p.waitFor())
        assertTrue(saida, saida.contains("OK"))
        dir.deleteRecursively()
    }

    @Test fun pemEImpressao() {
        val pem = CertificadoX509.pem(e.ca)
        assertTrue(pem.startsWith("-----BEGIN CERTIFICATE-----\n"))
        val lido = CertificateFactory.getInstance("X.509").generateCertificate(pem.byteInputStream())
        assertArrayEquals(e.ca.encoded, lido.encoded)
        assertEquals(32 * 3 - 1, CertificadoX509.impressao(e.ca).length)
    }

    @Test fun cadaEmissaoEhNova() {
        val outra = CertificadoX509.emitir(nomes, "192.168.1.50", 24, "Sala", agora)
        assertFalse(outra.ca.serialNumber == e.ca.serialNumber)
        assertFalse(outra.ca.publicKey == e.ca.publicKey)
    }

    @Test fun semIpSoNomes() {
        val s = CertificadoX509.emitir(listOf("tv"), null, 24, "Sala", agora)
        assertEquals(setOf("tv"), CertificadoX509.nomesDo(s.servidor))
    }

    @Test fun ipInvalidoRecusado() {
        for (ruim in listOf("tv", "1.2.3", "1.2.3.256", "1.2.3.4.5", "a.b.c.d", "")) {
            try { CertificadoX509.ipv4(ruim); fail(ruim) } catch (_: IllegalArgumentException) {}
        }
        assertArrayEquals(byteArrayOf(192.toByte(), 168.toByte(), 1, 50), CertificadoX509.ipv4("192.168.1.50"))
    }

    /** Conexão TLS de verdade: o cliente confia só na CA e confere o nome "tv" (como o navegador). */
    @Test fun handshakeTlsComONomeTv() {
        val agoraReal = System.currentTimeMillis()
        val em = CertificadoX509.emitir(nomes, "127.0.0.1", 8, "Teste", agoraReal)
        val servidorCtx = SSLContext.getInstance("TLS").apply {
            init(arrayOf(CertificadoX509.ChaveFixa(em.chaveServidor, arrayOf(em.servidor, em.ca))), null, null)
        }
        val ss = servidorCtx.serverSocketFactory.createServerSocket(0, 4, InetAddress.getLoopbackAddress()) as SSLServerSocket
        val atendeu = thread {
            repeat(2) {
                try { ss.accept().use { s -> s.getOutputStream().apply { write("ok".toByteArray()); flush() } } } catch (_: Exception) {}
            }
        }
        val ks = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null); setCertificateEntry("ca", em.ca) }
        val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(ks) }
        val clienteCtx = SSLContext.getInstance("TLS").apply { init(null, tmf.trustManagers, null) }

        fun conectar(nome: String): String {
            val cru = Socket(InetAddress.getLoopbackAddress(), ss.localPort)
            val s = clienteCtx.socketFactory.createSocket(cru, nome, ss.localPort, true) as SSLSocket
            s.sslParameters = s.sslParameters.apply { endpointIdentificationAlgorithm = "HTTPS" }
            return s.use { String(it.inputStream.readNBytes(2)) }
        }
        assertEquals("ok", conectar("tv"))
        try { conectar("outro.exemplo"); fail("nome fora do certificado passou") } catch (_: SSLHandshakeException) {}
        ss.close(); atendeu.join(2000)
        assertNotNull(em)
    }
}
