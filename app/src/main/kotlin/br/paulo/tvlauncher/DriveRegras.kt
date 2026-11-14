package br.paulo.tvlauncher

import org.json.JSONObject

/**
 * As contas do backup no Google Drive (DriveBackup.kt), sem nada de Android: assim dá para testar na JVM (DriveRegrasTest).
 *
 * Escopo `drive.file` (decisão do dono, 13/11/2026: "pasta visível no Drive"): o launcher só enxerga o que ele mesmo
 * criou. A pasta "OneTV-backup" aparece no Drive da pessoa como qualquer outra pasta (dá para baixar uma cópia pelo site
 * depois de um reset), e o launcher nunca vê o resto do Drive.
 * https://developers.google.com/workspace/drive/api/guides/api-specific-auth
 */
object DriveRegras {
    const val ESCOPO = "https://www.googleapis.com/auth/drive.file"
    /** O pedido de código do login "conta com Drive": o de sempre (e-mail, nome e foto) mais o Drive. */
    const val ESCOPOS_LOGIN = "email profile $ESCOPO"
    const val PASTA = BackupNoturnoRegras.PASTA_EXTERNA
    const val TIPO_PASTA = "application/vnd.google-apps.folder"
    const val API = "https://www.googleapis.com/drive/v3/files"
    const val UPLOAD = "https://www.googleapis.com/upload/drive/v3/files"

    /**
     * O Google deixa a pessoa desmarcar uma permissão na tela de aceite. A resposta do token diz o que foi dado
     * (campo "scope", separado por espaço); sem o Drive marcado, não adianta guardar a conta.
     */
    fun escopoConcedido(scope: String?): Boolean = scope?.split(' ')?.any { it == ESCOPO } == true

    /** Texto entre aspas simples na busca do Drive: barra e aspa escapadas com barra (regra da API "q"). */
    fun aspas(s: String): String = "'" + s.replace("\\", "\\\\").replace("'", "\\'") + "'"

    /** A nossa pasta (só as que o launcher criou aparecem, por causa do drive.file). */
    fun consultaPasta(): String = "name = ${aspas(PASTA)} and mimeType = ${aspas(TIPO_PASTA)} and trashed = false"

    /** As cópias dentro da pasta. */
    fun consultaCopias(pastaId: String): String = "${aspas(pastaId)} in parents and trashed = false"

    /** Ids do Drive são letras, números, "-" e "_": conferir antes de pôr num endereço. */
    fun idValido(id: String?): Boolean = id != null && id.length in 10..200 && id.all { it.isLetterOrDigit() || it == '-' || it == '_' }

    class Copia(val id: String, val nome: String, val tamanho: Long, val modificado: String)

    /**
     * Lê a resposta de files.list. Só entram arquivos com o nosso nome exato (BackupNoturnoRegras.nomeValido) e id
     * válido. Ordem: a mais nova primeiro (pelo nome, que tem a data; empate = a modificada por último primeiro).
     */
    fun copias(json: String): List<Copia> {
        val arr = JSONObject(json).optJSONArray("files") ?: return emptyList()
        val lista = (0 until arr.length()).mapNotNull { i ->
            val f = arr.optJSONObject(i) ?: return@mapNotNull null
            val id = f.optString("id"); val nome = f.optString("name")
            if (!idValido(id) || !BackupNoturnoRegras.nomeValido(nome)) null
            else Copia(id, nome, f.optString("size").toLongOrNull() ?: 0L, f.optString("modifiedTime"))
        }
        return lista.sortedWith(compareByDescending<Copia> { it.nome }.thenByDescending { it.modificado })
    }

    /**
     * Quais apagar para sobrarem as [manter] mais novas. No Drive dois arquivos podem ter o mesmo nome (o "Fazer agora"
     * duas vezes no mesmo dia sobe outra cópia): do mesmo nome fica só a mais nova, e as repetidas saem também.
     */
    fun quaisApagar(copias: List<Copia>, manter: Int = BackupNoturnoRegras.MANTER): List<String> {
        val vistos = HashSet<String>()
        val apagar = mutableListOf<String>()
        for (c in copias.sortedWith(compareByDescending<Copia> { it.nome }.thenByDescending { it.modificado })) {
            if (c.nome in vistos || vistos.size >= manter) apagar += c.id
            vistos += c.nome
        }
        return apagar
    }

    /** Corpo "multipart/related" do envio (metadados + conteúdo numa chamada só). */
    fun corpoMultipart(fronteira: String, nome: String, pastaId: String, bytes: ByteArray): ByteArray {
        val meta = JSONObject().put("name", nome).put("mimeType", "application/json")
            .put("parents", org.json.JSONArray().put(pastaId)).toString()
        val inicio = "--$fronteira\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n$meta\r\n" +
            "--$fronteira\r\nContent-Type: application/json\r\n\r\n"
        return inicio.toByteArray(Charsets.UTF_8) + bytes + "\r\n--$fronteira--\r\n".toByteArray(Charsets.UTF_8)
    }

    /**
     * O que fazer com a resposta da renovação do token. "invalid_grant" = a pessoa tirou o acesso, a senha da conta
     * mudou ou (cliente em modo teste) passaram os 7 dias: só entrando de novo pelo celular.
     */
    enum class Renovacao { OK, ENTRAR_DE_NOVO, TENTAR_DEPOIS }
    fun renovacao(resposta: JSONObject): Renovacao = when {
        resposta.optString("access_token").isNotEmpty() -> Renovacao.OK
        resposta.optString("error") == "invalid_grant" -> Renovacao.ENTRAR_DE_NOVO
        else -> Renovacao.TENTAR_DEPOIS
    }
}
