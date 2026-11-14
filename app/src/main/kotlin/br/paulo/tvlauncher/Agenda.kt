package br.paulo.tvlauncher

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import android.util.Log
import java.util.Calendar

/**
 * Leitura da agenda do Android (CalendarContract), que o sincronizador da agenda do Google preenche.
 * Saiu da CalendarioActivity em 05/11/2026 para o "Bom dia" falado também ler os compromissos do dia.
 */
object Agenda {
    /** Um evento (uma ocorrência dele, para os que se repetem). */
    class Evento(val titulo: String, val inicio: Long, val fim: Long, val diaInteiro: Boolean, val cor: Int)

    /**
     * As contas que a agenda mostra (ideia 32): com [Extras.Chave.AGENDA_TODAS] ligada (padrão), todas as contas Google
     * da TV juntas; desligada, só a do perfil ativo (como era). Modo visita: nenhuma (a agenda fica fechada).
     */
    fun contas(ctx: Context): List<String> = when {
        ModoVisita.ativo(ctx) -> emptyList()
        Extras.ligado(ctx, Extras.Chave.AGENDA_TODAS) -> Perfil.contas(ctx).ifEmpty { listOfNotNull(Perfil.ativa(ctx)) }
        else -> listOfNotNull(Perfil.ativa(ctx))
    }

    /** Os eventos das contas da agenda ([contas]) entre [de] e [ate]. */
    fun eventosDaTv(ctx: Context, de: Long, ate: Long): List<Evento> = eventos(ctx, contas(ctx), de, ate)

    fun eventos(ctx: Context, conta: String?, de: Long, ate: Long): List<Evento> = eventos(ctx, listOfNotNull(conta), de, ate)

    /**
     * Junta eventos de várias contas: o mesmo compromisso nas duas agendas (um convite da família) aparece uma vez só.
     * Mesmo título, mesmo início e mesmo fim = o mesmo. Fica em ordem de início. Pura (AgendaTest).
     */
    fun juntar(l: List<Evento>): List<Evento> =
        l.distinctBy { Triple(it.titulo.trim().lowercase(), it.inicio, it.fim) }.sortedBy { it.inicio }

    /** Os eventos das contas Google [contas] entre [de] e [ate] (ms), em ordem. Vazio sem conta ou sem READ_CALENDAR. */
    fun eventos(ctx: Context, contas: List<String>, de: Long, ate: Long): List<Evento> {
        if (contas.isEmpty()) return emptyList()
        if (ctx.checkSelfPermission(Manifest.permission.READ_CALENDAR) != PackageManager.PERMISSION_GRANTED) return emptyList()
        // Pede 1 dia a mais de cada lado: a agenda filtra o dia inteiro pelo horário UTC (ver Datas.janelaConsulta).
        // O recorte certo vem depois do ajuste de fuso, no laço abaixo.
        val (deConsulta, ateConsulta) = Datas.janelaConsulta(de, ate)
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().also {
            android.content.ContentUris.appendId(it, deConsulta)
            android.content.ContentUris.appendId(it, ateConsulta)
        }.build()
        val saida = mutableListOf<Evento>()
        try {
            ctx.contentResolver.query(
                uri,
                arrayOf(CalendarContract.Instances.TITLE, CalendarContract.Instances.BEGIN, CalendarContract.Instances.END,
                    CalendarContract.Instances.ALL_DAY, CalendarContract.Instances.DISPLAY_COLOR),
                // + ACCOUNT_TYPE (revisão 04/11): o mesmo e-mail pode existir em outro tipo de conta (ex.: Exchange).
                "${CalendarContract.Calendars.ACCOUNT_NAME} IN (${contas.joinToString(",") { "?" }}) AND ${CalendarContract.Calendars.ACCOUNT_TYPE} = 'com.google' AND ${CalendarContract.Instances.VISIBLE} = 1",
                contas.toTypedArray(), "${CalendarContract.Instances.BEGIN} ASC"
            )?.use { c ->
                while (c.moveToNext()) {
                    val diaInteiro = c.getInt(3) == 1
                    var inicio = c.getLong(1)
                    var fim = c.getLong(2)
                    // Evento de dia inteiro vem em UTC (meia-noite de Greenwich): ajusta para o fuso local, senão cai no dia anterior.
                    if (diaInteiro) {
                        val (i, f) = Datas.ajustarDiaInteiro(inicio, fim)
                        inicio = i; fim = f
                    }
                    // Recorta JÁ no horário local: sem isso o dia inteiro de amanhã entrava em "Hoje na agenda".
                    if (!Datas.dentro(inicio, fim, de, ate)) continue
                    saida.add(Evento(c.getString(0) ?: ctx.getString(R.string.cal_sem_titulo), inicio, fim, diaInteiro, c.getInt(4) or 0xFF000000.toInt()))
                }
            }
        } catch (e: Exception) {
            Log.w("TvLauncher.Agenda", "erro lendo a agenda: $e")
        }
        return if (contas.size > 1) juntar(saida) else saida
    }

    /**
     * Os compromissos de hoje (a partir de agora) em uma frase para a voz, ou null se não houver nenhum:
     * "Hoje na agenda: dentista às 9 e 30, e aniversário da Ana." No máximo 4, para a fala não ficar comprida.
     */
    fun resumoHoje(ctx: Context): String? {
        if (ModoVisita.ativo(ctx)) return null   // modo visita: a agenda fica fechada
        val agora = System.currentTimeMillis()
        val fimDoDia = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 23); set(Calendar.MINUTE, 59); set(Calendar.SECOND, 59)
        }.timeInMillis
        val hoje = eventosDaTv(ctx, agora, fimDoDia).filter { it.fim > agora }
        if (hoje.isEmpty()) return null
        val c = Calendar.getInstance()
        val itens = hoje.take(4).map { e ->
            if (e.diaInteiro) e.titulo else {
                c.timeInMillis = e.inicio
                val h = c.get(Calendar.HOUR_OF_DAY); val m = c.get(Calendar.MINUTE)
                // "às 9", "às 9 e 30"; quem já começou (uma reunião em curso) fica "agora".
                val quando = if (e.inicio <= agora) txt(R.string.ag_agora) else if (m == 0) txt(R.string.ag_as_h, h) else txt(R.string.ag_as_hm, h, m)
                "${e.titulo} $quando"
            }
        }
        val lista = if (itens.size == 1) itens[0] else itens.dropLast(1).joinToString(", ") + txt(R.string.dur_e) + itens.last()
        val mais = if (hoje.size > 4) txt(R.string.ag_e_mais, hoje.size - 4) else ""
        return txt(R.string.ag_hoje, lista + mais)
    }
}
