package br.paulo.tvlauncher

import org.json.JSONObject
import java.security.MessageDigest

/**
 * Regras puras das ideias da 2ª leva de 10/11/2026 (pedido do dono: "faz tudo"), sem nada do Android, para testar no
 * computador (NoiteRegrasTest). Quem usa: HoraDormirTv, ConsumoRedeTv, TocandoAgoraTv, RecadoLink, ModoLeveTv, SomNavegacao.
 */

/** Hora de dormir das crianças: num horário, só alguns apps abrem e o volume tem teto. */
object HoraDormir {
    const val CHAVE_INICIO = "dormir_inicio"     // minutos desde 0h
    const val CHAVE_FIM = "dormir_fim"
    const val CHAVE_TETO = "dormir_teto"         // % do volume máximo
    const val CHAVE_PERMITIDOS = "dormir_apps"   // pacotes separados por vírgula
    const val PADRAO_INICIO = 21 * 60
    const val PADRAO_FIM = 6 * 60 + 30
    const val PADRAO_TETO = 30

    /** O que nunca é barrado: a própria HOME, a do Google (a nossa vem por cima), a barra do sistema e o assistente de voz. */
    val ESSENCIAIS = setOf(
        "br.paulo.tvlauncher", "com.google.android.apps.tv.launcherx", "com.android.systemui",
        "com.google.android.katniss", "com.google.android.tvrecommendations", "android",
        // Janelas de sistema que só aparecem POR CIMA de outro app (auditoria de 10/11/2026): o teclado, o seletor de
        // contas e os diálogos do Google, e o pedido de permissão. Sem eles, abrir a busca num app permitido mostrava o
        // teclado e a TV voltava para a HOME. As Configurações da TV também ficam liberadas (decisão do dono, 10/11/2026).
        "com.google.android.inputmethod.latin", "com.google.android.tvkeyboard", "com.google.android.gms",
        "com.google.android.gsf", "com.google.android.permissioncontroller", "com.android.permissioncontroller",
        "com.android.tv.settings",
    )

    /** [agora] dentro de [inicio, fim)? A janela pode virar a meia-noite (21:00 até 06:30). inicio == fim = desligada. */
    fun dentro(agoraMin: Int, inicio: Int, fim: Int): Boolean = when {
        inicio == fim -> false
        inicio < fim -> agoraMin in inicio until fim
        else -> agoraMin >= inicio || agoraMin < fim
    }

