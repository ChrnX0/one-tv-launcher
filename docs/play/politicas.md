# Versão da Play enxuta (decisão do Paulo, 18/11/2026)

O que pode derrubar o app na Play não vai no pacote da Play (`-Pplay`). A Sala e o APK do site não mudam: rodam o
mesmo app com tudo liberado (o "Extras"). Ponto único no código: `PlayPolicy.kt` (testes em `PlayPolicyTest`).
Estudo com fontes: https://claude.ai/artifact/YZ254JNVm2MVFdguRvj4Jp

| Passo | O que sai da Play | Por quê (política da Play) | Estado |
| --- | --- | --- | --- |
| 1 | IA (MCP) agindo pela acessibilidade: `elementos_na_tela`, `tocar_elemento`, teclas e texto da IA pelo serviço | Acessibilidade: ação autônoma proibida | feito (18/11) |
| 2 | Instalar APK pelo shell, congelar apps, emprestar Shizuku/Dhizuku, ler o código de pareamento na tela | Abuso de dispositivo e rede; acessibilidade | feito (18/11) |
| 3 | IPTV (Xtream/M3U), TV de volta, gravação, add-ons que tocam vídeo (`stream`); catálogos ficam | Propriedade intelectual (denúncias) | feito (18/11) |
| 4 | Código do atualizador fora do AAB; roteiro do vídeo de acessibilidade; textos da loja | Abuso de dispositivo (análise do binário); declaração | feito (18/11), falta conferir o AAB |

Regra de ouro: o app da Play nunca linka, mostra QR ou manda instalar o Extras ou qualquer APK de fora da Play
(foi o que derrubou o Rootless Pixel Launcher em 2018).

## Vídeo da declaração de acessibilidade (Play Console, até 30 s, gravado na TV)

1. Abrir a Sofaly já instalada (a tela inicial aparece). Mostrar o aviso da Sofaly que explica o serviço e o "Concordo"
   apertado no controle; só então a tela de Acessibilidade do Android abre e a pessoa liga a Sofaly.
2. Apertar HOME no controle num app qualquer (ex.: YouTube): volta para a Sofaly, não para a tela do Google.
3. Segurar uma tecla do controle configurada como atalho: o app favorito abre.
4. Encerrar na tela inicial. Não mostrar IA, painel web nem nada que mexa em outro app.

## Ainda falta antes de enviar

- Loja e privacidade dizem "não temos servidor": hoje existe `api.sofaly.tv` (lista de add-ons recomendados, busca de
  podcasts). Reescrever as duas antes do envio e declarar no formulário de Segurança dos dados.
- Gerar o AAB (`./gradlew bundleRelease -Pplay`) e conferir no dex que `PackageInstaller.Session`, `pm install` e
  `pm disable-user` não aparecem por caminho vivo.
