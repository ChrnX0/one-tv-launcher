package br.paulo.tvlauncher.panel

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.os.Build
import android.os.Environment
import android.os.storage.StorageManager
import android.provider.Settings
import android.util.Log
import br.paulo.tvlauncher.ConfirmacoesTv
import br.paulo.tvlauncher.Fundo
import br.paulo.tvlauncher.R
import br.paulo.tvlauncher.backup.folder
import br.paulo.tvlauncher.network.CastigoIp
import java.net.ServerSocket
import java.nio.ByteBuffer
import java.util.UUID

/**
 * A parte Android da TV em "Este Computador" (o protocolo está em MtpIp.kt): abre a porta 15740, guarda os
 * computadores aceitos e pergunta na tela quando chega um novo. Liga e desliga junto com a unidade de rede
 * (AnuncioDav), que também cuida do anúncio (AnuncioUpnp).
 *
 * Sem PIN (o MTP não tem onde digitar senha; decisão do dono em 14/11/2026). As barreiras são: só a rede de casa
 * (a mesma conferência do painel), a chave desligada por padrão e o "Permitir?" na TV, uma vez por computador.
 * O identificador do computador pode ser copiado por quem escuta a rede: não é uma senha, é um "quem é você".
 */
object MtpNaRede {
    private const val TAG = "TvLauncher.Mtp"
    private const val PREFS = "mtp_permitidos"   // identificador do computador -> nome que ele disse ter
    private const val MAX_PERMITIDOS = 50
    @Volatile private var servico: MtpServico? = null

    // Pendrives (16/11/2026): cada um aparece no computador como um armazenamento separado, ao lado da memória interna.
    /** Identificador do pendrive -> 1, 2, 3... O número não é reaproveitado enquanto o app vive (o computador guarda). */
    private val numeros = HashMap<String, Int>()
    @Volatile private var armazens: List<MtpArmazem> = listOf(MtpArmazem.interno())
    private var receptor: BroadcastReceiver? = null
    private var ondeRegistrei: Context? = null   // o contexto do app (não de uma tela): pode ficar guardado
    // Nome da TV trocado nas configurações (16/11/2026): o computador só relê o nome quando o aparelho "volta".
    private var vigiaNome: ContentObserver? = null
    @Volatile private var nomeAnunciado = ""
    /** O que o dono pediu (chave da unidade de rede). As religadas internas (nome novo, esquecer) só religam se ainda for true. */
    @Volatile private var querLigado = false
    /** Muda a cada "Esquecer computadores": uma pergunta que já estava na tela não grava o computador depois. */
    private val geracao = java.util.concurrent.atomic.AtomicInteger()
    private val castigo = CastigoIp()

    /** A memória interna + os pendrives montados agora. O nome de cada pendrive vem do Android, no idioma da TV. */
    private fun lerArmazens(app: Context): List<MtpArmazem> {
        val sm = (if (Build.VERSION.SDK_INT >= 30) app.getSystemService(StorageManager::class.java) else null)
            ?: return listOf(MtpArmazem.interno())
        val saida = arrayListOf(MtpArmazem.interno())   // nome fixo: é o que a Ajuda cita, nos 14 idiomas
        for (v in sm.storageVolumes) {
            if (v.isPrimary || v.state != Environment.MEDIA_MOUNTED) continue
            val dir = v.folder ?: continue          // a pasta de verdade (/storage/XXXX-XXXX)
            val chave = v.uuid ?: dir.name
            val n = synchronized(numeros) { numeros.getOrPut(chave) { numeros.size + 1 } }
            saida.add(MtpArmazem(MtpArmazem.idDoPendrive(n), v.getDescription(app) ?: "USB", chave, true) { dir })
        }
        return saida
    }

    /** Relê os pendrives e avisa os computadores conectados do que entrou e do que saiu. */
    private fun conferirArmazens(app: Context) {
        // A troca da lista fica na trava; o aviso ao computador (escrita na rede, pode demorar) fica fora dela: antes um
        // computador que parou de ler travava ligar/desligar, chamados da tela das Configurações.
        val (s, saiu, entrou) = synchronized(this) {
            val antes = armazens
            val novo = try { lerArmazens(app) } catch (e: Exception) { Log.w(TAG, "lendo os armazenamentos: $e"); return }
            armazens = novo
            Triple(servico ?: return, antes.filter { a -> novo.none { it.id == a.id } }, novo.filter { a -> antes.none { it.id == a.id } })
        }
        saiu.forEach { s.avisarArmazem(it.id, false) }
        entrou.forEach { s.avisarArmazem(it.id, true) }
    }

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Os nomes dos computadores que já foram aceitos na TV. */
    fun permitidos(ctx: Context): List<String> = prefs(ctx).all.values.map { it.toString() }.sorted()

    /** Esquece todos e derruba quem está ligado: cada computador pergunta de novo na próxima vez. */
    fun esquecerTodos(ctx: Context) {
        geracao.incrementAndGet()
        prefs(ctx).edit().clear().apply()
        castigo.limpar()
        val religou = synchronized(this) {
            val ligado = servico != null
            desligarJa()
            if (ligado && querLigado) ligarJa(ctx)
            ligado && querLigado
        }
        if (religou) {
            // o anúncio recomeça junto: é ele que manda o Windows largar a conexão que acabamos de derrubar
            AnuncioUpnp.desligar(); AnuncioUpnp.ligar(ctx)
        }
    }

    @Synchronized fun ligar(ctx: Context) { querLigado = true; ligarJa(ctx) }

