package br.paulo.tvlauncher.observer

import br.paulo.tvlauncher.home.SofalyLogo
import br.paulo.tvlauncher.observer.SofyPolicy.Expression

/**
 * Os movimentos da Sofy (20/11/2026). Regra pura (SofyMotionTest); quem desenha é o [SofyDrawable].
 *
 * Por quê: o corpo tem que contar o mesmo que o rosto, senão a cara diz "triste" e o corpo pula (Paulo pediu "mais
 * coerente com as expressões"). Para quê: a pessoa entende o estado da Sofy de longe, mesmo sem ler o texto. Como: cada
 * [Expression] tem um [Clip]: trilhas de movimento para cada parte do corpo (antenas, braços, pés, o corpo inteiro) e a
 * sequência dos olhos, que trocam no ponto alto do movimento (como num desenho animado).
 *
 * Dose aprovada pelo Paulo (20/11/2026 14:41Z): um movimento a cada fala, de até [MAX_MS], e depois ela fica parada;
 * nada se mexe sozinho nem repete; com "reduzir movimento" ou alto contraste só o rosto muda ([SofyDrawable]).
 *
 * Medidas no quadro do símbolo da marca (120 x 112, o mesmo do [SofalyLogo.drawSymbol]); ângulos em graus, sentido do
 * relógio positivo (como no Canvas). Para o braço esquerdo, positivo = abre para fora; para o direito, negativo.
 */
object SofyMotion {
    const val MAX_MS = 2500L

    enum class Part(val pivotX: Float, val pivotY: Float) {
        BODY(60f, 110f),          // o corpo inteiro gira e amassa a partir dos pés
        ANTENNA_LEFT(60f, 36f), ANTENNA_RIGHT(60f, 36f),
        ARM_LEFT(20f, 64f), ARM_RIGHT(100f, 64f),   // o ombro é o alto do braço do sofá
        FOOT_LEFT(18f, 94f), FOOT_RIGHT(102f, 94f),
    }

    /** Pose das antenas: ponta da esquerda e da direita (a base é o pino, 60,36). [rightCurl] = a direita vira um "?". */
    enum class Antennas(val lx: Float, val ly: Float, val rx: Float, val ry: Float, val rightCurl: Boolean = false) {
        UP(35f, 18f, 89f, 24f), PERK(41f, 10f, 85f, 12f), DROOP(28f, 21f, 94f, 24f), LOW(36f, 26f, 88f, 30f),
        BANG(35f, 18f, 66f, 4f), ZIG(35f, 14f, 90f, 18f), ASK(35f, 18f, 80f, 34f, rightCurl = true),
    }

    /** Um ponto da trilha: em [t] (0..1 da trilha), girar [rot] graus, andar [tx]/[ty] e esticar [sx]/[sy]. */
    data class Key(val t: Float, val rot: Float = 0f, val tx: Float = 0f, val ty: Float = 0f, val sx: Float = 1f, val sy: Float = 1f)

    enum class Ease { OUT, IN_OUT, BOUNCE }

    data class Track(val part: Part, val startMs: Long, val durationMs: Long, val keys: List<Key>, val ease: Ease = Ease.OUT)

    /** Os olhos (e a boca) a partir de [atMs]. */
    data class Face(val atMs: Long, val eyes: String, val mouth: String = "")

    data class Clip(val expression: Expression, val antennas: Antennas, val tracks: List<Track>, val faces: List<Face>) {
        val lengthMs: Long = (tracks.maxOfOrNull { it.startMs + it.durationMs } ?: 0L).coerceAtLeast(faces.maxOfOrNull { it.atMs } ?: 0L)
    }

    private fun k(t: Float, rot: Float = 0f, tx: Float = 0f, ty: Float = 0f, sx: Float = 1f, sy: Float = 1f) = Key(t, rot, tx, ty, sx, sy)
    private fun rots(vararg r: Float) = r.mapIndexed { i, v -> Key(if (r.size == 1) 1f else i / (r.size - 1f), v) }
    private fun tr(p: Part, start: Long, dur: Long, keys: List<Key>, e: Ease = Ease.OUT) = Track(p, start, dur, keys, e)

