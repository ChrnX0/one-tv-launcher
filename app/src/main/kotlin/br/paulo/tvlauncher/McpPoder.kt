package br.paulo.tvlauncher

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.net.ssl.HttpsURLConnection

/**
 * Ferramentas "de poder" do MCP (10/11/2026, dono: "quero q esse negocio do mcp seja poderosíssimo"). Fazem pelo
 * próprio launcher o que antes só dava com o shell ou com o PC: controle remoto, abrir qualquer app ou link, instalar
 * APK por URL (o shell da TV não tem curl/wget), atualizar o launcher, aviso na tela, despertadores, arquivos, sistema
 * e energia.
 *
 * Cada ação passa pelas Confirmações (ConfirmacoesTv), como o resto do MCP: na Sala ("Liberar tudo") roda direto; numa
 * instalação nova pergunta na TV ou vem bloqueada. As validações puras ficam em [McpPoderRegras] (com teste).
 */
object McpPoder {
    private fun ferramenta(nome: String, descricao: String, props: JSONObject = JSONObject(), obrigatorios: List<String> = emptyList(),
                           leitura: Boolean = false, destrutiva: Boolean = false) =
        JSONObject().put("name", nome).put("description", descricao)
            .put("inputSchema", JSONObject().put("type", "object").put("properties", props)
                .apply { if (obrigatorios.isNotEmpty()) put("required", JSONArray(obrigatorios)) })
            .put("annotations", JSONObject().put("readOnlyHint", leitura).apply { if (!leitura) put("destructiveHint", destrutiva) })

    private fun s(desc: String) = JSONObject().put("type", "string").put("description", desc)
    private fun enumS(vararg v: String) = JSONObject().put("type", "string").put("enum", JSONArray(v.toList()))