    /** Lista de pacotes digitada (vírgula, espaço ou quebra de linha), só nomes de pacote válidos, sem repetir. */
    fun lerPermitidos(texto: String?): Set<String> =
        texto.orEmpty().split(',', ' ', '\n', ';').map { it.trim() }
            .filter { Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+$").matches(it) }.toSet()

    /** O app [pacote] deve ser fechado agora? */
    fun bloqueia(ligado: Boolean, agoraMin: Int, inicio: Int, fim: Int, pacote: String?, permitidos: Set<String>): Boolean =
        ligado && pacote != null && dentro(agoraMin, inicio, fim) && pacote !in ESSENCIAIS && pacote !in permitidos

    // ---- Horário por dia da semana (pedido do dono, 10/11/2026: "mantém o padrão, mas permita alterar por dia").

    /** Horário de um dia. inicio == fim = dia livre (sem hora de dormir). */
    data class Janela(val inicio: Int, val fim: Int)

    const val CHAVE_DIAS = "dormir_dias"   // 7 itens separados por ";" (domingo primeiro): "1260-390" ou "" = usa o padrão
    val NOMES_DIAS = listOf("dom", "seg", "ter", "qua", "qui", "sex", "sáb")

    /** Lê os 7 dias guardados. Item vazio ou estragado = null (usa o padrão). Sempre devolve 7 itens. */
    fun lerDias(texto: String?): List<Janela?> {
        val partes = texto.orEmpty().split(';')
        return (0 until 7).map { d ->
            val m = Regex("^(\\d{1,4})-(\\d{1,4})$").find(partes.getOrNull(d)?.trim().orEmpty()) ?: return@map null
            val i = m.groupValues[1].toInt(); val f = m.groupValues[2].toInt()
            if (i < 24 * 60 && f < 24 * 60) Janela(i, f) else null
        }
    }

    fun escreverDias(dias: List<Janela?>): String =
        (0 until 7).joinToString(";") { d -> dias.getOrNull(d)?.let { "${it.inicio}-${it.fim}" } ?: "" }

    /** O horário que vale no dia [dia] (0 = domingo, como Calendar.DAY_OF_WEEK - 1). */
    fun doDia(dia: Int, dias: List<Janela?>, padrao: Janela): Janela = dias.getOrNull(Math.floorMod(dia, 7)) ?: padrao

    /**
     * É hora de dormir no dia [dia] às [agoraMin]? Uma janela que vira a meia-noite pertence ao dia em que COMEÇA:
     * a de sexta 22:00-08:00 vale até as 8h de sábado, mesmo que o sábado tenha outro horário.
     */
    fun dentroNoDia(dia: Int, agoraMin: Int, dias: List<Janela?>, padrao: Janela): Boolean {
        val hoje = doDia(dia, dias, padrao)
        val ontem = doDia(dia - 1, dias, padrao)
        val hojeVale = when {
            hoje.inicio == hoje.fim -> false
            hoje.inicio < hoje.fim -> agoraMin in hoje.inicio until hoje.fim
            else -> agoraMin >= hoje.inicio   // a parte depois da meia-noite é do "ontem" de amanhã
        }
        val sobraDeOntem = ontem.inicio > ontem.fim && agoraMin < ontem.fim
        return hojeVale || sobraDeOntem
    }

    /** Subir o volume passaria do teto? [atual] e [maximo] são passos do AudioManager. */
    fun passaDoTeto(atual: Int, maximo: Int, tetoPorcento: Int): Boolean =
        maximo > 0 && atual + 1 > maximo * tetoPorcento.coerceIn(0, 100) / 100
}

/** Consumo de internet por app (NetworkStatsManager): soma por app e escreve "1,2 GB". */
object ConsumoRede {
    data class Uso(val uid: Int, val bytes: Long)
    data class PorApp(val nome: String, val bytes: Long)

    /** Junta as linhas do mesmo uid, troca o uid pelo nome ([nomes]; senão "uid N") e põe o maior primeiro. */
    fun somar(usos: List<Uso>, nomes: (Int) -> String?, limite: Int = 50): List<PorApp> =
        usos.groupBy { it.uid }.map { (uid, l) -> PorApp(nomes(uid) ?: "uid $uid", l.sumOf { it.bytes }) }
            .filter { it.bytes > 0 }.sortedByDescending { it.bytes }.take(limite)

    /** 0 B, 950 KB, 12,3 MB, 1,25 GB (base 1000, como as operadoras). */
    fun texto(bytes: Long): String = when {
        bytes < 1_000 -> "$bytes B"
        bytes < 1_000_000 -> "${bytes / 1_000} KB"
        bytes < 1_000_000_000 -> "%.1f MB".format(java.util.Locale("pt", "BR"), bytes / 1e6)
        else -> "%.2f GB".format(java.util.Locale("pt", "BR"), bytes / 1e9)
    }
}

/** O que está tocando: de várias sessões de mídia, qual mostrar. */
object TocandoAgora {
    data class Sessao(val pacote: String, val tocando: Boolean, val titulo: String, val atualizadoMs: Long)

