package br.paulo.tvlauncher

/**
 * Ideia 54 (14/11/2026): Wi-Fi das visitas em QR. O texto do QR no formato que a câmera do Android e do iPhone
 * entendem ("WIFI:T:WPA;S:<rede>;P:<senha>;;"). Puro, testado na JVM (WifiVisitaQrTest).
 * A TV não lê a senha do Wi-Fi: o dono digita uma vez (Configurações > Visitas). O certo é a rede de VISITAS do
 * roteador, porque quem está na sala lê a senha na tela.
 */
object WifiVisitaQr {
    const val MAX = 63   // o WPA aceita senha de até 63 caracteres; nome de rede tem até 32 bytes

    /** Rede e senha aceitas: rede com 1 a 32 caracteres; senha vazia (rede aberta) ou 8 a 63. Sem caractere de controle. */
    fun valido(rede: String, senha: String): Boolean =
        rede.isNotBlank() && rede.length <= 32 && (senha.isEmpty() || senha.length in 8..MAX) &&
            (rede + senha).none { Character.isISOControl(it) }

    /** \ ; , : e " levam uma barra antes (regra do formato WIFI:). */
    fun escapar(t: String): String = buildString { for (c in t) { if (c in "\\;,:\"") append('\\'); append(c) } }

    /** O texto do QR. Senha vazia = rede aberta (T:nopass). */
    fun texto(rede: String, senha: String): String =
        if (senha.isEmpty()) "WIFI:T:nopass;S:${escapar(rede)};;"
        else "WIFI:T:WPA;S:${escapar(rede)};P:${escapar(senha)};;"
}
