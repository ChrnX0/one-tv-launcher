package br.paulo.tvlauncher

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * "Pen drive e armazenamento" no painel web e no celular (proposta C, 20/11/2026). A mesma lógica da TV
 * (StorageMode / AppStorage), para escolher do sofá com o celular na mão.
 *
 *   GET  /api/storage            estado: memória da TV, pens (modo, tamanho, recomendação), apps móveis, tarefa em curso
 *   POST /api/storage/app        {pacote, externo}             move um app (PIN de novo)
 *   POST /api/storage/formatar   {disco, modo, comum}          APAGA o pen drive (PIN de novo; traz os apps antes)
 *
 * Mover e formatar levam minutos: rodam em segundo plano, uma tarefa por vez; o GET mostra o andamento.
 */
object PainelStorage {
    private fun ok(o: JSONObject = JSONObject()) = 200 to o.put("ok", true)
    private fun erro(codigo: Int, msg: String, motivo: String) = codigo to JSONObject().put("erro", msg).put("motivo", motivo)
    private fun pin() = 403 to JSONObject().put("erro", "confirme o PIN").put("precisaPin", true)

    @Volatile private var tarefa: String? = null      // o que está rodando agora (null = nada)
    @Volatile private var ultimo: JSONObject? = null  // resultado da última tarefa

    private fun rodar(app: Context, ip: String, nome: String, acao: () -> String?): Pair<Int, JSONObject> {
        synchronized(this) {
            if (tarefa != null) return erro(409, "já tem uma tarefa rodando", "OCUPADO")
            tarefa = nome
        }
        Fundo.rodar("PainelStorage") {
            val motivo = runCatching { acao() }.getOrElse { it.javaClass.simpleName }
            ultimo = JSONObject().put("tarefa", nome).put("ok", motivo == null).put("motivo", motivo ?: JSONObject.NULL)
            PainelArmazem.registrar(app, ip, "armazenamento: $nome", if (motivo == null) "ok" else "falhou")
            tarefa = null
        }
        return 202 to JSONObject().put("ok", true).put("tarefa", nome)
    }

    fun estado(app: Context): JSONObject {
        val e = StorageMode.ler(app)
        val o = JSONObject().put("shell", e != null).put("tarefa", tarefa ?: JSONObject.NULL).put("ultimo", ultimo ?: JSONObject.NULL)
        if (e == null) return o
        o.put("livreTv", e.livreTv).put("totalTv", e.totalTv).put("primarioNoPen", e.primarioNoPen)
        o.put("pens", JSONArray().apply {
            e.pens.forEach { p ->
                val r = StorageModeRules.recomendar(e.livreTv, p.tamanho, null)
                put(JSONObject().put("disco", p.disco).put("modo", p.modo.name).put("tamanho", p.tamanho)
                    .put("adotado", p.adotadoUuid != null)
                    .put("recomendado", r.modo.name).put("recomendadoComum", r.comumPorcento).put("motivo", r.motivo.name))
            }
        })
        o.put("apps", JSONArray().apply {
            StorageMode.appsMoveis(app).forEach { a ->
                put(JSONObject().put("pacote", a.pacote).put("nome", a.nome).put("bytes", a.bytes).put("noPen", a.noExterno))
            }
        })
        return o
    }

    fun rotear(app: Context, metodo: String, rota: String, corpo: JSONObject?, elevada: Boolean, ip: String): Pair<Int, JSONObject>? {
        val get = metodo == "GET"; val post = metodo == "POST"
        return when {
            get && rota == "" -> ok(estado(app))
            post && rota == "/app" -> {
                if (!elevada) return pin()
                val pacote = corpo?.optString("pacote").orEmpty()
                val externo = corpo?.optBoolean("externo") == true
                val pode = AppStorage.destino(app, pacote)
                val esperado = if (externo) AppStorageRules.Destino.EXTERNO else AppStorageRules.Destino.DISPOSITIVO
                if (pode != esperado) return erro(422, "este app não pode ir para lá agora", "INVALIDO")
                rodar(app, ip, "mover app") { AppStorage.mover(app, pacote, externo) }
            }
            post && rota == "/formatar" -> {
                if (!elevada) return pin()
                val disco = corpo?.optString("disco").orEmpty()
                val modo = runCatching { StorageModeRules.Modo.valueOf(corpo?.optString("modo").orEmpty()) }.getOrNull()
                    ?: return erro(422, "modo inválido", "INVALIDO")
                val comum = corpo?.optInt("comum", 50) ?: 50
                if (StorageModeRules.comandoParticao(disco, modo, comum) == null) return erro(422, "pedido inválido", "INVALIDO")
                rodar(app, ip, "formatar pen drive") {
                    val e = StorageMode.ler(app) ?: return@rodar "shell"
                    val pen = e.pens.firstOrNull { it.disco == disco } ?: return@rodar "sem pen drive"
                    val volta = if (pen.adotadoUuid != null) StorageBackup.trazerTudoDeVolta(app, e.primarioNoPen) {} else null
                    volta ?: StorageMode.formatar(app, disco, modo, comum)
                }
            }
            else -> null
        }
    }
}
