package br.paulo.tvlauncher

/**
 * Qual app está na frente agora, pelo que o serviço de acessibilidade vê (cada janela que abre).
 *
 * Até 12/11/2026 isto morava no ShizukuSobDemanda, que usava o mesmo sinal para subir o servidor do Shizuku embutido
 * quando um app cliente abria. O servidor saiu; o sinal ficou, porque o MCP (estado_tv), as rotinas, a Casa e a saúde
 * do acesso o usam.
 */
object JanelaNaFrente {
    @Volatile var pacote: String? = null
        private set

    /** A classe da tela da frente (só quando o sistema diz; ex.: "br.paulo.tvlauncher.MainActivity"). */
    @Volatile var classe: String? = null
        private set

    /** Chamado pelo serviço de acessibilidade a cada tela que abre. */
    fun apareceu(pacote: CharSequence?, classe: CharSequence? = null) {
        val p = pacote?.toString() ?: return
        if (p == "android" || p == "com.android.systemui") return   // diálogos e barras: não mudam quem está na frente
        this.pacote = p
        this.classe = classe?.toString()
    }

    /** A NOSSA HOME está na frente (teclas coloridas e números remapeados só valem aqui, ver RemapTeclas). */
    fun homeNaFrente(ctx: android.content.Context): Boolean =
        pacote == ctx.packageName && classe == MainActivity::class.java.name
}