    val LISTA: List<JSONObject> = listOf(
        ferramenta("controle_remoto", "Aperta teclas do controle na TV, em sequência: ${PainelControle.TECLAS.keys.joinToString(", ")}. " +
            "Para navegar em apps: combine com ver_tela para ver onde está o foco.",
            JSONObject().put("teclas", JSONObject().put("type", "array").put("maxItems", McpPoderRegras.MAX_TECLAS)
                .put("items", JSONObject().put("type", "string").put("enum", JSONArray(PainelControle.TECLAS.keys.toList()))))
                .put("pausa_ms", JSONObject().put("type", "integer").put("minimum", 0).put("maximum", 3000)
                    .put("description", "espera entre teclas (padrão 150)")),
            listOf("teclas")),
        ferramenta("digitar_texto", "Digita texto no campo com foco na TV (busca, login...). Só ASCII: o Android não digita acento por aqui.",
            JSONObject().put("texto", s("até ${McpPoderRegras.MAX_TEXTO} caracteres"))
                .put("enviar", JSONObject().put("type", "boolean").put("description", "aperta Enter no fim")),
            listOf("texto")),
        ferramenta("abrir", "Abre na TV um app instalado (pelo pacote, ver apps_instalados) ou um link: site, vídeo do YouTube " +
            "(https://www.youtube.com/watch?v=...), busca (https://www.youtube.com/results?search_query=...), link da Netflix etc. " +
            "Com pacote E link, o link abre naquele app.",
            JSONObject().put("pacote", s("ex.: com.netflix.ninja")).put("link", s("https://... ou esquema do app"))),
        ferramenta("apps_instalados", "Todos os apps instalados na TV (pacote, nome, versão, sistema, oculto, se abre).", leitura = true),
        ferramenta("instalar_apk", "Baixa um APK por HTTPS e instala em silêncio (Device Owner). Para atualizar o próprio launcher, " +
            "use atualizar_launcher. APK de várias partes (.apks/.xapk) não serve.",
            JSONObject().put("url", s("https://... direto para o .apk"))
                .put("sha256", s("opcional: confere o arquivo antes de instalar")),
            listOf("url")),
        ferramenta("desinstalar_app", "Desinstala um app que o usuário instalou (apps do sistema e o launcher são recusados).",
            JSONObject().put("pacote", s("pacote do app")), listOf("pacote"), destrutiva = true),
        ferramenta("atualizar_launcher", "Procura a versão nova do launcher nos Releases do GitHub. Com instalar=true, instala " +
            "(o launcher reinicia e o MCP fica fora ~1 min).",
            JSONObject().put("instalar", JSONObject().put("type", "boolean"))),
        ferramenta("aviso_na_tv", "Mostra um aviso por cima de qualquer tela da TV (ex.: \"O jantar está pronto!\").",
            JSONObject().put("texto", s("até ${Recado.MAX_CARACTERES} caracteres, até 3 linhas")).put("titulo", s("opcional"))
                .put("segundos", JSONObject().put("type", "integer").put("minimum", 5).put("maximum", 300))
                .put("som", JSONObject().put("type", "boolean").put("description", "dois bipes para chamar atenção")),
            listOf("texto")),
        ferramenta("despertadores", "Lista, cria, liga, desliga ou apaga despertadores da TV. Para criar: hora \"07:30\", " +
            "dias (ex.: [\"seg\",\"qua\"], [\"uteis\"], [\"todos\"]; vazio = uma vez só) e nome.",
            JSONObject().put("acao", enumS("listar", "criar", "ligar", "desligar", "apagar"))
                .put("id", JSONObject().put("type", "integer")).put("hora", s("HH:MM"))
                .put("dias", JSONObject().put("type", "array").put("items", JSONObject().put("type", "string")))
                .put("nome", s("opcional")),
            listOf("acao")),
        ferramenta("arquivos", "Arquivos da memória interna da TV (Download, Movies, Music...; a pasta Android fica de fora). " +
            "listar uma pasta, ler um arquivo de texto (até 256 KB), escrever texto (até 1 MB), criar_pasta ou apagar.",
            JSONObject().put("acao", enumS("listar", "ler", "escrever", "criar_pasta", "apagar"))
                .put("caminho", s("relativo à memória interna, ex.: Download/notas.txt; vazio = raiz"))
                .put("conteudo", s("texto para escrever")),
            listOf("acao")),
        ferramenta("sistema", "Resumo da TV: modelo, Android, versão do launcher, memória, disco, tempo ligada, Device Owner, " +
            "shell próprio, túnel e acesso pela IA.", leitura = true),
        ferramenta("energia", "Acorda ou põe a TV para dormir, ou reinicia a TV inteira (a família perde o que estava vendo).",
            JSONObject().put("acao", enumS("acordar", "dormir", "reiniciar")), listOf("acao"), destrutiva = true),
    )

    fun conhece(nome: String) = LISTA.any { it.optString("name") == nome }

    /**
     * O app que está mesmo na frente: o pacote da janela ativa pela acessibilidade. O JanelaNaFrente.pacote
     * vem do vigia de janelas e, no teste de 10/11/2026, dizia "launcherx" com a NOSSA HOME por cima dele (o vigia vê a
     * HomeActivity do Google aparecer antes de a nossa cobri-la). Sem acessibilidade, cai nele.
     */
    fun appNaFrente(): String? = try {
        AutorizaShizukuService.instancia?.rootInActiveWindow?.packageName?.toString()
    } catch (e: Exception) { null } ?: JanelaNaFrente.pacote

    fun chamar(ctx: Context, nome: String, a: JSONObject): String {
        val app = ctx.applicationContext
        return when (nome) {
            "controle_remoto" -> controle(app, a)
            "digitar_texto" -> digitar(app, a)
            "abrir" -> abrir(app, a)
            "apps_instalados" -> PainelApps.listar(app).toString()
            "instalar_apk" -> instalarApk(app, a)
            "desinstalar_app" -> desinstalar(app, a)
            "atualizar_launcher" -> atualizar(app, a)
            "aviso_na_tv" -> aviso(app, a)
            "despertadores" -> despertadores(app, a)
            "arquivos" -> arquivos(app, a)
            "sistema" -> sistema(app).toString()
            "energia" -> energia(app, a)
            else -> throw McpProtocolo.ErroFerramenta("ferramenta desconhecida")
        }
    }

