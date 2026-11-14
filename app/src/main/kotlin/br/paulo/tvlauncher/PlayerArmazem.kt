package br.paulo.tvlauncher

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Player universal, passo 2 (06/11/2026): onde o player guarda as coisas dele na TV.
 *
 * - As contas (links de lista, usuário e senha do Xtream) ficam CIFRADAS (AES-GCM) com uma chave do Android Keystore,
 *   que nunca sai do aparelho, no arquivo files/player/contas.json. Ele fica fora das preferências "launcher", que o
 *   backup do painel exporta (BackupConfig): senha de IPTV não pode ir num JSON baixado. É arquivo, e não
 *   SharedPreferences, porque quem grava é o processo do launcher e quem lê é o do player (":player"), e cada processo
 *   guarda a sua própria cópia das preferências em memória (o player veria a conta velha).
 * - O último canal fica em files/player/ultimo.json (a HOME também lê). Antes do 06/11 era a preferência "player".
 * - Cache de canais e do guia: arquivos em files/player/, sem senha (ver PlayerDados.canaisParaJson).
 */
object PlayerArmazem {
    private const val TAG = "TvLauncher.Player"
    private const val APELIDO = "player_fontes"

    // internal: o backup completo guarda uma cópia para desfazer, se a restauração falhar no meio
    internal fun arquivoContas(ctx: Context) = File(pasta(ctx), "contas.json")
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("player", Context.MODE_PRIVATE)

    fun pasta(ctx: Context): File = File(ctx.filesDir, "player").apply { mkdirs() }

    // ---------------------------------------------------------------- contas

    /** Há conta cadastrada? (só olha se o arquivo existe: barato o bastante para a HOME chamar a cada desenho) */
    fun temContas(ctx: Context): Boolean = File(File(ctx.filesDir, "player"), "contas.json").exists()

    fun fontes(ctx: Context): List<FonteTv> {
        val f = arquivoContas(ctx)
        if (!f.exists()) return emptyList()
        val o = runCatching { JSONObject(f.readText()) }.getOrNull() ?: return emptyList()
        val dados = o.optString("dados").ifEmpty { return emptyList() }
        val iv = o.optString("iv").ifEmpty { return emptyList() }
        val claro = runCatching { decifrar(dados, iv) }.onFailure { Log.w(TAG, "contas ilegíveis: ${it.javaClass.simpleName}") }.getOrNull()
        return PlayerDados.fontesDeJson(claro)
    }

    private fun gravar(ctx: Context, fontes: List<FonteTv>): Boolean = runCatching {
        val (dados, iv) = cifrar(PlayerDados.fontesParaJson(fontes))
        // Grava num temporário e troca de nome: se a TV desligar no meio, fica o arquivo antigo inteiro, não metade.
        val tmp = File(pasta(ctx), "contas.json.tmp")
        tmp.writeText(JSONObject().put("dados", dados).put("iv", iv).toString())
        tmp.renameTo(arquivoContas(ctx))
    }.onFailure { Log.w(TAG, "não gravou as contas: ${it.javaClass.simpleName}") }.getOrDefault(false)

    /** Troca TODAS as contas pelas da lista (restauração do backup completo; o Keystore desta instalação cifra de novo). */
    fun substituirContas(ctx: Context, fontes: List<FonteTv>): Boolean =
        if (fontes.isEmpty()) { arquivoContas(ctx).delete(); true } else gravar(ctx, fontes)

    /** Cria uma conta nova (id gerado). Devolve a conta, ou o motivo de não ter criado. */
    fun adicionar(ctx: Context, nome: String, tipo: String, endereco: String, usuario: String = "", senha: String = "", guia: String = "",
                  reservas: String = "", guiaExtra: String = ""): Result<FonteTv> {
        val atuais = fontes(ctx)
        if (atuais.size >= 8) return Result.failure(IllegalStateException("já há 8 contas; apague uma antes"))
        val nova = PlayerDados.criar(UUID.randomUUID().toString().take(8), nome, tipo, endereco, usuario, senha, guia, reservas, guiaExtra).getOrElse { return Result.failure(it) }
        return if (gravar(ctx, atuais + nova)) Result.success(nova) else Result.failure(IllegalStateException("não consegui gravar"))
    }

