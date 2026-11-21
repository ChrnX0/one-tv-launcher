package br.paulo.tvlauncher.panel

import android.os.Handler
import android.os.Looper
import android.os.Process
import br.paulo.tvlauncher.BackupNoturno
import br.paulo.tvlauncher.LoginGoogle
import br.paulo.tvlauncher.RecuperacaoTv
import br.paulo.tvlauncher.backup.BackupCompleto
import br.paulo.tvlauncher.backup.BackupConfig
import br.paulo.tvlauncher.backup.BackupNoturnoRegras
import br.paulo.tvlauncher.backup.CofreBackup
import br.paulo.tvlauncher.backup.DriveBackup
import br.paulo.tvlauncher.backup.DriveRegras
import br.paulo.tvlauncher.backup.MudarDeTv
import br.paulo.tvlauncher.panel.PainelWeb.Resposta
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import org.json.JSONArray
import org.json.JSONObject

/** Painel web: backup, backup noturno e conta do Google Drive. Saiu do PainelWeb na refatoração de 21/11/2026. */

internal fun PainelWeb.restaurar(req: Requisicao, token: String?, ip: String): Resposta {
    if (!sessoes.elevada(token)) return precisaPin()
    return restaurarTexto(req.corpoTexto(), token, ip, "restaurar backup")
}

internal fun PainelWeb.backupCompleto(req: Requisicao, token: String?, ip: String): Resposta {
    if (!sessoes.elevada(token)) return precisaPin()
    val senha = corpoJson(req)?.optString("senha") ?: return erro(400, "JSON inválido")
    if (!CofreBackup.senhaValida(senha)) return erro(400, "a senha do backup precisa ter de 8 a 128 caracteres")
    val texto = try { BackupCompleto.exportar(app, senha) } catch (e: Exception) {
        PainelArmazem.registrar(app, ip, "baixar backup completo", "falhou: ${e.javaClass.simpleName}")
        return erro(500, "não consegui montar o backup (${e.javaClass.simpleName})")
    }
    PainelArmazem.registrar(app, ip, "baixar backup completo", "ok")
    return Resposta(200, "application/json; charset=utf-8", texto.toByteArray(),
        mapOf("Content-Disposition" to "attachment; filename=\"launcher-backup-completo.json\""))
}

/**
 * Elevação 19.6: chave de recuperação do backup. GET = se existe (data e digital, nunca o código). POST com PIN =
 * cria uma nova e devolve o código e o QR UMA vez (a TV guarda só a parte pública; nada aparece na tela da TV).
 * POST {"apagar":true} = a TV esquece a pública (backups novos voltam a abrir só com a senha).
 */
internal fun PainelWeb.recuperacao(req: Requisicao, token: String?, ip: String): Resposta {
    if (req.metodo == "GET") return json(200, JSONObject().put("tem", RecuperacaoTv.publica(app) != null)
        .put("criadaEm", RecuperacaoTv.criadaEm(app)).put("digital", RecuperacaoTv.digital(app) ?: ""))
    if (req.metodo != "POST") return erro(405, "método não aceito")
    if (!sessoes.elevada(token)) return precisaPin()
    if (corpoJson(req)?.optBoolean("apagar") == true) {
        RecuperacaoTv.apagar(app)
        PainelArmazem.registrar(app, ip, "chave de recuperação", "apagada")
        return json(200, JSONObject().put("ok", true))
    }
    val codigo = RecuperacaoTv.criar(app)
    val qr = java.util.Base64.getEncoder().encodeToString(QrCodigo.png(codigo, 480))
    PainelArmazem.registrar(app, ip, "chave de recuperação", "criada (${RecuperacaoTv.digital(app)})")   // o código nunca vai para o registro
    return json(200, JSONObject().put("codigo", codigo).put("qr", qr).put("digital", RecuperacaoTv.digital(app) ?: ""),
        mapOf("Cache-Control" to "no-store"))
}

internal fun PainelWeb.restaurarCompleto(req: Requisicao, token: String?, ip: String): Resposta {
    if (!sessoes.elevada(token)) return precisaPin()
    val o = corpoJson(req) ?: return erro(400, "JSON inválido")
    val senha = o.optString("senha"); val arquivo = o.optString("arquivo")
    return try {
        restaurarTexto(arquivo, token, ip, "restaurar backup completo") { BackupCompleto.importar(app, it, senha) }
    } catch (e: CofreBackup.SenhaErrada) {
        PainelArmazem.registrar(app, ip, "restaurar backup completo", "senha errada")
        erro(400, "senha do backup errada (ou o arquivo foi alterado)")
    }
}

