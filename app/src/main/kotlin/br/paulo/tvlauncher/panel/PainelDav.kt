package br.paulo.tvlauncher.panel

import android.content.Context
import android.util.Log
import br.paulo.tvlauncher.player.avisar
import br.paulo.tvlauncher.player.item
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.util.UUID

/**
 * A TV como UNIDADE DE REDE (14/11/2026, pedido do dono: "aparecer no explorador de arquivos do Windows como uma
 * unidade"). Um servidor WebDAV dentro do painel web, na porta https de casa (8444) e com o mesmo certificado:
 *   Windows: Este Computador › Mapear unidade de rede › https://<tv>:8444/dav/
 *   Mac: Finder › Ir › Conectar ao Servidor › https://<tv>:8444/dav/
 *   Linux: davs://<tv>:8444/dav/ no gerenciador de arquivos
 *   Android: apps de arquivos com WebDAV (ex.: Solid Explorer, CX, Material Files)
 * Usuário: qualquer um ("tv"). Senha: o PIN do painel, com o MESMO bloqueio crescente (PainelWeb.conferirSenhaDav).
 *
 * Quais pastas: as mesmas da aba Arquivos do painel (a memória interna, sem a pasta Android), pelas mesmas barreiras
 * de caminho (PainelArquivos.resolver: sem "..", links não saem da raiz). A lógica pura (caminhos, XML, chunked) está
 * em DavLogica.kt, com testes; o servidor inteiro roda também no teste JVM (DavServidorTest), numa pasta temporária.
 *
 * Só em https (a senha vai no cabeçalho, em Base64, que não é cifra) e só com a chave "Unidade de rede" ligada
 * (desligada por padrão; Configurações › Acesso remoto, ou a aba Configurações do painel).
 */
object PainelDav {
    private const val LIMITE_XML = 64 * 1024   // PROPFIND, PROPPATCH e LOCK mandam XML pequeno
    /** PROPPATCH e LOCK: as regex do DavLogica são quadráticas no pior caso (64 KB de "<a><a>..." = 6 s no PC). */
    private const val LIMITE_XML_REGEX = 8 * 1024
    /** Prazo total do corpo XML chunked (o Content-Length já tem o dele no PainelHttp). */
    private const val PRAZO_XML_MS = 30_000L
    private const val LOCK_SEGUNDOS = 3600

    /**
     * A resposta nunca roda nada no endereço do painel: "sandbox" põe o que for aberto no navegador numa origem à parte,
     * sem cookie nem script (um .html enviado para a TV não consegue mexer no painel).
     */
    private const val CSP = "sandbox; default-src 'none'; img-src 'self'; media-src 'self'; style-src 'unsafe-inline'; frame-ancestors 'none'"
    private val CABECALHOS = mapOf("Content-Security-Policy" to CSP, "DAV" to "1, 2", "MS-Author-Via" to "DAV")

    /** A chave está ligada (e o painel no ar, que é de onde vem a porta e o PIN)? */
    fun ligado(ctx: Context): Boolean = PainelArmazem.unidadeRede(ctx) && PainelArmazem.podeLigar(ctx)

    /** Este pedido é nosso? O caminho /dav, ou OPTIONS/PROPFIND na raiz (o Windows sonda a raiz do servidor antes). */
    fun meu(req: Requisicao): Boolean = DavLogica.ehDav(req.caminho) ||
        (req.caminho == "/" && (req.metodo == "OPTIONS" || req.metodo == "PROPFIND"))

    /**
     * O que o servidor precisa do Android, separado para o teste JVM (DavServidorTest) rodar o servidor de verdade
     * numa pasta temporária: acesso à memória, o diário da TV e o aviso à galeria.
     */
    interface Ambiente {
        fun acesso(): Boolean
        fun registrar(ip: String, acao: String)
        fun avisar(f: File)
        /** O atalho "Abrir o painel da TV.url" (PanelShortcut), só leitura, na raiz; null = sem atalho. */
        fun atalho(): File? = null
    }

