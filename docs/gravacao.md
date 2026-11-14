# Gravação da TV ao vivo: estudo (14/11/2026)

Pedido do dono (14/11/2026): gravar a TV ao vivo no Chromecast, com hub USB, e também em pastas da rede e outros
drives. Antes a gravação era porta fechada; ele reabriu. Este é o estudo curto antes do código.

Legenda das afirmações: **[verificado]** = medido na TV da sala ou rodado aqui; **[conta]** = cálculo a partir de
números conhecidos; **[não verificado]** = depende de teste com pendrive real ou de ler o contrato da lista.

## 1. Onde guardar

| Destino | Serve? | Por quê |
|---|---|---|
| Memória interna da TV | Não (só como último recurso, até 30 min) | 1,8 GB livres de 3,9 GB [verificado, `df` em 14/11]. Um canal HD ocupa 1,3 a 3,6 GB/h [conta]. Encher a memória trava atualizações e apps. |
| Pendrive ou disco no hub USB-C | **Sim, o principal** | Aparece em `/storage/<UUID>` como volume removível; o launcher já escreve lá no backup noturno (`BackupNoturno.pastaPendrive` + acesso a todos os arquivos). Gravar num pendrive na TV ainda **[não verificado]**: na hora do teste não havia nenhum ligado (`sm list-volumes` só mostrou a memória interna). |
| Pasta da rede: SMB | Sim | O smbj já grava (o backup noturno grava no NAS). Taxa de 1 MB/s é folgada para qualquer NAS. |
| Pasta da rede: FTP, SFTP, WebDAV | Sim, numa 2ª etapa | Commons Net (STOR), JSch (put) e WebDAV (PUT) gravam; falta só o "escrever" no molde `PastaArquivos`. |
| NFS | Depois | O nosso NFS (Nfs.kt) só lê; gravar exige WRITE/CREATE/COMMIT (mais RPC). |
| Nuvem (Drive, Dropbox...) | Não para gravar ao vivo | Envio contínuo pela internet da casa e cota. No máximo copiar depois. |

**Sistema de arquivos do pendrive:** FAT32 limita cada arquivo a 4 GB. A gravação vira **pedaços de até 1 hora** (no
máximo ~3,6 GB) com o mesmo nome e "parte 2, 3..."; nosso player emenda os pedaços. exFAT não tem esse limite, mas o
suporte a exFAT no Chromecast **[não verificado]**.

**Espaço livre:** antes de começar e a cada minuto: se faltar espaço para 10 minutos, para, guarda o que tem e avisa.

## 2. Quanto ocupa [conta]

Bitrate de canal IPTV: 3 a 8 Mbps (o mesmo número usado na pausa do ao vivo, `PausaAoVivo.kt`).

| Bitrate | Por hora | Pendrive 32 GB | Pendrive 64 GB |
|---|---|---|---|
| 3 Mbps (SD/HD leve) | 1,35 GB | ~23 h | ~47 h |
| 5 Mbps (HD comum) | 2,25 GB | ~14 h | ~28 h |
| 8 Mbps (Full HD) | 3,6 GB | ~8 h | ~17 h |

O bitrate real dos canais do dono **[não verificado]**: o primeiro teste mede (bytes gravados / tempo).

## 3. Como grava (sem converter nada)

- O canal Xtream vem como `.ts` (MPEG-TS) por um GET HTTP que nunca acaba (`PlayerXtream.kt`). Gravar = copiar esses
  bytes para o arquivo. Zero processamento de vídeo: a CPU 32 bits da TV não sente.
- Canal em HLS (`.m3u8`): baixa os pedaços `.ts` da lista em ordem e emenda no mesmo arquivo (TS emendado continua
  tocando). Lista com pedaços fMP4 (raro em IPTV) fica fora no começo.
- TS é tolerante a corte: se o pendrive sair ou a energia cair, o que já foi escrito toca até o ponto do corte.

## 4. A lista tem 1 conexão: o ponto mais importante

Memória do projeto: a lista do dono permite **1 conexão** por vez. Duas consequências:

1. **Gravar outro canal enquanto assiste não dá.** Quando uma gravação está ativa, abrir outro canal mostra um aviso
   claro ("Gravando Globo até 22h. Para ver outro canal, pare a gravação.") com os botões *Ver o que está gravando* e
   *Parar gravação*. Filmes e séries da mesma lista também usam conexão: mesmo aviso.
2. **Gravar o canal que está assistindo também gastaria 2 conexões** (o player e o gravador pedindo o mesmo canal).
   A saída: **o player toca a partir da gravação**. O gravador é quem baixa o canal; o player lê o arquivo que está
   crescendo (ou um servidor local na TV, 127.0.0.1, que entrega o mesmo fluxo). Uma conexão só.

Bônus do item 2: com o player lendo da gravação, **pausar e voltar no ao vivo** deixa de ter o limite de 2 minutos da
memória (`PausaAoVivo`) e passa a ir até o começo da gravação.

## 5. Agendar

- Pelo guia: **OK segurado num programa futuro** = "Gravar este programa" (com 2 min de folga antes e 5 depois, porque
  o horário do guia atrasa). No canal atual, menu ▼ do player: "Gravar agora" (até o fim do programa ou por 1 h, 2 h).
- O horário dispara por `AlarmManager` exato (o app já tem `USE_EXACT_ALARM`) e a gravação roda num serviço em primeiro
  plano com WakeLock e WifiLock (a TV pode estar com a tela desligada).
- **[não verificado]**: se o Chromecast em espera profunda mantém o Wi-Fi. Primeiro teste: agendar uma gravação de
  5 min com a TV dormindo e conferir o arquivo.
- Conflito: duas gravações no mesmo horário em canais diferentes = impossível com 1 conexão; o app recusa na hora de
  agendar e diz qual é a outra.

## 6. Assistir o que gravou

- Tela "Gravações" (nova): lista por data, com canal, programa (do guia), duração e tamanho; OK toca no nosso player
  (que já toca `.ts` e já guarda "continuar de onde parou"); segurar OK = apagar (com confirmação).
- As gravações também aparecem em "Na rede" › "Ligado na TV" (o pendrive), como qualquer arquivo.

## 7. Contrato da lista

**[não verificado]**: não li os termos do provedor. A API Xtream informa `max_connections` e `exp_date`, não regras de
gravação. Gravar para assistir depois em casa é cópia privada; o launcher não envia gravações para fora da casa nem as
compartilha. Fica a cargo do dono conferir o contrato dele.

## 8. Ordem do código (depois do esboço aprovado)

1. Gravador (puro, com teste JVM contra um servidor HTTP local que simula o canal): copiar fluxo para arquivo, pedaços
   de 1 h, parar sem espaço, retomar se a conexão cair (até 3 tentativas).
2. Destino pendrive + SMB; "Gravar agora" no menu do player; aviso de conexão ocupada.
3. Player lendo da gravação (canal atual sem 2ª conexão) e pausa longa.
4. Agendar pelo guia; tela Gravações; configurações (destino, folgas, apagar gravações com mais de N dias).
5. Destinos FTP/SFTP/WebDAV.

Teste na TV: pendrive ligado no hub, gravação de 5 min do canal atual, depois uma agendada com a TV dormindo.