    fun clip(e: Expression): Clip = when (e) {
        // Feliz: agacha, pula esticando, cai amassando; antenas chegam atrasadas e balançam; olhos viram ^ ^ no alto.
        Expression.HAPPY -> Clip(e, Antennas.PERK, listOf(
            tr(Part.BODY, 0, 950, listOf(k(0f), k(.2f, ty = 2f, sx = 1.04f, sy = .95f), k(.5f, ty = -14f, sx = .97f, sy = 1.04f), k(.75f, sx = 1.03f, sy = .97f), k(1f))),
            tr(Part.ANTENNA_LEFT, 90, 1100, rots(0f, 14f, -10f, 4f, 0f)), tr(Part.ANTENNA_RIGHT, 130, 1100, rots(0f, -14f, 10f, -4f, 0f)),
            tr(Part.ARM_LEFT, 0, 900, rots(0f, 35f, 0f)), tr(Part.ARM_RIGHT, 0, 900, rots(0f, -35f, 0f)),
        ), listOf(Face(0, "o o"), Face(380, "^ ^")))
        // Atenta: um pulinho só, o mesmo que já existia na abertura.
        Expression.ATTENTIVE -> Clip(e, Antennas.UP, listOf(
            tr(Part.BODY, 0, 550, listOf(k(0f), k(.3f, sx = 1.03f, sy = .97f), k(.6f, ty = -10f, sx = .98f, sy = 1.02f), k(1f))),
            tr(Part.ANTENNA_LEFT, 80, 700, rots(0f, 8f, -4f, 0f)), tr(Part.ANTENNA_RIGHT, 110, 700, rots(0f, -8f, 4f, 0f)),
        ), listOf(Face(0, "o o")))
        // Surpresa: susto que estica para cima, braços saltam, antenas tremem.
        Expression.SURPRISED -> Clip(e, Antennas.BANG, listOf(
            tr(Part.BODY, 0, 700, listOf(k(0f), k(.35f, ty = -12f, sx = .95f, sy = 1.05f), k(.7f, sx = 1.03f, sy = .97f), k(1f)), Ease.BOUNCE),
            tr(Part.ARM_LEFT, 0, 900, rots(0f, 70f, 40f, 0f)), tr(Part.ARM_RIGHT, 0, 900, rots(0f, -70f, -40f, 0f)),
            tr(Part.ANTENNA_LEFT, 60, 800, rots(0f, 12f, -9f, 6f, -3f, 0f)), tr(Part.ANTENNA_RIGHT, 90, 800, rots(0f, -12f, 9f, -6f, 3f, 0f)),
        ), listOf(Face(0, "o o"), Face(160, "O O", "o")))
        // Pergunta: inclina, leva a mão ao "queixo" e a antena direita vira "?"; fica assim esperando a resposta.
        Expression.QUESTION -> Clip(e, Antennas.ASK, listOf(
            tr(Part.BODY, 0, 700, listOf(k(0f), k(1f, rot = -7f))),
            tr(Part.ARM_RIGHT, 250, 800, rots(0f, 52f)),
            tr(Part.ANTENNA_RIGHT, 450, 700, rots(-25f, 6f, 0f), Ease.BOUNCE),
        ), listOf(Face(0, "o O"), Face(650, "? ?")))
        // Consertando (em andamento): martela 5 vezes, o corpo treme a cada batida; quando acabar, vira Feliz ou Triste.
        Expression.FIXING -> Clip(e, Antennas.ZIG, listOf(
            tr(Part.ARM_RIGHT, 0, 1600, rots(0f, -45f, -85f, -45f, -85f, -45f, -85f, -45f, -85f, -45f, -85f, 0f), Ease.IN_OUT),
            tr(Part.BODY, 0, 1500, listOf(k(0f), k(.2f, tx = 1f), k(.4f, tx = -1f), k(.6f, tx = 1f), k(.8f, tx = -1f), k(1f)), Ease.IN_OUT),
            tr(Part.ANTENNA_LEFT, 80, 1500, rots(0f, 5f, -5f, 5f, -5f, 0f), Ease.IN_OUT),
        ), listOf(Face(0, "> <"), Face(300, "- -"), Face(600, "> <"), Face(900, "- -"), Face(1200, "> <")))
        // Bronca (com o problema, nunca com a pessoa): mãos na cintura, bate o pé 3 vezes, antenas duras.
        Expression.CROSS -> Clip(e, Antennas.BANG, listOf(
            tr(Part.ARM_LEFT, 0, 400, rots(0f, -32f)), tr(Part.ARM_RIGHT, 0, 400, rots(0f, 32f)),
            tr(Part.FOOT_RIGHT, 350, 1350, rots(0f, -20f, 0f, -20f, 0f, -20f, 0f), Ease.IN_OUT),
            tr(Part.BODY, 350, 1350, listOf(k(0f), k(.17f, sx = 1.02f, sy = .98f), k(.33f), k(.5f, sx = 1.02f, sy = .98f), k(.67f), k(.83f, sx = 1.02f, sy = .98f), k(1f)), Ease.IN_OUT),
            tr(Part.ANTENNA_LEFT, 350, 1400, rots(0f, 3f, -3f, 3f, -3f, 3f, -3f, 0f)), tr(Part.ANTENNA_RIGHT, 380, 1400, rots(0f, -3f, 3f, -3f, 3f, -3f, 3f, 0f)),
        ), listOf(Face(0, "o o"), Face(220, "\\ /")))
        // Encantada: sobe devagar, abre os braços, os olhos brilham 3 vezes.
        Expression.DELIGHTED -> Clip(e, Antennas.PERK, listOf(
            tr(Part.BODY, 0, 2200, listOf(k(0f), k(.3f, ty = -7f), k(.7f, ty = -7f), k(1f)), Ease.IN_OUT),
            tr(Part.ARM_LEFT, 0, 2200, listOf(k(0f), k(.3f, rot = 50f), k(.7f, rot = 50f), k(1f)), Ease.IN_OUT),
            tr(Part.ARM_RIGHT, 0, 2200, listOf(k(0f), k(.3f, rot = -50f), k(.7f, rot = -50f), k(1f)), Ease.IN_OUT),
            tr(Part.ANTENNA_LEFT, 200, 900, rots(0f, -6f, 0f)), tr(Part.ANTENNA_RIGHT, 260, 900, rots(0f, 6f, 0f)),
        ), listOf(Face(0, "o o"), Face(420, "* *"), Face(800, "+ +"), Face(1100, "* *"), Face(1400, "+ +"), Face(1700, "* *")))
        // Triste: afunda devagar e fica; as antenas caem uma depois da outra; os braços pendem.
        Expression.SAD -> Clip(e, Antennas.DROOP, listOf(
            tr(Part.BODY, 0, 1300, listOf(k(0f), k(1f, ty = 3f, sy = .95f)), Ease.IN_OUT),
            tr(Part.ANTENNA_LEFT, 250, 1000, rots(18f, -3f, 0f), Ease.IN_OUT), tr(Part.ANTENNA_RIGHT, 650, 1000, rots(-16f, 3f, 0f), Ease.IN_OUT),
            tr(Part.ARM_LEFT, 0, 1200, rots(0f, -8f), Ease.IN_OUT), tr(Part.ARM_RIGHT, 0, 1200, rots(0f, 8f), Ease.IN_OUT),
        ), listOf(Face(0, "o o"), Face(500, "; ;"), Face(1100, "T T")))
        // Preocupada: o mesmo caminho da tristeza, mais curto; para no "; ;".
        Expression.WORRIED -> Clip(e, Antennas.DROOP, listOf(
            tr(Part.BODY, 0, 900, listOf(k(0f), k(1f, ty = 2f, sy = .97f)), Ease.IN_OUT),
            tr(Part.ANTENNA_LEFT, 150, 800, rots(18f, -2f, 0f), Ease.IN_OUT), tr(Part.ANTENNA_RIGHT, 300, 800, rots(-16f, 2f, 0f), Ease.IN_OUT),
        ), listOf(Face(0, "o o"), Face(450, "; ;")))
        // Confusa: balança de um lado para o outro, antenas se cruzam.
        Expression.CONFUSED -> Clip(e, Antennas.ZIG, listOf(
            tr(Part.BODY, 0, 1500, rots(0f, -5f, 5f, -4f, 3f, 0f), Ease.IN_OUT),
            tr(Part.ANTENNA_LEFT, 120, 1300, rots(0f, -12f, 9f, 0f), Ease.IN_OUT), tr(Part.ANTENNA_RIGHT, 160, 1300, rots(0f, 12f, -9f, 0f), Ease.IN_OUT),
        ), listOf(Face(0, "o O"), Face(500, "O o"), Face(1000, "@ @")))
        // Desconfiada: inclina devagar para o lado e fica; uma antena abaixa.
        Expression.SUSPICIOUS -> Clip(e, Antennas.UP, listOf(
            tr(Part.BODY, 0, 1000, listOf(k(0f), k(1f, rot = 4f, tx = 5f)), Ease.IN_OUT),
            tr(Part.ANTENNA_RIGHT, 300, 900, rots(0f, 14f), Ease.IN_OUT),
        ), listOf(Face(0, "o o"), Face(550, "= =")))
        // Piscadela: pulinho de lado e aponta.
        Expression.WINK -> Clip(e, Antennas.UP, listOf(
            tr(Part.BODY, 0, 600, listOf(k(0f), k(.5f, tx = 4f, ty = -8f, sx = .98f, sy = 1.02f), k(1f, tx = 6f))),
            tr(Part.ARM_RIGHT, 250, 1500, listOf(k(0f), k(.25f, rot = -80f), k(.75f, rot = -80f), k(1f))),
            tr(Part.ANTENNA_LEFT, 300, 700, rots(0f, 8f, 0f)),
        ), listOf(Face(0, "^ ^"), Face(320, "^ -")))
        // Dormindo: boceja espreguiçando, abaixa as antenas e para (sem respiração em loop).
        Expression.SLEEPING -> Clip(e, Antennas.LOW, listOf(
            tr(Part.ARM_LEFT, 0, 1300, listOf(k(0f), k(.3f, rot = 130f), k(.7f, rot = 130f), k(1f)), Ease.IN_OUT),
            tr(Part.ARM_RIGHT, 0, 1300, listOf(k(0f), k(.3f, rot = -130f), k(.7f, rot = -130f), k(1f)), Ease.IN_OUT),
            tr(Part.ANTENNA_LEFT, 1200, 900, rots(-10f, 0f), Ease.IN_OUT), tr(Part.ANTENNA_RIGHT, 1250, 900, rots(10f, 0f), Ease.IN_OUT),
            tr(Part.BODY, 1300, 1100, listOf(k(0f), k(.5f, sx = 1.01f, sy = 1.03f), k(1f)), Ease.IN_OUT),
        ), listOf(Face(0, "o o"), Face(300, "O O", "o"), Face(1300, "- -")))
    }