    /** O ambiente de verdade, na TV. */
    fun ambiente(app: Context): Ambiente = object : Ambiente {
        override fun acesso() = PainelArquivos.garantirAcesso(app)
        override fun registrar(ip: String, acao: String) = PainelArmazem.registrar(app, ip, acao, "ok")
        override fun avisar(f: File) = PainelArquivos.avisarGaleria(app, f)
        override fun atalho(): File? = PainelWeb.atalhoPainel(app)
    }

    /** Recusa da senha: código HTTP e texto. */
    class Recusa(val codigo: Int, val texto: String, val extras: Map<String, String> = emptyMap())

    /**
     * Atende um pedido do WebDAV inteiro e escreve a resposta em [saida]. [entrada] é a conexão (o corpo ainda está nela).
     * [hostOk]: o Host é um nome nosso (mesma regra do painel, barra DNS rebinding). [conferir] confere o cabeçalho
     * Authorization e devolve null (ok) ou a recusa.
     */
    fun atender(amb: Ambiente, req: Requisicao, entrada: InputStream, saida: OutputStream, ip: String,
                hostOk: Boolean, conferir: (String?) -> Recusa?) {
        if (!hostOk) return texto(saida, req, 421, "nome desconhecido")
        // Um site de fora aberto no navegador não fala com a unidade: PUT/DELETE/PROPFIND de outra origem já exigem
        // CORS (que não damos); um Origin estranho aqui é recusado de qualquer jeito.
        val origem = req.cabecalho("origin")
        if (origem != null && !ChecagensPainel.origemPermitida(origem, req.cabecalho("host"))) return texto(saida, req, 403, "pedido de fora")
        // OPTIONS sem senha: o Windows e o Finder perguntam "você fala WebDAV?" antes de mandar a senha.
        if (req.metodo == "OPTIONS") return PainelHttp.escrever(saida, 200, null, ByteArray(0), CABECALHOS + ("Allow" to DavLogica.METODOS))
        conferir(req.cabecalho("authorization"))?.let { r ->
            if (!req.chunked) req.descartarCorpo(LIMITE_XML)   // corpo pequeno: jogado fora, para o cliente ler o 401
            val extras = if (r.codigo == 401) r.extras + ("WWW-Authenticate" to "Basic realm=\"One TV\", charset=\"UTF-8\"") else r.extras
            return texto(saida, req, r.codigo, r.texto, extras)
        }
        if (!amb.acesso()) return texto(saida, req, 503, "o launcher ainda não tem acesso à memória interna")
        if (req.caminho == "/") return raizDoServidor(req, saida)
        val rel = DavLogica.relativo(req.caminho) ?: return texto(saida, req, 400, "caminho inválido")
        try {
            when (req.metodo) {
                "PROPFIND" -> propfind(amb, req, entrada, saida, rel)
                "GET", "HEAD" -> baixar(amb, req, saida, rel)
                "PUT" -> enviar(amb, req, entrada, saida, rel, ip)
                "DELETE" -> apagar(amb, req, saida, rel, ip)
                "MKCOL" -> criarPasta(req, entrada, saida, rel)
                "MOVE" -> mover(amb, req, saida, rel, ip)
                "COPY" -> copiar(amb, req, saida, rel, ip)
                "LOCK" -> travar(req, entrada, saida, rel)
                "UNLOCK" -> PainelHttp.escrever(saida, 204, null, ByteArray(0), CABECALHOS)
                "PROPPATCH" -> proppatch(req, entrada, saida, rel)
                else -> texto(saida, req, 405, "método não suportado", mapOf("Allow" to DavLogica.METODOS))
            }
        } catch (e: PainelArquivos.Erro) {
            texto(saida, req, e.codigo, e.message ?: "erro")
        }
    }

