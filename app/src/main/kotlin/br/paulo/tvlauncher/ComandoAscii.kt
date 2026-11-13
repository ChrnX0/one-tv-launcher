package br.paulo.tvlauncher

import java.util.Base64

/**
 * Comando com acento no shell próprio (bug achado em 11/11/2026): a libadb 3.1.1 reserva o pacote do "shell:<cmd>" pelo
 * número de LETRAS e grava os BYTES em UTF-8. "ç" vira 2 bytes, o pacote estoura (BufferOverflowException), o comando
 * não roda e a fila do adb fica presa uns segundos.
 *
 * Saída: se o comando tiver qualquer coisa fora do ASCII visível, ele viaja em base64 (só letras ASCII) e o próprio
 * shell da TV desfaz: `echo <base64> | base64 -d | sh`. O comando roda igual, com os acentos intactos. O que é só ASCII
 * passa sem mudança (a maioria, e o caminho medido de sempre).
 * Puro: testado em ComandoAsciiTest.
 */
object ComandoAscii {
    fun preparar(cmd: String): String {
        if (cmd.all { it == '\n' || it == '\t' || it in ' '..'~' }) return cmd
        val b64 = Base64.getEncoder().encodeToString(cmd.toByteArray(Charsets.UTF_8))
        return "echo $b64 | base64 -d | sh"
    }
}