    // ---------------------------------------------------------------- confirmações

    private fun exigir(app: Context, acao: Confirmacoes.Acao, oQue: String) {
        if (ConfirmacoesTv.permitir(app, acao, oQue, "internet")) return
        throw McpProtocolo.ErroFerramenta(
            if (ConfirmacoesTv.modo(app, acao) == Confirmacoes.Modo.BLOQUEAR)
                "\"${acao.titulo}\" está bloqueado nas Confirmações da TV (Configurações › Sistema › Confirmações)"
            else "a TV negou, ou ninguém respondeu em 60 s")
    }

    private fun falha(motivo: String?, ok: String): String = motivo?.let { throw McpProtocolo.ErroFerramenta(it) } ?: ok

    private fun registrar(app: Context, oQue: String, detalhe: String) = PainelArmazem.registrar(app, "internet", oQue, detalhe.take(300))

    // ---------------------------------------------------------------- controle

    private fun controle(app: Context, a: JSONObject): String {
        val arr = a.optJSONArray("teclas") ?: throw McpProtocolo.ErroFerramenta("falta \"teclas\"")
        if (arr.length() == 0 || arr.length() > McpPoderRegras.MAX_TECLAS) throw McpProtocolo.ErroFerramenta("de 1 a ${McpPoderRegras.MAX_TECLAS} teclas")
        val teclas = (0 until arr.length()).map { arr.optString(it) }
        teclas.firstOrNull { it !in PainelControle.TECLAS }?.let { throw McpProtocolo.ErroFerramenta("tecla desconhecida: ${it.take(20)}") }
        exigir(app, Confirmacoes.Acao.MCP_ACOES, txt(R.string.cf_apertar, teclas.joinToString(" ")))
        val pausa = a.optLong("pausa_ms", 150).coerceIn(0, 3000)
        teclas.forEachIndexed { i, t ->
            PainelControle.tecla(app, t)?.let { throw McpProtocolo.ErroFerramenta("parou na tecla ${i + 1} ($t): $it") }
            if (i < teclas.size - 1 && pausa > 0) Thread.sleep(pausa)
        }
        return "ok: ${teclas.size} tecla(s)"
    }

    private fun digitar(app: Context, a: JSONObject): String {
        val arg = McpPoderRegras.textoParaInput(a.optString("texto"))
            ?: throw McpProtocolo.ErroFerramenta("texto vazio, longo demais ou com acento/emoji (o Android só digita ASCII por aqui)")
        exigir(app, Confirmacoes.Acao.MCP_ACOES, txt(R.string.cf_digitar))
        AutoDesligar.atividade()
        GuardiaoShizuku.comando(app, "input text '$arg'") ?: throw McpProtocolo.ErroFerramenta("o shell do launcher não respondeu")
        if (a.optBoolean("enviar")) PainelControle.tecla(app, "entrar")
        return "ok"
    }