/**
 * Estado para a aba Backup. A senha NUNCA volta (só "temSenha"). As pastas de rede vêm com id e nome; ler as contas
 * decifra pelo Keystore, por isso fica aqui (thread do servidor) e não na tela da TV.
 */
internal fun PainelWeb.backupNoturnoEstado(): JSONObject {
    val destino = BackupNoturno.destino(app)
    val nas = JSONArray().apply {
        for (c in runCatching { BackupNoturno.contasSmb(app) }.getOrDefault(emptyList()))
            put(JSONObject().put("valor", BackupNoturnoRegras.destinoSmb(c.id)).put("nome", c.nome))
    }
    val copias = JSONArray().apply {
        for (f in BackupNoturno.listar(app)) put(JSONObject().put("nome", f.name).put("tamanho", f.length()).put("quando", f.lastModified()))
    }
    return JSONObject()
        .put("ligado", BackupNoturno.ligado(app))
        .put("temSenha", BackupNoturno.temSenha(app))
        .put("minutoDoDia", BackupNoturno.minutoDoDia(app))
        .put("destino", destino)
        .put("destinoRotulo", BackupNoturno.rotuloDestino(app, destino))
        .put("nas", nas)
        .put("nuvens", JSONArray().apply {   // OneDrive, Dropbox e Box de "Na rede" (ideia 98)
            for (c in runCatching { BackupNoturno.contasNuvem(app) }.getOrDefault(emptyList()))
                put(JSONObject().put("valor", BackupNoturnoRegras.destinoNuvem(c.id)).put("nome", BackupNoturno.nomeNuvem(c)))
        })
        .put("drive", DriveBackup.email(app) ?: "")   // conta do Google Drive conectada na TV (só o e-mail)
        .put("google", LoginGoogle.configurado(app))   // sem o cliente OAuth do dono não há login do Drive
        .put("ultimo", BackupNoturno.ultimo(app))
        .put("copias", copias)
}

/**
 * Muda a configuração do backup noturno. Cada campo é opcional: {"ligado":true}, {"minutoDoDia":210},
 * {"destino":"pendrive"}, {"senha":"..."}. Senha e destino pedem o PIN de novo (sessão elevada), como o backup
 * completo: quem troca a senha decide quem abre as cópias; quem troca o destino decide para onde elas vão.
 */
internal fun PainelWeb.backupNoturnoConfig(req: Requisicao, token: String?, ip: String): Resposta {
    val o = corpoJson(req) ?: return erro(400, "JSON inválido")
    if ((o.has("senha") || o.has("destino")) && !sessoes.elevada(token)) return precisaPin()
    // Confere TUDO antes de gravar qualquer coisa: um campo ruim não deixa a configuração pela metade.
    val minuto = if (o.has("minutoDoDia")) o.optInt("minutoDoDia", -1).takeIf { it in 0 until 24 * 60 }
        ?: return erro(400, "horário inválido") else null
    var destinoNome = ""
    val destino = if (o.has("destino")) {
        val d = o.optString("destino")
        when {
            d == BackupNoturnoRegras.SO_TV || d == BackupNoturnoRegras.PENDRIVE -> d
            // Google Drive: só com a conta já conectada NA TV (o login é pelo código no celular, na tela da TV).
            d == BackupNoturnoRegras.DRIVE -> if (DriveBackup.conectado(app)) d
                else return erro(400, "conecte a conta do Google Drive primeiro (botão Conectar, logo acima)")
            // Nuvem: só uma conta OneDrive/Dropbox/Box já cadastrada em "Na rede".
            BackupNoturnoRegras.idNuvem(d) != null -> {
                val conta = runCatching { BackupNoturno.contasNuvem(app) }.getOrDefault(emptyList())
                    .firstOrNull { it.id == BackupNoturnoRegras.idNuvem(d) } ?: return erro(400, "destino desconhecido")
                destinoNome = BackupNoturno.nomeNuvem(conta)
                d
            }
            // NAS: só uma pasta SMB que já está cadastrada em "Mídias na rede" (nada de endereço digitado aqui).
            else -> {
                val conta = BackupNoturnoRegras.idSmb(d)?.let { id ->
                    runCatching { BackupNoturno.contasSmb(app) }.getOrDefault(emptyList()).firstOrNull { it.id == id }
                } ?: return erro(400, "destino desconhecido")
                destinoNome = conta.nome
                d
            }
        }
    } else null
    val senha = if (o.has("senha")) o.optString("senha").takeIf { CofreBackup.senhaValida(it) }
        ?: return erro(400, "a senha do backup precisa ter de 8 a 128 caracteres") else null
    if (senha != null) {
        if (!BackupNoturno.definirSenha(app, senha)) return erro(500, "não consegui guardar a senha na TV")
        PainelArmazem.registrar(app, ip, "backup noturno: senha nova", "ok")   // nunca a senha no registro
    }
    if (destino != null) {
        BackupNoturno.definirDestino(app, destino, destinoNome)
        PainelArmazem.registrar(app, ip, "backup noturno: cópia extra", BackupNoturno.rotuloDestino(app, destino))
    }
    if (minuto != null) BackupNoturno.definirMinutoDoDia(app, minuto)
    if (o.has("ligado")) {
        val sim = o.optBoolean("ligado")
        if (!BackupNoturno.definirLigado(app, sim)) return erro(400, "defina a senha antes de ligar")
        PainelArmazem.registrar(app, ip, "backup noturno", if (sim) "ligado" else "desligado")
    }
    return json(200, backupNoturnoEstado())
}

