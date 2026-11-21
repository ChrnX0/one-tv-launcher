package br.paulo.tvlauncher.weather

import android.content.Context
import java.io.InputStream
import java.text.Normalizer
import java.util.Locale
import java.util.zip.GZIPInputStream

/**
 * Lista de cidades DENTRO do app (build da Play, 16/11/2026), no lugar do Nominatim e da busca de cidades da Open-Meteo:
 * os dois não servem a um app pago (Nominatim pede no máximo 1 pedido/s somando todos os usuários e desaconselha uso
 * comercial; a Open-Meteo grátis é só não comercial). Também substitui a localização pelo IP (ipwho.is, grátis só não
 * comercial): sem Wi-Fi do Android e sem cidade cadastrada, vale a maior cidade do fuso horário da TV.
 *
 * Dados: GeoNames (CC BY 4.0, crédito na tela de Licenças), montados por scripts/montar-cidades.py em
 * app/src/main/assets/cidades.tsv.gz (todo build desde 19/11/2026): o mundo com 15 mil habitantes ou mais e o Brasil com mil ou mais (~37 mil
 * cidades, ~1 MB). Ordem: da mais populosa para a menos, então o primeiro achado é o mais provável.
 * Nada sai da TV ao buscar uma cidade.
 */
object CidadesOffline {
    class Cidade(val nome: String, val apelidos: List<String>, val pais: String, val estado: String,
                 val lat: Double, val lon: Double, val populacao: Long, val fuso: String)

    @Volatile private var cache: List<Cidade>? = null

    /** A lista do APK (lida uma vez, ~37 mil linhas). Vazia no APK do site, que não traz o arquivo. Fora da thread da tela. */
    fun lista(ctx: Context): List<Cidade> = cache ?: synchronized(this) {
        cache ?: (try { ctx.assets.open("cidades.tsv.gz").use { ler(it) } } catch (e: Exception) { emptyList() }).also { cache = it }
    }

    fun ler(entrada: InputStream): List<Cidade> = GZIPInputStream(entrada).bufferedReader(Charsets.UTF_8).useLines { linhas ->
        linhas.mapNotNull { l ->
            val f = l.split('\t')
            if (f.size < 8) return@mapNotNull null
            Cidade(f[0], f[1].split(';').filter { it.isNotBlank() }, f[2], f[3],
                f[4].toDoubleOrNull() ?: return@mapNotNull null, f[5].toDoubleOrNull() ?: return@mapNotNull null,
                f[6].toLongOrNull() ?: 0, f[7])
        }.toList()
    }

    /** "São Paulo" -> "sao paulo": sem acento, minúsculo, só letras, números e espaço (o teclado da TV erra acento). */
    fun normalizar(s: String): String = Normalizer.normalize(s.lowercase(Locale.ROOT), Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "").replace(Regex("[^a-z0-9 ]"), " ").replace(Regex(" +"), " ").trim()

    /**
     * Busca por nome. Aceita "Cidade", "Cidade, Estado" ou "Cidade, País" (o que vem depois da vírgula filtra pelo
     * estado, pelo código do país ou pelo nome do país no idioma da TV). Ordem: nome exato, apelido exato (Londres,
     * Nova York), começo do nome. Entre iguais, a mais populosa.
     */
    fun buscar(lista: List<Cidade>, texto: String, idioma: Locale = Locale.getDefault()): Cidade? {
        val partes = texto.split(',').map { normalizar(it) }.filter { it.isNotEmpty() }
        val nome = partes.firstOrNull() ?: return null
        val filtro = partes.drop(1)
        fun passa(c: Cidade) = filtro.all { f ->
            normalizar(c.estado) == f || c.pais.equals(f, true) || normalizar(Locale("", c.pais).getDisplayCountry(idioma)) == f ||
                normalizar(Locale("", c.pais).getDisplayCountry(Locale.ENGLISH)) == f || siglaUf(c) == f
        }
        return lista.firstOrNull { normalizar(it.nome) == nome && passa(it) }
            ?: lista.firstOrNull { c -> c.apelidos.any { normalizar(it) == nome } && passa(c) }
            ?: lista.firstOrNull { normalizar(it.nome).startsWith(nome) && nome.length >= 3 && passa(it) }
    }

    /** A cidade do CEP (ViaCEP manda cidade e UF) na lista, para virar coordenada sem o Nominatim. */
    fun noBrasil(lista: List<Cidade>, cidade: String, uf: String): Cidade? {
        val n = normalizar(cidade); val u = uf.lowercase(Locale.ROOT)
        return lista.firstOrNull { it.pais == "BR" && normalizar(it.nome) == n && siglaUf(it) == u }
            ?: lista.firstOrNull { it.pais == "BR" && normalizar(it.nome) == n }
    }

    /** A maior cidade do fuso da TV ("America/Sao_Paulo" -> São Paulo): o palpite de lugar sem Wi-Fi e sem cadastro. */
    fun doFuso(lista: List<Cidade>, fuso: String): Cidade? = lista.firstOrNull { it.fuso == fuso }

    /** "Campinas, São Paulo, Brasil" no idioma da TV (o mesmo jeito da busca antiga). */
    fun rotulo(c: Cidade, idioma: Locale = Locale.getDefault()): String =
        listOf(c.nome, c.estado.takeIf { it.isNotEmpty() && it != c.nome }, Locale("", c.pais).getDisplayCountry(idioma).takeIf { it.isNotEmpty() })
            .filterNotNull().joinToString(", ")

    // Estados do Brasil no GeoNames vêm por nome; o ViaCEP manda a sigla.
    private val UFS = mapOf("acre" to "ac", "alagoas" to "al", "amapa" to "ap", "amazonas" to "am", "bahia" to "ba", "ceara" to "ce",
        "federal district" to "df", "distrito federal" to "df", "espirito santo" to "es", "goias" to "go", "maranhao" to "ma",
        "mato grosso" to "mt", "mato grosso do sul" to "ms", "minas gerais" to "mg", "para" to "pa", "paraiba" to "pb", "parana" to "pr",
        "pernambuco" to "pe", "piaui" to "pi", "rio de janeiro" to "rj", "rio grande do norte" to "rn", "rio grande do sul" to "rs",
        "rondonia" to "ro", "roraima" to "rr", "santa catarina" to "sc", "sao paulo" to "sp", "sergipe" to "se", "tocantins" to "to")

    private fun siglaUf(c: Cidade): String? = if (c.pais == "BR") UFS[normalizar(c.estado)] else null
}