    private fun abrir(app: Context, a: JSONObject): String {
        val pacote = a.optString("pacote").trim().takeIf { it.isNotEmpty() }
        val link = a.optString("link").trim().takeIf { it.isNotEmpty() }
        if (pacote == null && link == null) throw McpProtocolo.ErroFerramenta("passe \"pacote\" ou \"link\"")
        if (pacote != null && !ChecagensPainel.pacoteValido(pacote)) throw McpProtocolo.ErroFerramenta("pacote inválido")
        if (link != null && !McpPoderRegras.linkValido(link)) throw McpProtocolo.ErroFerramenta("link inválido (file:, content:, intent: e javascript: não abrem)")
        exigir(app, Confirmacoes.Acao.MCP_ACOES, txt(R.string.cf_abrir, link ?: pacote ?: ""))
        AutoDesligar.atividade()
        if (link == null) return falha(PainelControle.abrir(app, pacote!!), "aberto: $pacote")
        val i = Intent(Intent.ACTION_VIEW, Uri.parse(link)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (pacote != null) i.setPackage(pacote)
        return try { app.startActivity(i); "aberto: ${link.take(100)}" }
        catch (e: android.content.ActivityNotFoundException) { throw McpProtocolo.ErroFerramenta("nenhum app da TV abre este link" + (pacote?.let { " ($it)" } ?: "")) }
        catch (e: Exception) { throw McpProtocolo.ErroFerramenta("o Android não deixou abrir: ${e.javaClass.simpleName}") }
    }

    // ---------------------------------------------------------------- apps

    private fun instalarApk(app: Context, a: JSONObject): String {
        val url = a.optString("url").trim()
        if (!McpPoderRegras.urlApkValida(url)) throw McpProtocolo.ErroFerramenta("URL inválida: só https://")
        val sha = a.optString("sha256").trim().takeIf { it.isNotEmpty() }
        if (sha != null && !McpPoderRegras.sha256Valido(sha)) throw McpProtocolo.ErroFerramenta("sha256 inválido (64 caracteres hexadecimais)")
        exigir(app, Confirmacoes.Acao.INSTALAR, txt(R.string.cf_instalar_de, url.take(150)))
        val tmp = File.createTempFile("mcp-apk", ".apk", app.cacheDir)
        try {
            val calculado = baixar(url, tmp)
            if (sha != null && !sha.equals(calculado, ignoreCase = true)) throw McpProtocolo.ErroFerramenta("o sha256 não confere (baixado: $calculado)")
            val r = tmp.inputStream().use { PainelApps.instalar(app, it, tmp.length()) }
            registrar(app, "instalar APK (MCP)", "${r.pacote ?: "?"} ${r.versao ?: ""}: ${r.motivo ?: "ok"}")
            r.motivo?.let { throw McpProtocolo.ErroFerramenta(it) }
            return JSONObject().put("instalado", r.pacote).put("nome", r.nome).put("versao", r.versao).put("sha256", calculado).toString()
        } finally { tmp.delete() }
    }

    /** Baixa [url] para [destino] (segue redirecionamento só para https) e devolve o sha256. TLS com a verificação normal. */
    internal fun baixar(url: String, destino: File): String {
        var atual = URL(url)
        repeat(5) {
            val c = atual.openConnection() as? HttpsURLConnection ?: throw McpProtocolo.ErroFerramenta("só https://")
            c.instanceFollowRedirects = false
            c.connectTimeout = 15_000; c.readTimeout = 30_000
            c.setRequestProperty("User-Agent", "onetv-launcher")
            try {
                val codigo = c.responseCode
                if (codigo in 300..399) {
                    val prox = c.getHeaderField("Location") ?: throw McpProtocolo.ErroFerramenta("redirecionamento sem destino")
                    atual = URL(atual, prox)
                    if (atual.protocol != "https") throw McpProtocolo.ErroFerramenta("o link redireciona para fora do https")
                    return@repeat
                }
                if (codigo != 200) throw McpProtocolo.ErroFerramenta("o servidor respondeu $codigo")
                val tamanho = c.contentLengthLong
                if (tamanho > PainelApps.LIMITE_APK) throw McpProtocolo.ErroFerramenta("arquivo grande demais (máx. 1 GB)")
                if (tamanho > 0 && app0(destino).usableSpace < tamanho * 2 + (200L shl 20)) throw McpProtocolo.ErroFerramenta("falta espaço na TV")
                val md = MessageDigest.getInstance("SHA-256")
                var total = 0L
                c.inputStream.use { ent -> destino.outputStream().use { sai ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = ent.read(buf); if (n < 0) break
                        total += n
                        if (total > PainelApps.LIMITE_APK) throw McpProtocolo.ErroFerramenta("arquivo grande demais (máx. 1 GB)")
                        md.update(buf, 0, n); sai.write(buf, 0, n)
                    }
                } }
                if (tamanho > 0 && total != tamanho) throw McpProtocolo.ErroFerramenta("o download veio incompleto")
                return md.digest().joinToString("") { "%02x".format(it) }
            } finally { c.disconnect() }
        }
        throw McpProtocolo.ErroFerramenta("redirecionamentos demais")
    }