/**
 * "Fazer agora": roda já e espera até 60 s pelo resultado (o backup é pequeno; a cópia para o NAS é o que demora).
 * Passou disso, solta a thread do servidor, responde "rodando" e o resultado aparece depois em "último".
 */
internal fun PainelWeb.backupNoturnoAgora(ip: String): Resposta {
    if (!BackupNoturno.temSenha(app)) return erro(400, "defina a senha antes")
    val fila = ArrayBlockingQueue<String>(1)
    BackupNoturno.fazerAgora(app) { r -> fila.offer(r) }
    val r = fila.poll(60, java.util.concurrent.TimeUnit.SECONDS)
    PainelArmazem.registrar(app, ip, "backup noturno: fazer agora", r ?: "rodando")
    return json(200, backupNoturnoEstado().put("resultado", r ?: JSONObject.NULL))
}

/**
 * Começa o login do Drive: a TV pede ao Google um código curto, o painel mostra o código e o endereço
 * (google.com/device) com QR, e a TV fica perguntando ao Google até a pessoa terminar no celular. Pede o PIN de novo:
 * quem conecta a conta decide para onde as cópias da casa vão.
 */
internal fun PainelWeb.driveConectar(token: String?, ip: String): Resposta {
    if (!sessoes.elevada(token)) return precisaPin()
    if (!LoginGoogle.configurado(app)) return erro(400, "falta o login Google da TV (id e segredo do cliente OAuth, aba Claude)")
    loginDrive?.let { if (it.estado == LoginGoogle.Estado.ESPERANDO) it.estado = LoginGoogle.Estado.ERRO }
    val s = try { LoginGoogle.iniciar(app, emptyMap(), LoginGoogle.Finalidade.DRIVE) } catch (e: Exception) {
        PainelArmazem.registrar(app, ip, "Google Drive: conectar", "falhou: ${e.javaClass.simpleName}")
        return erro(502, e.message ?: "o Google não respondeu")
    }
    loginDrive = s
    PainelArmazem.registrar(app, ip, "Google Drive: conectar", "código pedido")
    return json(200, driveLoginEstado())
}

/** Estado do login do painel: esperando (com código e endereço), ok (com o e-mail), recusado/erro (com o motivo). */
internal fun PainelWeb.driveLoginEstado(): JSONObject {
    val s = loginDrive ?: return JSONObject().put("estado", "nenhum").put("drive", DriveBackup.email(app) ?: "")
    val o = JSONObject().put("estado", s.estado.name.lowercase()).put("drive", DriveBackup.email(app) ?: "")
    when (s.estado) {
        LoginGoogle.Estado.ESPERANDO -> o.put("codigo", s.codigoUsuario).put("url", s.urlVerificacao)
            .put("restaMs", (s.expira - System.currentTimeMillis()).coerceAtLeast(0))
        LoginGoogle.Estado.OK -> o.put("email", s.email ?: "")
        else -> o.put("mensagem", s.mensagem)
    }
    return o
}

/** "Restaurar do Drive": baixa a cópia escolhida e restaura com a senha do backup (o mesmo da tela da TV). */
internal fun PainelWeb.driveRestaurar(req: Requisicao, token: String?, ip: String): Resposta {
    if (!sessoes.elevada(token)) return precisaPin()
    val o = corpoJson(req) ?: return erro(400, "JSON inválido")
    val id = o.optString("id").takeIf { DriveRegras.idValido(it) } ?: return erro(400, "cópia inválida")
    val senha = o.optString("senha")
    if (!CofreBackup.senhaValida(senha)) return erro(400, "a senha do backup precisa ter de 8 a 128 caracteres")
    val texto = try { DriveBackup.baixar(app, id) } catch (e: Exception) {
        PainelArmazem.registrar(app, ip, "restaurar do Google Drive", "falhou: ${e.javaClass.simpleName}")
        return erro(502, "o Drive não entregou a cópia: ${e.message ?: e.javaClass.simpleName}")
    }
    return try {
        restaurarTexto(texto, token, ip, "restaurar do Google Drive") { BackupCompleto.importar(app, it, senha) }
    } catch (e: CofreBackup.SenhaErrada) {
        PainelArmazem.registrar(app, ip, "restaurar do Google Drive", "senha errada")
        erro(400, "senha do backup errada (ou o arquivo foi alterado)")
    }
}

