# Modelo de ameaças (elevação 2.5, 17/11/2026)

Para quem mexe no código: o que defender, de quem, por onde entram, o que já protege e o que o fuzz cobre.
Atualizar quando surgir uma porta, um leitor de formato ou um poder novo.

## O que vale proteger

| Bem | Por que importa |
|---|---|
| Device Owner (`PrivilegioDO`) | Esconde apps, dá permissões, reinicia, apaga dados de outro app. Perder o controle dele = TV dominada. |
| Shell próprio (uid 2000, `GuardiaoShizuku`) | `pm`, `cmd`, `settings`: quase tudo na TV. |
| PIN do painel e cookie de sessão | Abrem o painel inteiro (e o WebDAV usa o mesmo PIN). |
| Tokens (Drive, Home Assistant, Trakt, Tailscale) | Contas da pessoa fora da TV. |
| Fotos, recados, diário, uso da TV | Privacidade da família. |
| Atualização (Releases do GitHub) | Uma APK falsa com a nossa assinatura chega em todas as TVs. |

## Quem ataca

1. **Aparelho da mesma rede** (visita no Wi-Fi, celular ou IoT comprometido): fala com as portas 8080, 8444, 15740,
   SSDP/mDNS. É o atacante mais provável.
2. **Site aberto no navegador de alguém da casa**: tenta CSRF/DNS rebinding contra o painel.
3. **Internet pelo túnel** (Tailscale Funnel, só quando ligado): MCP com OAuth, link da família, recado por link.
4. **Conteúdo de terceiros**: lista M3U, guia XMLTV, addon, feed, legenda, arquivo de vídeo, nome de arquivo do PC.
   Chega mesmo sem ninguém mal-intencionado na casa.
5. **App instalado na TV**: tenta usar os providers (Dhizuku, plugins), broadcasts ou o shell emprestado.
6. **Quem rouba a conta do GitHub**: tenta publicar uma versão.

## Portas e defesas que já existem

| Entrada | Defesa | Onde |
|---|---|---|
| Painel HTTP 8080 / HTTPS 8444 | Só LAN; Host/Origin; `X-Painel: 1` no POST; cookie HttpOnly SameSite=Strict; PIN PBKDF2 com bloqueio crescente; PIN de novo no que não volta; limites de cabeçalho (8 KB), corpo e tempo (10 s/30 s) | `PainelWeb`, `PainelHttp`, `PainelSeguranca` |
| WebDAV `/dav/` | Mesmo PIN e bloqueio; caminho relativo conferido (sem `..`); chunked com limite | `PainelDav`, `DavLogica` |
| MTP 15740 | Só LAN; "Permitir?" na TV por computador; pacote de controle até 1 MB | `MtpIp`, `MtpNaRede` |
| MCP 127.0.0.1:8091 | Só local; fora, só pelo túnel; OAuth 2.1 com PKCE; catálogo fechado de ações | `McpServidor`, `McpAutorizacao` |
| Providers Dhizuku/plugins | PIN na TV por app; `FiltroDpm`/`RegrasDono` (nunca wipe, nunca o nosso pacote); plugin preso ao certificado | `DonoEmprestado`, `PluginsTv` |
| Broadcasts adb | Protegidos por `android.permission.DUMP` | `ControleShizukuAdbReceiver` |
| Guia XMLTV | DOCTYPE desligado (sem XXE); teto de tamanho; erro no meio fica com o que leu | `Xmltv` |
| Atualização | Assinatura própria; Release só à mão na `main`, chave no Environment com aprovação; tag `v<versionCode>` | `release.yml`, `Atualizador` |
| Diário/desfazer | Segredos (PIN, senha, token) nunca entram no diário nem nos passos de desfazer | `DesfazerRegras` |

## Fuzz (o que roda em todo PR)

`FuzzLeitoresTest` (JVM, entra na suíte que roda em todo PR desde a elevação 21.1) estraga entradas boas de cada leitor
escrito à mão e exige: **ou lê, ou recusa com IOException** (no XMLTV, também XmlPullParserException), **nunca outra
exceção** e **nunca mais de 2 s**.

| Leitor | Alvo no código |
|---|---|
| HTTP do painel (cabeçalhos, Content-Length, chunked) | `PainelHttp.lerCabecalhos`, `Requisicao.lerCorpo` |
| WebDAV (PROPPATCH, LOCK, Destination, Basic, datas, chunked) | `DavLogica.*`, `DavLogica.LeitorChunked` |
| MTP / PTP-IP (ObjectInfo, lista de propriedades, pacotes) | `Mtp.lerInfoObjeto`, `Mtp.lerListaProps`, `PtpIp.ler` |
| Lista M3U | `M3u.ler`, `M3u.guiaDaLista` |
| Guia XMLTV e guia extra | `Xmltv.lerEmFluxo`, `Xmltv.lerExtraEmFluxo` |
| Feeds de podcast | entram quando o #189 (Ouvir) for mesclado |

1.500 entradas por leitor no CI (menos de 1 s). Rodada longa: `FUZZ_ITERACOES=200000 FUZZ_SEMENTE=7`.
**Achado na primeira rodada:** `&#EXTM3U;` num título do guia fazia o kxml lançar NumberFormatException, que escapava
do leitor e jogava fora o guia inteiro. Corrigido no mesmo commit (vale como arquivo com defeito).

## O que ainda não está coberto

- Parser de vídeo/legenda: é do ExoPlayer/Media3 (fuzz deles). Nosso código só escolhe a faixa.
- JSON de addons, Jellyfin, Plex, Xtream: `org.json` recusa com JSONException; falta conferir que todo ponto de
  leitura trata isso (próxima rodada do fuzz).
- Teste de invasão feito por gente de fora: ver abaixo.

## Teste de invasão profissional (decisão do Paulo)

Escopo sugerido: APK (Android TV) + painel web (8080/8444) + WebDAV + MTP + MCP/túnel + providers Dhizuku/plugins,
caixa cinza (com código), 5 a 8 dias de trabalho.

Faixa de preço publicada em 2026 (não pedi orçamento):
- app móvel de uma plataforma: US$ 5.000 a 9.000 (li em bluefire-redteam.com/mobile-application-penetration-testing-pricing-cost-instant-quote/);
- app móvel em geral: US$ 7.000 a 35.000 (li em stingrai.io/blog/mobile-app-penetration-testing-cost-2026);
- app web ou API sozinho: US$ 3.000 a 18.000 (li em blog.ostorlab.co/application-penetration-test-cost-2026.html).

Com app + painel + protocolos, a faixa realista é **US$ 8.000 a 20.000** (estimativa minha a partir dessas faixas).
Alternativa mais barata e aberta: programa de recompensa por falha depois da venda (paga só o que acharem).
