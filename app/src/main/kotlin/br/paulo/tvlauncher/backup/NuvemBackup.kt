package br.paulo.tvlauncher.backup

import br.paulo.tvlauncher.player.ContaMidia
import br.paulo.tvlauncher.player.item
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.URLEncoder
import org.json.JSONObject

/**
 * Cópia extra do backup noturno no OneDrive, Dropbox ou Box (ideia 98, 14/11/2026), usando as contas que a pessoa já
 * cadastrou em "Na rede" (Nuvem.kt). O arquivo já vem trancado pelo CofreBackup; aqui é só enviar para a pasta
 * "OneTV-backup" e apagar os mais velhos, deixando as [BackupNoturnoRegras.MANTER] últimas (igual ao NAS e ao Drive).
 *
 * Permissão de escrever: OneDrive pede sempre Files.ReadWrite.AppFolder (só a pasta do próprio app, em Aplicativos);
 * Dropbox e Box só escrevem se o dono marcou a permissão de escrita no app dele (os passos na tela dizem). Sem ela,
 * o envio falha e a tela do backup mostra "parcial: TV ok; ... falhou".
 *
 * A parte pura (endereços, corpo do envio do Box) tem teste em NuvemBackupTest.
 */
object NuvemBackup {
    val TIPOS = setOf(ContaMidia.ONEDRIVE, ContaMidia.DROPBOX, ContaMidia.BOX)
    private const val PASTA = BackupNoturnoRegras.PASTA_EXTERNA

    fun enviar(c: ContaMidia, nome: String, bytes: ByteArray) {
        require(BackupNoturnoRegras.nomeValido(nome)) { "nome de backup inválido" }
        when (c.tipo) {
            ContaMidia.ONEDRIVE -> oneDrive(c, nome, bytes)
            ContaMidia.DROPBOX -> dropbox(c, nome, bytes)
            ContaMidia.BOX -> box(c, nome, bytes)
            else -> throw IOException("esta conta não recebe backup")
        }
    }

    private fun ok(r: Nuvem.Resposta): String {
        if (r.codigo in 200..299) return r.corpo
        // 401/403: token sem permissão de escrever (Dropbox/Box sem a caixinha de escrita marcada no app do dono).
        throw IOException(if (r.codigo == 401 || r.codigo == 403) "sem permissão de escrever (HTTP ${r.codigo})" else "HTTP ${r.codigo}")
    }

    // ---------------------------------------------------------------- OneDrive: pasta do app (special/approot)

    fun oneDriveArquivo(nome: String) = "${Nuvem.GRAPH}/me/drive/special/approot:/$PASTA/${URLEncoder.encode(nome, "UTF-8")}:"

    private fun oneDrive(c: ContaMidia, nome: String, bytes: ByteArray) {
        // Envio simples (PUT .../content): o backup cifrado tem poucos MB.
        ok(Nuvem.enviar(oneDriveArquivo(nome) + "/content", "PUT", Nuvem.acesso(c), bytes, "application/octet-stream"))
        val lista = Nuvem.get("${Nuvem.GRAPH}/me/drive/special/approot:/$PASTA:/children?\$select=name&\$top=200", Nuvem.acesso(c))
        val nomes = Nuvem.lerFilhosGraph(ok(lista)).first.map { it.nome }
        for (velho in BackupNoturnoRegras.quaisApagar(nomes))
            runCatching { Nuvem.enviar(oneDriveArquivo(velho), "DELETE", Nuvem.acesso(c), null, null) }
    }

    // ---------------------------------------------------------------- Dropbox: /OneTV-backup

    private const val DBX_CONTEUDO = "https://content.dropboxapi.com"

    /** O cabeçalho Dropbox-API-Arg do envio: substitui o do mesmo dia ("Fazer agora" duas vezes). */
    fun argDropbox(nome: String): String = JSONObject().put("path", "/$PASTA/$nome").put("mode", "overwrite").put("mute", true).toString()

