package br.paulo.tvlauncher

import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteStatement
import android.util.Log
import java.io.File

/**
 * Guia de programação em banco SQLite (10/11/2026), no lugar do guia.json.
 *
 * Por quê: o guia.json era lido INTEIRO para a memória (dezenas de milhares de programas, e o texto do arquivo junto),
 * tanto no player quanto na HOME (Painel, Configurações). Na TV de 2 GB esse pico pesava. No banco:
 * - a gravação é em fluxo: cada programa sai do XMLTV direto para o disco (Xmltv.lerEmFluxo), sem lista na memória;
 * - a leitura é por canal: o índice (canal, inicio) acha os programas de UM canal na hora, sem ler o resto.
 *
 * O SQLite já vem no Android (android.database.sqlite): nenhuma biblioteca nova.
 */
object GuiaBanco {
    private const val TAG = "TvLauncher.Player"

    /** Muda quando o formato da tabela mudar: banco de outra versão é descartado e baixado de novo. */
    private const val VERSAO = 1

    /**
     * Grava um guia novo num arquivo temporário. Uso: [lote] por tentativa de download, [concluir] no fim.
     * Um lote que dá erro (rede caiu no meio) é desfeito inteiro: assim a nova tentativa não duplica programas.
     */
    class Gravador internal constructor(private val db: SQLiteDatabase) {
        private val insere: SQLiteStatement =
            db.compileStatement("INSERT INTO programa(canal, inicio, fim, titulo, descricao, categoria) VALUES (?,?,?,?,?,?)")

        /** Roda `bloco` numa transação: deu certo, grava tudo; deu erro, desfaz e o erro sobe. */
        fun <T> lote(bloco: ((Programa) -> Unit) -> T): T {
            db.beginTransaction()
            try {
                val r = bloco { p ->
                    insere.clearBindings()
                    insere.bindString(1, p.guiaId); insere.bindLong(2, p.inicio); insere.bindLong(3, p.fim)
                    insere.bindString(4, p.titulo); insere.bindString(5, p.descricao); insere.bindString(6, p.categoria)
                    insere.executeInsert()
                }
                db.setTransactionSuccessful()
                return r
            } finally {
                db.endTransaction()
            }
        }

        /**
         * Fecha o arquivo. Com `indexar`, cria o índice antes (criar no fim é bem mais rápido que manter durante as
         * dezenas de milhares de inserções).
         */
        fun concluir(indexar: Boolean) {
            runCatching {
                if (indexar) db.execSQL("CREATE INDEX IF NOT EXISTS programa_canal ON programa(canal, inicio)")
                db.version = VERSAO   // marca o banco como completo e desta versão
            }.onFailure { Log.w(TAG, "guia: índice ${it.javaClass.simpleName}") }
            runCatching { insere.close() }
            runCatching { db.close() }
        }
    }

    /** Começa um banco novo e vazio em `arquivo` (apaga o que houver lá). */
    fun criar(arquivo: File): Gravador {
        apagar(arquivo)
        val db = SQLiteDatabase.openDatabase(arquivo.path, null,
            SQLiteDatabase.CREATE_IF_NECESSARY or SQLiteDatabase.NO_LOCALIZED_COLLATORS)
        // Diário clássico (DELETE), não WAL: a partir do Android 9 o banco pode sair em modo WAL "de compatibilidade", e
        // um banco WAL aberto só para leitura (abrir) depende dos arquivos -wal/-shm ao lado. Em DELETE o arquivo único
        // basta, e o temporário pode trocar de nome sem levar arquivos de apoio junto. (Revisão de 10/11/2026.)
        runCatching { db.disableWriteAheadLogging() }
        runCatching { db.rawQuery("PRAGMA journal_mode=DELETE", null).use { it.moveToFirst() } }
        db.execSQL("CREATE TABLE programa(canal TEXT NOT NULL, inicio INTEGER NOT NULL, fim INTEGER NOT NULL, " +
            "titulo TEXT NOT NULL, descricao TEXT NOT NULL, categoria TEXT NOT NULL)")
        return Gravador(db)
    }

    /**
     * Abre um guia pronto, só para leitura. null = não serve (não existe, versão antiga, cortado, sem nenhum programa):
     * aí o PlayerCarga baixa de novo, como fazia com o guia.json vazio.
     */
    fun abrir(arquivo: File): FonteGuia? {
        if (!arquivo.exists()) return null
        val db = runCatching {
            SQLiteDatabase.openDatabase(arquivo.path, null, SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS)
        }.getOrNull() ?: return null
        val presta = runCatching {
            db.version == VERSAO && db.rawQuery("SELECT 1 FROM programa LIMIT 1", null).use { it.moveToFirst() }
        }.getOrDefault(false)
        if (!presta) { runCatching { db.close() }; return null }
        return FonteSqlite(db)
    }