    /** A que está tocando; empate = a mais recente. Nenhuma tocando = a pausada mais recente com título. */
    fun escolher(l: List<Sessao>): Sessao? =
        l.filter { it.tocando }.maxByOrNull { it.atualizadoMs }
            ?: l.filter { it.titulo.isNotBlank() }.maxByOrNull { it.atualizadoMs }
}

/**
 * Recado por link no formato do TvOverlay / PiPup (Home Assistant): POST /recado/<token> com
 * {"title":"...","message":"...","duration":10}. Aceita também "titulo"/"texto"/"segundos".
 */
object RecadoLink {
    const val CHAVE_RESUMO = "recado_link_resumo"
    const val MIN_SEGUNDOS = 5
    const val MAX_SEGUNDOS = 300
    const val INTERVALO_MS = 3_000L   // no máximo 1 recado a cada 3 s: um script em laço não tranca a TV

    data class Pedido(val titulo: String, val texto: String, val segundos: Int)

    fun ler(json: String?): Pedido? = try {
        val o = JSONObject(json ?: "")
        val titulo = Recado.limpar(o.optString("title").ifBlank { o.optString("titulo") })?.take(60) ?: ""
        val texto = Recado.limpar(o.optString("message").ifBlank { o.optString("texto") })
        val seg = if (o.has("duration")) o.optInt("duration", 10) else o.optInt("segundos", 10)
        when {
            texto != null -> Pedido(titulo, texto, seg.coerceIn(MIN_SEGUNDOS, MAX_SEGUNDOS))
            titulo.isNotEmpty() -> Pedido("", titulo, seg.coerceIn(MIN_SEGUNDOS, MAX_SEGUNDOS))
            else -> null
        }
    } catch (e: Exception) { null }

    /** O token confere com o resumo guardado? Mesmo formato e mesma conta das cenas (SHA-256, tempo constante). */
    fun confere(token: String, resumoGuardado: String): Boolean {
        if (resumoGuardado.isEmpty() || !Regex("^[0-9a-f]{32}$").matches(token)) return false
        return MessageDigest.isEqual(Cenas.resumo(token).toByteArray(), resumoGuardado.toByteArray())
    }
}

/** Modo leve: animações mais curtas, guardando os valores de antes para desfazer. */
object ModoLeve {
    val CHAVES = listOf("window_animation_scale", "transition_animation_scale", "animator_duration_scale")
    const val VALOR = 0.5f
    const val CHAVE_ANTES = "modo_leve_antes"

    /** {"window_animation_scale":1.0,...}; null (não definido no aparelho) vira 1.0, o padrão do Android. */
    fun guardar(antes: Map<String, Float?>): String =
        JSONObject().apply { CHAVES.forEach { put(it, (antes[it] ?: 1f).toDouble()) } }.toString()

    /**
     * O que o "desfazer" deve gravar: null quando não há "antes" guardado (o modo leve não estava ligado, ou já foi
     * desfeito). Sem isso, um "desligar" repetido pelo painel ou pelo adb gravava 1.0 por cima do que o dono ajustou.
     */
    fun paraVoltar(texto: String?): Map<String, Float>? = if (texto.isNullOrBlank()) null else ler(texto)

    /** Os valores para voltar. Texto estragado ou vazio = 1.0 em todas (o padrão do Android). */
    fun ler(texto: String?): Map<String, Float> {
        val o = try { JSONObject(texto ?: "") } catch (e: Exception) { JSONObject() }
        return CHAVES.associateWith { o.optDouble(it, 1.0).toFloat().takeIf { v -> v in 0f..10f } ?: 1f }
    }
}

/** Som de navegação: setas e OK, só com a nossa tela na frente. */
object SomNavegacao {
    enum class Som { CIMA, BAIXO, ESQUERDA, DIREITA, CLIQUE }

    /** Código da tecla (KeyEvent) -> som; null = tecla sem som. 19..22 = setas, 23 e 66 = OK/Enter. */
    fun som(codigo: Int): Som? = when (codigo) {
        19 -> Som.CIMA; 20 -> Som.BAIXO; 21 -> Som.ESQUERDA; 22 -> Som.DIREITA
        23, 66, 160 -> Som.CLIQUE
        else -> null
    }
}