    private fun app0(f: File): File = f.parentFile ?: f

    private fun desinstalar(app: Context, a: JSONObject): String {
        val p = a.optString("pacote").trim()
        if (!ChecagensPainel.pacoteValido(p)) throw McpProtocolo.ErroFerramenta("pacote inválido")
        if (p == app.packageName) throw McpProtocolo.ErroFerramenta("o launcher não se desinstala (perderia o Device Owner)")
        exigir(app, Confirmacoes.Acao.REMOVER, txt(R.string.cf_desinstalar, p))
        val motivo = PainelApps.desinstalar(app, p)
        registrar(app, "desinstalar (MCP)", "$p: ${motivo ?: "ok"}")
        return falha(motivo, "desinstalado: $p")
    }

    private fun atualizar(app: Context, a: JSONObject): String {
        val instalar = a.optBoolean("instalar")
        if (instalar) {
            exigir(app, Confirmacoes.Acao.MCP_ACOES, txt(R.string.cf_atualizar))
            registrar(app, "atualizar launcher (MCP)", "pedido")
            Atualizador.verificar(app, instalar = true)
            return "procurando e, se houver versão nova, instalando. O launcher reinicia e o MCP volta em ~1 min; depois confira com sistema."
        }
        val pronto = CountDownLatch(1)
        // 25 s, não mais: o cliente MCP desiste em 60 s, e a 1ª consulta ao GitHub já passou disso no teste (10/11/2026).
        // A verificação continua sozinha e grava o resultado; a próxima chamada já o encontra.
        var texto = "ainda procurando (o GitHub demorou): chame de novo em 1 min. Última: ${Atualizador.estado(app)}"
        Atualizador.verificar(app, instalar = false) { texto = it; pronto.countDown() }
        pronto.await(25, TimeUnit.SECONDS)
        return JSONObject().put("resultado", texto).put("versao_instalada", BuildConfig.VERSION_CODE)
            .put("pendente", Atualizador.pendente(app) ?: JSONObject.NULL).toString()
    }

    // ---------------------------------------------------------------- aviso e despertadores

    private fun aviso(app: Context, a: JSONObject): String {
        val texto = Recado.limpar(a.optString("texto")) ?: throw McpProtocolo.ErroFerramenta("texto vazio ou com mais de ${Recado.MAX_CARACTERES} caracteres")
        val titulo = Recado.limpar(a.optString("titulo"))?.take(40) ?: "Recado"
        exigir(app, Confirmacoes.Acao.MCP_ACOES, txt(R.string.cf_aviso, texto.take(60)))
        val r = AvisoTv.mostrar(app, titulo, texto, a.optInt("segundos", 30).coerceIn(5, 300), a.optBoolean("som"), guardarSeApagada = true, fica = AvisoRegras.Fica.ATENCAO)
        registrar(app, "aviso (MCP)", texto)
        return when (r) {
            AvisoTv.Resultado.MOSTRADO -> "aviso na tela"
            AvisoTv.Resultado.GUARDADO -> "a TV está dormindo: o aviso aparece quando a tela acender"
            AvisoTv.Resultado.DESCARTADO -> "a TV está dormindo e o aviso foi descartado"
            AvisoTv.Resultado.SEM_ACESSIBILIDADE -> throw McpProtocolo.ErroFerramenta("o serviço de acessibilidade do launcher está desligado")
        }
    }

    private fun alarmeJson(app: Context, x: Despertador.Alarme) = JSONObject().put("id", x.id).put("hora", Despertador.horaTexto(x))
        .put("dias", Despertador.diasTexto(app, x.dias)).put("nome", x.nome).put("ligado", x.ativo)