    /** Apaga o banco e os arquivos de apoio que o SQLite cria ao lado (-journal, -wal, -shm). */
    fun apagar(arquivo: File) {
        arquivo.delete()
        for (fim in listOf("-journal", "-wal", "-shm")) File(arquivo.path + fim).delete()
    }

    /** Leitura do banco. Toda consulta usa o índice (canal, inicio); erro vira null (o Guia não guarda no cache). */
    private class FonteSqlite(private val db: SQLiteDatabase) : FonteGuia {
        override fun doCanal(guiaId: String): List<Programa>? = runCatching {
            db.rawQuery("SELECT inicio, fim, titulo, descricao, categoria FROM programa WHERE canal = ? ORDER BY inicio",
                arrayOf(guiaId)).use { c ->
                val saida = ArrayList<Programa>(c.count)
                while (c.moveToNext()) saida += Programa(guiaId, c.getLong(0), c.getLong(1), c.getString(2), c.getString(3), c.getString(4))
                saida
            }
        }.getOrNull()

        override fun doCanais(ids: Collection<String>): Map<String, List<Programa>>? = runCatching {
            if (ids.isEmpty()) return@runCatching emptyMap<String, List<Programa>>()
            val marcas = ids.joinToString(",") { "?" }   // poucos canais por vez (o Guia manda blocos de 50)
            db.rawQuery("SELECT canal, inicio, fim, titulo, descricao, categoria FROM programa WHERE canal IN ($marcas) " +
                "ORDER BY canal, inicio", ids.toTypedArray()).use { c ->
                val saida = HashMap<String, ArrayList<Programa>>()
                while (c.moveToNext()) {
                    val canal = c.getString(0)
                    saida.getOrPut(canal) { ArrayList() } += Programa(canal, c.getLong(1), c.getLong(2), c.getString(3), c.getString(4), c.getString(5))
                }
                saida
            }
        }.getOrNull()

        override fun titulosAgora(instante: Long): Map<String, String>? = runCatching {
            val t = instante.toString()
            db.rawQuery("SELECT canal, titulo FROM programa WHERE inicio <= ? AND fim > ? ORDER BY canal, inicio", arrayOf(t, t)).use { c ->
                val saida = HashMap<String, String>(c.count)
                while (c.moveToNext()) saida.putIfAbsent(c.getString(0), c.getString(1))   // o de início mais cedo vence
                saida
            }
        }.getOrNull()

        override fun comPrograma(instante: Long): Set<String>? = runCatching {
            val t = instante.toString()
            db.rawQuery("SELECT DISTINCT canal FROM programa WHERE inicio <= ? AND fim > ?", arrayOf(t, t)).use { c ->
                val saida = HashSet<String>(c.count)
                while (c.moveToNext()) saida += c.getString(0)
                saida
            }
        }.getOrNull()

        override fun programasEntre(desde: Long, ate: Long, aoLer: (Programa) -> Unit): Boolean = runCatching {
            // Sem a descrição (só o título interessa aos lembretes): menos texto para o cursor carregar.
            db.rawQuery("SELECT canal, inicio, fim, titulo, categoria FROM programa WHERE inicio >= ? AND inicio <= ?",
                arrayOf(desde.toString(), ate.toString())).use { c ->
                while (c.moveToNext()) aoLer(Programa(c.getString(0), c.getLong(1), c.getLong(2), c.getString(3), "", c.getString(4)))
            }
            true
        }.getOrDefault(false)

        override fun titulosEntre(desde: Long, ate: Long, ate0: Long): Set<String>? = runCatching {
            db.rawQuery("SELECT DISTINCT titulo FROM programa WHERE inicio >= ? AND inicio <= ? AND fim > ?",
                arrayOf(desde.toString(), ate.toString(), ate0.toString())).use { c ->
                val saida = HashSet<String>(c.count)
                while (c.moveToNext()) saida += c.getString(0)
                saida
            }
        }.getOrNull()

        override fun programasComTitulos(titulos: Collection<String>, desde: Long, ate: Long, aoLer: (Programa) -> Unit): Boolean = runCatching {
            // Em blocos: o SQLite do Android aceita até 999 parâmetros por consulta.
            for (bloco in titulos.chunked(400)) {
                val marcas = bloco.joinToString(",") { "?" }
                db.rawQuery("SELECT canal, inicio, fim, titulo, categoria FROM programa WHERE inicio >= ? AND inicio <= ? " +
                    "AND titulo IN ($marcas)", arrayOf(desde.toString(), ate.toString()) + bloco.toTypedArray()).use { c ->
                    while (c.moveToNext()) aoLer(Programa(c.getString(0), c.getLong(1), c.getLong(2), c.getString(3), "", c.getString(4)))
                }
            }
            true
        }.getOrDefault(false)

        override fun fechar() { runCatching { db.close() } }
    }

}