    // ---------------------------------------------------------------- respostas

    private fun texto(saida: OutputStream, req: Requisicao, codigo: Int, msg: String, extras: Map<String, String> = emptyMap()) {
        val corpo = if (req.metodo == "HEAD") ByteArray(0) else (msg + "\n").toByteArray()
        PainelHttp.escrever(saida, codigo, "text/plain; charset=utf-8", corpo, CABECALHOS + extras)
    }

    private fun xml(saida: OutputStream, codigo: Int, xml: String, extras: Map<String, String> = emptyMap()) =
        PainelHttp.escrever(saida, codigo, "application/xml; charset=utf-8", xml.toByteArray(Charsets.UTF_8), CABECALHOS + extras)

    /** O XML pequeno do pedido (PROPFIND, LOCK, PROPPATCH), com Content-Length ou chunked. */
    private fun corpoXml(req: Requisicao, entrada: InputStream, limite: Int = LIMITE_XML): String {
        val b = if (req.chunked) {
            val r = DavLogica.LeitorChunked(entrada)
            val buf = java.io.ByteArrayOutputStream()
            val pedaco = ByteArray(8192)
            val ate = System.currentTimeMillis() + PRAZO_XML_MS
            while (true) {
                val n = r.read(pedaco)
                if (n < 0) break
                buf.write(pedaco, 0, n)
                if (buf.size() > limite) throw ErroHttp(413, "corpo grande demais")
                if (System.currentTimeMillis() > ate) throw ErroHttp(408, "corpo demorou demais")
            }
            buf.toByteArray()
        } else req.lerCorpo(limite)
        return String(b, Charsets.UTF_8)
    }

    // ---------------------------------------------------------------- listar

    private fun item(f: File, rel: String) = DavLogica.Item(rel, f.isDirectory, if (f.isFile) f.length() else 0, f.lastModified())

    private fun filho(rel: String, nome: String) = if (rel.isEmpty()) nome else "$rel/$nome"

    /** O que aparece dentro de [pasta]: sem a pasta Android na raiz, sem nomes estranhos e sem envios pela metade. */
    private fun filhos(pasta: File, rel: String): List<File> =
        (pasta.listFiles() ?: emptyArray()).filter {
            // link simbólico fora: a listagem mostrava tamanho/data/tipo do alvo (que pode estar fora da raiz)
            !Files.isSymbolicLink(it.toPath()) &&
            !(rel.isEmpty() && it.name.lowercase() in ChecagensPainel.PASTAS_FECHADAS) &&
                ChecagensPainel.nomeArquivoValido(it.name) && !DavLogica.escondido(it.name)
        }.sortedWith(compareBy<File>({ !it.isDirectory }, { it.name.lowercase() }))

    /**
     * O atalho do painel se [rel] é ele (raiz, nome exato) e não há arquivo de verdade com o mesmo nome. Só GET, HEAD e
     * PROPFIND o enxergam: apagar, mover ou gravar por cima caem no arquivo de verdade (que não existe: 404).
     */
    private fun atalhoEm(amb: Ambiente, rel: String): File? {
        if (rel.isEmpty() || '/' in rel) return null
        val f = try { amb.atalho() } catch (_: Exception) { null } ?: return null
        if (!PanelShortcut.isShortcut(rel, f.name) || File(PainelArquivos.raiz(), rel).exists()) return null
        return f
    }

