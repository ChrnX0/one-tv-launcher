package br.paulo.tvlauncher

import org.json.JSONObject

/**
 * A frase do destaque de um app na HOME (ideia 13, 14/11/2026: "frase do destaque para todos os apps").
 *
 * Ordem de escolha:
 *  1. a frase escrita à mão (MainActivity.DESCRICAO_APPS, já no idioma da TV);
 *  2. a ficha do app em `assets/fichas-apps.json` (a mesma tabela do painel, PainelFicha.kt). A tabela é em português
 *     ("d"); o inglês é opcional ("en"). TV em inglês sem "en": pula a ficha (nunca mostra português para ela);
 *  3. a categoria que o app declara, no idioma da TV;
 *  4. null (quem chama mostra "App").
 *
 * A ficha é escrita para o painel ("Solid Explorer: gerenciador de arquivos.", "Netflix. O botão Netflix do controle
 * abre ele."), então aqui ela vira uma frase curta: só a primeira frase que diz algo além do nome do app, sem o
 * "Nome: " do começo e sem o ponto final. Ficha que é só o nome ("YouTube Music.") não serve: cai na categoria.
 *
 * Puro (sem Android) para o teste JVM; o org.json de verdade entra no teste (ver app/build.gradle.kts).
 */
object FrasesApps {
    /** Uma ficha reduzida ao que a HOME usa: a frase em português e, se houver, em inglês. */
    class Ficha(val pt: String?, val en: String?)

    /** Frase mais longa que isto não cabe na linha do destaque: fica a categoria. */
    const val MAXIMO = 80

    /** Lê a tabela (o JSON inteiro de fichas-apps.json). JSON quebrado: mapa vazio (a HOME segue com a categoria). */
    fun ler(json: String): Map<String, Ficha> = try {
        val o = JSONObject(json)
        val m = HashMap<String, Ficha>()
        for (p in o.keys()) {
            val x = o.optJSONObject(p) ?: continue
            m[p] = Ficha(x.optString("d").takeIf { it.isNotBlank() }, x.optString("en").takeIf { it.isNotBlank() })
        }
        m
    } catch (e: Exception) { emptyMap() }

    /**
     * A frase do app [pacote] (nome visível [nome]). [manual]: a frase escrita à mão, se houver; [fichas]: a tabela
     * (null enquanto não foi lida fora da thread da tela); [portugues]: a TV está em português; [categoria]: o título
     * da categoria declarada, já traduzido.
     */
    fun escolher(pacote: String, nome: String, manual: String?, fichas: Map<String, Ficha>?, portugues: Boolean,
                 categoria: String?): String? {
        manual?.takeIf { it.isNotBlank() }?.let { return it }
        val ficha = fichas?.get(pacote)
        val texto = if (portugues) ficha?.pt else ficha?.en
        texto?.let { curta(it, nome) }?.let { return it }
        return categoria?.takeIf { it.isNotBlank() }
    }

    /** A primeira frase de [texto] que diz algo além de [nome], curta; null se nenhuma servir. */
    fun curta(texto: String, nome: String): String? {
        val frases = texto.split(Regex("(?<=[.!?])\\s+")).map { it.trim() }.filter { it.isNotEmpty() }
        for (bruta in frases) {
            var f = bruta.trimEnd('.', ' ')
            // "Solid Explorer: gerenciador de arquivos" -> "gerenciador de arquivos" (o nome já está em cima).
            val doisPontos = f.indexOf(": ")
            if (doisPontos > 0 && mesmoNome(f.substring(0, doisPontos), nome)) f = f.substring(doisPontos + 2).trim()
            if (f.isEmpty() || mesmoNome(f, nome)) continue
            if (f.length > MAXIMO) return null
            return f.replaceFirstChar { it.uppercaseChar() }
        }
        return null
    }

    private fun mesmoNome(a: String, b: String): Boolean = normal(a) == normal(b)

    private fun normal(s: String): String =
        java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")
            .lowercase().filter { it.isLetterOrDigit() }
}