    /**
     * O servidor reserva `servidor` respondeu e o principal não: ele vira o principal (e o antigo vai para as reservas).
     * Assim o próximo carregamento, os filmes e o endereço de cada canal já usam o que funciona.
     */
    @Synchronized
    fun promoverServidor(ctx: Context, id: String, servidor: String): Boolean {
        val atuais = fontes(ctx)
        val f = atuais.firstOrNull { it.id == id } ?: return false
        if (f.endereco == servidor || servidor !in f.reservas) return false
        val nova = f.copy(endereco = servidor, reservas = listOf(f.endereco) + f.reservas.filter { it != servidor })
        return gravar(ctx, atuais.map { if (it.id == id) nova else it })
    }

    /**
     * Vencimento de cada lista (só nome e data, sem senha nem endereço), gravado a cada login pelo PlayerCarga, para a
     * HOME avisar antes de vencer sem precisar de rede. expiraEm null = a lista não informa: sai do arquivo.
     */
    @Synchronized
    fun definirVencimento(ctx: Context, id: String, nome: String, expiraEm: Long?) {
        val o = runCatching { JSONObject(lerArquivo(ctx, "vencimentos.json") ?: "{}") }.getOrDefault(JSONObject())
        if (expiraEm == null) o.remove(id) else o.put(id, JSONObject().put("nome", nome).put("expira", expiraEm))
        gravarArquivo(ctx, "vencimentos.json", o.toString())
    }

    /** (nome, vencimento em segundos) das listas que ainda existem. */
    fun vencimentos(ctx: Context): List<Pair<String, Long>> {
        val o = runCatching { JSONObject(lerArquivo(ctx, "vencimentos.json") ?: return emptyList()) }.getOrNull() ?: return emptyList()
        val ids = fontes(ctx).map { it.id }.toSet()
        return o.keys().asSequence().filter { it in ids }.mapNotNull { k ->
            o.optJSONObject(k)?.let { v -> v.optString("nome") to v.optLong("expira").takeIf { it > 0 } }?.let { (n, e) -> e?.let { n to it } }
        }.toList()
    }

    fun apagar(ctx: Context, id: String): Boolean {
        val atuais = fontes(ctx)
        if (atuais.none { it.id == id }) return false
        PlayerCarga.apagarCache(ctx, id)
        return gravar(ctx, atuais.filter { it.id != id })
    }

    // ---------------------------------------------------------------- favoritos, recentes e categoria escolhida
    // Arquivos (e não prefs) pelo mesmo motivo do ultimo.json: quem grava é o player, mas a HOME pode querer ler
    // (ex.: uma linha "Favoritos" na HOME). Sem senha nem endereço: só os ids dos canais.

    fun favoritos(ctx: Context): Set<String> = PlayerLista.idsDeJson(lerArquivo(ctx, "favoritos.json"), PlayerLista.MAX_FAVORITOS).toSet()
    // Id que é URL (formato antigo, ver IdCanal) fica até migrarIdsAntigos trocá-lo: descartar aqui apagaria o favorito
    // de uma conta que ainda não carregou. Nenhum código novo cria id com URL (M3u.ler usa IdCanal.estavel).
    fun definirFavoritos(ctx: Context, ids: Set<String>) = gravarArquivo(ctx, "favoritos.json", PlayerLista.idsParaJson(ids))

    fun recentes(ctx: Context): List<String> = PlayerLista.idsDeJson(lerArquivo(ctx, "recentes.json"), PlayerLista.MAX_RECENTES)
    fun definirRecentes(ctx: Context, ids: List<String>) = gravarArquivo(ctx, "recentes.json", PlayerLista.idsParaJson(ids))

