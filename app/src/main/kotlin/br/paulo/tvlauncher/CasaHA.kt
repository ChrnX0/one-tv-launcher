package br.paulo.tvlauncher

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * A TV como painel da casa (ideia grande 1, 10/11/2026), pelo Home Assistant da casa, pela API REST dele
 * (https://developers.home-assistant.io/docs/api/rest/). Nada de nuvem: a TV fala direto com o servidor na rede de casa.
 *
 *  - lista luzes, tomadas, ventiladores, cortinas, cenas e scripts ([DOMINIOS]) e liga/desliga com o controle, pelo painel
 *    e pelo MCP. Catálogo fechado: só "ligar", "desligar" e "alternar" nesses domínios ([servico]); nada de serviço livre;
 *  - a TV vira sensor da casa: publica `sensor.tv_<nome>` com "ligada"/"dormindo", o app na frente e, com o Tocando agora
 *    ligado, a mídia (tocando/pausado e o título, [atributosMidia]) ([publicarEstado]),
 *    para automações ("pausou o filme, acende a luz baixa");
 *  - campainha: se um sensor estiver configurado ([CHAVE_CAMPAINHA]) e for para "on", a TV mostra a foto da câmera
 *    ([CHAVE_CAMERA]) por cima do que estiver passando, com o [AvisoTv] (mesmo caminho do recado com foto).
 *
 * O token de acesso (long-lived token do Home Assistant) fica cifrado no Keystore, como a chave do UniFi. Verificação de
 * TLS normal: se o servidor usar https com certificado próprio, não conecta (nunca desligar a verificação).
 *
 * Google Home: fica de fora. As Home APIs (https://developers.home.google.com/apis) falam em Android e iOS; não verifiquei
 * se rodam no Google TV nem se pedem aprovação da Google. Quem tem o Google Home pode ligá-lo ao Home Assistant.
 *
 * Não verificado: tudo isto contra um Home Assistant de verdade (a nuvem não alcança a casa).
 */
object CasaHA {
    private const val TAG = "TvLauncher.Casa"
    private const val PREFS = "casa_ha"
    private const val APELIDO = "casa_ha_token"
    const val CHAVE_CAMPAINHA = "campainha"
    const val CHAVE_CAMERA = "camera"
    val DOMINIOS = setOf("light", "switch", "fan", "cover", "scene", "script", "input_boolean")

    class Entidade(val id: String, val nome: String, val estado: String) {
        val dominio get() = id.substringBefore('.')
        fun json(): JSONObject = JSONObject().put("id", id).put("nome", nome).put("estado", estado)
    }

    // ---------------------------------------------------------------- puro

    /** Endereço do Home Assistant: http(s)://host[:porta], sem caminho. Devolve normalizado ou null. */
    fun enderecoValido(texto: String): String? {
        val t = texto.trim().trimEnd('/')
        val m = Regex("^(https?)://([A-Za-z0-9.-]{1,253})(:\\d{1,5})?$").matchEntire(t) ?: return null
        val porta = m.groupValues[3].removePrefix(":").toIntOrNull()
        if (porta != null && porta !in 1..65535) return null
        // http manda o token em texto aberto: só dentro de casa (auditoria de 10/11/2026). Fora de casa, https.
        if (m.groupValues[1] == "http" && !hostDeCasa(m.groupValues[2])) return null
        return t
    }

    /**
     * Endereço de dentro de casa (ou da rede Tailscale), onde http sem cadeado é aceitável. Pura.
     * IP privado (10/8, 172.16/12, 192.168/16, 127/8), nome sem ponto ("homeassistant", resolvido pelo DNS do
     * roteador, como o "chromecast" do painel) ou terminado em .local/.lan/.home.arpa. Fora de propósito: 100.64/10
     * (também é o CGNAT da operadora, fora de casa) e *.ts.net (o app não passa pela rede Tailscale; de fora é Funnel).
     */
    fun hostDeCasa(host: String): Boolean {
        val h = host.lowercase().trimEnd('.')
        if (h.isNotEmpty() && '.' !in h && ':' !in h) return true
        if (h.endsWith(".local") || h.endsWith(".lan") || h.endsWith(".home.arpa")) return true
        val n = h.split('.').takeIf { it.size == 4 }?.map { it.toIntOrNull() ?: return false } ?: return false
        if (n.any { it !in 0..255 }) return false
        return n[0] == 10 || n[0] == 127 || (n[0] == 172 && n[1] in 16..31) || (n[0] == 192 && n[1] == 168)
    }

    /**
     * Campainha: devolve true só na passagem para "on" e já guarda o estado novo NA MESMA conta (auditoria de 10/11/2026).
     * Antes o estado só era guardado depois de buscar a foto da câmera (até 15 s), e as voltas seguintes, a cada 3 s,
     * ainda viam "não tocou" e repetiam o aviso com som.
     */
    class Campainha {
        private var antes = ""
        @Synchronized fun tocou(estado: String): Boolean {
            val tocou = estado == "on" && antes != "on"
            antes = estado
            return tocou
        }
    }

    /** Token "long-lived" do Home Assistant: um JWT (três partes base64url separadas por ponto). */
    fun tokenValido(t: String): Boolean = Regex("^[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+$").matches(t.trim()) && t.length in 40..1000

    fun idValido(id: String): Boolean = Regex("^[a-z_]+\\.[a-z0-9_]{1,100}$").matches(id)

    /** Resposta de GET /api/states -> as entidades controláveis, por nome. Pura. */
    fun lerEstados(texto: String): List<Entidade> {
        val a = try { JSONArray(texto) } catch (e: Exception) { return emptyList() }
        return (0 until a.length()).mapNotNull { i ->
            val o = a.optJSONObject(i) ?: return@mapNotNull null
            val id = o.optString("entity_id")
            if (!idValido(id) || id.substringBefore('.') !in DOMINIOS) return@mapNotNull null
            val nome = o.optJSONObject("attributes")?.optString("friendly_name")?.takeIf { it.isNotBlank() } ?: id
            Entidade(id, nome.take(60), o.optString("state"))
        }.sortedBy { it.nome.lowercase() }
    }

    /** (domínio do serviço, serviço) para a ação pedida, ou null se a combinação não é permitida. Pura. */
    fun servico(id: String, acao: String): Pair<String, String>? {
        if (!idValido(id)) return null
        val d = id.substringBefore('.')
        if (d !in DOMINIOS) return null
        return when {
            d == "scene" || d == "script" -> if (acao == "ligar") d to "turn_on" else null
            d == "cover" -> when (acao) { "ligar" -> d to "open_cover"; "desligar" -> d to "close_cover"; "alternar" -> d to "toggle"; else -> null }
            else -> when (acao) { "ligar" -> d to "turn_on"; "desligar" -> d to "turn_off"; "alternar" -> d to "toggle"; else -> null }
        }
    }

    /**
     * Atributos de mídia do sensor da TV, a partir do [TocandoAgoraTv.json]. Pura.
     *  - midia: "tocando", "pausado", "nada" (nenhuma sessão) ou "desconhecido" (Tocando agora desligado, ou sem acesso);
     *  - midia_titulo e midia_app: só quando há sessão. O título vai só para o Home Assistant da casa (rede local).
     * O estado do sensor continua "ligada"/"dormindo" para não quebrar automação que já exista.
     */
    fun atributosMidia(m: JSONObject, destino: JSONObject): JSONObject {
        val situacao = when {
            !m.optBoolean("ligado") -> "desconhecido"
            m.optBoolean("nada") -> if (m.optBoolean("acesso", true)) "nada" else "desconhecido"
            m.optBoolean("tocando") -> "tocando"
            else -> "pausado"
        }
        destino.put("midia", situacao)
        if (situacao == "tocando" || situacao == "pausado") {
            destino.put("midia_titulo", m.optString("titulo").take(120)).put("midia_app", m.optString("app").take(60))
        }
        return destino
    }

    /** Nome da entidade que a TV publica: sensor.tv_<nome do aparelho em minúsculas, sem acento>. Pura. */
    fun idDaTv(nomeAparelho: String): String {
        val base = java.text.Normalizer.normalize(nomeAparelho.lowercase(), java.text.Normalizer.Form.NFD)
            .replace(Regex("\\p{M}"), "").replace(Regex("[^a-z0-9]+"), "_").trim('_').take(40)
        return "sensor.tv_" + base.ifEmpty { "sala" }
    }

    // ---------------------------------------------------------------- configuração (na TV)

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    fun endereco(ctx: Context): String? = prefs(ctx).getString("endereco", null)
    fun configurado(ctx: Context) = endereco(ctx) != null && prefs(ctx).contains("token_cifrado")
    fun texto(ctx: Context, chave: String): String = prefs(ctx).getString(chave, "") ?: ""

    /** Grava endereço e token. Devolve null se gravou, ou o motivo. */
    fun configurar(ctx: Context, endereco: String, token: String): String? {
        val e = enderecoValido(endereco) ?: return "endereço inválido (ex.: http://192.168.1.10:8123; fora da rede de casa use https)"
        if (!tokenValido(token)) return "token inválido: crie um em Perfil > Segurança > Tokens de acesso de longa duração"
        return try {
            val c = Cipher.getInstance("AES/GCM/NoPadding"); c.init(Cipher.ENCRYPT_MODE, chave())
            prefs(ctx).edit().putString("endereco", e)
                .putString("token_cifrado", Base64.encodeToString(c.doFinal(token.trim().toByteArray()), Base64.NO_WRAP))
                .putString("token_iv", Base64.encodeToString(c.iv, Base64.NO_WRAP)).commit()
            null
        } catch (ex: Exception) { "o Keystore da TV falhou: ${ex.javaClass.simpleName}" }
    }

    /** Campainha e câmera (ids de entidade; vazio desliga o aviso). */
    fun configurarCampainha(ctx: Context, sensor: String, camera: String): String? {
        if (sensor.isNotEmpty() && !idValido(sensor)) return "sensor inválido (ex.: binary_sensor.campainha)"
        if (camera.isNotEmpty() && !(idValido(camera) && camera.startsWith("camera."))) return "câmera inválida (ex.: camera.porta)"
        prefs(ctx).edit().putString(CHAVE_CAMPAINHA, sensor).putString(CHAVE_CAMERA, camera).apply()
        return null
    }

    fun esquecer(ctx: Context) { prefs(ctx).edit().clear().commit() }

    /** Chaves que o Keystore cifrou: não servem em outra instalação (o backup completo leva o token em claro, no cofre). */
    val CHAVES_DO_KEYSTORE = setOf("token_cifrado", "token_iv")

    /** O token em claro, só para o backup completo (que vai trancado com a senha do dono). */
    internal fun tokenParaBackup(ctx: Context): String? = token(ctx)

    /** Cifra [token] com o Keystore DESTA instalação (restaurar backup). Devolve false se o Keystore falhar. */
    internal fun definirToken(ctx: Context, token: String): Boolean = try {
        val c = Cipher.getInstance("AES/GCM/NoPadding"); c.init(Cipher.ENCRYPT_MODE, chave())
        prefs(ctx).edit()
            .putString("token_cifrado", Base64.encodeToString(c.doFinal(token.trim().toByteArray()), Base64.NO_WRAP))
            .putString("token_iv", Base64.encodeToString(c.iv, Base64.NO_WRAP)).commit()
    } catch (ex: Exception) { Log.w(TAG, "Keystore: ${ex.javaClass.simpleName}"); false }

    private fun token(ctx: Context): String? {
        val p = prefs(ctx)
        val cif = p.getString("token_cifrado", null) ?: return null
        val iv = p.getString("token_iv", null) ?: return null
        return try {
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, chave(), GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)))
            String(c.doFinal(Base64.decode(cif, Base64.NO_WRAP)))
        } catch (e: Exception) { Log.w(TAG, "token ilegível: ${e.javaClass.simpleName}"); null }
    }

    private fun chave(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(APELIDO, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val g = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        g.init(KeyGenParameterSpec.Builder(APELIDO, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256).build())
        return g.generateKey()
    }

    // ---------------------------------------------------------------- pedidos (fora da thread da tela)

    class Erro(msg: String) : Exception(msg)

    private fun pedir(ctx: Context, metodo: String, caminho: String, corpo: String? = null, limite: Int = 2 shl 20): ByteArray {
        val e = endereco(ctx) ?: throw Erro("o Home Assistant não está configurado")
        val t = token(ctx) ?: throw Erro("o token do Home Assistant sumiu: configure de novo")
        val c = (URL(e + caminho).openConnection() as HttpURLConnection).apply {
            requestMethod = metodo; connectTimeout = 5_000; readTimeout = 10_000; instanceFollowRedirects = false
            setRequestProperty("Authorization", "Bearer $t")
            if (corpo != null) { doOutput = true; setRequestProperty("Content-Type", "application/json") }
        }
        try {
            corpo?.let { b -> c.outputStream.use { it.write(b.toByteArray()) } }
            if (c.responseCode == 401) throw Erro("o Home Assistant recusou o token")
            if (c.responseCode !in 200..299) throw Erro("o Home Assistant respondeu ${c.responseCode}")
            return c.inputStream.use { ent ->
                val s = java.io.ByteArrayOutputStream(); val b = ByteArray(16 * 1024)
                while (true) { val n = ent.read(b); if (n < 0) break; s.write(b, 0, n); if (s.size() > limite) throw Erro("resposta grande demais") }
                s.toByteArray()
            }
        } catch (ex: Erro) { throw ex
        } catch (ex: Exception) { throw Erro("não consegui falar com o Home Assistant: ${ex.javaClass.simpleName}")
        } finally { c.disconnect() }
    }

    fun listar(ctx: Context): List<Entidade> = lerEstados(String(pedir(ctx, "GET", "/api/states")))

    /** Liga, desliga ou alterna. Devolve o texto do resultado. */
    fun acionar(ctx: Context, id: String, acao: String): String {
        val (d, s) = servico(id, acao) ?: throw Erro("ação não permitida para $id")
        pedir(ctx, "POST", "/api/services/$d/$s", JSONObject().put("entity_id", id).toString())
        return "$id: $acao"
    }

    /** Foto da câmera (JPEG), pelo proxy do Home Assistant. */
    fun fotoCamera(ctx: Context, camera: String): ByteArray {
        if (!(idValido(camera) && camera.startsWith("camera."))) throw Erro("câmera inválida")
        return pedir(ctx, "GET", "/api/camera_proxy/$camera", limite = RecadoImagem.MAX_BYTES)
    }

    // ---------------------------------------------------------------- vigia: estado da TV e campainha

    private val ui by lazy { Handler(Looper.getMainLooper()) }
    @Volatile private var rodando = false
    private var ultimoPublicado = ""
    private val campainha = Campainha()
    private var ultimoEnvio = 0L
    // Uma volta por vez, numa thread só: com o Home Assistant lento (até 15 s por pedido) as voltas não se empilham.
    private val trabalho = java.util.concurrent.Executors.newSingleThreadExecutor()
    private val ocupado = java.util.concurrent.atomic.AtomicBoolean(false)

    private val tique = object : Runnable {
        override fun run() {
            val c = contexto ?: return
            if (!rodando) return
            // Volta anterior ainda rodando: pula esta (a próxima vem no próximo tique).
            if (ocupado.compareAndSet(false, true)) trabalho.execute {
                try { vigiar(c) } catch (_: Exception) {} finally { ocupado.set(false) }
            }
            // Campainha a cada 3 s só com a tela acesa: apagada, o aviso nem aparece (AvisoTv descarta), basta 30 s.
            val telaAcesa = c.getSystemService(PowerManager::class.java)?.isInteractive != false
            // Com a casa configurada e a tela acesa, 5 s: o sensor da TV avisa "pausou" a tempo de a luz acender. Cada volta
            // só lê as sessões de mídia da própria TV; o POST ao Home Assistant sai só quando algo muda (publicarEstado).
            ui.postDelayed(this, when {
                !telaAcesa -> 30_000L
                texto(c, CHAVE_CAMPAINHA).isNotEmpty() -> 3_000L
                configurado(c) -> 5_000L
                else -> 30_000L
            })
        }
    }
    @Volatile private var contexto: Context? = null

    /** Chamado quando o serviço de acessibilidade conecta (processo vivo o tempo todo). */
    fun iniciar(ctx: Context) {
        contexto = ctx.applicationContext
        if (rodando) return
        rodando = true; ui.post(tique)
    }

    fun parar() { rodando = false; ui.removeCallbacks(tique) }

    private fun vigiar(ctx: Context) {
        if (!configurado(ctx)) return
        try { publicarEstado(ctx) } catch (_: Exception) {}
        val sensor = texto(ctx, CHAVE_CAMPAINHA)
        if (sensor.isEmpty()) return
        val estado = try { JSONObject(String(pedir(ctx, "GET", "/api/states/$sensor"))).optString("state") } catch (e: Exception) { return }
        if (campainha.tocou(estado)) tocouCampainha(ctx)   // o estado já ficou guardado antes de buscar a foto
    }

    private fun tocouCampainha(ctx: Context) {
        val camera = texto(ctx, CHAVE_CAMERA)
        val foto = if (camera.isEmpty()) null else try { fotoCamera(ctx, camera) } catch (e: Exception) { null }
        AvisoTv.mostrar(ctx, "Campainha", "Tem alguém na porta", 30, som = true, guardarSeApagada = false,
            imagem = foto?.let { RecadoImagem.decodificar(it) }, fica = AvisoRegras.Fica.ATENCAO)
    }

    /** Publica sensor.tv_<nome> no Home Assistant quando muda, e de novo a cada 5 min (o HA esquece ao reiniciar). */
    fun publicarEstado(ctx: Context) {
        val ligada = ctx.getSystemService(PowerManager::class.java)?.isInteractive ?: true
        val app = JanelaNaFrente.pacote ?: ""
        val nome = android.provider.Settings.Global.getString(ctx.contentResolver, "device_name") ?: "Sala"
        val atributos = JSONObject().put("friendly_name", "TV $nome").put("app", app).put("icon", "mdi:television")
        // Mídia (11/11/2026): "pausou o filme, acende a luz baixa" e "me avisa quando acabar" viram automações do HA.
        val midia = try { TocandoAgoraTv.json(ctx) } catch (e: Exception) { JSONObject().put("ligado", false) }
        atributosMidia(midia, atributos)
        val corpo = JSONObject().put("state", if (ligada) "ligada" else "dormindo").put("attributes", atributos).toString()
        if (corpo == ultimoPublicado && System.currentTimeMillis() - ultimoEnvio < 5 * 60_000) return
        pedir(ctx, "POST", "/api/states/${idDaTv(nome)}", corpo)
        ultimoPublicado = corpo; ultimoEnvio = System.currentTimeMillis()
    }
}
