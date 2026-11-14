package br.paulo.tvlauncher

/**
 * Ideia 43 (14/11/2026): páginas do painel web que só se configuram por lá ganham uma linha nas Configurações da TV
 * com o QR que entra no painel JÁ na página certa. O QR é o mesmo de "Entrar pelo celular" (código de uso único,
 * [CodigoEntrada]); só o fim do endereço muda: `#qr=<código>&ir=<aba>[.<id do cartão>]`. O app.js entra, abre a aba e
 * rola até o cartão. Objeto puro, testado na JVM.
 */
enum class DestinoPainel(val aba: String, val cartao: String = "") {
    CASA("extras", "cartaoCasa"),
    CENAS("extras", "cartaoCenas"),
    TIME("extras", "cartaoTime"),
    MURAL("extras", "cartaoMural"),
    FOTOS("fotos"),
    ROTEADOR("roteador");

    /** O pedaço `ir=` do endereço: "extras.cartaoCasa" ou só "fotos". */
    val ir: String get() = if (cartao.isEmpty()) aba else "$aba.$cartao"

    companion object {
        /** Só letras e números (e o ponto entre aba e cartão): o valor vai para o endereço sem escapar nada. */
        private val SEGURO = Regex("^[a-zA-Z0-9]+(\\.[a-zA-Z0-9]+)?$")

        /** O fim do endereço do QR: `#qr=<código>`, com `&ir=` quando há [destino]. */
        fun fragmento(codigo: String, destino: DestinoPainel?): String =
            "#qr=$codigo" + (destino?.ir?.takeIf { SEGURO.matches(it) }?.let { "&ir=$it" } ?: "")

        fun porNome(nome: String?): DestinoPainel? = values().firstOrNull { it.name == nome }
    }
}
