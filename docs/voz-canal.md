# "Coloca na Globo" por voz: estudo (ideia 9, 14/11/2026)

A lógica de trocar de canal por frase já existe (`CanalBusca.interpretarFrase`, `CanalTv.voz`), mas não tem entrada:
o alias de voz saiu numa revisão de segurança porque **qualquer app instalado podia chamá-lo**. A pergunta deste estudo:
existe uma entrada que só o sistema ou o Assistente/Gemini consiga chamar, e que um app comum não consiga forjar?

**Conclusão: não achei caminho que eu consiga justificar como não forjável e que o Gemini da TV de fato use. Nada foi
implementado.** A entrada continua desligada; abaixo, cada candidato e por que ficou de fora.

Legenda das fontes: **li em** = conferi agora na fonte indicada; **da memória** = conhecimento meu, não verifiquei.

## Candidatos

### (a) `MEDIA_PLAY_FROM_SEARCH` / `ACTION_SEARCH`

- É o caminho natural do "tocar Globo no One TV": o Assistente manda `android.media.action.MEDIA_PLAY_FROM_SEARCH`
  com a consulta em `SearchManager.QUERY` para o app nomeado. (da memória, não verifiquei)
- Problema: o intent-filter tem de ser `exported="true"` e o sistema **não** restringe quem envia essas ações. Qualquer
  app faz `startActivity(Intent(MEDIA_PLAY_FROM_SEARCH).setPackage("br.paulo.tvlauncher"))`. (da memória, não verifiquei)
- Sozinho, é exatamente a porta que a revisão fechou. Só serviria combinado com (b) ou (d).

### (b) Activity com `android:permission` que só o Assistente tenha

- O sistema exige que **quem chama** tenha a permissão declarada na activity. Com uma permissão `signature|privileged`
  da plataforma, um app comum (da loja ou instalado por APK) não consegue tê-la. (da memória, não verifiquei)
- Problemas:
  1. Não sei **qual** permissão o app que dispara a ação na TV (Gemini/katniss/googlequicksearchbox/launcherx) tem, e
     não posso conferir na TV nesta tarefa (`dumpsys package` daria a lista). Escolher no escuro = ou a voz não funciona
     (o Assistente recebe SecurityException e cai em outro app), ou escolho uma permissão comum demais.
  2. "Privilegiado" não é "o Assistente": vale para qualquer app de sistema do fabricante. É mais estreito que hoje,
     mas não é a regra que o dono pediu ("conferir quem chamou").
  3. Se o Assistente lançar o intent por um `PendingIntent` ou pelo `system_server`, quem conta é outro chamador.
- Fica como o candidato mais promissor, mas só depois de medir na TV (ver "Próximo passo").

### (c) Busca de canal via TIF (TV Input Framework)

- O Google TV acha canais pelo `TvProvider` (`TvContract.Channels`) que cada `TvInputService` publica; "assistir Globo"
  abre o canal com `ACTION_VIEW` na URI do canal (`content://android.media.tv/channel/N`). (da memória, não verifiquei)
- Problemas: exige o launcher virar uma entrada de TV (`TvInputService` + permissão de escrita no TvProvider), muito
  além desta ideia; e a activity que recebe o `ACTION_VIEW` também é exportada, logo qualquer app poderia mandá-lo.
  Não resolve o "quem chamou".

### (d) Conferir o pacote de quem chamou

- `Intent.EXTRA_REFERRER` / `EXTRA_REFERRER_NAME`: **forjáveis**, quem chama põe o que quiser. `Activity.getReferrer()`
  devolve primeiro esses extras. (da memória, não verifiquei)
- Tirar os dois extras do intent antes de chamar `getReferrer()` faria cair no referrer que o sistema preencheu com o
  pacote chamador. Mas isso depende de detalhe interno (como o sistema valida o pacote declarado pelo chamador, e o que
  vale quando o lançamento vem de `PendingIntent` ou de outro app intermediário), e a documentação diz que o referrer
  é informativo, não garantia de segurança. (da memória, não verifiquei)
- `getCallingPackage()` / `getCallingActivity()`: só valem com `startActivityForResult`; o Assistente não lança assim.
  (da memória, não verifiquei)
- `Activity.getLaunchedFromPackage()` / `getLaunchedFromUid()` (Android 14, API 34): é a API certa (identidade dada
  pelo sistema), **mas** só devolve o pacote se o app que lançou optou por compartilhar a identidade
  (`ActivityOptions.setShareIdentityEnabled(true)`), se for o mesmo uid, se o launcher fosse assinado com a chave da
  plataforma, ou (em versão mais nova) se veio de `startActivityForResult`; senão devolve null. (li em: Microsoft Learn,
  referência de `Activity.LaunchedFromPackage`, espelho da documentação do Android, 14/11/2026)
  Nada indica que o Gemini/Assistente da TV ative esse opt-in; sem ele a resposta é sempre null, ou seja, a voz nunca
  funcionaria.

### Risco extra que vale para qualquer caminho: o "intermediário confuso"

Mesmo conferindo que quem chamou foi o Assistente, um app comum pode, em tese, pedir ao Assistente uma consulta de texto
("tocar Globo no One TV") por algum intent público dele, e o Assistente repassaria. O impacto aqui é baixo (trocar de
canal, já filtrado pelo controle parental e pelas categorias ocultas do player), mas a conferência de pacote não fecha
esse caminho. (da memória, não verifiquei)

## Por que não implementei

A regra pedida era: só ligar com um caminho que eu justifique como não forjável por app comum, com teste JVM da regra.
(a) e (c) são forjáveis; (d) com referrer é forjável ou frágil, e com `getLaunchedFromPackage` não funcionaria sem o
opt-in do Google; (b) talvez sirva, mas depende de dados que só a TV dá. Ligar no escuro enfraqueceria a segurança que a
revisão restaurou, então a entrada continua desligada e `CanalTv.voz` continua sem quem a chame.

## Próximo passo (se o dono quiser seguir)

Com a TV ociosa e sem mexer em nada dela, medir:
1. Que pacote dispara "Ok Google, tocar Globo no One TV" (log `am_start`/`ActivityTaskManager` com o `callingPackage`).
2. Se esse pacote passa `setShareIdentityEnabled` (teste: alias temporário que só registra `getLaunchedFromPackage()`).
3. As permissões `signature|privileged` que esse pacote tem (`dumpsys package <pacote>`).

Com (2) positivo: lista fechada de pacotes do Google + `getLaunchedFromUid()` conferido contra o uid desse pacote, regra
pura e testada. Com só (3): alias com `android:permission` dessa permissão, sabendo que vale para qualquer app de sistema.
Os atalhos de voz das telas (ideia 8) não mudam nada disso: abrem telas, não trocam canal.