    private fun propfind(amb: Ambiente, req: Requisicao, entrada: InputStream, saida: OutputStream, rel: String) {
        corpoXml(req, entrada)   // a pergunta (quais propriedades) é ignorada: respondemos sempre o mesmo conjunto
        val f = atalhoEm(amb, rel) ?: PainelArquivos.resolver(rel)
        val itens = ArrayList<DavLogica.Item>()
        itens.add(item(f, rel))
        if (f.isDirectory && DavLogica.profundidade(req.cabecalho("depth")) == 1) {
            val lista = filhos(f, rel).take(DavLogica.MAX_ITENS)
            for (x in lista) itens.add(item(x, filho(rel, x.name)))
            // o atalho do painel, no fim da raiz (PanelShortcut)
            if (rel.isEmpty()) (try { amb.atalho() } catch (_: Exception) { null })?.let { a -> if (atalhoEm(amb, a.name) != null) itens.add(item(a, a.name)) }
        }
        val raiz = PainelArquivos.raiz()
        xml(saida, 207, DavLogica.multistatus(itens, PainelArquivos.livre(), raiz.totalSpace - raiz.usableSpace))
    }

    /** PROPFIND na raiz do servidor ("/"): uma pasta com a "dav" dentro (o Windows confere a raiz ao mapear). */
    private fun raizDoServidor(req: Requisicao, saida: OutputStream) {
        if (req.metodo != "PROPFIND") return texto(saida, req, 405, "método não suportado")
        if (!req.chunked) req.descartarCorpo(LIMITE_XML)
        xml(saida, 207, DavLogica.multistatusRaiz(DavLogica.profundidade(req.cabecalho("depth")), System.currentTimeMillis()))
    }

    // ---------------------------------------------------------------- baixar

    private fun baixar(amb: Ambiente, req: Requisicao, saida: OutputStream, rel: String) {
        val f = atalhoEm(amb, rel) ?: PainelArquivos.resolver(rel)
        if (f.isDirectory) {
            val html = DavLogica.paginaPasta(rel, filhos(f, rel).take(DavLogica.MAX_ITENS).map { item(it, filho(rel, it.name)) })
            val b = html.toByteArray(Charsets.UTF_8)
            PainelHttp.escreverCabecalho(saida, 200, "text/html; charset=utf-8", b.size.toLong(), CABECALHOS)
            if (req.metodo != "HEAD") saida.write(b)
            saida.flush(); return
        }
        if (!f.canRead()) return texto(saida, req, 403, "o Android não deixou ler este arquivo")
        // Só tipos da lista fechada viram "abrir no navegador"; o resto (inclusive .html e .svg) é sempre download.
        val tipo = PainelHttp.tipoPrevia(f.name) ?: "application/octet-stream"
        FileInputStream(f).use { fluxo ->
            val total = f.length()
            val faixa = PainelHttp.faixa(req.cabecalho("range"), total)
            if (faixa != null && faixa.first < 0)
                return texto(saida, req, 416, "intervalo fora do arquivo", mapOf("Content-Range" to "bytes */$total"))
            val tamanho = if (faixa == null) total else faixa.second - faixa.first + 1
            val extras = HashMap(CABECALHOS).apply {
                put("Accept-Ranges", "bytes")
                put("Last-Modified", DavLogica.dataHttp(f.lastModified()))
                put("ETag", DavLogica.etag(item(f, rel)))
                if (tipo == "application/octet-stream") put("Content-Disposition", PainelHttp.nomeParaBaixar(f.name))
                if (faixa != null) put("Content-Range", "bytes ${faixa.first}-${faixa.second}/$total")
            }
            PainelHttp.escreverCabecalho(saida, if (faixa == null) 200 else 206, tipo, tamanho, extras)
            if (req.metodo == "HEAD") { saida.flush(); return }
            if (faixa != null) { var pular = faixa.first; while (pular > 0) { val n = fluxo.skip(pular); if (n <= 0) break; pular -= n } }
            val buf = ByteArray(64 * 1024)
            var faltam = tamanho
            while (faltam > 0) {
                val n = fluxo.read(buf, 0, minOf(buf.size.toLong(), faltam).toInt())
                if (n < 0) break
                saida.write(buf, 0, n)
                faltam -= n
            }
            saida.flush()
        }
    }

    // ---------------------------------------------------------------- gravar

    private fun pai(rel: String) = rel.substringBeforeLast('/', "")

