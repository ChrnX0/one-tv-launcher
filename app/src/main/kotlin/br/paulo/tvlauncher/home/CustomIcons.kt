package br.paulo.tvlauncher.home

import android.content.Context
import android.graphics.drawable.Drawable
import br.paulo.tvlauncher.settings.Planos

/**
 * Ícone trocado à mão, app por app (18/11/2026, "Trocar ícone" no segurar OK da cruz). A escolha manual vence o pacote
 * de ícones escolhido para todos ([PacoteIcones]); "original" vence os dois.
 *
 * Guardado em prefs "custom_icons": pacote do app -> "pacote_de_icones|nome_do_recurso" ou [ORIGINAL].
 * Pacote de ícones de outro desenvolvedor é do plano Pro (Recurso.PLUGINS); nossos estilos (Linha, Símbolo, Duotom) não.
 */
object CustomIcons {
    private const val PREFS = "custom_icons"
    const val ORIGINAL = "-"

    private fun p(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun escolha(ctx: Context, app: String): String? = p(ctx).getString(app, null)

    /** [valor] null = volta a seguir o pacote de ícones de todos. */
    fun definir(ctx: Context, app: String, valor: String?) {
        p(ctx).edit().apply { if (valor == null) remove(app) else putString(app, valor) }.apply()
    }

    /** Muda quando qualquer troca muda (entra na chave do cache de ícones da HOME). */
    fun versao(ctx: Context): Int = p(ctx).all.entries.sortedBy { it.key }.joinToString(";") { "${it.key}=${it.value}" }.hashCode()

    /**
     * O ícone do app vindo de um pacote (troca manual ou o pacote de todos), ou null = o ícone do próprio app.
     * Lê recursos de outro app: fora da thread da tela.
     */
    fun icone(ctx: Context, app: String, classe: String?): Drawable? {
        if (!Planos.permite(ctx, Planos.Recurso.PLUGINS)) return null
        val e = escolha(ctx, app)
        if (e == ORIGINAL) return null
        CustomIconsRules.ler(e)?.let { (pacote, nome) -> PacoteIcones.desenho(ctx, pacote, nome)?.let { return it } }
        return PacoteIcones.icone(ctx, app, classe)
    }
}

/** A parte pura do "Trocar ícone" (CustomIconsRulesTest). */
object CustomIconsRules {
    /** "pacote|nome" -> (pacote, nome); null se vazio, [CustomIcons.ORIGINAL] ou fora do formato. */
    fun ler(valor: String?): Pair<String, String>? {
        val v = valor ?: return null
        val i = v.indexOf('|')
        if (i <= 0 || i == v.length - 1) return null
        val pacote = v.substring(0, i); val nome = v.substring(i + 1)
        if (!pacote.all { it.isLetterOrDigit() || it == '.' || it == '_' } || !PacoteIconesRegras.drawableValido(nome)) return null
        return pacote to nome
    }

    fun escrever(pacote: String, nome: String) = "$pacote|$nome"

    /** "youtube_tv" -> "Youtube tv": o nome do recurso vira legenda legível na grade. */
    fun legenda(nome: String): String = nome.replace('_', ' ').trim().replaceFirstChar { it.uppercase() }

    private fun palavras(t: String) = t.lowercase().replace(Regex("[^\\p{L}\\p{N}]+"), " ").split(' ').filter { it.length >= 3 }

    /**
     * Todos os ícones do pacote, em ordem para a grade: primeiro o que o pacote já liga a este app ([doApp]), depois os
     * que têm no nome uma palavra do nome do app ou do pacote (ex.: "Globoplay" acha "globoplay_alt"), depois o resto em
     * ordem alfabética. Sem repetidos; no máximo [MAX] (pacotes de 15 mil ícones não cabem numa grade de TV).
     */
    fun ordenar(todos: Collection<String>, doApp: String?, nomeApp: String, pacoteApp: String): List<String> {
        val chaves = (palavras(nomeApp) + palavras(pacoteApp.substringAfterLast('.'))).toSet()
        val unicos = todos.filter { PacoteIconesRegras.drawableValido(it) }.distinct()
        val parecidos = unicos.filter { n -> n != doApp && chaves.any { n.lowercase().contains(it) } }.sorted()
        val resto = unicos.filter { it != doApp && it !in parecidos }.sorted()
        return (listOfNotNull(doApp?.takeIf { it in unicos }) + parecidos + resto).take(MAX)
    }

    /** Quantos dos primeiros são "sugeridos" (o do app + os parecidos), para marcar na grade. */
    fun sugeridos(lista: List<String>, doApp: String?, nomeApp: String, pacoteApp: String): Int {
        val chaves = (palavras(nomeApp) + palavras(pacoteApp.substringAfterLast('.'))).toSet()
        return lista.takeWhile { n -> n == doApp || chaves.any { n.lowercase().contains(it) } }.size
    }

    const val MAX = 3_000
}
