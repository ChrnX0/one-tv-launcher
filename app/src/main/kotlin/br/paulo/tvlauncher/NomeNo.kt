package br.paulo.tvlauncher

import java.text.Normalizer

/**
 * Nome da TV na rede Tailscale (10/11/2026, decisão do dono): "onetv-<nome do aparelho>", ex.: "onetv-sala".
 * Antes era "tv-sala" fixo no código, e toda TV com o launcher entraria com esse nome.
 *
 * O nome do aparelho é o das configurações da TV (Settings.Global "device_name", ex.: "Sala"). O resultado vira o
 * começo do endereço (onetv-sala.tail1234.ts.net), então precisa valer como nome DNS: só a-z, 0-9 e hífen, sem hífen
 * nas pontas, até 63 caracteres. "Sala de Estar" -> "onetv-sala-de-estar"; "Cozinha Nº 2" -> "onetv-cozinha-n-2".
 * Puro (sem Android): testado na JVM em NomeNoTest.
 */
object NomeNo {
    const val PREFIXO = "onetv"   // "One TV Launcher", nome escolhido pelo dono
    private const val MAX = 63    // limite de um rótulo DNS

    fun montar(nomeAparelho: String?): String {
        // Tira o acento: "ç" vira "c" + sinal solto (NFD), e o sinal some no filtro abaixo.
        val semAcento = Normalizer.normalize(nomeAparelho ?: "", Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "").lowercase()
        val miolo = semAcento.replace(Regex("[^a-z0-9]+"), "-").trim('-')
        val nome = if (miolo.isEmpty()) "$PREFIXO-tv" else "$PREFIXO-$miolo"
        return nome.take(MAX).trimEnd('-')
    }
}
