# O que falta para publicar a Sofaly na Play (16/11/2026)

## Depende do Paulo
1. **Fonte do clima** (aberta): Open-Meteo comercial (US$29/mês) ou MET Norway (pronto no código, `-Pclima_met`,
   sem chance de chuva por hora fora da Escandinávia, sem qualidade do ar) ou outra. Até decidir, a tela de Licenças
   da Play diz "[FONTE EM ESTUDO]".
2. **Conta de desenvolvedor da Play** (US$25) e, nela: app `tv.sofaly.launcher`, assinaturas `sofaly_pro` e
   `sofaly_ultra` com preços (docs/play/cobranca.md), Play App Signing com chave de upload nova.
3. **E-mail de suporte** do produto (a Play exige; vai na política de privacidade e na loja).
4. **Teste fechado**: conta pessoal nova exige 12 testadores por 14 dias antes de produção.
5. Licenças: TMDB comercial e Simkl (no build da Play os dois ficam vazios até lá); domínios sofaly.tv / getsofaly.com.
6. Aprovar textos da loja (docs/play/loja.md) para eu traduzir a descrição completa nos outros 14 idiomas.

## Técnico, ainda não feito (eu faço depois)
- **Arquivos / unidade de rede sem MANAGE_EXTERNAL_STORAGE**: no build da Play a aba Arquivos, WebDAV e MTP só veem
  o que o shell próprio alcança; precisa revisar tela por tela.
- **targetSdk**: o app está em 34 (não verifiquei a exigência da Play para TV em 2026; conferir antes do envio).
- **Bibliotecas nativas alinhadas a 16 KB** (libtsnet.so, conscrypt): não verificado.
- **Atualização dentro do app** (In-App Updates API): não feito; hoje o build da Play só diz "atualize pela Play".
- **Ultra "até 3 TVs"**: sem servidor não dá para contar TVs; a Play só garante a mesma conta Google.
- **Plugin Extras**: textos só em inglês e português; ícone provisório; publicar o APK no site (fora da Play).
- **Política de privacidade** (docs/play/privacidade-sofaly.md): publicar no site em en/pt.
- Rodar `-Ptelas` com o build da Play (tela dos planos com botões de compra).

## Degrau 4 (17/11/2026): declarar na Play ao enviar
Nenhuma permissão Android nova. O que vai no formulário "Segurança dos dados" e nas notas ao revisor:
- **Recado em vídeo** (família conectada, opt-in de quem mora): vídeo de até 30 s enviado pela família fica só na TV
  (apagado em 30 dias); nada vai para servidor nosso. Declarar "vídeos" como dado recebido e guardado no aparelho.
- **TVs da família** (opt-in): TVs da mesma conta Tailscale trocam título, posição, pausa e reação. Nenhum vídeo.
- **Som no fone**: servidor HTTP na rede de casa (porta 8097), só IP privado, só enquanto o player está aberto e a
  pessoa pediu; o som é do nosso player (sem MediaProjection, sem outros apps).
- **Mural lido pelo Claude**: no build da Play (`-Pplay`) os recados vão marcados como dados na ferramenta MCP
  "mural" (Mural.paraIa). Na versão de casa ficam como estão (decisão do Paulo, 17/11/2026).