    /** A pasta onde [rel] vai ficar; sem ela, 409 (a RFC manda assim: "crie a pasta antes"). */
    private fun pastaPai(rel: String): File {
        val p = try { PainelArquivos.resolver(pai(rel)) } catch (e: PainelArquivos.Erro) { throw PainelArquivos.Erro(409, "a pasta de destino não existe") }
        if (!p.isDirectory) throw PainelArquivos.Erro(409, "o destino não é uma pasta")
        return p
    }

    /**
     * PUT: grava o arquivo. Como no envio pela aba Arquivos, vai primeiro para um ".parcial" escondido e só ganha o
     * nome no fim (conexão caiu no meio = nada pela metade com o nome certo). Vai direto para o disco, aos pedaços:
     * um filme de 4 GB não passa pela memória. Antes de receber, confere o espaço livre (sempre sobram 200 MB).
     */
    private fun enviar(amb: Ambiente, req: Requisicao, entrada: InputStream, saida: OutputStream, rel: String, ip: String) {
        if (rel.isEmpty()) return texto(saida, req, 405, "a raiz não é um arquivo")
        val pasta = pastaPai(rel)
        val alvo = PainelArquivos.resolver(rel, deveExistir = false)
        if (alvo.isDirectory) return texto(saida, req, 405, "já existe uma pasta com esse nome")
        // O Finder manda "chunked" (tamanho desconhecido) e avisa o total em X-Expected-Entity-Length.
        val esperado = if (req.chunked) req.cabecalho("x-expected-entity-length")?.toLongOrNull() ?: -1L else req.tamanho
        val livre = PainelArquivos.livre()
        if (esperado > livre) return texto(saida, req, 507, "não cabe: faltam ${(esperado - livre) shr 20} MB na TV")
        // "Expect: 100-continue": o cliente espera o nosso "pode mandar" antes do corpo (senha e espaço já conferidos).
        if (req.cabecalho("expect")?.contains("100-continue", ignoreCase = true) == true) {
            saida.write("HTTP/1.1 100 Continue\r\n\r\n".toByteArray(Charsets.ISO_8859_1)); saida.flush()
        }
        val fluxo: InputStream = if (req.chunked) DavLogica.LeitorChunked(entrada) else req.fluxoCorpo()
        val parcial = File.createTempFile(".${alvo.name.take(40)}.", ".parcial", pasta)
        var total = 0L
        try {
            FileOutputStream(parcial).use { out ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = fluxo.read(buf)
                    if (n < 0) break
                    total += n
                    if (total > livre) throw PainelArquivos.Erro(507, "a memória da TV encheu durante o envio")
                    // Relê o espaço a cada ~16 MB: dois envios em paralelo mediam o mesmo "livre" e juntos comiam a folga.
                    if ((total - n) shr 24 != total shr 24 && PainelArquivos.livre() <= 0) throw PainelArquivos.Erro(507, "a memória da TV encheu durante o envio")
                    out.write(buf, 0, n)
                }
                if (!req.chunked && total != req.tamanho) throw IOException("chegaram $total de ${req.tamanho} bytes")
                out.fd.sync()
            }
            val existia = alvo.exists()
            // rename() já substitui de uma vez; apagar antes perdia o antigo se o rename falhasse.
            if (!parcial.renameTo(alvo)) {
                if (existia && !alvo.delete()) throw PainelArquivos.Erro(409, "não consegui substituir o arquivo")
                if (!parcial.renameTo(alvo)) throw PainelArquivos.Erro(500, "não consegui dar o nome final ao arquivo")
            }
            amb.avisar(alvo)
            // Arquivo vazio é o "reservar o nome" do Windows e do Finder antes do envio de verdade: não enche o diário.
            if (total > 0) amb.registrar(ip, "unidade de rede: enviar ${alvo.name.take(80)} (${total / 1024} KB)")
            PainelHttp.escrever(saida, if (existia) 204 else 201, null, ByteArray(0), CABECALHOS)
        } catch (e: Exception) {
            parcial.delete()
            if (e is PainelArquivos.Erro) throw e
            throw if (e is IOException) e else IOException(e)
        }
    }

    private fun apagar(amb: Ambiente, req: Requisicao, saida: OutputStream, rel: String, ip: String) {
        if (rel.isEmpty()) return texto(saida, req, 403, "a raiz não se apaga")
        val n = try { PainelArquivos.apagar(rel, amb::avisar) } catch (e: PainelArquivos.Erro) {
            throw if (e.codigo == 400) PainelArquivos.Erro(403, e.message ?: "não pode") else e
        }
        amb.registrar(ip, "unidade de rede: apagar ${rel.take(80)} ($n item(ns))")
        PainelHttp.escrever(saida, 204, null, ByteArray(0), CABECALHOS)
    }

    private fun criarPasta(req: Requisicao, entrada: InputStream, saida: OutputStream, rel: String) {
        if (req.tamanho > 0 || req.chunked) { corpoXml(req, entrada); return texto(saida, req, 415, "MKCOL sem corpo") }
        if (rel.isEmpty()) return texto(saida, req, 405, "a raiz já existe")
        pastaPai(rel)
        val nova = PainelArquivos.resolver(rel, deveExistir = false)
        if (nova.exists()) return texto(saida, req, 405, "já existe")
        if (!nova.mkdir()) return texto(saida, req, 500, "o Android não deixou criar a pasta")
        PainelHttp.escrever(saida, 201, null, ByteArray(0), CABECALHOS)
    }

    /** Destino de MOVE/COPY já conferido: o caminho e se havia algo lá (apagado antes, com Overwrite: T). */
    private class Destino(val rel: String, val existia: Boolean)

    /** null = já existe e o cliente pediu para não sobrescrever (412). */
    private fun prepararDestino(amb: Ambiente, req: Requisicao, origem: File, rel: String, movendo: Boolean): Destino? {
        val dest = DavLogica.destino(req.cabecalho("destination"), req.cabecalho("host")) ?: throw PainelArquivos.Erro(400, "destino inválido")
        if (dest.isEmpty() || dest == rel) throw PainelArquivos.Erro(403, "destino igual à origem")
        pastaPai(dest)
        val alvo = PainelArquivos.resolver(dest, deveExistir = false)
        if (origem.isDirectory && ChecagensPainel.dentroDe(alvo.canonicalPath, origem.canonicalPath))
            throw PainelArquivos.Erro(403, "não dá para pôr uma pasta dentro dela mesma")
        // Destino que CONTÉM a origem (MOVE Fotos/2024/a.jpg -> Fotos): com Overwrite: T apagava Fotos inteira, origem
        // junto, e só então o mover dava 404. Sem lixeira: perda definitiva.
        if (alvo.exists() && ChecagensPainel.dentroDe(origem.canonicalPath, alvo.canonicalPath))
            throw PainelArquivos.Erro(403, "o destino contém a origem")
        // Mesmo arquivo com outra caixa ("foto.JPG" -> "foto.jpg"). Só o mesmo arquivo de verdade (isSameFile): num disco
        // que diferencia caixa, "foto.jpg" é OUTRO arquivo e vale o Overwrite.
        val mesmo = alvo.exists() && runCatching { Files.isSameFile(alvo.toPath(), origem.toPath()) }.getOrDefault(false)
        // COPY de um arquivo para ele mesmo abria a leitura e depois a escrita do mesmo arquivo: zerava.
        if (mesmo && !movendo) throw PainelArquivos.Erro(403, "destino igual à origem")
        val existia = alvo.exists() && !mesmo
        if (existia) {
            if (!DavLogica.sobrescrever(req.cabecalho("overwrite"))) return null
            // MOVE de pasta do sistema seria recusado pelo mover() DEPOIS de apagar o destino: confere antes.
            if (movendo && !rel.contains('/') && origem.isDirectory && ChecagensPainel.pastaPadraoTopo(rel))
                throw PainelArquivos.Erro(403, "a pasta \"$rel\" é do sistema e fica onde está")
            PainelArquivos.apagar(dest, amb::avisar)
        }
        return Destino(dest, existia)
    }

    private fun mover(amb: Ambiente, req: Requisicao, saida: OutputStream, rel: String, ip: String) {
        if (rel.isEmpty()) return texto(saida, req, 403, "a raiz não se move")
        val origem = PainelArquivos.resolver(rel)
        val d = prepararDestino(amb, req, origem, rel, movendo = true) ?: return texto(saida, req, 412, "já existe no destino")
        PainelArquivos.mover(rel, pai(d.rel), d.rel.substringAfterLast('/'), amb::avisar)
        amb.registrar(ip, "unidade de rede: mover ${rel.take(60)} → ${d.rel.take(60)}")
        PainelHttp.escrever(saida, if (d.existia) 204 else 201, null, ByteArray(0), CABECALHOS)
    }

    private fun copiar(amb: Ambiente, req: Requisicao, saida: OutputStream, rel: String, ip: String) {
        val origem = PainelArquivos.resolver(rel)
        if (tamanhoTotal(origem) > PainelArquivos.livre()) return texto(saida, req, 507, "não cabe na TV")
        val d = prepararDestino(amb, req, origem, rel, movendo = false) ?: return texto(saida, req, 412, "já existe no destino")
        val alvo = PainelArquivos.resolver(d.rel, deveExistir = false)
        copiarTudo(origem, alvo, soAPasta = req.cabecalho("depth")?.trim() == "0")
        amb.avisar(alvo)
        amb.registrar(ip, "unidade de rede: copiar ${rel.take(60)} → ${d.rel.take(60)}")
        PainelHttp.escrever(saida, if (d.existia) 204 else 201, null, ByteArray(0), CABECALHOS)
    }

    /** Bytes de um arquivo ou de uma pasta inteira (links simbólicos não contam nem são seguidos). */
    private fun tamanhoTotal(f: File): Long =
        if (Files.isSymbolicLink(f.toPath())) 0L
        else if (f.isDirectory) (f.listFiles() ?: emptyArray()).sumOf { tamanhoTotal(it) } else f.length()

    /** Copia sem seguir links simbólicos (um link poderia apontar para fora da memória interna). */
    private fun copiarTudo(de: File, para: File, soAPasta: Boolean = false) {
        if (Files.isSymbolicLink(de.toPath())) return
        if (de.isDirectory) {
            if (!para.exists() && !para.mkdir()) throw PainelArquivos.Erro(500, "o Android não deixou criar a pasta")
            if (!soAPasta) (de.listFiles() ?: emptyArray()).forEach { copiarTudo(it, File(para, it.name)) }
        } else {
            FileInputStream(de).use { i -> FileOutputStream(para).use { o -> i.copyTo(o, 64 * 1024); o.fd.sync() } }
            para.setLastModified(de.lastModified())
        }
    }

    // ---------------------------------------------------------------- travas e propriedades (de mentira)

    private fun travar(req: Requisicao, entrada: InputStream, saida: OutputStream, rel: String) {
        val corpo = if (req.tamanho > 0 || req.chunked) corpoXml(req, entrada, LIMITE_XML_REGEX) else ""
        var f = PainelArquivos.resolver(rel, deveExistir = false)
        var codigo = 200
        if (!f.exists()) {
            // LOCK num nome que não existe cria um arquivo vazio (RFC 4918, 9.10.4): é como o Windows reserva o nome.
            pastaPai(rel)
            if (!f.createNewFile()) return texto(saida, req, 409, "não consegui criar o arquivo")
            f = PainelArquivos.resolver(rel)
            codigo = 201
        }
        // Renovação (sem corpo, com If: (<token>)): devolve o mesmo token.
        val token = Regex("<(opaquelocktoken:[^>]+)>").find(req.cabecalho("if") ?: "")?.groupValues?.get(1)
            ?: "opaquelocktoken:${UUID.randomUUID()}"
        xml(saida, codigo, DavLogica.lockXml(token, rel, f.isDirectory, DavLogica.donoDoLock(corpo), LOCK_SEGUNDOS),
            mapOf("Lock-Token" to "<$token>"))
    }

    /** PROPPATCH: dizemos "ok" a tudo; só a data de modificação (que o Windows manda após copiar) vale de verdade. */
    private fun proppatch(req: Requisicao, entrada: InputStream, saida: OutputStream, rel: String) {
        val f = PainelArquivos.resolver(rel)
        val props = DavLogica.propsDoProppatch(corpoXml(req, entrada, LIMITE_XML_REGEX))
        DavLogica.dataPedida(props)?.let { f.setLastModified(it) }   // se o Android não deixar, fica a data da cópia
        xml(saida, 207, DavLogica.proppatchXml(rel, f.isDirectory, props))
    }
}