    /**
     * player-8 (10/11/2026): troca os ids antigos (a URL do canal) pelos ids estáveis (IdCanal) em favoritos, recentes,
     * canais bloqueados e último canal. Roda a cada carga de canais, mas só grava se achou id antigo (barato no resto das
     * vezes). Chamar fora da thread da tela, com os canais já juntados (PlayerCarga.canais).
     */
    fun migrarIdsAntigos(ctx: Context, canais: List<Canal>) {
        if (canais.isEmpty()) return   // sem canais não dá para achar o id novo: tenta na próxima carga
        val contas = fontes(ctx).mapTo(HashSet()) { it.id }   // conta por conta: a que está fora do ar espera (IdCanal.migrar)
        IdCanal.migrar(favoritos(ctx).toList(), canais, contas)?.let { definirFavoritos(ctx, it.toCollection(LinkedHashSet())) }
        IdCanal.migrar(recentes(ctx), canais, contas)?.let { definirRecentes(ctx, it) }
        val p = parental(ctx)
        IdCanal.migrar(p.canais.toList(), canais, contas)?.let { definirParental(ctx, p.copy(canais = it.toCollection(LinkedHashSet()))) }
        // O último canal de antes de 06/11 (preferência "player") podia guardar a URL: não serve mais, sai.
        if (prefs(ctx).contains("ultimo")) prefs(ctx).edit().remove("ultimo").apply()
        val u = ultimo(ctx) ?: return
        if (!IdCanal.pareceUrl(u.id)) return
        val novo = IdCanal.migrarUm(u.id, canais, contas)
        if (novo == u.id) return   // conta fora do ar agora: tenta na próxima carga
        val c = novo?.let { id -> canais.firstOrNull { it.id == id } }
        if (c != null) definirUltimoCanal(ctx, c, u.programas) else File(pasta(ctx), "ultimo.json").delete()
    }

    /** Grupos escondidos pelo usuário e grupos adultos que ele liberou com o PIN (ver PlayerFiltro.ocultasEfetivas). */
    fun gruposEscondidos(ctx: Context): Set<String> = PlayerLista.idsDeJson(lerArquivo(ctx, "ocultas.json"), 500).toSet()
    fun definirGruposEscondidos(ctx: Context, g: Set<String>) = gravarArquivo(ctx, "ocultas.json", PlayerLista.idsParaJson(g))
    fun gruposLiberados(ctx: Context): Set<String> = PlayerLista.idsDeJson(lerArquivo(ctx, "liberadas.json"), 500).toSet()
    fun definirGruposLiberados(ctx: Context, g: Set<String>) = gravarArquivo(ctx, "liberadas.json", PlayerLista.idsParaJson(g))

    /** Onde parou em cada filme/episódio (chave = Filme.chave). */
    fun posicoes(ctx: Context): Map<String, Posicao> = Vod.posicoesDeJson(lerArquivo(ctx, "posicoes.json"))
    fun definirPosicoes(ctx: Context, p: Map<String, Posicao>) = gravarArquivo(ctx, "posicoes.json", Vod.posicoesParaJson(p))

    /**
     * Filmes ou séries marcados como "interesse" (OK segurado 2 s na grade). Só as chaves, na ordem em que entraram.
     * Um arquivo para cada tela: assim uma tela aberta por trás nunca grava por cima do que a outra mudou.
     */
    fun interesses(ctx: Context, series: Boolean): Set<String> =
        PlayerLista.idsDeJson(lerArquivo(ctx, arquivoInteresses(series)), Vod.MAX_INTERESSES).toCollection(LinkedHashSet())
    fun definirInteresses(ctx: Context, series: Boolean, chaves: Set<String>) = gravarArquivo(ctx, arquivoInteresses(series), PlayerLista.idsParaJson(chaves))
    private fun arquivoInteresses(series: Boolean) = if (series) "interesses_series.json" else "interesses_filmes.json"

    /** Último episódio aberto em cada série (chave = Serie.chave). */
    fun ultimosEpisodios(ctx: Context): Map<String, UltimoEpisodio> = Series.ultimosDeJson(lerArquivo(ctx, "series_ultimos.json"))
    fun definirUltimosEpisodios(ctx: Context, u: Map<String, UltimoEpisodio>) = gravarArquivo(ctx, "series_ultimos.json", Series.ultimosParaJson(u))

    /**
     * Arte do último filme e da última série abertos (files/player/capas_home.json), para o fundo dos cards Filmes e
     * Séries na HOME (CapaAtalho): as imagens de cena (backdrop) e o pôster, para quando não houver cena. Só endereços
     * de imagem: nada de senha (as imagens do Xtream são links públicos, quase sempre do TMDB).
     */
    data class CapaHome(val poster: String, val cenas: List<String>, val ficha: FichaHome? = null)

