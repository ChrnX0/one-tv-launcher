package br.paulo.tvlauncher

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Estudo global (20/11/2026, ideia 1): design/tokens.json é a única fonte da cara do Sofaly. Este teste falha quando o
 * DesignTokens.kt (app ou celular) ou o bloco de tokens do style.css do painel ficaram para trás.
 * Falhou? Rode `python3 scripts/design-tokens.py` e faça commit dos arquivos gerados.
 */
class DesignTokensTest {
    private fun raiz(): File = listOf(File(".."), File(".")).first { File(it, "design/tokens.json").isFile }
    private val tokens by lazy { JSONObject(File(raiz(), "design/tokens.json").readText()) }

    @Test fun coresDoAppIguaisAoJson() {
        val cores = tokens.getJSONObject("color")
        val campos = DesignTokens::class.java.declaredFields.associateBy { it.name }
        for (nome in cores.keys()) {
            val constante = nome.replace(Regex("(?<!^)(?=[A-Z])"), "_").uppercase()
            val campo = campos[constante] ?: error("DesignTokens.$constante não existe: rode scripts/design-tokens.py")
            campo.isAccessible = true
            val esperado = (0xFF000000 or cores.getJSONObject(nome).getString("value").removePrefix("#").toLong(16)).toInt()
            assertEquals("DesignTokens.$constante ficou para trás (rode scripts/design-tokens.py)", esperado, campo.getInt(null))
        }
        assertEquals(tokens.getJSONObject("motion").getJSONObject("quick").getLong("value"), DesignTokens.MS_QUICK)
        assertEquals(tokens.getJSONObject("radius").getJSONObject("medium").getInt("value"), DesignTokens.RADIUS_MEDIUM)
    }

    @Test fun painelECelularTemOsMesmosTokens() {
        val css = File(raiz(), "app/src/main/assets/painel/style.css").readText()
        val bloco = css.substringAfter("/* design-tokens:start").substringBefore("/* design-tokens:end */")
        val cores = tokens.getJSONObject("color")
        for (nome in cores.keys()) {
            val varCss = "--t-" + nome.replace(Regex("(?<!^)(?=[A-Z])"), "-").lowercase()
            assertTrue("style.css sem $varCss correto (rode scripts/design-tokens.py)",
                bloco.contains("$varCss: ${cores.getJSONObject(nome).getString("value").lowercase()};"))
        }
        val app = File(raiz(), "app/src/main/kotlin/br/paulo/tvlauncher/DesignTokens.kt").readText()
        val celular = File(raiz(), "celular/src/main/kotlin/br/paulo/onetv/celular/DesignTokens.kt").readText()
        assertEquals("o DesignTokens do celular difere do app (rode scripts/design-tokens.py)",
            app.substringAfter("\n\n"), celular.substringAfter("\n\n"))
    }

    @Test fun todoTokenTemUmPorque() {
        for (grupo in listOf("color", "font", "radius", "space", "motion")) {
            val g = tokens.getJSONObject(grupo)
            for (nome in g.keys()) assertTrue("$grupo.$nome sem \"why\"", g.getJSONObject(nome).optString("why").length > 10)
        }
    }
}
