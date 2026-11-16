# Addons (formato do Stremio, só HTTP)

Decidido em 16/11/2026 (thread "Estudar o Stremio para inspirar a Sofaly", opção "Compatível com Stremio, só HTTP").

## Regras (não afrouxar)

1. **Só vídeo por link direto (http/https).** `AddonsProtocolo.lerFontes` descarta `infoHash`, `magnet:`, `nzbUrl`,
   `ytId`, `externalUrl` e arquivos. Nada de P2P no app.
2. **Recusa na instalação**: `behaviorHints.p2p`, `behaviorHints.adult`, `configurationRequired` e addon sem recurso
   útil (`AddonsProtocolo.Motivo`).
3. **Nenhum addon de terceiros vem instalado e não há loja/lista de addons** no app nem no site. A ficha da Play fica
   neutra (não citar addon, torrent, "filmes grátis").
4. **Instalar só pelo painel web**, com o PIN de novo e o aceite dos termos (`aceite_termos` no prefs `addons`).
   Na TV, Configurações > Addons só lista e remove.
5. **Ids `tt…` (IMDb)**: a ficha vem do addon que tem `meta`; se nenhum tiver, do TMDB `/find` (`AddonsTmdb`). Nunca
   Cinemeta nem dados do IMDb (licença: ver estudo `estudo-stremio`).
6. Cabeçalhos que o addon pede (`proxyHeaders.request`) passam só se forem `User-Agent`, `Referer`, `Origin` ou
   `Accept-Language` (`cabecalhoPermitido`). Cookie e Authorization nunca.
7. Limites: 20 addons, 200 itens por página de catálogo, 60 fontes, 3 MB por resposta, 12 s de prazo, redirecionamento
   nunca de https para http.

## Onde está

- `AddonsProtocolo.kt` (puro, `AddonsProtocoloTest`): manifesto, URLs, catálogo, ficha, fontes, legendas, próximo episódio.
- `AddonsTv.kt`: lista no prefs `addons` (MODE_MULTI_PROCESS, vai no backup completo), rede, perguntas das telas.
- `AddonsActivity.kt` (`:player`): catálogos, busca, episódios, fontes; toca em `VodActivity.abrirAddon` com a posição
  guardada em `addon:<videoId>`; próximo episódio com `Conforto.contagem` e o mesmo `bingeGroup`.
- `PainelAddons.kt` + `assets/painel/addons.js`: `GET /api/addons`, `POST /api/addons/instalar` e `/remover` (PIN).
- QR: botão "Ler QR" do painel (só em https, porta 8444): BarcodeDetector do navegador ou `assets/painel/jsqr.js` (jsQR 1.4.0, Apache 2.0, licença em `jsqr-LICENSE.txt`, carregado só quando precisa). O QR só preenche o campo.
- Termos: `site/terms.html`, seção 4.