    /**
     * A ficha curta do último filme/episódio para o destaque da HOME (12/11/2026, dono: "por que não aparecem as
     * informações como no launcher do Google?"): título, "T1 E4", nota, gênero, ano, duração e sinopse. [chave] é a da
     * posição salva (Filme.chave / Episodio.chave), para a HOME calcular "você parou em 34%" e "faltam 1 h 18 min".
     */
    data class FichaHome(
        val titulo: String,
        val episodio: String = "",
        val nota: Float? = null,
        val ano: Int? = null,
        val genero: String = "",
        val duracaoMin: Int? = null,
        val sinopse: String = "",
        val chave: String = "",
    )

    fun capaHome(ctx: Context, serie: Boolean): CapaHome = runCatching {
        val o = JSONObject(lerArquivo(ctx, "capas_home.json") ?: "{}").optJSONObject(if (serie) "serie" else "filme")
            ?: return CapaHome("", emptyList())
        val cs = o.optJSONArray("cenas")
        val f = o.optJSONObject("ficha")?.let {
            FichaHome(it.optString("titulo"), it.optString("ep"), it.optDouble("nota").takeIf { n -> !n.isNaN() }?.toFloat(),
                it.optInt("ano").takeIf { a -> a > 0 }, it.optString("genero"), it.optInt("dur").takeIf { d -> d > 0 },
                it.optString("sinopse"), it.optString("chave"))
        }?.takeIf { it.titulo.isNotEmpty() }
        CapaHome(o.optString("poster"), if (cs == null) emptyList() else (0 until cs.length()).map { cs.optString(it) }.filter { it.isNotEmpty() }, f)
    }.getOrDefault(CapaHome("", emptyList()))

    fun definirCapaHome(ctx: Context, serie: Boolean, poster: String, cenas: List<String>, ficha: FichaHome? = null) {
        if (poster.isEmpty() && cenas.isEmpty() && ficha == null) return
        val o = runCatching { JSONObject(lerArquivo(ctx, "capas_home.json") ?: "{}") }.getOrDefault(JSONObject())
        val item = JSONObject().put("poster", poster.take(500))
            .put("cenas", org.json.JSONArray().apply { cenas.take(Vod.MAX_CENAS).forEach { put(it.take(500)) } })
        if (ficha != null) item.put("ficha", JSONObject().put("titulo", ficha.titulo.take(120)).put("ep", ficha.episodio.take(40))
            .put("nota", ficha.nota?.toDouble() ?: JSONObject.NULL).put("ano", ficha.ano ?: 0).put("genero", ficha.genero.take(80))
            .put("dur", ficha.duracaoMin ?: 0).put("sinopse", ficha.sinopse.take(400)).put("chave", ficha.chave.take(120)))
        gravarArquivo(ctx, "capas_home.json", o.put(if (serie) "serie" else "filme", item).toString())
    }

    /** Quantos episódios de cada série já foram vistos (chave = Serie.chave), para a etiqueta e os "Assistidos". */
    fun progressoSeries(ctx: Context): Map<String, ProgressoSerie> = Series.progressoDeJson(lerArquivo(ctx, "series_progresso.json"))
    fun definirProgressoSeries(ctx: Context, p: Map<String, ProgressoSerie>) = gravarArquivo(ctx, "series_progresso.json", Series.progressoParaJson(p))

    /**
     * Controle parental (ControleParental): canais e categorias bloqueados, e se está ligado. Arquivo, e não prefs: quem
     * grava são as Configurações (processo do launcher) e quem lê é o player (":player").
     */
    fun parental(ctx: Context): ControleParental.Regras = ControleParental.deJson(lerArquivo(ctx, "parental.json"))
    // Grava o que receber: canal bloqueado pelo id antigo (URL) só sai pela migração (migrarIdsAntigos), que o troca pelo
    // id novo. Filtrar aqui soltava o bloqueio se alguém mexesse nas Configurações antes de o player abrir (revisão 10/11).
    fun definirParental(ctx: Context, r: ControleParental.Regras): Boolean {
        gravarArquivo(ctx, "parental.json", ControleParental.paraJson(r))
        return parental(ctx) == r   // confere lendo de volta: gravarArquivo não diz se falhou
    }

