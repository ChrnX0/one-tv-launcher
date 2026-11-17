package br.paulo.tvlauncher

import org.json.JSONObject
import java.util.Base64

/**
 * "Tema por QR" (degrau 1, proposta da brainstorm, 15/11/2026): o visual da TV vira um código curto que cabe num QR.
 * A TV mostra o QR; o celular abre a página pública com o código (site/visual.html), que mostra o que ele muda e deixa
 * copiar; na outra TV, o código entra pelo painel web ou pelo adb. A comunidade pode trocar visuais como links.
 *
 * O que entra: só ajustes (tema, tamanho do texto, pacote de ícones, cor do fundo, pilhas, brilho e contraste do
 * "Meu fundo"). Arquivo de foto ou vídeo NÃO entra: não cabe num QR e é da pessoa. Nada de nome, conta ou rede.
 *
 * Formato: "sofaly-visual:1:" + Base64 URL-safe (sem "=") do JSON. Leitura tolerante: chave desconhecida é ignorada e
 * valor fora da faixa vira null (não aplica), para um código feito à mão não estragar a TV. Puro (VisualCodigoTest).
 * 17/11/2026 (revisão, T4): o prefixo passou de "onetv-visual:1:" para "sofaly-visual:1:"; o antigo continua aceito na
 * leitura, para não quebrar QR já gerado. Também 17/11 (1b): [PRONTOS], a galeria de visuais prontos (TV e site).
 */
object VisualCodigo {
    const val PREFIXO = "sofaly-visual:1:"
    /** Prefixo dos códigos gerados antes de 17/11/2026: só leitura. */
    const val PREFIXO_ANTIGO = "onetv-visual:1:"
    const val MAX_CHARS = 1200
    val TEMAS = setOf("ESCURO", "GALERIA", "CEU", "MEU")   // CEU: "Céu de verdade" (#180)

    data class Visual(
        val tema: String? = null,
        val texto: Int? = null,          // 100, 110 ou 120 (TamanhoTexto)
        val icones: String? = null,      // pacote do pacote de ícones ("" = nenhum)
        val corDoFundo: Boolean? = null,
        val pilhas: Boolean? = null,
        val brilho: Int? = null,
        val contraste: Int? = null,
        val cartoes: Int? = null,        // 100 ou 115 (LayoutHome)
        val transparente: Boolean? = null,
        val grade: Boolean? = null,
    )

    fun codificar(v: Visual): String {
        val j = JSONObject()
        v.tema?.let { j.put("t", it) }; v.texto?.let { j.put("x", it) }; v.icones?.let { j.put("i", it) }
        v.corDoFundo?.let { j.put("c", it) }; v.pilhas?.let { j.put("p", it) }
        v.brilho?.let { j.put("b", it) }; v.contraste?.let { j.put("k", it) }
        v.cartoes?.let { j.put("s", it) }; v.transparente?.let { j.put("v", it) }; v.grade?.let { j.put("g", it) }
        return PREFIXO + Base64.getUrlEncoder().withoutPadding().encodeToString(j.toString().toByteArray(Charsets.UTF_8))
    }

    /** Aceita o código puro ou um link que o contenha depois de "#" (o da página pública). Null = não é um código nosso. */
    fun decodificar(entrada: String): Visual? {
        val t = entrada.trim().let { s -> s.substringAfter('#', s) }.trim()
        val prefixo = listOf(PREFIXO, PREFIXO_ANTIGO).firstOrNull { t.startsWith(it) } ?: return null
        if (t.length > MAX_CHARS) return null
        val j = runCatching {
            JSONObject(String(Base64.getUrlDecoder().decode(t.removePrefix(prefixo)), Charsets.UTF_8))
        }.getOrNull() ?: return null
        fun int(k: String, ok: (Int) -> Boolean) = if (j.has(k)) j.optInt(k, Int.MIN_VALUE).takeIf(ok) else null
        fun bool(k: String) = if (j.has(k)) j.opt(k) as? Boolean else null
        return Visual(
            tema = j.optString("t", "").takeIf { it in TEMAS },
            texto = int("x") { it in setOf(100, 110, 120) },
            icones = if (j.has("i")) j.optString("i").takeIf { it.isEmpty() || Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+").matches(it) } else null,
            corDoFundo = bool("c"),
            pilhas = bool("p"),
            brilho = int("b") { it in -100..100 },
            contraste = int("k") { it in -100..100 },
            cartoes = int("s") { it in setOf(100, 115) },
            transparente = bool("v"),
            grade = bool("g"),
        )
    }

    /**
     * Visuais prontos (revisão dos degraus, 1b, 17/11/2026): a mesma lista na TV (Configurações › Aparência › Visuais
     * prontos) e na galeria do site (site/visual.html, que repete estes códigos; VisualCodigoTest confere que batem).
     * Não mexem em pacote de ícones nem em pilhas (são da organização da pessoa) e não usam "Meu fundo" (pede arquivo).
     */
    data class Pronto(val id: String, val visual: Visual)

    val PRONTOS: List<Pronto> = listOf(
        Pronto("classico", Visual(tema = "ESCURO", texto = 100, corDoFundo = false, cartoes = 100, transparente = false, grade = false)),
        Pronto("leitura", Visual(tema = "ESCURO", texto = 120, corDoFundo = false, cartoes = 115, transparente = false, grade = false)),
        Pronto("galeria", Visual(tema = "GALERIA", texto = 100, corDoFundo = false, cartoes = 100, transparente = true, grade = false)),
        Pronto("galeria_grande", Visual(tema = "GALERIA", texto = 110, corDoFundo = false, cartoes = 115, transparente = true, grade = false)),
        Pronto("grade", Visual(tema = "ESCURO", texto = 100, corDoFundo = false, cartoes = 100, transparente = false, grade = true)),
        Pronto("grade_grande", Visual(tema = "ESCURO", texto = 110, corDoFundo = false, cartoes = 115, transparente = false, grade = true)),
        Pronto("cores", Visual(tema = "ESCURO", texto = 100, corDoFundo = true, cartoes = 100, transparente = true, grade = false)),
        Pronto("cinema", Visual(tema = "GALERIA", texto = 100, corDoFundo = false, cartoes = 100, transparente = true, grade = true)),
    )

    /** Qual pronto bate com o visual atual (só os campos que o pronto define), ou null se a pessoa ajustou à mão. */
    fun prontoAtual(atual: Visual): Pronto? = PRONTOS.firstOrNull { p ->
        val v = p.visual
        v.tema == atual.tema && v.texto == atual.texto && v.corDoFundo == atual.corDoFundo && v.cartoes == atual.cartoes &&
            v.transparente == atual.transparente && v.grade == atual.grade
    }

    /** O próximo da lista (OK na linha da TV); de um visual feito à mão, começa pelo primeiro. */
    fun proximoPronto(atual: Visual): Pronto =
        prontoAtual(atual)?.let { PRONTOS[(PRONTOS.indexOf(it) + 1) % PRONTOS.size] } ?: PRONTOS.first()
}