    /** Os olhos em [ms] (a última troca até ali). */
    fun faceAt(c: Clip, ms: Long): Face = c.faces.lastOrNull { it.atMs <= ms } ?: c.faces.first()

    /**
     * Os mesmos movimentos para o painel web e o celular (sofy.js desenha em SVG): uma fonte só, a TV manda tudo em JSON.
     * Formato: {max_ms, parts:{NOME:[px,py]}, clips:{EXPR:{eyes, mouth, antennas:[lx,ly,rx,ry,curl], length_ms,
     * faces:[[ms,olhos,boca]], tracks:[[parte, início, duração, curva, [[t,rot,tx,ty,sx,sy]…]]]}}}.
     */
    fun toJson(): org.json.JSONObject {
        fun arr(vararg v: Any) = org.json.JSONArray().apply { v.forEach { put(it) } }
        fun Float.r(): Double = Math.round(this * 1000.0) / 1000.0   // 0.3f vira 0.3, não 0.30000001192092896
        val parts = org.json.JSONObject().apply { Part.entries.forEach { put(it.name, arr(it.pivotX.r(), it.pivotY.r())) } }
        val clips = org.json.JSONObject()
        for (e in Expression.entries) {
            val c = clip(e); val a = c.antennas
            clips.put(e.name, org.json.JSONObject()
                .put("eyes", e.eyes).put("mouth", e.mouth).put("length_ms", c.lengthMs)
                .put("antennas", arr(a.lx.r(), a.ly.r(), a.rx.r(), a.ry.r(), a.rightCurl))
                .put("faces", org.json.JSONArray().apply { c.faces.forEach { put(arr(it.atMs, it.eyes, it.mouth)) } })
                .put("tracks", org.json.JSONArray().apply {
                    c.tracks.forEach { t ->
                        val keys = org.json.JSONArray().apply { t.keys.forEach { k -> put(arr(k.t.r(), k.rot.r(), k.tx.r(), k.ty.r(), k.sx.r(), k.sy.r())) } }
                        put(arr(t.part.name, t.startMs, t.durationMs, t.ease.name, keys))
                    }
                }))
        }
        return org.json.JSONObject().put("max_ms", MAX_MS).put("parts", parts).put("clips", clips)
    }

