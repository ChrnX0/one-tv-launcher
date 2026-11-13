package br.paulo.tvlauncher

import java.security.MessageDigest
import java.text.Normalizer

/**
 * Id estável do canal de lista M3U (player-8, 10/11/2026). Antes, canal sem tvg-id tinha a própria URL como id. Problemas:
 *  - muitas listas trocam o token da URL todo dia: o id mudava e o favorito (e o bloqueio do controle parental) se perdia;
 *  - URL com mais de 300 letras era descartada ao ler os favoritos;
 *  - a URL costuma levar usuário, senha ou token, e ia parar em favoritos.json, recentes.json e parental.json.
 * Agora o id é, nesta ordem: o tvg-id; o nome + a categoria, normalizados; um resumo (hash) da URL sem usuário, senha,
 * parâmetros nem token. Nunca a URL em si.
 *
 * Só lógica pura (sem Android), testada em IdCanalTest.
 */
object IdCanal {
    private const val MAX_ID = 200   // bem abaixo dos 300 que PlayerLista.idsDeJson aceita (o id ainda ganha "conta:")

    /** O id estável do canal (sem o prefixo da conta: quem põe é PlayerDados.juntar). */
    fun estavel(tvgId: String, nome: String, grupo: String, url: String): String {
        val t = tvgId.trim()
        if (t.isNotEmpty()) return if (t.length > MAX_ID || pareceUrl(t)) "t#" + resumo(t) else t
        val n = normalizar(nome)
        if (n.isNotEmpty()) {
            val id = "n:$n|${normalizar(grupo)}"
            return if (id.length > MAX_ID) "n#" + resumo(id) else id
        }
        return "u:" + resumo(urlSemSegredo(url))
    }

    /** Minúsculas, sem acento e com um espaço só entre as palavras: "  Canal  Ação " e "canal acao" dão o mesmo id. */
    fun normalizar(s: String): String =
        Normalizer.normalize(s.trim().lowercase(), Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")
            .replace(Regex("\\s+"), " ")

    /**
     * A URL sem o que muda ou é segredo: tira usuário:senha@, tudo depois de "?" (token, assinatura, validade) e de "#".
     * Usada só para o resumo e para achar o canal de um favorito antigo; nunca é gravada.
     */
    fun urlSemSegredo(url: String): String {
        val u = url.trim().substringBefore('#').substringBefore('?')
        val esquema = u.substringBefore("://", "")
        if (esquema.isEmpty()) return u
        val resto = u.substringAfter("://")
        val host = resto.substringBefore('/')
        val caminho = resto.removePrefix(host)
        return "${esquema.lowercase()}://${host.substringAfterLast('@').lowercase()}$caminho"
    }

    /** O id é uma URL (o formato antigo)? Esses nunca podem ser gravados. */
    fun pareceUrl(id: String): Boolean = "://" in id

    private fun resumo(s: String): String {
        val d = MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8))
        return d.take(10).joinToString("") { "%02x".format(it) }
    }

    /** Id feito do nome + categoria (pode repetir na mesma lista: ver M3u.desduplicarIds). */
    fun porNome(id: String): Boolean = id.startsWith("n:") || id.startsWith("n#")

    /** Resumo curto da URL sem segredo: desempate de canais com o mesmo nome na mesma categoria (HD/SD, reserva). */
    fun resumoUrl(url: String): String = resumo(urlSemSegredo(url)).take(12)

    /**
     * Migração dos ids antigos (favoritos, recentes, canais bloqueados, último canal), conta por conta. Um id no formato
     * antigo é "conta:URL":
     *  - conta com canais carregados agora: acha o canal com a mesma URL (ou a mesma URL sem token, para o caso de o token
     *    já ter mudado só nos parâmetros) e troca pelo id novo; não achou, o id sai (não volta a valer e pode levar senha);
     *  - conta que existe mas está sem canais agora (lista fora do ar): o id fica como está e espera a próxima carga
     *    (apagar tiraria o bloqueio do controle parental por engano);
     *  - conta que não existe mais (apagada, ou id sem conta na frente): o id sai.
     * Ids que não são URL ficam como estão.
     *
     * @param canais os canais já juntados (id com a conta na frente, como em PlayerDados.juntar)
     * @param contas as contas cadastradas agora (PlayerArmazem.fontes). Vazio = não sabe: nenhuma conta conta como apagada.
     * @return a lista nova, sem repetidos e na mesma ordem; ou null se nada mudou (não precisa gravar)
     */
    fun migrar(ids: List<String>, canais: List<Canal>, contas: Set<String> = emptySet()): List<String>? {
        if (ids.none { pareceUrl(it) }) return null
        val porUrl = HashMap<String, String>()
        val porUrlLimpa = HashMap<String, String>()
        val carregadas = HashSet<String>()
        for (c in canais) {
            val conta = c.id.substringBefore(':', "")
            carregadas += conta
            porUrl.putIfAbsent("$conta:${c.url}", c.id)
            porUrlLimpa.putIfAbsent("$conta:${urlSemSegredo(c.url)}", c.id)
        }
        val saida = LinkedHashSet<String>()
        for (id in ids) {
            if (!pareceUrl(id)) { saida += id; continue }
            val conta = id.substringBefore(':', "")
            if (conta !in carregadas) {
                if (contas.isEmpty() || conta in contas) saida += id   // fora do ar agora: espera (nunca solta bloqueio)
                continue                                                // conta apagada: sai
            }
            val url = id.substringAfter(':')
            val novo = porUrl["$conta:$url"] ?: porUrlLimpa["$conta:${urlSemSegredo(url)}"]
            if (novo != null) saida += novo
        }
        val nova = saida.toList()
        return if (nova == ids) null else nova
    }

    /** O mesmo para um id só (último canal): o próprio id = espera; null = sumiu. */
    fun migrarUm(id: String, canais: List<Canal>, contas: Set<String> = emptySet()): String? =
        if (!pareceUrl(id)) id else migrar(listOf(id), canais, contas).let { if (it == null) id else it.firstOrNull() }
}
