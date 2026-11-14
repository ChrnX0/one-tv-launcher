package br.paulo.tvlauncher

/**
 * Perfis de verdade (ideia 11, 14/11/2026): cada perfil (conta Google da TV, ver [Perfil]) com os SEUS favoritos de
 * canais, posições/"continuar", últimos episódios, interesses e ordem dos apps.
 *
 * Regra que protege as TVs instaladas: nada é migrado nem apagado. O dado de hoje (o "compartilhado", ex.:
 * favoritos.json) continua onde está e vira a base de todos os perfis:
 *  - LEITURA: o arquivo/chave do perfil ativo, se existir; senão o compartilhado (TV com uma conta só não muda nada);
 *  - ESCRITA: sempre no do perfil ativo (o compartilhado nunca é apagado nem sobrescrito por um perfil).
 * Sem conta, ou em modo visita, o perfil "ativo para os dados" é "" = o compartilhado, exatamente como antes.
 *
 * Só lógica pura (sem Android): testada em PerfilDadosTest. Quem lê o perfil ativo do disco é [Perfil.chaveDados].
 */
object PerfilDados {
    /** Arquivo pequeno em filesDir com a chave do perfil dos dados (vazio/ausente = compartilhado). Ver [Perfil.chaveDados]. */
    const val ARQUIVO_ATIVO = "perfil_dados.txt"
    const val MAX_CHAVE = 40

    /** Arquivos de files/player que passam a ser por perfil (os outros continuam da TV inteira). */
    val ARQUIVOS_POR_PERFIL = setOf("favoritos.json", "posicoes.json", "series_ultimos.json",
        "interesses_filmes.json", "interesses_series.json")

    private val VALIDA = Regex("[a-z0-9._-]{1,$MAX_CHAVE}")

    /**
     * "Ana.Maria+tv@gmail.com" -> "ana.maria_tv". Só [a-z0-9._-]: o resultado entra em nome de arquivo e em chave de
     * preferência, então nada de "/", "@" ou espaço. Pontos sozinhos ("." / "..") viram "_" para nunca parecer pasta.
     */
    fun sanitizar(conta: String?): String {
        val usuario = conta?.substringBefore('@')?.trim()?.lowercase() ?: return ""
        val limpo = usuario.map { if (it in 'a'..'z' || it in '0'..'9' || it == '.' || it == '_' || it == '-') it else '_' }
            .joinToString("").take(MAX_CHAVE)
        return if (limpo.isNotEmpty() && limpo.all { it == '.' }) "_".repeat(limpo.length) else limpo
    }

    /** A chave do perfil dos dados: "" (compartilhado) sem conta ou em modo visita. */
    fun chaveDoPerfil(conta: String?, visita: Boolean): String = if (visita) "" else sanitizar(conta)

    /** Relê a chave gravada no [ARQUIVO_ATIVO] (arquivo mexido à mão não vira nome de arquivo estranho). */
    fun lerChave(texto: String?): String {
        val t = texto?.trim().orEmpty()
        return if (VALIDA.matches(t)) sanitizar(t) else ""
    }

    /** "favoritos.json" + "ana" -> "favoritos@ana.json". Perfil "" = o próprio nome (compartilhado). */
    fun nomeArquivo(base: String, perfil: String): String {
        if (perfil.isEmpty()) return base
        val ponto = base.lastIndexOf('.')
        return if (ponto <= 0) "$base@$perfil" else base.substring(0, ponto) + "@" + perfil + base.substring(ponto)
    }

    /** "ordem" + "ana" -> "ordem@ana". Perfil "" = a própria chave. */
    fun chavePrefs(base: String, perfil: String): String = if (perfil.isEmpty()) base else "$base@$perfil"

    /** Qual nome ler: o do perfil se [existe], senão o compartilhado. */
    fun nomeLeitura(base: String, perfil: String, existe: (String) -> Boolean): String {
        val proprio = nomeArquivo(base, perfil)
        return if (perfil.isNotEmpty() && existe(proprio)) proprio else base
    }

    /** Mesma escolha, para chaves de preferência. */
    fun chaveLeitura(base: String, perfil: String, existe: (String) -> Boolean): String {
        val propria = chavePrefs(base, perfil)
        return if (perfil.isNotEmpty() && existe(propria)) propria else base
    }

    /** Onde gravar: sempre no do perfil ativo (no compartilhado só sem perfil). */
    fun nomeEscrita(base: String, perfil: String): String = nomeArquivo(base, perfil)

    /**
     * [nome] é a cópia de um perfil de um dos arquivos [ARQUIVOS_POR_PERFIL]? (ex.: "favoritos@ana.json"). Usado pelo
     * backup completo para levar e aceitar os arquivos novos sem abrir a porta para qualquer nome.
     */
    fun ehCopiaDePerfil(nome: String): Boolean {
        val arroba = nome.indexOf('@')
        if (arroba <= 0 || nome.indexOf('@', arroba + 1) >= 0) return false
        for (base in ARQUIVOS_POR_PERFIL) {
            val ponto = base.lastIndexOf('.')
            val raiz = base.substring(0, ponto); val ext = base.substring(ponto)
            if (!nome.startsWith("$raiz@") || !nome.endsWith(ext)) continue
            val perfil = nome.substring(raiz.length + 1, nome.length - ext.length)
            if (VALIDA.matches(perfil) && sanitizar(perfil) == perfil) return true
        }
        return false
    }
}
