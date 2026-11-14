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
| `TRAKT_ID` e `TRAKT_SEGREDO` | trakt.tv/oauth/applications/new | Client ID e Client Secret |
| `SIMKL_ID` | simkl.com/settings/developer › Create new app | Client ID |
| `OPENSUBTITLES_CHAVE` | opensubtitles.com/consumers › New consumer | API Key |
| `MDBLIST_CHAVE` | mdblist.com/preferences › API Key | a chave (opcional: dá notas a todas as TVs com a cota de 1.000 consultas/dia dessa conta) |

Campos dos formulários:
- **Trakt**: Name `One TV Launcher`; Redirect uri `urn:ietf:wg:oauth:2.0:oob`; Javascript origins vazio; marque
  `/checkin` e `/scrobble`.
- **Simkl**: Name `One TV Launcher`; Redirect URI `urn:ietf:wg:oauth:2.0:oob` (o login por PIN não usa, mas o
  formulário pede).
- **TMDB**: tipo de uso "Personal"/não comercial; URL do app: a página do projeto no GitHub.
- **OpenSubtitles**: nome `OneTVLauncher` (é o User-Agent que o app manda).

Salvar no GitHub: repositório › Settings › Secrets and variables › Actions › New repository secret. Nome exatamente
como na tabela, valor = a chave, sem espaço. Depois, rodar o "Build e Release" na main.
