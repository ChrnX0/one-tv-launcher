package br.paulo.tvlauncher.privilege

/**
 * Quando o launcher pode emprestar o shell a outros apps (servidor do Shizuku embutido, 15/11/2026, "poder máximo").
 * Só lógica pura (sem Android), testada em RegrasEmprestimoTest.
 *
 * O servidor fica PARADO (e nenhum app recebe nada) se:
 *  - o dono não ligou (padrão de fábrica: desligado);
 *  - o plano não inclui o recurso (ponto único: Planos, recurso PODER_EMPRESTADO);
 *  - o app Shizuku oficial está instalado: os dois servidores brigariam pelos mesmos apps clientes, e quem tem o
 *    oficial já escolheu usar ele.
 */
object RegrasEmprestimo {
    enum class Modo { DESLIGADO, SOB_DEMANDA, SEMPRE }

    enum class Parado { NAO, DESLIGADO, PLANO, SHIZUKU_OFICIAL }

    /** Pacote do app Shizuku oficial (o "gerente" deles). */
    const val PACOTE_SHIZUKU_OFICIAL = "moe.shizuku.privileged.api"

    /** A permissão que os apps clientes PEDEM. O launcher não a define (definir conflitava com o app oficial). */
    const val PERMISSAO_CLIENTE = "moe.shizuku.manager.permission.API_V23"

    fun parado(modo: Modo, planoPermite: Boolean, oficialInstalado: Boolean): Parado = when {
        modo == Modo.DESLIGADO -> Parado.DESLIGADO
        !planoPermite -> Parado.PLANO
        oficialInstalado -> Parado.SHIZUKU_OFICIAL
        else -> Parado.NAO
    }

    /** Lido das preferências: texto desconhecido (versão futura, arquivo mexido) = desligado. */
    fun modoDe(texto: String?): Modo = Modo.values().firstOrNull { it.name == texto } ?: Modo.DESLIGADO

    /** O app pede o Shizuku? (lista de permissões que o próprio app declara no manifesto) */
    fun ehCliente(pedidas: Array<String>?): Boolean = pedidas?.contains(PERMISSAO_CLIENTE) == true

    /**
     * Comando de partida do servidor, o mesmo que o starter do Shizuku monta: app_process apontando para o APK do
     * launcher, numa sessão nova (setsid), sem entrada nem saída, para sobreviver ao fim do shell do adb. O
     * redirecionamento fica no GRUPO "( ... )": senão o subshell segura a saída do adb aberta (medido em 04/11: 20 s).
     * "sleep 2" no fim: se o shell do adb fechasse na hora, levaria junto o subshell antes do setsid (medido em 04/11).
     */
    fun comandoPartida(apk: String, libs: String): String {
        require(apk.none { it.isWhitespace() || it in "'\"`$;&|<>()\\" }) { "caminho do APK inesperado" }
        require(libs.none { it.isWhitespace() || it in "'\"`$;&|<>()\\" }) { "caminho das libs inesperado" }
        return "(cd / && CLASSPATH=$apk exec setsid /system/bin/app_process -Djava.class.path=$apk " +
            "-Dshizuku.library.path=$libs /system/bin --nice-name=$NOME_PROCESSO $CLASSE) </dev/null >/dev/null 2>&1 & " +
            "sleep 2; echo disparado"
    }

    const val NOME_PROCESSO = "shizuku_server"
    const val CLASSE = "rikka.shizuku.server.ShizukuService"
}