    private fun despertadores(app: Context, a: JSONObject): String {
        val acao = a.optString("acao")
        if (acao == "listar") {
            val prox = Despertador.proximo(app)
            return JSONObject().put("alarmes", JSONArray().apply { Despertador.listar(app).forEach { put(alarmeJson(app, it)) } })
                .put("proximo", prox?.let { java.text.SimpleDateFormat("EEE dd/MM HH:mm", java.util.Locale("pt", "BR")).format(java.util.Date(it.second)) } ?: JSONObject.NULL)
                .toString()
        }
        if (acao == "criar") {
            val (h, m) = McpPoderRegras.hora(a.optString("hora")) ?: throw McpProtocolo.ErroFerramenta("hora inválida: use HH:MM")
            val arr = a.optJSONArray("dias") ?: JSONArray()
            val dias = McpPoderRegras.dias((0 until arr.length()).map { arr.optString(it) })
                ?: throw McpProtocolo.ErroFerramenta("dia desconhecido: use dom, seg, ter, qua, qui, sex, sab, uteis, fim de semana ou todos")
            exigir(app, Confirmacoes.Acao.MCP_ACOES, txt(R.string.cf_criar_despertador, "%02d:%02d".format(h, m)))
            val novo = Despertador.salvar(app, Despertador.Alarme(0, h, m, dias, a.optString("nome").take(40), true))
            registrar(app, "despertador (MCP)", "criado ${Despertador.horaTexto(novo)}")
            return alarmeJson(app, novo).toString()
        }
        val id = a.optInt("id", -1)
        val alvo = Despertador.listar(app).firstOrNull { it.id == id } ?: throw McpProtocolo.ErroFerramenta("despertador $id não existe (veja com acao=listar)")
        exigir(app, Confirmacoes.Acao.MCP_ACOES, txt(R.string.cf_despertador, Despertador.horaTexto(alvo), acao))
        when (acao) {
            "ligar", "desligar" -> Despertador.salvar(app, alvo.copy(ativo = acao == "ligar"))
            "apagar" -> Despertador.apagar(app, id)
            else -> throw McpProtocolo.ErroFerramenta("acao desconhecida")
        }
        registrar(app, "despertador (MCP)", "$acao ${Despertador.horaTexto(alvo)}")
        return "ok: $acao ${Despertador.horaTexto(alvo)}"
    }

    // ---------------------------------------------------------------- arquivos

    private fun arquivos(app: Context, a: JSONObject): String {
        if (!PainelArquivos.garantirAcesso(app)) throw McpProtocolo.ErroFerramenta("o launcher não tem acesso aos arquivos (o shell próprio precisa estar pareado)")
        val caminho = a.optString("caminho").trim()
        try {
            return when (a.optString("acao")) {
                "listar" -> PainelArquivos.listar(caminho).toString()
                "ler" -> {
                    val f = PainelArquivos.paraBaixar(caminho)
                    if (f.length() > McpPoderRegras.MAX_LER_BYTES) throw McpProtocolo.ErroFerramenta("arquivo com ${f.length()} bytes: o limite para ler é ${McpPoderRegras.MAX_LER_BYTES}")
                    val b = f.readBytes()
                    if (!McpPoderRegras.pareceTexto(b)) throw McpProtocolo.ErroFerramenta("não é um arquivo de texto")
                    String(b, Charsets.UTF_8)
                }
                "escrever" -> {
                    val bytes = a.optString("conteudo").toByteArray(Charsets.UTF_8)
                    if (bytes.size > McpPoderRegras.MAX_ESCREVER_BYTES) throw McpProtocolo.ErroFerramenta("conteúdo maior que 1 MB")
                    val pasta = caminho.substringBeforeLast('/', "")
                    val nome = caminho.substringAfterLast('/')
                    exigir(app, Confirmacoes.Acao.MCP_SHELL, txt(R.string.cf_gravar, caminho))
                    val f = PainelArquivos.receber(app, pasta, nome, bytes.size.toLong(), substituir = true, entrada = bytes.inputStream())
                    registrar(app, "arquivo (MCP)", "gravou $caminho")
                    "gravado: $caminho (${f.length()} bytes)"
                }
                "criar_pasta" -> {
                    exigir(app, Confirmacoes.Acao.MCP_SHELL, txt(R.string.cf_pasta, caminho))
                    PainelArquivos.criarPasta(caminho.substringBeforeLast('/', ""), caminho.substringAfterLast('/'))
                    "pasta criada: $caminho"
                }
                "apagar" -> {
                    exigir(app, Confirmacoes.Acao.MCP_SHELL, txt(R.string.cf_apagar, caminho))
                    val n = PainelArquivos.apagar(app, caminho)
                    registrar(app, "arquivo (MCP)", "apagou $caminho")
                    "apagado: $caminho ($n item(ns))"
                }
                else -> throw McpProtocolo.ErroFerramenta("acao desconhecida")
            }
        } catch (e: PainelArquivos.Erro) { throw McpProtocolo.ErroFerramenta(e.message ?: "erro") }
    }

