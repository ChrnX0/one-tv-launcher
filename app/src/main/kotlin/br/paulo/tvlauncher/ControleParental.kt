package br.paulo.tvlauncher

import org.json.JSONArray
import org.json.JSONObject

/**
 * Controle parental da TV ao vivo (07/11/2026, pedido do dono): canais (ou categorias inteiras) escolhidos ficam
 * bloqueados. Bloqueado = some da lista, da grade, do ▲▼, da busca e do número digitado, e não toca, até alguém digitar
 * o PIN do painel no player (Filtros > "Mostrar categorias ocultas e canais bloqueados"). Ligar, editar e desligar também pedem o PIN
 * (nas Configurações do launcher, aba "Controle parental").
 *
 * Fica em arquivo (files/player/parental.json), e não em preferência, pelo mesmo motivo do resto do player: quem grava é o
 * processo do launcher (Configurações) e quem lê é o do player (":player"); preferência ficaria velha no outro processo.
 * Guarda só ids de canal e nomes de categoria: nada de endereço, usuário ou senha.
 *
 * Desde 10/11/2026 é a regra ÚNICA das três telas (TV ao vivo, Filmes e Séries), com o modo infantil (ver Regras).
 *
 * Só lógica pura (sem Android), testada em ControleParentalTest. Quem lê e grava o arquivo é o PlayerArmazem
 * (parental / definirParental).
 */
object ControleParental {
    const val MAX_CANAIS = 5000
    const val MAX_GRUPOS = 500

    data class Regras(
        val ligado: Boolean = false,
        val canais: Set<String> = emptySet(),   // ids de canal (Canal.id, já com o id da conta na frente)
        val grupos: Set<String> = emptySet(),   // categorias inteiras (Canal.grupo, sem espaço nas pontas)
        // Modo infantil (10/11/2026): ligado, só as categorias marcadas como infantis aparecem, nas TRÊS telas (TV ao vivo,
        // Filmes e Séries); todo o resto fica como "bloqueado" e só aparece com o PIN. Independe do `ligado` acima: dá para
        // usar só um dos dois. Os nomes valem para os três catálogos (uma categoria "Infantil" marcada vale onde existir).
        val infantil: Boolean = false,
        val infantis: Set<String> = emptySet(),
    ) {
        val vazio: Boolean get() = canais.isEmpty() && grupos.isEmpty()
        /** Há algo bloqueado valendo agora? (modo infantil ligado sempre bloqueia algo: tudo que não for infantil) */
        val ativo: Boolean get() = (ligado && !vazio) || infantil
    }

    /**
     * A regra ÚNICA da categoria, usada pela TV ao vivo (via [bloqueado]) e por Filmes e Séries (VodFiltro): a categoria
     * está bloqueada no controle parental, ou o modo infantil está ligado e ela não é infantil.
     */
    fun grupoBloqueado(grupo: String, r: Regras): Boolean {
        val g = grupo.trim()
        return (r.ligado && g in r.grupos) || (r.infantil && g !in r.infantis)
    }

    /** O canal está bloqueado por estas regras? (Desligado = nada bloqueado, mesmo com a lista preenchida.) */
    fun bloqueado(c: Canal, r: Regras): Boolean = (r.ligado && c.id in r.canais) || grupoBloqueado(c.grupo, r)

    /**
     * Marca ou desmarca uma categoria como infantil. Categoria adulta (PlayerFiltro.ehAdulto) nunca vira infantil: seria um
     * jeito fácil de errar o OK e deixar conteúdo adulto livre no modo das crianças.
     */
    fun alternarInfantil(r: Regras, grupo: String): Regras {
        val g = grupo.trim()
        return if (g in r.infantis) r.copy(infantis = r.infantis - g)
        else if (g.isEmpty() || PlayerFiltro.ehAdulto(g) || r.infantis.size >= MAX_GRUPOS) r
        else r.copy(infantis = r.infantis + g)
    }

    fun alternarCanal(r: Regras, id: String): Regras =
        if (id in r.canais) r.copy(canais = r.canais - id)
        else if (r.canais.size >= MAX_CANAIS) r
        else r.copy(canais = r.canais + id)

    fun alternarGrupo(r: Regras, grupo: String): Regras {
        val g = grupo.trim()
        return if (g in r.grupos) r.copy(grupos = r.grupos - g)
        else if (r.grupos.size >= MAX_GRUPOS) r
        else r.copy(grupos = r.grupos + g)
    }

    fun paraJson(r: Regras): String = JSONObject()
        .put("ligado", r.ligado)
        .put("canais", JSONArray(r.canais.take(MAX_CANAIS)))
        .put("grupos", JSONArray(r.grupos.take(MAX_GRUPOS)))
        .put("infantil", r.infantil)
        .put("infantis", JSONArray(r.infantis.take(MAX_GRUPOS)))
        .toString()

    fun deJson(texto: String?): Regras {
        val o = runCatching { JSONObject(texto ?: return Regras()) }.getOrNull() ?: return Regras()
        fun conjunto(chave: String, max: Int): Set<String> {
            val a = o.optJSONArray(chave) ?: return emptySet()
            val s = LinkedHashSet<String>()
            for (i in 0 until minOf(a.length(), max)) a.optString(i, "").takeIf { it.isNotEmpty() && it.length <= 2000 }?.let { s += it }
            return s
        }
        // Adulta marcada como infantil (arquivo mexido à mão ou de versão velha) não vale: some na leitura.
        val infantis = conjunto("infantis", MAX_GRUPOS).filterTo(LinkedHashSet()) { !PlayerFiltro.ehAdulto(it) }
        return Regras(o.optBoolean("ligado", false), conjunto("canais", MAX_CANAIS), conjunto("grupos", MAX_GRUPOS),
            o.optBoolean("infantil", false), infantis)
    }
}
