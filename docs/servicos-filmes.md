# Serviços de filmes e séries: chaves do aplicativo

O launcher fala com TMDB, Trakt, Simkl, MDBList e OpenSubtitles (código em `ServicosFilmes.kt`, `ServicosContas.kt`,
`ServicosFilmesActivity.kt`, `PainelServicos.kt`). As chaves abaixo são do **aplicativo** (de quem publica o build), não
de quem usa a TV: a pessoa na TV só entra com a própria conta (código no celular) e nunca cola chave nenhuma.
O OpenSubtitles proíbe app que pede a chave ao usuário, por isso é assim.

As chaves nunca vão para o git. No PC: `pessoal.properties` (nomes em minúsculas, ver `pessoal.properties.exemplo`).
No GitHub: secrets do repositório com os nomes abaixo; o `release.yml` passa para o build só no "Build e Release".
Sem uma chave, o serviço some da tela (os outros continuam).

Ela fica dentro do APK (dá para extrair, como em todo app aberto, Kodi incluído). Nunca vai para log.

| Secret | Onde criar | O que copiar |
|---|---|---|
| `TMDB_CHAVE` | themoviedb.org › Configurações › API › Criar (tipo Developer) | "Chave da API" (v3) |
| `TRAKT_ID` (e `TRAKT_SEGREDO` só em app antigo) | developer.trakt.tv › My Apps › New app | Client ID (apps novos não recebem Client Secret: deixe `TRAKT_SEGREDO` sem criar) |
| `SIMKL_ID` | simkl.com/settings/developer › Create new app | Client ID |
| `OPENSUBTITLES_CHAVE` | opensubtitles.com/consumers › New consumer | API Key |

O MDBList não tem chave de aplicativo: a chave é da conta de cada pessoa. É um extra opcional (só as listas
dela, como o Top 250, viram categorias) que a pessoa conecta na TV pelo QR (Configurações › TV e filmes › Serviços de filmes e séries) ou
colando a chave no painel web. A nota do Trakt vale para todos sem login (só o `TRAKT_ID` do app): de madrugada a TV
pergunta ao Trakt (`/search/tmdb/{id}?extended=full`, até 600 títulos por noite, cada nota vale 30 dias).

Fora desde 16/11/2026, porque o app vai ser vendido: **IMDb** (os dados, inclusive a nota que o MDBList repassa, são só
para uso pessoal e não comercial: data.imdb.com e Conditions of Use do imdb.com; licença comercial por contrato) e
**"Onde assistir" da JustWatch** (vinha pelo TMDB; a JustWatch só libera para parceiros com contrato). O código do "Onde
assistir" ficou, desligado em `OndeAssistir.JUSTWATCH_LIBERADO`: ligar só com permissão por escrito do TMDB ou da JustWatch.
Também fora (16/11/2026): as notas de **Rotten Tomatoes, Metacritic e Letterboxd** que vinham pelo MDBList (o MDBList só
repassa; os donos licenciam por contrato: RT/Metacritic via Fandango/Fandom, Letterboxd API só por pedido). O MDBList ficou
só para listas.

Campos dos formulários:
- **Trakt**: App name `One TV Launcher`; App type Development (até 16 pessoas; peça Production antes de vender); Redirect URIs
  `urn:ietf:wg:oauth:2.0:oob`; Allowed origins vazio. O login na TV é por código (device flow em auth.trakt.tv), sem segredo.
- **Simkl**: app novo (AUTH V2); tipo **TV, devices & command line** (login por código no celular, sem endereço de
  retorno). O login antigo por PIN (V1) não serve para apps novos e sai do ar por volta de abril de 2027.
- **TMDB**: tipo de uso "Personal"/não comercial; URL do app: a página do projeto no GitHub.
- **OpenSubtitles**: nome `OneTVLauncher` (é o User-Agent que o app manda).

Salvar no GitHub: repositório › Settings › Secrets and variables › Actions › New repository secret. Nome exatamente
como na tabela, valor = a chave, sem espaço. Depois, rodar o "Build e Release" na main.