    // ---------------------------------------------------------------- sistema e energia

    private fun sistema(app: Context): JSONObject {
        val mem = ActivityManager.MemoryInfo().also { app.getSystemService(ActivityManager::class.java)?.getMemoryInfo(it) }
        val raiz = PainelArquivos.raiz()
        fun mb(b: Long) = b / (1024 * 1024)
        return JSONObject()
            .put("aparelho", "${Build.MANUFACTURER} ${Build.MODEL}")
            .put("nome", android.provider.Settings.Global.getString(app.contentResolver, "device_name") ?: JSONObject.NULL)
            .put("android", "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            .put("launcher", "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            .put("memoria_mb", JSONObject().put("total", mb(mem.totalMem)).put("livre", mb(mem.availMem)).put("pouca", mem.lowMemory))
            .put("disco_mb", JSONObject().put("total", mb(raiz.totalSpace)).put("livre", mb(raiz.usableSpace)))
            .put("ligada_ha_min", SystemClock.elapsedRealtime() / 60_000)
            .put("device_owner", PrivilegioDO.somosDonos(app))
            .put("shell_ia_ligado", McpServidor.shellLigado(app))
            .put("tunel", JSONObject().put("rodando", TunelTailscale.rodando()).put("endereco", TunelTailscale.nome(app) ?: JSONObject.NULL))
            .put("atualizacao_pendente", Atualizador.pendente(app) ?: JSONObject.NULL)
            .put("na_tela", PainelApps.naTela(app))
    }

    private fun energia(app: Context, a: JSONObject): String = when (val acao = a.optString("acao")) {
        "acordar", "dormir" -> {
            exigir(app, Confirmacoes.Acao.MCP_ACOES, txt(if (acao == "acordar") R.string.cf_acordar else R.string.cf_dormir))
            falha(PainelControle.tecla(app, if (acao == "acordar") "ligar" else "apagar"), "ok: $acao")
        }
        "reiniciar" -> {
            exigir(app, Confirmacoes.Acao.REMOVER, txt(R.string.cf_reiniciar))
            registrar(app, "reiniciar TV (MCP)", "pedido")
            if (!PrivilegioDO.somosDonos(app)) throw McpProtocolo.ErroFerramenta("o launcher não é mais o dono do aparelho (Device Owner)")
            // Reinicia 2 s DEPOIS de responder: no teste de 10/11/2026 o túnel caía antes da resposta e o cliente via
            // "Bad Gateway" com a TV reiniciando normalmente.
            Fundo.rodar { Thread.sleep(2000); PainelControle.reiniciarTv(app)?.let { android.util.Log.w("TvLauncher.Mcp", "reiniciar: $it") } }
            "reiniciando em 2 s: a TV e o MCP voltam em ~2 min"
        }
        else -> throw McpProtocolo.ErroFerramenta("acao desconhecida")
    }
}
