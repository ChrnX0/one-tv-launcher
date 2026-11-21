package br.paulo.tvlauncher.weather

import android.content.Context
import br.paulo.tvlauncher.alarm.FaixaDias
import br.paulo.tvlauncher.player.ServicosContas
import br.paulo.tvlauncher.settings.Planos

/**
 * De onde vêm os dados de fora (19/11/2026, regra do Paulo): "o que existir de alternativa grátis hoje usa para o plano
 * básico, o que for pago começa no pro e para o ultra ficam os mais especiais". Ponto único: nenhuma tela escolhe a
 * fonte sozinha. Cada fonte paga tem DUAS travas: o plano ([Planos]) e a licença/contrato (as constantes abaixo). Sem a
 * licença, todo mundo fica na grátis, inclusive a Sala (que é Ultra por não ter venda ativa).
 *
 * | Dado     | Básico (grátis, uso comercial liberado)       | Pro (pago)                          | Ultra (especial)                 |
 * |----------|-----------------------------------------------|-------------------------------------|----------------------------------|
 * | Clima    | MET Norway (api.met.no/doc/FAQ)               | WeatherAPI pelo api.sofaly.tv       | Open-Meteo pago: 3 modelos + ar  |
 * | Fichas   | Trakt (developer.trakt.tv/docs/api-use-policy)| TMDB (contrato comercial)           | (igual ao Pro)                   |
 * | Simkl    | nota de quem conectou a própria conta         | nota para todos (licença US$ 100/mês)| (igual ao Pro)                  |
 *
 * Puro (DataSourcesTest), menos as funções que recebem Context.
 */
object DataSources {
    /** Open-Meteo pago (mediana de ECMWF/ICON/GFS + qualidade do ar). O grátis deles é só não comercial. Sem contrato. */
    const val OPEN_METEO_LICENSED = false
    /**
     * WeatherAPI: a chave fica no Worker (secret WEATHERAPI_KEY). Pode ficar ligado sem a chave: o servidor responde 503
     * e a TV volta ao MET Norway e só pergunta de novo horas depois ([WeatherApiSource]).
     */
    const val WEATHERAPI_ENABLED = true
    /** TMDB comercial: só com contrato escrito (themoviedb.org/api-terms-of-use). A chave pessoal de hoje não serve. */
    const val TMDB_LICENSED = false
    /** Simkl: nota para quem não conectou a conta exige a licença paga (Simkl, 17/11/2026). */
    const val SIMKL_LICENSED = false

    enum class Weather { MET_NORWAY, WEATHERAPI, OPEN_METEO }

    fun weather(plano: Planos.Plano, openMeteoLicensed: Boolean = OPEN_METEO_LICENSED,
                weatherApiEnabled: Boolean = WEATHERAPI_ENABLED, weatherApiDown: Boolean = false): Weather = when {
        openMeteoLicensed && Planos.permite(plano, Planos.Recurso.CLIMA_3_MODELOS) -> Weather.OPEN_METEO
        weatherApiEnabled && !weatherApiDown && Planos.permite(plano, Planos.Recurso.CLIMA_PRO) -> Weather.WEATHERAPI
        else -> Weather.MET_NORWAY
    }

    fun weather(ctx: Context): Weather = weather(Planos.atual(ctx), weatherApiDown = WeatherApiSource.emPausa())

    /** Fichas do TMDB: só com contrato E plano Pro E a chave no build. Fora disso, as fichas vêm do Trakt. */
    fun tmdb(plano: Planos.Plano, temChave: Boolean, licensed: Boolean = TMDB_LICENSED): Boolean =
        licensed && temChave && Planos.permite(plano, Planos.Recurso.FICHAS_TMDB)

    fun tmdb(ctx: Context): Boolean = tmdb(Planos.atual(ctx), ServicosContas.chavesApp().tmdb.isNotBlank())

    /** Nota do Simkl para quem NÃO conectou a conta: licença + Pro. Quem conectou vê a nota em qualquer plano. */
    fun simklSemLogin(plano: Planos.Plano, licensed: Boolean = SIMKL_LICENSED): Boolean =
        licensed && Planos.permite(plano, Planos.Recurso.SIMKL_NOTA_TODOS)

    fun simklSemLogin(ctx: Context): Boolean = simklSemLogin(Planos.atual(ctx))

    /**
     * Previsão no formato da Open-Meteo para a fonte do aparelho, ou null quando a fonte é a Open-Meteo paga (quem chama
     * monta o pedido dela, como sempre fez) ou quando nada respondeu. A WeatherAPI que falhar cai no MET na mesma hora.
     */
    fun forecast(ctx: Context, lat: Double, lon: Double, desdeAgora: Boolean, dias: Int = FaixaDias.DIAS): org.json.JSONObject? =
        when (weather(ctx)) {
            Weather.OPEN_METEO -> null
            Weather.WEATHERAPI -> WeatherApiSource.previsao(lat, lon, desdeAgora, dias)
                ?.let { wa -> if (WeatherApiSource.dias(wa) >= dias) wa else WeatherApiSource.completar(wa, ClimaMet.previsao(lat, lon, desdeAgora, dias)) }
                ?: ClimaMet.previsao(lat, lon, desdeAgora, dias)
            Weather.MET_NORWAY -> ClimaMet.previsao(lat, lon, desdeAgora, dias)
        }
}