    /** A boca enquanto ela fala: aberta "o", fechada "-" (ASCII, como o resto do rosto). */
    fun talkingMouth(open: Boolean): String = if (open) "o" else "-"

    /** A pose de uma parte em [ms]: soma das trilhas dessa parte (antes de começar = primeiro ponto; depois = último). */
    fun poseAt(c: Clip, p: Part, ms: Long): Key {
        var rot = 0f; var tx = 0f; var ty = 0f; var sx = 1f; var sy = 1f
        for (t in c.tracks) if (t.part == p) {
            val f = ((ms - t.startMs).toFloat() / t.durationMs).coerceIn(0f, 1f)
            val k = sample(t.keys, ease(t.ease, f))
            rot += k.rot; tx += k.tx; ty += k.ty; sx *= k.sx; sy *= k.sy
        }
        return Key(1f, rot, tx, ty, sx, sy)
    }

    /** Interpolação linear entre os pontos (o tempo já vem com a curva aplicada). */
    fun sample(keys: List<Key>, t: Float): Key {
        if (keys.isEmpty()) return Key(t)
        if (t <= keys.first().t) return keys.first()
        if (t >= keys.last().t) return keys.last()
        val i = keys.indexOfLast { it.t <= t }
        val a = keys[i]; val b = keys[i + 1]
        val u = if (b.t == a.t) 1f else (t - a.t) / (b.t - a.t)
        fun l(x: Float, y: Float) = x + (y - x) * u
        return Key(t, l(a.rot, b.rot), l(a.tx, b.tx), l(a.ty, b.ty), l(a.sx, b.sx), l(a.sy, b.sy))
    }

    /** Curvas: OUT = ease-out suave (cubic-bezier .22,1,.36,1 aproximada), IN_OUT, BOUNCE = passa um pouco e volta. */
    fun ease(e: Ease, t: Float): Float = when (e) {
        Ease.OUT -> 1f - (1f - t).let { it * it * it }
        Ease.IN_OUT -> if (t < .5f) 4f * t * t * t else 1f - (-2f * t + 2f).let { it * it * it } / 2f
        Ease.BOUNCE -> { val c1 = 1.70158f; val c3 = c1 + 1f; 1f + c3 * (t - 1f).let { it * it * it } + c1 * (t - 1f).let { it * it } }
    }
}