    private fun dropbox(c: ContaMidia, nome: String, bytes: ByteArray) {
        ok(Nuvem.enviar("$DBX_CONTEUDO/2/files/upload", "POST", Nuvem.acesso(c), bytes, "application/octet-stream",
            mapOf("Dropbox-API-Arg" to argDropbox(nome))))
        val r = Nuvem.postarJson("${Nuvem.DBX_API}/2/files/list_folder", Nuvem.acesso(c),
            JSONObject().put("path", "/$PASTA").put("limit", 200).toString())
        val nomes = Nuvem.lerListaDropbox(ok(r)).first.filter { !it.pasta }.map { it.nome }
        for (velho in BackupNoturnoRegras.quaisApagar(nomes))
            runCatching { Nuvem.postarJson("${Nuvem.DBX_API}/2/files/delete_v2", Nuvem.acesso(c), JSONObject().put("path", "/$PASTA/$velho").toString()) }
    }

    // ---------------------------------------------------------------- Box: pasta OneTV-backup na raiz

    private const val BOX_ENVIO = "https://upload.box.com/api/2.0/files"

    /** Corpo multipart do envio do Box: primeiro "attributes" (nome e pasta), depois o arquivo. */
    fun corpoBox(fronteira: String, atributos: String, nome: String, bytes: ByteArray): ByteArray = ByteArrayOutputStream().apply {
        fun linha(s: String) = write((s + "\r\n").toByteArray(Charsets.UTF_8))
        linha("--$fronteira"); linha("Content-Disposition: form-data; name=\"attributes\""); linha(""); linha(atributos)
        linha("--$fronteira"); linha("Content-Disposition: form-data; name=\"file\"; filename=\"$nome\"")
        linha("Content-Type: application/octet-stream"); linha("")
        write(bytes); linha(""); linha("--$fronteira--")
    }.toByteArray()

    private fun box(c: ContaMidia, nome: String, bytes: ByteArray) {
        val raiz = Nuvem.lerListaBox(ok(Nuvem.get(Nuvem.boxItens("0", 0), Nuvem.acesso(c)))).first
        val idPasta = raiz.firstOrNull { it.item.pasta && it.item.nome == PASTA }?.id ?: run {
            val r = Nuvem.postarJson("${Nuvem.BOX_API}/folders", Nuvem.acesso(c),
                JSONObject().put("name", PASTA).put("parent", JSONObject().put("id", "0")).toString())
            // 409 = a pasta já existe (raiz com mais de 1000 itens: a 1ª página da lista não a mostrou). O Box diz qual é.
            if (r.codigo == 409) runCatching { JSONObject(r.corpo).getJSONObject("context_info").getJSONArray("conflicts")
                .getJSONObject(0).getString("id") }.getOrNull() ?: JSONObject(ok(r)).getString("id")
            else JSONObject(ok(r)).getString("id")
        }
        val itens = Nuvem.lerListaBox(ok(Nuvem.get(Nuvem.boxItens(idPasta, 0), Nuvem.acesso(c)))).first.filter { !it.item.pasta }
        val fronteira = "onetv" + RetornoNuvem.novoNumero()
        val existente = itens.firstOrNull { it.item.nome == nome }
        // Mesmo nome (2ª vez no dia): versão nova do mesmo arquivo; senão, arquivo novo na pasta.
        val (url, atributos) = if (existente != null) "$BOX_ENVIO/${existente.id}/content" to JSONObject().put("name", nome).toString()
            else "$BOX_ENVIO/content" to JSONObject().put("name", nome).put("parent", JSONObject().put("id", idPasta)).toString()
        ok(Nuvem.enviar(url, "POST", Nuvem.acesso(c), corpoBox(fronteira, atributos, nome, bytes), "multipart/form-data; boundary=$fronteira"))
        val porNome = itens.associate { it.item.nome to it.id }
        for (velho in BackupNoturnoRegras.quaisApagar(porNome.keys + nome))
            porNome[velho]?.let { id -> runCatching { Nuvem.enviar("${Nuvem.BOX_API}/files/$id", "DELETE", Nuvem.acesso(c), null, null) } }
    }
}
