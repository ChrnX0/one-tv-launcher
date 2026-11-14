package br.paulo.tvlauncher

import org.json.JSONObject
import java.util.Base64

/**
 * Ideia 64 (14/11/2026): ensaio da restauração. Depois de gravar a cópia da noite, o BackupNoturno relê o arquivo do
 * disco, abre com a senha e confere aqui se ele SERIA aceito pelo BackupCompleto.importar, sem aplicar nada.
 * Assim um backup estragado aparece no dia em que estragou, e não no dia de um reset (quando já é tarde).
 *
 * Puro (só org.json): roda na JVM (EnsaioRestauracaoTest). Confere a mesma casca que o importar confere antes de
 * mexer em algo: versão 3, pacote, preferências, nomes de arquivo conhecidos e Base64 legível. A validação fina de cada
 * preferência (BackupConfig.validar) precisa de Context e fica para a restauração de verdade.
 */
object EnsaioRestauracao {
    /** Resultado: [ok] e, se não, o motivo curto (vai para o registro e para a tela do backup). */
    data class Resultado(val ok: Boolean, val motivo: String = "", val preferencias: Int = 0, val arquivos: Int = 0)

    /**
     * [claro] = o texto que o CofreBackup.abrir devolveu. [pacote] = o nosso. [arquivosPermitidos] = os caminhos que
     * o importar aceita (player/... e tunel/...). [limite] = o mesmo LIMITE_BYTES do BackupCompleto.
     */
    fun conferir(claro: String, pacote: String, arquivosPermitidos: Set<String>, limite: Int): Resultado =
        conferir(claro, pacote, limite) { it in arquivosPermitidos }

    /**
     * Mesma conferência, com [permitido] dizendo se um caminho é aceito. Perfis de verdade (ideia 11): os arquivos por
     * perfil (player/favoritos@ana.json...) não cabem numa lista fixa; o BackupNoturno passa BackupCompleto.caminhoPermitido.
     */
    fun conferir(claro: String, pacote: String, limite: Int, permitido: (String) -> Boolean): Resultado {
        if (claro.length > limite) return Resultado(false, "grande demais")
        val o = runCatching { JSONObject(claro) }.getOrNull() ?: return Resultado(false, "ilegível")
        if (o.optInt("versao") != 3) return Resultado(false, "versão ${o.opt("versao")}")
        if (o.optString("pacote") != pacote) return Resultado(false, "de outro app")
        val prefs = o.optJSONObject("preferencias") ?: return Resultado(false, "sem preferências")
        if (prefs.length() == 0) return Resultado(false, "sem preferências")
        for (nome in prefs.keys()) if (prefs.optJSONObject(nome) == null) return Resultado(false, "preferência estragada: $nome")
        val arquivos = o.optJSONObject("arquivos") ?: JSONObject()
        for (caminho in arquivos.keys()) {
            if (!permitido(caminho)) return Resultado(false, "arquivo desconhecido: $caminho")
            val b = runCatching { Base64.getDecoder().decode(arquivos.getString(caminho)) }.getOrNull()
                ?: return Resultado(false, "arquivo estragado: $caminho")
            if (b.isEmpty()) return Resultado(false, "arquivo vazio: $caminho")
        }
        return Resultado(true, preferencias = prefs.length(), arquivos = arquivos.length())
    }

    /**
     * O ensaio inteiro a partir do arquivo gravado: abre o cofre e confere. [abrir] = CofreBackup.abrir (injetado para
     * o teste poder usar o cofre de verdade sem o Android). Senha que não abre = "não abre com a senha guardada".
     */
    fun ensaiar(envelope: String, senha: String, pacote: String, arquivosPermitidos: Set<String>, limite: Int,
                abrir: (String, String) -> String = CofreBackup::abrir): Resultado =
        ensaiar(envelope, senha, pacote, limite, { it in arquivosPermitidos }, abrir)

    fun ensaiar(envelope: String, senha: String, pacote: String, limite: Int, permitido: (String) -> Boolean,
                abrir: (String, String) -> String = CofreBackup::abrir): Resultado {
        val claro = try { abrir(envelope, senha) }
        catch (e: CofreBackup.SenhaErrada) { return Resultado(false, "não abre com a senha guardada") }
        catch (e: Exception) { return Resultado(false, "cofre estragado") }
        return conferir(claro, pacote, limite, permitido)
    }
}
