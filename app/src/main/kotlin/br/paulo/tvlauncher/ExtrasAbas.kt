package br.paulo.tvlauncher

/**
 * Ideia 46 (14/11/2026): cada chave de [Extras] mora na aba das Configurações a que ela pertence, em vez de uma tela
 * só com 19 chaves sem relação entre si. Objeto puro (só nomes), testado na JVM: chave nova sem aba quebra o teste.
 *
 * A tela Extras continua existindo, mas só com os ajustes finos (horários do filtro e da hora de dormir) e as
 * ferramentas (testador de teclas, calibração, diário de DNS...).
 */
object ExtrasAbas {
    /** As abas das Configurações que recebem chaves, na ordem do menu (ConfigLauncherActivity.abas). */
    enum class Aba { PARENTAL, APARENCIA, ENERGIA, VISITAS, REDE, REMOTO, SISTEMA }

    /** Nome da chave ([Extras.Chave.name]) -> aba. A ordem dentro da aba é a desta lista. */
    val MAPA: Map<String, Aba> = linkedMapOf(
        // Controle parental: o que segura a criança.
        "HORA_DORMIR" to Aba.PARENTAL, "TEMPO_TELA" to Aba.PARENTAL, "PIN_HOME" to Aba.PARENTAL,
        // Aparência: o que muda o que se vê e ouve nas telas do launcher.
        "COR_DO_FUNDO" to Aba.APARENCIA, "MODO_SIMPLES" to Aba.APARENCIA, "SOM_NAVEGACAO" to Aba.APARENCIA,
        "TOCANDO_AGORA" to Aba.APARENCIA, "AGENDA_TODAS" to Aba.APARENCIA, "HOJE_NA_TV" to Aba.APARENCIA,
        // Tela e energia: noite, protetor e desligar.
        "FILTRO_NOTURNO" to Aba.ENERGIA, "SONO_SUAVE" to Aba.ENERGIA, "PRESENCA" to Aba.ENERGIA,
        "MURAL" to Aba.ENERGIA, "RETROSPECTIVA" to Aba.ENERGIA, "MODO_LEVE" to Aba.ENERGIA,
        // Rede.
        "DIARIO_DNS" to Aba.REDE,
        // Acesso remoto: o que deixa algo de fora agir na TV.
        "RECADO_LINK" to Aba.REMOTO, "VER_TELA" to Aba.REMOTO,
        // Sistema: atualização e proteção do próprio launcher.
        "JANELA_OTA" to Aba.SISTEMA, "TRAVAR_CRITICOS" to Aba.SISTEMA, "DEBLOAT_POS_OTA" to Aba.SISTEMA,
    )

    /** As chaves de [aba], na ordem do [MAPA], entre os [nomes] que existem nesta versão. */
    fun daAba(aba: Aba, nomes: Collection<String>): List<String> = MAPA.filter { it.value == aba && it.key in nomes }.keys.toList()
}