    @Synchronized private fun ligarJa(ctx: Context) {
        if (servico != null) return
        val app = ctx.applicationContext
        val ss = try { ServerSocket(PtpIp.PORTA, 16) } catch (e: Exception) { Log.w(TAG, "porta ${PtpIp.PORTA} ocupada: $e"); return }
        val id = PainelVizinhos.idDaTv(app)
        val u = UUID.fromString(Ssdp.uuidMtp(id))
        val guid = ByteBuffer.allocate(16).putLong(u.mostSignificantBits).putLong(u.leastSignificantBits).array()
        val versao = try { app.packageManager.getPackageInfo(app.packageName, 0).versionName ?: "1" } catch (_: Exception) { "1" }
        val icone = try { app.assets.open("upnp/sofaly.ico").use { it.readBytes() } } catch (_: Exception) { null }
        val ap = Mtp.Aparelho(Ssdp.nomeNaUnidade(PainelVizinhos.nomeDaTv(app)), Build.MODEL ?: "Android TV", versao, id, guid, icone)
        armazens = try { lerArmazens(app) } catch (_: Exception) { listOf(MtpArmazem.interno()) }
        val s = MtpServico(ss, ap, MtpLoja({ armazens }, atalho = { PainelWeb.atalhoPainel(app) }) { PainelArquivos.avisarGaleria(app, it) },
            aceitaRede = { PainelWeb.daRedeDeCasa(it) },
            permitir = { pc, nome, ip -> permitir(app, pc, nome, ip) },
            registro = { Log.i(TAG, it) })
        MtpSessao.lenta = { Log.i(TAG, it) }   // só operações demoradas: poucas linhas, sem nomes de arquivo
        servico = s
        Thread({
            // Sem a permissão de "todos os arquivos" o Android esconde as pastas: pede ao nosso shell antes de servir.
            try { PainelArquivos.garantirAcesso(app) } catch (e: Exception) { Log.w(TAG, "acesso aos arquivos: $e") }
            s.rodar()
        }, "Mtp-servidor").apply { isDaemon = true }.start()
        // Pendrive espetado ou tirado com a TV ligada: o Android avisa, e nós avisamos o computador.
        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                // fora da thread da tela: o aviso ao computador escreve na rede
                Fundo.rodar("Mtp-pendrive") { conferirArmazens(app) }
            }
        }
        val filtro = IntentFilter().apply {
            addAction(Intent.ACTION_MEDIA_MOUNTED); addAction(Intent.ACTION_MEDIA_UNMOUNTED); addAction(Intent.ACTION_MEDIA_EJECT)
            addAction(Intent.ACTION_MEDIA_REMOVED); addAction(Intent.ACTION_MEDIA_BAD_REMOVAL)
            addDataScheme("file")   // estes avisos trazem o caminho do volume como "file://..."; sem isto o filtro não casa
        }
        try { app.registerReceiver(r, filtro); receptor = r; ondeRegistrei = app } catch (e: Exception) { Log.w(TAG, "avisos de pendrive: $e") }
        nomeAnunciado = PainelVizinhos.nomeDaTv(app)
        if (vigiaNome == null) {
            // ContentObserver: o Android chama onChange quando o valor "device_name" das configurações muda
            val v = object : ContentObserver(null) {
                override fun onChange(selfChange: Boolean) {
                    if (PainelVizinhos.nomeDaTv(app) == nomeAnunciado || servico == null) return
                    // fora da thread que avisou: religar fecha portas e escreve na rede
                    Fundo.rodar("Mtp-nome") {
                        Log.i(TAG, "nome da TV mudou: anunciando de novo")
                        // Confere na trava: o dono pode ter desligado a unidade entre o aviso e esta thread.
                        val religou = synchronized(MtpNaRede) {
                            if (!querLigado || servico == null) false else { desligarJa(); ligarJa(app); true }
                        }
                        if (religou) { AnuncioUpnp.desligar(); AnuncioUpnp.ligar(app) }   // o adeus + o olá fazem o Windows reler a descrição
                    }
                }
            }
            try { app.contentResolver.registerContentObserver(Settings.Global.getUriFor("device_name"), false, v); vigiaNome = v }
            catch (e: Exception) { Log.w(TAG, "vigia do nome: $e") }
        }
        Log.i(TAG, "MTP pela rede ligado na porta ${PtpIp.PORTA}")
    }

    /** Há algum computador conectado agora? */
    fun temSessao(): Boolean = servico?.temConexao() == true

    @Synchronized fun desligar() { querLigado = false; desligarJa() }

    @Synchronized private fun desligarJa() {
        servico?.parar()
        servico = null
        receptor?.let { r -> try { ondeRegistrei?.unregisterReceiver(r) } catch (_: Exception) {} }
        receptor = null
    }

    /** Computador conhecido entra direto; novo, a TV pergunta (até 60 s; sem resposta = não). */
    private fun permitir(app: Context, pc: String, nome: String, ip: String): Boolean {
        if (prefs(app).contains(pc)) return true
        if (prefs(app).all.size >= MAX_PERMITIDOS) return false
        if (castigo.emCastigo(ip, System.currentTimeMillis())) return false   // disse "não" há pouco: não pergunta de novo
        // Outra pergunta já na tela: recusa agora SEM castigo (senão a pergunta de um aparelho insistente punia o
        // computador verdadeiro, que pediu na mesma hora).
        if (ConfirmacoesTv.atual() != null) return false
        val g = geracao.get()
        val sim = ConfirmacoesTv.perguntarSempre(app, app.getString(R.string.mtp_pergunta_titulo),
            app.getString(R.string.mtp_pergunta, nome), ip)
        if (!sim) { castigo.castigar(ip, System.currentTimeMillis()); return false }
        if (geracao.get() != g) return false   // "Esquecer computadores" foi apertado enquanto a pergunta estava na tela
        prefs(app).edit().putString(pc, nome.take(80)).apply()
        return true
    }
}