    /** A última categoria escolhida na lista (só o player usa: preferência basta). */
    fun categoria(ctx: Context): String? = prefs(ctx).getString("categoria", null)
    fun definirCategoria(ctx: Context, chave: String) = prefs(ctx).edit().putString("categoria", chave).apply()

    private fun lerArquivo(ctx: Context, nome: String): String? = runCatching { File(pasta(ctx), nome).readText() }.getOrNull()

    /** Escrita atômica: grava ao lado e troca o nome (TV desligada no meio deixa o arquivo antigo inteiro). */
    private fun gravarArquivo(ctx: Context, nome: String, texto: String) {
        runCatching {
            val tmp = File(pasta(ctx), "$nome.tmp")
            tmp.writeText(texto)
            tmp.renameTo(File(pasta(ctx), nome))
        }.onFailure { Log.w(TAG, "não gravou $nome: ${it.javaClass.simpleName}") }
    }

    // ---------------------------------------------------------------- uso

    /**
     * O último canal assistido. Arquivo (files/player/ultimo.json), e não preferência: quem grava é o player (":player") e
     * quem também lê é a HOME, para o "Continuar" do disco da TV; preferência ficaria velha no processo da HOME.
     */
    /** O último canal e o que passa nele (até 4 programas, gravados pelo player), para o destaque da HOME. */
    // guiaId (12/11/2026): a HOME consulta o guia em cache e mostra o programa de AGORA, mesmo horas depois de o player
    // ter gravado (os 4 programas gravados envelhecem). Vazio em ultimo.json antigo: a HOME procura o canal na lista.
    data class Ultimo(val id: String, val numero: Int, val nome: String, val programas: List<Programa> = emptyList(), val guiaId: String = "")

    // Arquivo, e não prefs: o player roda em outro processo (":player"), e prefs não se veem entre processos na hora.
    fun ultimo(ctx: Context): Ultimo? = runCatching {
        val o = JSONObject(File(File(ctx.filesDir, "player"), "ultimo.json").readText())
        val ps = o.optJSONArray("p")
        val programas = if (ps == null) emptyList() else (0 until minOf(ps.length(), 4)).mapNotNull { i ->
            ps.optJSONObject(i)?.let { Programa("", it.optLong("i"), it.optLong("f"), it.optString("t").take(80), it.optString("d").take(240)) }
        }
        Ultimo(o.getString("id"), o.optInt("numero"), o.optString("nome").take(60), programas, o.optString("g").take(120))
    }.getOrNull()

    fun ultimoCanal(ctx: Context): String? = ultimo(ctx)?.id ?: prefs(ctx).getString("ultimo", null)?.takeUnless(IdCanal::pareceUrl)

    /** Grava o canal (e os próximos programas, se o guia já carregou). Escrita atômica: grava ao lado e troca o nome. */
    fun definirUltimoCanal(ctx: Context, c: Canal, programas: List<Programa> = emptyList()) {
        runCatching {
            val ps = org.json.JSONArray()
            programas.take(4).forEach { ps.put(JSONObject().put("i", it.inicio).put("f", it.fim).put("t", it.titulo.take(80)).put("d", it.descricao.take(240))) }
            val tmp = File(pasta(ctx), "ultimo.json.tmp")
            tmp.writeText(JSONObject().put("id", c.id).put("numero", c.numero).put("nome", c.nome).put("g", c.guiaId).put("p", ps).toString())
            tmp.renameTo(File(pasta(ctx), "ultimo.json"))
        }
    }

    // ---------------------------------------------------------------- cifra (mesma receita do IaArmazem)

    private fun chave(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(APELIDO, null) as? SecretKey)?.let { return it }
        val gerador = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gerador.init(KeyGenParameterSpec.Builder(APELIDO, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build())
        return gerador.generateKey()
    }

    internal fun cifrar(texto: String): Pair<String, String> {
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, chave()) }
        return Base64.encodeToString(c.doFinal(texto.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP) to
            Base64.encodeToString(c.iv, Base64.NO_WRAP)
    }

    internal fun decifrar(dados: String, iv: String): String {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, chave(), GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)))
        return String(c.doFinal(Base64.decode(dados, Base64.NO_WRAP)), Charsets.UTF_8)
    }
}
