package br.paulo.tvlauncher.screensaver

import br.paulo.tvlauncher.R
import br.paulo.tvlauncher.player.LojaCurada

/**
 * Vitrine de plugins (18/11/2026): a lista ESCOLHIDA por nós de plugins que funcionam no Sofaly, com onde conseguir cada
 * um. Nada de busca livre. Fontes: a página pública de plugins do ecossistema de TV e os repositórios de cada projeto
 * (lidos em 18/11/2026). Pacotes Android marcados [pacote] foram lidos na Play ou no repositório; null = não conferido
 * (o "instalado" vem do que a loja guardou ao instalar).
 *
 * Na TV: Play Store quando o plugin está lá ([play]); senão, no APK do site, instala direto do Release oficial pela
 * [LojaCurada] (mesma conferência de assinatura); sempre com um QR para a página. O build da Play nunca baixa APK.
 * Só a lista e as regras puras (PluginCatalogTest).
 */
object PluginCatalog {
    enum class Tipo { FUNDO, ICONES }

    class Entrada(
        val id: String, val tipo: Tipo, val nome: String, val descricao: Int,
        val pacote: String? = null, val play: String? = null, val repo: String? = null,
    ) {
        /** Id na LojaCurada (só quem tem repositório e um APK que serve para TV). */
        val lojaId: String? get() = repo?.let { "pl-$id" }
    }

    val LISTA = listOf(
        Entrada("aerialviews", Tipo.FUNDO, "Aerial Views", R.string.wp_d_aerial, "com.neilturner.aerialviews", play = "com.neilturner.aerialviews"),
        Entrada("overflight", Tipo.FUNDO, "Overflight", R.string.wp_d_overflight, "tv.projectivy.plugin.wallpaperprovider.overflight",
            play = "tv.projectivy.plugin.wallpaperprovider.overflight", repo = "spocky/projectivy-plugin-wallpaper-overflight"),
        Entrada("dailyphotos", Tipo.FUNDO, "Daily Photos", R.string.wp_d_daily, repo = "spocky/projectivy-plugin-wallpaper-bingwallpaper"),
        Entrada("weather", Tipo.FUNDO, "Weather Wallpaper", R.string.wp_d_weather, repo = "shortusername/projectivy-weather-wallpaper"),
        Entrada("movieart", Tipo.FUNDO, "Movie Art", R.string.wp_d_movieart, "com.danielkorgel.projectivy.plugin.cinemaglow",
            play = "com.danielkorgel.projectivy.plugin.cinemaglow"),
        Entrada("sofadrop", Tipo.FUNDO, "Sofadrop", R.string.wp_d_sofadrop, repo = "rfmarcos/projectivy-plugin-sofadrop"),
        Entrada("immich", Tipo.FUNDO, "Immich Wallpaper", R.string.wp_d_immich, repo = "raizer88/immich-projectivy-provider"),
        // O app se chama "Projectivy Icon Pack": aqui e na nossa tela mostramos um nome neutro (regra: nada de nome de concorrente).
        Entrada("tvicons", Tipo.ICONES, "TV Icon Pack", R.string.wp_d_tvicons, "dev.projectivy.blueprint.app", repo = "SicMundus86/ProjectivyIconPack"),
        Entrada("minimalist", Tipo.ICONES, "Minimalist Icon Pack", R.string.wp_d_minimalist, repo = "hqn-scl/android-tv-minimalist-icon-pack"),
        // Arcticons: traço fino, combina com o estilo Linha da cruz. O Release tem vários APKs (claro/escuro): só pela página.
        Entrada("arcticons", Tipo.ICONES, "Arcticons", R.string.wp_d_arcticons, "com.donnnno.arcticons", play = "com.donnnno.arcticons"),
    )

    /** Nome que mostramos para um pacote instalado que está na vitrine (o rótulo do app pode citar outro launcher). */
    fun nomeDoPacote(pacote: String): String? = LISTA.firstOrNull { it.pacote == pacote }?.nome

    /** Página para o QR: a da Play quando existe, senão o último Release no GitHub. */
    fun pagina(e: Entrada): String? = when {
        e.play != null -> "https://play.google.com/store/apps/details?id=${e.play}"
        e.repo != null -> "https://github.com/${e.repo}/releases/latest"
        else -> null
    }

    /** O que o botão principal faz neste build. */
    enum class Acao { PLAY, INSTALAR, SO_QR }

    fun acao(e: Entrada, buildPlay: Boolean, temPlayStore: Boolean): Acao = when {
        e.play != null && temPlayStore -> Acao.PLAY
        !buildPlay && e.lojaId != null -> Acao.INSTALAR
        else -> Acao.SO_QR
    }

    /** Itens para a LojaCurada (painel e TV): só os com repositório. A descrição do painel fica em português. */
    fun itensLoja(): List<LojaCurada.Item> = LISTA.mapNotNull { e ->
        e.lojaId?.let { LojaCurada.Item(it, e.nome, e.repo!!, if (e.tipo == Tipo.FUNDO) "Plugin de fundo" else "Pacote de ícones") }
    }
}