/**
 * Anúncio da unidade de rede por mDNS/DNS-SD (o mesmo mecanismo do PainelVizinhos): `_webdavs._tcp` com path=/dav/.
 * Apps e gerenciadores de arquivos que procuram servidores WebDAV na rede encontram a TV sem digitar o endereço. O
 * Windows não lista WebDAV em "Rede" (ver docs/unidade-de-rede.md): lá se mapeia pelo endereço. Só anunciado com a
 * porta https aberta e a chave ligada.
 */
object AnuncioDav {
    private const val TAG = "TvLauncher.Dav"
    const val TIPO = "_webdavs._tcp."
    private var nsd: android.net.nsd.NsdManager? = null
    private var registro: android.net.nsd.NsdManager.RegistrationListener? = null

    @Synchronized fun ligar(ctx: Context) {
        if (registro != null) return
        val app = ctx.applicationContext
        val m = app.getSystemService(android.net.nsd.NsdManager::class.java) ?: return
        nsd = m
        val info = android.net.nsd.NsdServiceInfo().apply {
            serviceName = PainelVizinhos.nomeDaTv(app).take(60)
            serviceType = TIPO
            port = PainelTls.PORTA
            setAttribute("path", "${DavLogica.PREFIXO}/")
        }
        val r = object : android.net.nsd.NsdManager.RegistrationListener {
            override fun onServiceRegistered(i: android.net.nsd.NsdServiceInfo) { Log.i(TAG, "unidade de rede anunciada: ${i.serviceName}") }
            override fun onRegistrationFailed(i: android.net.nsd.NsdServiceInfo, e: Int) { Log.w(TAG, "anúncio falhou: $e") }
            override fun onServiceUnregistered(i: android.net.nsd.NsdServiceInfo) {}
            override fun onUnregistrationFailed(i: android.net.nsd.NsdServiceInfo, e: Int) {}
        }
        try { m.registerService(info, android.net.nsd.NsdManager.PROTOCOL_DNS_SD, r); registro = r }
        catch (e: Exception) { Log.w(TAG, "mDNS indisponível: $e") }
        MtpNaRede.ligar(app)     // e em "Este Computador", como um celular (MTP pela rede)
        AnuncioUpnp.mostrarMtp(true, app)   // o anúncio SSDP que leva o Windows até ele
    }

    @Synchronized fun desligar() {
        AnuncioUpnp.mostrarMtp(false)   // o atalho "· painel" em Rede continua (é do painel, não da unidade)
        MtpNaRede.desligar()
        val m = nsd ?: return
        try { registro?.let { m.unregisterService(it) } } catch (_: Exception) {}
        registro = null
    }
}
