# Estudos: lâmpadas Tuya sem Home Assistant (ideia 53) e som da TV no celular (ideia 74)

14/11/2026. Só estudo: nenhuma linha de código saiu daqui. Cada afirmação diz de onde veio.

## 53. Lâmpadas Tuya direto, sem Home Assistant

**Resposta curta:** dá para fazer, mas a parte difícil não é o código, é a chave de cada lâmpada. Tamanho real: G.

O que se sabe (li no README do [tinytuya](https://github.com/jasonacox/tinytuya), a biblioteca que o Home Assistant
local também usa como referência):

- As lâmpadas falam na rede de casa por **TCP 6668** e se anunciam por **UDP 6666, 6667 e 7000**. Versões do
  protocolo: 3.1 a 3.5.
- Cada aparelho tem uma **local key**. Sem ela não se manda nada. Ela só sai da **nuvem da Tuya**: conta de
  desenvolvedor em iot.tuya.com, projeto de nuvem, "Link Tuya App Account" lendo um QR com o app Smart Life, e os
  serviços IoT Core e Authorization autorizados (o IoT Core é um período de teste que precisa ser renovado).
- **Resetar ou parear de novo a lâmpada troca a local key**: os passos acima se repetem.
- Lâmpada RGB da versão 3.3: DP 20 liga e desliga, DP 22 é o brilho (10 a 1000), DP 23 a temperatura da cor (0 a 1000).

O que o launcher teria de ter (inferência minha, não verificado):

1. O protocolo em Kotlin: 3.3 é AES-ECB com a local key e CRC32; 3.4 e 3.5 negociam uma chave de sessão
   (HMAC-SHA256, e a 3.5 usa AES-GCM). Dá para testar na JVM com pacotes gravados do tinytuya.
2. A busca da local key pela API de nuvem da Tuya, uma vez, com o ID e o segredo do projeto que o dono criou. Isso
   é um segundo "cada dono cria o seu", como o cliente OAuth do Google. A chave ficaria cifrada no Keystore, como o
   token do Home Assistant.
3. Descoberta pelo UDP (o broadcast 6667 vem cifrado com uma chave fixa conhecida) e cartões na tela Casa ao lado
   dos do Home Assistant.

Riscos: (a) a Tuya muda o protocolo de tempos em tempos (já foram cinco versões); (b) firmware novo pode chegar pela
nuvem e trocar a versão; (c) o período de teste do IoT Core expira e a busca da chave para de funcionar (a lâmpada
continua funcionando localmente com a chave já guardada).

**Minha recomendação:** não fazer agora. O Home Assistant já fala com as lâmpadas e a tela Casa já mostra tudo o que
ele tem (ideias 50 a 52). Se o problema é o HA depender de um PC ligado, resolver isso (HA num Raspberry Pi ou no
próprio roteador) custa menos que manter um cliente Tuya. Se o dono quiser mesmo assim: começar só pela versão que
as lâmpadas usam (o `python -m tinytuya scan` no PC diz qual).

## 74. Ouvir a TV no celular, ou tela apagada com som

**Resposta curta:** "tela apagada com som" o launcher não consegue na LG; "ouvir no celular" só dá para o player
próprio do launcher (IPTV), não para Netflix e outros apps.

### Tela apagada com som

- O Chromecast manda imagem pelo HDMI; quem acende o painel é a LG. Uma tela preta do launcher deixa a luz de fundo
  acesa (inferência; é como todo painel LCD funciona).
- Desligar a LG pelo HDMI-CEC põe a TV em espera, e em espera ela não toca som (não verifiquei).
- Muitas LG têm no menu de energia uma opção "Tela desligada" que apaga o painel e mantém o som. Não verifiquei se a
  TV tem, nem se o CEC alcança essa opção (o CEC não tem comando padrão para isso, até onde sei).

Caminho possível: o dono procura no menu da LG (Configurações > Imagem > Economia de energia) e, se existir, usa
pelo controle da LG. Do lado do launcher não há o que fazer.

### Som da TV no celular

- **Apps dos outros (Netflix, YouTube, IPTV de terceiros):** o Android só deixa capturar o som de outro app pela
  `AudioPlaybackCapture` (Android 10+), que pede a autorização de projeção de tela na TV a cada sessão e respeita o
  app que proíbe a captura. Não verifiquei quais apps proíbem; os com DRM costumam proibir.
- **O player próprio do launcher (IPTV):** o som já passa pelo media3 dentro do nosso processo, então dá para copiar
  o áudio decodificado (um `AudioProcessor` no ExoPlayer) e mandar ao painel no celular, sem permissão nenhuma e sem
  segunda conexão com o provedor (listas IPTV costumam aceitar 1). Custo: M a G (codificar em Opus ou AAC, mandar por
  WebSocket ou WebRTC, atraso de 0,5 a 2 s entre imagem e som). Não verifiquei o atraso.
- Abrir o mesmo canal no celular direto do provedor gastaria a segunda conexão, que muitas listas não dão.

**Minha recomendação:** se valer a pena, só o "ouvir o canal de TV ao vivo no celular" pelo player próprio, como
ideia própria e medida antes (atraso aceitável?). O resto não depende do launcher.