/** Restaura um backup já lido (do arquivo enviado ou de uma cópia automática). [acao] vai para o registro. */
internal fun PainelWeb.restaurarTexto(texto: String, token: String?, ip: String, acao: String,
                           importar: (String) -> Int = { BackupConfig.importar(app, it) }): Resposta {
    if (!sessoes.elevada(token)) return precisaPin()
    val n = try {
        importar(texto)
    } catch (e: CofreBackup.SenhaErrada) {
        throw e
    } catch (e: IllegalArgumentException) {
        PainelArmazem.registrar(app, ip, acao, "recusado: ${e.message}")
        return erro(400, "backup recusado: ${e.message}")
    } catch (e: Exception) {
        PainelArmazem.registrar(app, ip, acao, "falhou: $e")
        return erro(500, "a restauração falhou na TV (${e.javaClass.simpleName}); as configurações de antes foram mantidas")
    }
    PainelArmazem.registrar(app, ip, acao, "ok, $n chaves")
    // Reinicia o launcher depois de a resposta sair (várias telas leem as preferências só ao abrir). É o mesmo
    // jeito do REINICIAR do adb. O sistema sobe o processo de novo para religar o serviço de acessibilidade, e é
    // ele (AutorizaShizukuService.onServiceConnected) que reabre o painel mesmo sem a HOME na frente. As sessões caem.
    Handler(Looper.getMainLooper()).postDelayed({ Process.killProcess(Process.myPid()) }, 1500)
    return json(200, JSONObject().put("ok", true).put("chaves", n).put("reiniciando", true))
}

/**
 * Elevação 19.7, TV antiga: manda tudo (backup completo + lista de apps) para a TV nova do código. Leva os segredos
 * da casa para outro aparelho: só com o PIN confirmado de novo, como baixar o backup completo.
 */
internal fun PainelWeb.enviarParaTvNova(req: Requisicao, token: String?, ip: String): Resposta {
    if (!sessoes.elevada(token)) return precisaPin()
    val codigo = corpoJson(req)?.optString("codigo").orEmpty()
    val de = try { MudarDeTv.enviar(app, codigo) } catch (e: IllegalArgumentException) {
        PainelArmazem.registrar(app, ip, "levar para TV nova", "recusado: ${e.message}")
        return erro(400, e.message ?: "não deu")
    } catch (e: Exception) {
        PainelArmazem.registrar(app, ip, "levar para TV nova", "falhou: $e")
        return erro(500, "não deu para montar o envio (${e.javaClass.simpleName})")
    }
    PainelArmazem.registrar(app, ip, "levar para TV nova", "ok")
    return json(200, JSONObject().put("ok", true).put("de", de))
}

/** Elevação 19.7, TV nova: o envelope chegou (segredo já conferido). Restaura e reinicia, como o restaurar. */
internal fun PainelWeb.receberDeOutraTv(req: Requisicao, ip: String): Resposta {
    val de = try { MudarDeTv.receber(app, req.corpoTexto()) } catch (e: CofreBackup.SenhaErrada) {
        return erro(400, "o envelope não abriu")
    } catch (e: IllegalArgumentException) {
        PainelArmazem.registrar(app, ip, "trazer de outra TV", "recusado: ${e.message}")
        return erro(400, "a TV nova recusou: ${e.message}")
    } catch (e: MudarDeTv.NaoEsperando) {
        return erro(410, "esta TV não está esperando")
    } catch (e: Exception) {
        PainelArmazem.registrar(app, ip, "trazer de outra TV", "falhou: $e")
        return erro(500, "a restauração falhou na TV nova (${e.javaClass.simpleName}); nada mudou")
    }
    PainelArmazem.registrar(app, ip, "trazer de outra TV", "ok")
    Handler(Looper.getMainLooper()).postDelayed({ Process.killProcess(Process.myPid()) }, 4000)   // a tela mostra "Pronto" antes
    return json(200, JSONObject().put("ok", true).put("de", PainelVizinhos.nomeDaTv(app)))
}
