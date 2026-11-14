// Painel em inglês (ideia 76, 14/11/2026). Gerado uma vez por script a partir dos textos em português do painel;
// daqui em diante edite aqui: texto novo no painel sem entrada em T (ou padrão em P) aparece em português.
// Como funciona: se o navegador NÃO está em português, troca cada texto da página (e o que os scripts escrevem
// depois, por um MutationObserver) pelo da tabela. Só textos visíveis, placeholder, aria-label, title e alt;
// nunca o que a pessoa digitou. Mensagens que vêm da TV (erros do servidor) continuam como a TV manda.
(function () {
  'use strict';
  const idioma = (navigator.languages && navigator.languages[0]) || navigator.language || 'pt';
  if (/^pt/i.test(idioma)) return;
  document.documentElement.lang = 'en';
  const T = {
"\"Coloca o último episódio de Round 6 na Netflix.\"": "\"Put on the latest episode of Squid Game on Netflix.\"",
"\"Cria um despertador às 6:30 nos dias úteis.\"": "\"Set an alarm for 6:30 on weekdays.\"",
"\"Faz um checkup da TV e da internet.\"": "\"Run a checkup of the TV and the internet.\"",
"\"Instala este APK: https://...\" e \"Atualiza o launcher.\"": "\"Install this APK: https://...\" and \"Update the launcher.\"",
"\"Manda um recado na TV: o jantar está pronto.\"": "\"Send a message to the TV: dinner is ready.\"",
"\"O que está passando na TV?\" (precisa de \"Ver a tela pelo Claude\" ligado em Extras)": "\"What's on the TV?\" (needs \"Let Claude see the screen\" on in Extras)",
"\"Põe a TV para dormir.\" / \"Aumenta o volume.\"": "\"Put the TV to sleep.\" / \"Turn up the volume.\"",
"(o painel sem cadeado continua funcionando). Use isto se o certificado venceu, se o IP da TV mudou ou se um aparelho se perdeu.": "(the panel without the padlock keeps working). Use this if the certificate expired, the TV's IP changed or a device lost it.",
"+ Adicionar ação": "+ Add action",
", força": ", strength",
", volume até": ", volume up to",
"0 selecionados": "0 selected",
"1 min na tela": "1 min on screen",
"30 dias": "30 days",
"30 s na tela": "30 s on screen",
"5 min na tela": "5 min on screen",
"7 dias": "7 days",
": início": ": start",
"A TV": "The TV",
"A TV ainda está gerando o certificado. Volte em 1 minuto.": "The TV is still creating the certificate. Come back in 1 minute.",
"A TV ainda não deu acesso às sessões de mídia (o shell do launcher precisa estar de pé).": "The TV has no access to media sessions yet (the launcher's shell must be running).",
"A TV avisa no celular: lembrete de jogo, alarme perdido, TV ligada de madrugada, erro, pedido de ajuda. Funciona com o painel aberto pelo endereço https (Tailscale, porta 8443) e o Chrome do Android. O aviso sai cifrado da TV.": "The TV notifies your phone: game reminder, missed alarm, TV on late at night, error, help request. Works with the panel opened at the https address (Tailscale, port 8443) in Chrome on Android. The notification leaves the TV encrypted.",
"A TV desliga e liga de novo (cerca de 1 min). O painel volta sozinho depois.": "The TV turns off and on again (about 1 min). The panel comes back by itself.",
"A TV entra na sua rede Tailscale e publica só o MCP na internet.": "The TV joins your Tailscale network and publishes only the MCP on the internet.",
"A TV está sem conta Google (ou o launcher está sem a permissão de ver as contas).": "The TV has no Google account (or the launcher can't see the accounts).",
"A TV fala direto com o Home Assistant da casa (sem nuvem): luzes e cenas aqui, na TV (Extras > Casa) e pelo Claude. Ela também aparece lá como sensor (ligada/dormindo, app aberto). O token fica cifrado na TV. Configurar pede o PIN.": "The TV talks directly to your Home Assistant (no cloud): lights and scenes here, on the TV (Extras > Home) and through Claude. It also shows up there as a sensor (on/sleeping, open app). The token stays encrypted on the TV. Setting it up asks for the PIN.",
"A TV guarda sozinha uma cópia por dia (as últimas 7), só quando algo mudou. Baixe uma para restaurar em outra TV, ou volte esta TV para o estado daquele dia.": "The TV keeps one copy a day by itself (the last 7), only when something changed. Download one to restore on another TV, or take this TV back to that day.",
"A TV ligou": "The TV turned on",
"A TV manda cada linha nova das origens marcadas (POST com JSON) para este endereço: https, ou http só dentro de casa (ex.: um webhook do Home Assistant). Mudar pede o PIN. Vazio desliga.": "The TV sends each new line from the checked sources (POST with JSON) to this address: https, or http only inside the house (e.g. a Home Assistant webhook). Changing it asks for the PIN. Empty turns it off.",
"A TV não criou a chave dos avisos. Tente de novo.": "The TV didn't create the notification key. Try again.",
"A TV não está no Wi-Fi (no cabo ou desconectada): nada a ajustar no Wi-Fi.": "The TV isn't on Wi-Fi (wired or disconnected): nothing to adjust on Wi-Fi.",
"A TV passa a responder pela internet (Tailscale Funnel). Quem entrar precisa do PIN.": "The TV starts answering over the internet (Tailscale Funnel). Whoever signs in needs the PIN.",
"A TV sai da rede Tailscale. Para voltar, cole uma chave nova.": "The TV leaves the Tailscale network. To come back, paste a new key.",
"A TV só digita letras, números, espaço e . , @ _ -": "The TV only types letters, numbers, space and . , @ _ -",
"A TV vai": "The TV will",
"A TV vai chamar": "The TV will call",
"A conexão com a TV caiu no meio do envio (rede, ou a TV recusou o arquivo).": "The connection to the TV dropped during the upload (network, or the TV refused the file).",
"A foto da tela não chegou inteira. Tentando de novo…": "The screenshot didn't arrive complete. Trying again…",
"A imagem da bolinha do perfil, no alto da tela da TV. Escolha qualquer foto: a TV recorta um quadrado do meio e guarda só nela. JPG, PNG ou WebP, até 8 MB.": "The profile picture at the top of the TV screen. Pick any photo: the TV crops a square from the middle and keeps it only on the TV. JPG, PNG or WebP, up to 8 MB.",
"A leitura está desligada.": "Reading is off.",
"A semana ainda não foi montada (domingo, 20h).": "The week hasn't been put together yet (Sunday, 8 PM).",
"A tela da TV está apagada: o recado aparece quando ela ligar.": "The TV screen is off: the message shows when it turns on.",
"Abra o painel pelo endereço de sempre (o IP da TV ou tv:8080).": "Open the panel at the usual address (the TV's IP or tv:8080).",
"Abrindo na TV.": "Opening on the TV.",
"Abrindo no YouTube da TV.": "Opening in YouTube on the TV.",
"Abrir": "Open",
"Abrir a pasta": "Open folder",
"Abrir app": "Open app",
"Abrir com cadeado": "Open with padlock",
"Abrir na TV": "Open on TV",
"Abrir neste aparelho": "Open on this device",
"Abrir o controle completo e a tela ao vivo →": "Open the full remote and the live screen →",
"Abrir o controle e a tela ao vivo": "Open the remote and the live screen",
"Abrir o painel pelos aparelhos da minha conta Tailscale e deixar o Claude ver a lista deles (só a lista; não mexe neles)": "Open the panel from the devices on my Tailscale account and let Claude see their list (only the list; it doesn't touch them)",
"Abrir pela rede Tailscale?": "Open over the Tailscale network?",
"Abrir tela": "Open screen",
"Acesso remoto": "Remote access",
"Acessos": "Sign-ins",
"Acordar": "Wake",
"Acordar a TV": "Wake the TV",
"Acordar o computador": "Wake the computer",
"Adicione pelo menos uma ação.": "Add at least one action.",
"Agenda de todas as contas Google da TV juntas": "Calendar of all the TV's Google accounts together",
"Agora na TV": "On the TV now",
"Ainda não configurado.": "Not set up yet.",
"Ainda rodando na TV: o resultado aparece em \"última vez\" daqui a pouco.": "Still running on the TV: the result shows in \"last time\" shortly.",
"Ainda sem medições: o launcher mede a cada": "No measurements yet: the launcher measures every",
"Anterior": "Previous",
"Apaga a pasta e TUDO o que está dentro dela, na TV. Não tem volta.": "Deletes the folder and EVERYTHING inside it, on the TV. There's no undo.",
"Apaga login, configurações e downloads do app, como se tivesse acabado de instalar. Não tem volta.": "Deletes the app's sign-in, settings and downloads, as if just installed. There's no undo.",
"Apaga o arquivo da TV. Não tem volta.": "Deletes the file from the TV. There's no undo.",
"Apaga o endereço, a chave guardada, o certificado e a lista de aparelhos conhecidos.": "Deletes the address, the saved key, the certificate and the list of known devices.",
"Apagar": "Delete",
"Apagar cena": "Delete scene",
"Apagar diário": "Delete diary",
"Apagar endereço": "Delete address",
"Apagar o endereço e o token do Home Assistant da TV?": "Delete the Home Assistant address and token from the TV?",
"Apagar todo o diário de DNS guardado na TV?": "Delete the whole DNS diary stored on the TV?",
"Apagar uma letra": "Delete a letter",
"Apagar…": "Delete…",
"Aparece no protetor de tela (chave \"Mural\" ligada): lista de compras, até 3 recados fixos e os aniversários de hoje da agenda.": "Shows on the screensaver (\"Board\" switch on): shopping list, up to 3 pinned messages and today's birthdays from the calendar.",
"Aparelho da casa": "Home device",
"Aparelhos conectados": "Connected devices",
"Aperte OK no controle da TV para confirmar (ou Cancelar).": "Press OK on the TV remote to confirm (or Cancel).",
"Apps abertos, baixados do GitHub oficial de cada projeto. Na 1ª instalação a TV guarda quem assinou; depois só aceita a mesma assinatura. Pede o PIN.": "Open-source apps, downloaded from each project's official GitHub. On the 1st install the TV remembers who signed it; after that it only accepts the same signature. Asks for the PIN.",
"Apps da TV vão alcançar esses aparelhos pela rede Tailscale.": "TV apps will reach these devices over the Tailscale network.",
"Apps de IA liberados": "Allowed AI apps",
"Apps liberados": "Allowed apps",
"Apps liberados (pacotes separados por vírgula)": "Allowed apps (packages separated by commas)",
"Armado. Aperte os botões do controle...": "Armed. Press the remote buttons...",
"Armar teclas (2 min)": "Arm keys (2 min)",
"Arquivos": "Files",
"Arraste para mover · toque = OK · segure = voltar": "Drag to move · tap = OK · hold = back",
"As próximas cópias saem trancadas com esta senha. Anote-a: sem ela não dá para restaurar.": "The next copies are locked with this password. Write it down: without it you can't restore.",
"Atalhos de porta (servidor de mídia em outro lugar)": "Port shortcuts (media server somewhere else)",
"Atalhos salvos. O túnel reinicia em alguns segundos.": "Shortcuts saved. The tunnel restarts in a few seconds.",
"Atualizando rápido": "Refreshing fast",
"Atualizar": "Update",
"Atualização do sistema só de madrugada (3h às 5h)": "System update only at night (3 to 5 AM)",
"Até 6 cards grandes no lugar da HOME (chave \"Modo simples\"; desligar pede o PIN). Marque os apps; sem marcar, vão os mais usados.": "Up to 6 big cards instead of the HOME (\"Simple mode\" switch; turning it off asks for the PIN). Check the apps; if none, the most used go.",
"Aumentar o volume": "Volume up",
"Avançar": "Forward",
"Avisar um link meu": "Notify a link of mine",
"Avisos no celular": "Phone notifications",
"Ação": "Action",
"Ações em JSON": "Actions in JSON",
"Backup completo (cifrado)": "Full backup (encrypted)",
"Backup noturno cifrado": "Encrypted nightly backup",
"Backup restaurado e launcher reiniciado. Entre de novo com o PIN.": "Backup restored and launcher restarted. Sign in again with the PIN.",
"Baixado. Guarde o arquivo e a senha em lugares diferentes.": "Downloaded. Keep the file and the password in different places.",
"Baixando a lista e o guia de cada conta (pode levar até um minuto)...": "Downloading the list and guide of each account (may take up to a minute)...",
"Baixar": "Download",
"Baixar agora": "Download now",
"Baixar backup": "Download backup",
"Baixar backup completo": "Download full backup",
"Baixar o volume": "Volume down",
"Baixo": "Down",
"Busca rápida": "Quick search",
"Buscar (Ctrl+K)": "Search (Ctrl+K)",
"Buscar app, aba ou ação": "Search app, tab or action",
"Buscar app, aba ou ação…": "Search app, tab or action…",
"CEP": "CEP (Brazilian ZIP)",
"CEP (ex.: 14015-060)": "CEP (e.g. 14015-060)",
"Cada \"aparelho:porta\" vira http://127.0.0.1:porta na TV: no Jellyfin, Emby ou Kodi, use esse endereço como servidor. Só com a rede Tailscale ligada. Na mesma rede de casa não precisa.": "Each \"device:port\" becomes http://127.0.0.1:port on the TV: in Jellyfin, Emby or Kodi, use that address as the server. Only with the Tailscale network on. Not needed on the same home network.",
"Cada evento roda no máximo uma vez a cada 20 s. Filmes de outros apps precisam do acesso às notificações (\"Tocando agora\").": "Each event runs at most once every 20 s. Movies from other apps need notification access (\"Now playing\").",
"Cada letra vai direto para a TV": "Each letter goes straight to the TV",
"Cadastrar": "Add",
"Cadastrar a conta na TV?": "Add the account to the TV?",
"Cadastrar conta": "Add account",
"Canal da TV": "TV channel",
"Cancelado na TV.": "Canceled on the TV.",
"Cancelar": "Cancel",
"Cancelar seleção": "Cancel selection",
"Carregando a tela…": "Loading the screen…",
"Carregando…": "Loading…",
"Casa (Home Assistant)": "Home (Home Assistant)",
"Casa não configurada": "Home not set up",
"Cenas por link": "Scenes by link",
"Certificado do roteador": "Router certificate",
"Certificado novo pronto. Instale de novo nos aparelhos (Passo a passo).": "New certificate ready. Install it again on the devices (Step by step).",
"Chave de API": "API key",
"Chave guardada. O túnel sobe em alguns segundos.": "Key saved. The tunnel comes up in a few seconds.",
"Chaves": "Switches",
"Cidade": "City",
"Cima": "Up",
"Claude (claude.ai, app do celular ou do PC)": "Claude (claude.ai, phone or PC app)",
"Claude pela internet (MCP)": "Claude over the internet (MCP)",
"Claude, ChatGPT e outras IAs controlam a TV: controle remoto, abrir apps e links, instalar APK, despertadores, avisos, arquivos e shell. O que pede OK no controle segue as Confirmações. O login é este mesmo PIN, ou a conta Google, se ligada abaixo. O painel não fica na internet: só o MCP.": "Claude, ChatGPT and other AIs control the TV: remote, opening apps and links, installing APKs, alarms, messages, files and shell. Whatever needs OK on the remote follows the Confirmations. Sign-in is this same PIN, or the Google account, if turned on below. The panel isn't on the internet: only the MCP.",
"Cole a chave inteira (começa com tskey-).": "Paste the whole key (starts with tskey-).",
"Cole no arquivo de servidores MCP (mcp.json) e entre com o PIN quando pedir.": "Paste into the MCP servers file (mcp.json) and sign in with the PIN when asked.",
"Com a chave \"Presença\" ligada: se os celulares marcados saírem da rede por 15 min e ninguém mexer no controle, a TV apaga a tela e avisa no celular.": "With the \"Presence\" switch on: if the checked phones leave the network for 15 min and nobody touches the remote, the TV turns off the screen and notifies your phone.",
"Com a chave ligada, um link secreto recebe recados (POST com JSON) do Home Assistant, dos Atalhos do iPhone ou de apps feitos para o TvOverlay. Gerar um link novo invalida o anterior e pede o PIN. O link só aparece agora: guarde-o.": "With the switch on, a secret link receives messages (POST with JSON) from Home Assistant, iPhone Shortcuts or apps made for TvOverlay. Creating a new link invalidates the previous one and asks for the PIN. The link only shows now: save it.",
"Com a chave ligada: no horário abaixo, só abrem os apps da lista (os do sistema sempre abrem) e o volume não passa do teto. Salvar pede o PIN.": "With the switch on: in the hours below, only apps on the list open (system apps always open) and the volume doesn't go over the cap. Saving asks for the PIN.",
"Com isto, o login do Claude (e dos outros apps) deixa de aceitar o PIN: só passa uma conta Google que esteja em algum perfil desta TV. Crie no Google Cloud um cliente OAuth do tipo \"TVs e dispositivos de entrada limitada\" e cole o id e o segredo aqui. Deixe o id vazio e salve para voltar ao PIN.": "With this, the Claude sign-in (and other apps') stops accepting the PIN: only a Google account that is on some profile of this TV gets in. In Google Cloud, create an OAuth client of type \"TVs and Limited Input devices\" and paste the id and secret here. Leave the id empty and save to go back to the PIN.",
"Com o certificado que a TV fez instalado no aparelho, o painel abre com cadeado e vira app no celular e no PC.": "With the TV's certificate installed on the device, the panel opens with a padlock and becomes an app on the phone and PC.",
"Com pelo menos uma foto, o protetor da TV passa a mostrá-las por trás do relógio, trocando a cada minuto. Ficam só na TV, numa pasta do launcher. JPG, PNG ou WebP, até 15 MB cada.": "With at least one photo, the TV's screensaver shows them behind the clock, changing every minute. They stay only on the TV, in a launcher folder. JPG, PNG or WebP, up to 15 MB each.",
"Como a TV está": "How the TV is doing",
"Compras": "Shopping",
"Conectar": "Connect",
"Conectar uma IA": "Connect an AI",
"Conectar uma IA à TV": "Connect an AI to the TV",
"Configurações": "Settings",
"Configurações › Aplicativos e conectores › Avançado › ligue o Modo desenvolvedor.": "Settings › Apps and connectors › Advanced › turn on Developer mode.",
"Configurações › Conectores › Adicionar conector personalizado.": "Settings › Connectors › Add custom connector.",
"Confira a impressão antes de confiar.": "Check the fingerprint before trusting.",
"Confirmar": "Confirm",
"Console do Tailscale: Settings → Keys → Generate auth key. A chave fica cifrada na TV e é apagada depois do primeiro login.": "Tailscale console: Settings → Keys → Generate auth key. The key stays encrypted on the TV and is deleted after the first sign-in.",
"Contas de TV ao vivo": "Live TV accounts",
"Continuar o que estava vendo": "Continue watching",
"Controle": "Remote",
"Controle rápido": "Quick remote",
"Copiado!": "Copied!",
"Copiar o endereço": "Copy the address",
"Cor de destaque tirada do fundo (tema Galeria)": "Accent color taken from the background (Gallery theme)",
"Criar": "Create",
"Criar cena": "Create scene",
"Câmera": "Camera",
"Câmera (ex.: camera.porta)": "Camera (e.g. camera.door)",
"Cópia extra do backup noturno": "Extra copy of the nightly backup",
"Cópia extra trocada.": "Extra copy changed.",
"Cópia extra:": "Extra copy:",
"Cópias automáticas": "Automatic copies",
"DNS falhou": "DNS failed",
"DNS não resolvia nomes (internet ok)": "DNS didn't resolve names (internet ok)",
"Dados de": "Data of",
"Depois desse tempo sem ninguém apertar o controle, a TV mostra um aviso por 60 s e desliga. Qualquer botão cancela. Assistir um filme sem tocar no controle também conta como \"sem uso\".": "After this long without anyone pressing the remote, the TV shows a notice for 60 s and turns off. Any button cancels. Watching a movie without touching the remote also counts as \"not in use\".",
"Desenho da HOME nesta versão:": "HOME drawing in this version:",
"Desinstalar": "Uninstall",
"Desligado: a TV só publica o MCP. Ligado: http://onetv-<nome da TV>:8080, ou com cadeado https://onetv-<nome da TV>.<sua rede>.ts.net:8443, abre este painel de qualquer aparelho da conta Tailscale, com o mesmo PIN, nunca pela internet aberta.": "Off: the TV only publishes the MCP. On: http://onetv-<TV name>:8080, or with padlock https://onetv-<TV name>.<your network>.ts.net:8443, opens this panel from any device on the Tailscale account, with the same PIN, never over the open internet.",
"Desligado: defina a senha primeiro.": "Off: set the password first.",
"Desligado: o login usa o PIN do painel.": "Off: sign-in uses the panel PIN.",
"Desligar": "Turn off",
"Desligar a TV em": "Turn the TV off in",
"Desligar o link": "Turn off the link",
"Desligar sozinha": "Turn off by itself",
"Desligar sozinha depois de": "Turn off by itself after",
"Desligar suave (o timer escurece a tela e baixa o volume nos últimos 5 min)": "Soft off (the timer dims the screen and lowers the volume in the last 5 min)",
"Diagnosticar": "Diagnose",
"Digitar na TV": "Type on the TV",
"Digite o PIN (6 a 12 números).": "Enter the PIN (6 to 12 digits).",
"Digite o PIN definido nas Configurações do launcher, na TV.": "Enter the PIN set in the launcher Settings, on the TV.",
"Direita": "Right",
"Diário apagado.": "Diary deleted.",
"Diário da TV": "TV diary",
"Diário de DNS": "DNS diary",
"Diário de DNS por app (fica só na TV, 7 dias)": "DNS diary per app (stays only on the TV, 7 days)",
"Diário de DNS:": "DNS diary:",
"Domínio do app de IA": "AI app domain",
"Dormir": "Sleep",
"Dormir (apagar a TV)": "Sleep (turn the TV off)",
"Dê um nome à cena.": "Give the scene a name.",
"Endereço": "Address",
"Endereço (ex.: http://192.168.1.10:8123)": "Address (e.g. http://192.168.1.10:8123)",
"Endereço apagado: a TV se acha só pelo Wi-Fi ou pela internet.": "Address deleted: the TV finds it only over Wi-Fi or the internet.",
"Endereço do Home Assistant": "Home Assistant address",
"Endereço do clima": "Weather address",
"Endereço do roteador na rede de casa": "Router address on the home network",
"Endereço do webhook": "Webhook address",
"Endereço salvo.": "Address saved.",
"Entidades do Home Assistant": "Home Assistant entities",
"Entrar": "Sign in",
"Entre com o PIN. Na conversa, escolha o conector TV no botão +.": "Sign in with the PIN. In the chat, choose the TV connector in the + button.",
"Entrou pelo QR da TV. Para instalar ou desinstalar, o painel ainda pede o PIN.": "Signed in with the TV's QR. To install or uninstall, the panel still asks for the PIN.",
"Enviado para a TV.": "Sent to the TV.",
"Enviar": "Send",
"Enviar arquivos…": "Send files…",
"Enviar fotos…": "Send photos…",
"Enviar foto…": "Send photo…",
"Enviar para a TV": "Send to the TV",
"Erro:": "Error:",
"Erros do launcher": "Launcher errors",
"Escolha o app": "Choose the app",
"Escolha um arquivo .apk, .apks ou .xapk.": "Choose an .apk, .apks or .xapk file.",
"Escolha uma foto.": "Choose a photo.",
"Escolha uma senha com 8 ou mais caracteres.": "Choose a password with 8 or more characters.",
"Escolher arquivo…": "Choose file…",
"Escreva o recado.": "Write the message.",
"Espaço livre na TV:": "Free space on the TV:",
"Esquecer": "Forget",
"Esquecer o Tailscale (para entrar com outra conta)": "Forget Tailscale (to sign in with another account)",
"Esquecer o Tailscale?": "Forget Tailscale?",
"Esquecer o roteador (apaga endereço, chave e lista)": "Forget the router (deletes address, key and list)",
"Esquecer o roteador?": "Forget the router?",
"Esquerda": "Left",
"Esse arquivo não é um backup válido (JSON).": "This file isn't a valid backup (JSON).",
"Esse é um backup completo: escreva a senha dele no campo abaixo e escolha o arquivo de novo.": "This is a full backup: type its password in the field below and choose the file again.",
"Esta versão do launcher ainda não mostra o acesso remoto.": "This launcher version doesn't show remote access yet.",
"Este app não entra em ações em lote (abra Detalhes)": "This app doesn't take part in batch actions (open Details)",
"Este app é protegido: o sistema ou o launcher dependem dele. As permissões aparecem só para consulta.": "This app is protected: the system or the launcher depend on it. Permissions are shown for reference only.",
"Este navegador não recebe avisos por aqui. Abra o painel com cadeado (https, porta 8444) no Chrome do celular: veja Extras, \"Instalar como app\".": "This browser doesn't get notifications here. Open the panel with the padlock (https, port 8444) in Chrome on the phone: see Extras, \"Install as app\".",
"Ex.: O jantar está pronto!": "E.g.: Dinner is ready!",
"Falta a chave de API.": "The API key is missing.",
"Falta confirmar o certificado.": "The certificate still needs confirming.",
"Falta o endereço do roteador.": "The router address is missing.",
"Falta o endereço.": "The address is missing.",
"Fazendo o backup… (com cópia na rede pode levar até 1 min)": "Making the backup… (with a network copy it may take up to 1 min)",
"Fazer agora": "Do it now",
"Fazer o backup toda noite": "Back up every night",
"Fecha o app à força (como \"Forçar parada\"). Ele volta ao normal quando for aberto de novo.": "Force-closes the app (like \"Force stop\"). It goes back to normal when opened again.",
"Fecha os apps em segundo plano (o da frente continua). Nada é apagado; o app fechado abre do zero.": "Closes apps in the background (the one in front stays). Nothing is deleted; the closed app starts from scratch.",
"Fechar": "Close",
"Feito: a TV sai do Wi-Fi e volta em alguns segundos. Depois, toque em Diagnosticar de novo.": "Done: the TV leaves the Wi-Fi and comes back in a few seconds. Then tap Diagnose again.",
"Fica cifrada na TV. Depois, use \"Testar e recarregar canais\" para ver se ela funciona.": "It stays encrypted on the TV. Then use \"Test and reload channels\" to see if it works.",
"Filme ou vídeo começou": "Movie or video started",
"Filme ou vídeo pausou ou acabou": "Movie or video paused or ended",
"Filtro noturno (tela mais quente no horário abaixo)": "Night filter (warmer screen in the hours below)",
"Filtro noturno:": "Night filter:",
"Fim": "End",
"Fim de semana": "Weekend",
"Flamengo, Fórmula 1": "Liverpool, Formula 1",
"Força": "Strength",
"Foto": "Photo",
"Foto a cada 2 s": "Picture every 2 s",
"Foto do perfil": "Profile picture",
"Foto removida: a bolinha volta a mostrar a inicial.": "Photo removed: the circle shows the initial again.",
"Foto trocada. Ela aparece na TV quando a tela inicial for aberta de novo.": "Photo changed. It shows on the TV when the home screen opens again.",
"Fotos do protetor": "Screensaver photos",
"Fotos do protetor de tela": "Screensaver photos",
"Gerar certificado novo": "Create new certificate",
"Gerar link novo": "Create new link",
"Gerar um link novo (o anterior para de funcionar)": "Create a new link (the previous one stops working)",
"Gráfico de latência e quedas": "Latency and outage chart",
"Guardar": "Save",
"Guardar a chave do Tailscale?": "Save the Tailscale key?",
"Guardar a senha do backup noturno?": "Save the nightly backup password?",
"Guardar o endereço e o token na TV?": "Save the address and token on the TV?",
"Guardar senha": "Save password",
"Hoje na TV (sugestões na tela Buscar)": "Today on TV (suggestions on the Search screen)",
"Home Assistant: person.ana, device_tracker.celular_bia": "Home Assistant: person.ana, device_tracker.bia_phone",
"Hora de dormir": "Bedtime",
"Hora de dormir (só apps liberados e volume com teto)": "Bedtime (only allowed apps and capped volume)",
"Horário do backup noturno": "Nightly backup time",
"Horário:": "Time:",
"Há uma atualização do sistema esperando a janela das 3h.": "A system update is waiting for the 3 AM window.",
"IPs dos celulares": "Phone IPs",
"IPs dos celulares: 192.168.1.20, 192.168.1.21": "Phone IPs: 192.168.1.20, 192.168.1.21",
"Instalando na TV…": "Installing on the TV…",
"Instalar": "Install",
"Instalar APK…": "Install APK…",
"Instalar como app (https)": "Install as app (https)",
"Internet por app": "Internet per app",
"Início": "Home",
"Isso não existe mais na TV (talvez já tenha sido apagado).": "This no longer exists on the TV (maybe it was already deleted).",
"Leia o QR com o celular para colar o endereço no app da IA. O login pede o PIN deste painel (ou a conta Google, se ligada na aba Claude).": "Scan the QR with your phone to paste the address into the AI app. Sign-in asks for this panel's PIN (or the Google account, if turned on in the Claude tab).",
"Lendo o certificado (a chave não vai junto)…": "Reading the certificate (the key doesn't come along)…",
"Lendo o roteador…": "Reading the router…",
"Lendo…": "Reading…",
"Ler em voz alta o que está em foco nas telas do launcher": "Read aloud what is in focus on the launcher screens",
"Ler o certificado": "Read the certificate",
"Ler o roteador": "Read the router",
"Liberar": "Free up",
"Liberar memória": "Free up memory",
"Ligado, esperando o Tailscale criar o endereço (veja a aba Claude).": "On, waiting for Tailscale to create the address (see the Claude tab).",
"Ligado. Este é o endereço da sua TV:": "On. This is your TV's address:",
"Ligado. O túnel reinicia em alguns segundos.": "On. The tunnel restarts in a few seconds.",
"Ligado: só entra conta Google de um perfil da TV.": "On: only a Google account from a TV profile gets in.",
"Ligando o vídeo na TV...": "Starting the video on the TV...",
"Ligar": "Turn on",
"Ligar o acesso pelo Claude": "Turn on access by Claude",
"Ligar o acesso pelo Claude?": "Turn on access by Claude?",
"Ligar o login pela conta Google?": "Turn on sign-in with Google account?",
"Ligar/desligar": "On/off",
"Ligue a chave \"Tocando agora\" para ver o que está tocando.": "Turn on the \"Now playing\" switch to see what's playing.",
"Limpar caches dos apps": "Clear app caches",
"Limpar os dados de": "Clear data of",
"Link da lista (http:// ou https://)": "List link (http:// or https://)",
"Link de um 2º guia XMLTV (opcional: só preenche canais sem programação)": "Link to a 2nd XMLTV guide (optional: only fills channels without a schedule)",
"Link do diário": "Diary link",
"Link do guia": "Guide link",
"Link do guia XMLTV (opcional)": "XMLTV guide link (optional)",
"Link do guia extra": "Extra guide link",
"Lista": "List",
"Lista de canais (link .m3u)": "Channel list (.m3u link)",
"Lista de compras": "Shopping list",
"Listas de canais (link .m3u) ou contas Xtream que o player da TV usa. Usuário, senha e link ficam cifrados na TV e nunca voltam para o painel: aqui aparece só o nome e o servidor. Cadastrar e apagar pedem o PIN de novo.": "Channel lists (.m3u link) or Xtream accounts used by the TV player. User, password and link stay encrypted on the TV and never come back to the panel: only the name and server show here. Adding and deleting ask for the PIN again.",
"Login pela conta Google": "Sign-in with Google account",
"Logins revogados: o Claude pede o PIN de novo.": "Sign-ins revoked: Claude asks for the PIN again.",
"Loja curada": "Curated store",
"MAC do PC (AA:BB:CC:DD:EE:FF)": "PC MAC (AA:BB:CC:DD:EE:FF)",
"MAC guardado na TV.": "MAC saved on the TV.",
"Mais": "More",
"Mais usados hoje": "Most used today",
"Mandar para a TV?": "Send to the TV?",
"Mandar recado": "Send message",
"Mandar um teste": "Send a test",
"Manutenção": "Maintenance",
"Marque ao menos uma origem.": "Check at least one source.",
"Memória interna": "Internal storage",
"Meu time e lembretes": "My team and reminders",
"Modo simples": "Simple mode",
"Modo simples (cards grandes no lugar da HOME)": "Simple mode (big cards instead of the HOME)",
"Montar a semana agora": "Build the week now",
"Mostrar": "Show",
"Mostrar a câmera na TV quando a campainha tocar?": "Show the camera on the TV when the doorbell rings?",
"Mostrar na TV": "Show on TV",
"Mudo": "Mute",
"Mural da família": "Family board",
"Mural da família no protetor de tela": "Family board on the screensaver",
"Mídia": "Media",
"Na TV": "On the TV",
"Na tela da TV, entre com o PIN deste painel. Pronto: peça \"abre o YouTube na TV\".": "On the TV screen, sign in with this panel's PIN. Done: ask \"open YouTube on the TV\".",
"Na tela da TV.": "On the TV screen.",
"Nada com esse nome.": "Nothing with that name.",
"Nada com esse nome. (Os apps aparecem aqui depois que a aba Apps carrega uma vez.)": "Nothing with that name. (Apps show up here after the Apps tab loads once.)",
"Nada no diário ainda.": "Nothing in the diary yet.",
"Nada registrado hoje. A chave do diário está ligada?": "Nothing recorded today. Is the diary switch on?",
"Negar uma permissão fecha o app se ele estiver aberto. \"O app pede\" devolve a decisão para quem estiver na TV.": "Denying a permission closes the app if it is open. \"The app asks\" gives the decision back to whoever is at the TV.",
"Nenhum acesso ainda.": "No sign-ins yet.",
"Nenhum app": "No app",
"Nenhum app usado hoje.": "No app used today.",
"Nenhum app.": "No apps.",
"Nenhum aviso no período.": "No notices in the period.",
"Nenhum celular recebe avisos ainda.": "No phone gets notifications yet.",
"Nenhum certificado confirmado ainda.": "No certificate confirmed yet.",
"Nenhum domínio extra": "No extra domain",
"Nenhum dos marcados entra nesta ação:": "None of the checked ones takes part in this action:",
"Nenhum erro guardado.": "No saved errors.",
"Nenhum lembrete nas próximas 24 h.": "No reminders in the next 24 h.",
"Nenhuma (só na TV)": "None (only on the TV)",
"Nenhuma cena ainda.": "No scenes yet.",
"Nenhuma conta ainda. Sem conta, o disco \"TV ao vivo\" não aparece na HOME.": "No accounts yet. Without an account, the \"Live TV\" disc doesn't show on the HOME.",
"Nenhuma cópia ainda: a primeira sai quando a HOME abrir.": "No copies yet: the first one is made when the HOME opens.",
"Nenhuma foto ainda: o protetor mostra só o relógio.": "No photos yet: the screensaver shows only the clock.",
"Nenhuma queda no período.": "No outages in the period.",
"Nenhuma: este app não pede localização, microfone, câmera, contatos etc.": "None: this app doesn't ask for location, microphone, camera, contacts etc.",
"No máximo 6 apps.": "At most 6 apps.",
"No teclado: setas, Enter = OK, Esc = voltar, espaço = tocar/pausar, + e − = volume.": "On the keyboard: arrows, Enter = OK, Esc = back, space = play/pause, + and − = volume.",
"Nome": "Name",
"Nome (ex.: Cinema)": "Name (e.g. Movie night)",
"Nome (ex.: TV da casa)": "Name (e.g. Home TV)",
"Nome da TV no roteador (o que vai no endereço com cadeado)": "TV name on the router (what goes in the padlock address)",
"Nome da cena": "Scene name",
"Nome da conta": "Account name",
"Nome da nova pasta": "New folder name",
"Nome de pasta inválido.": "Invalid folder name.",
"Nome deste celular": "Name of this phone",
"Nome do filme ou da série": "Movie or show name",
"Nome inválido (sem barra).": "Invalid name (no slash).",
"Nome inválido: uma palavra só, com letras, números e hífen (ex.: tv-sala).": "Invalid name: a single word, with letters, numbers and hyphen (e.g. tv-living).",
"Nome ou número (ex.: Globo)": "Name or number (e.g. BBC One)",
"Nome: TV. URL: cole o endereço. Salvar e Conectar.": "Name: TV. URL: paste the address. Save and Connect.",
"Nova pasta": "New folder",
"Não chegou vídeo: o shell próprio da TV pode estar desligado, ou outra pessoa já está vendo.": "No video arrived: the TV's own shell may be off, or someone else is already watching.",
"Não consegui falar com a TV.": "Couldn't reach the TV.",
"Não consegui falar com a TV. Confira se ela está ligada e na mesma rede.": "Couldn't reach the TV. Check that it is on and on the same network.",
"Não consegui falar com a TV. Confira se ela está ligada e se o painel está ligado nas Configurações do launcher.": "Couldn't reach the TV. Check that it is on and that the panel is turned on in the launcher Settings.",
"Não consegui listar": "Couldn't list",
"Não consegui listar:": "Couldn't list:",
"Não consegui mostrar esta imagem. Baixe para abrir.": "Couldn't show this image. Download it to open.",
"Não deu certo": "It didn't work",
"Número": "Number",
"Número da casa (opcional)": "House number (optional)",
"O PIN tem de 6 a 12 números.": "The PIN has 6 to 12 digits.",
"O acesso pela internet está desligado: ligue na aba Claude.": "Internet access is off: turn it on in the Claude tab.",
"O arquivo leva senhas e acessos, trancados com a senha que você escolheu. Guarde a senha: sem ela não dá para restaurar.": "The file carries passwords and sign-ins, locked with the password you chose. Keep the password: without it you can't restore.",
"O launcher não voltou em 1 min. Confira a TV.": "The launcher didn't come back in 1 min. Check the TV.",
"O login volta a pedir o PIN do painel.": "Sign-in asks for the panel PIN again.",
"O navegador não tocou este arquivo (formato ou codec sem suporte). Baixe para abrir num player.": "The browser didn't play this file (unsupported format or codec). Download it to open in a player.",
"O painel mandou um pedido que a TV não entendeu. Recarregue a página e tente de novo.": "The panel sent a request the TV didn't understand. Reload the page and try again.",
"O painel passa a abrir pelos aparelhos da sua conta Tailscale (ainda pede o PIN).": "The panel starts opening from the devices on your Tailscale account (still asks for the PIN).",
"O primeiro mês aparece amanhã: a TV fecha um dia de cada vez.": "The first month shows tomorrow: the TV closes one day at a time.",
"O que a TV deve fazer": "What the TV should do",
"O que dá para pedir": "What you can ask",
"O que ele faz nesta TV agora": "What it does on this TV now",
"O que mexeu na TV nos últimos 7 dias: painel, Claude, adb e a própria TV. O Claude lê o mesmo diário (ferramenta diario_tv).": "What changed the TV in the last 7 days: panel, Claude, adb and the TV itself. Claude reads the same diary (diario_tv tool).",
"O que o Android avisou": "What Android reported",
"O roteador usa um certificado próprio. A TV só fala com ele depois de você conferir a impressão digital: abra https://<endereço do roteador> no computador, clique no cadeado e compare com a SHA-256 abaixo.": "The router uses its own certificate. The TV only talks to it after you check the fingerprint: open https://<router address> on the computer, click the padlock and compare with the SHA-256 below.",
"O shell do launcher (teclas do painel, comandos do Claude, autocura) e o túnel do Claude pela internet às vezes param sem avisar. Aqui aparece se estão no ar, e o que a TV fez na manutenção da noite.": "The launcher's shell (panel keys, Claude commands, self-healing) and Claude's internet tunnel sometimes stop without warning. Here you see if they're up, and what the TV did in the nightly maintenance.",
"O shell do launcher não respondeu: sem a lista de memória e sem liberar (a depuração sem fio está ligada?).": "The launcher's shell didn't answer: no memory list and nothing freed (is wireless debugging on?).",
"O vídeo terminou (2 min). Clique em \"Ver ao vivo\" para continuar.": "The video ended (2 min). Click \"Watch live\" to continue.",
"Ocultar": "Hide",
"Ordem dos apps, tema, alarmes, DNS e as demais configurações do launcher, num arquivo JSON. O PIN não vai junto.": "App order, theme, alarms, DNS and the other launcher settings, in a JSON file. The PIN doesn't go along.",
"Organizar": "Organize",
"Os aparelhos que já instalaram o certificado atual vão parar de abrir o painel com cadeado até instalarem o novo": "Devices that already installed the current certificate will stop opening the panel with the padlock until they install the new one",
"Os canais dela somem do player da TV. Para voltar, é preciso cadastrar de novo.": "Its channels disappear from the TV player. To bring them back, you have to add it again.",
"Os logins atuais são revogados: cada app entra de novo, agora com a conta Google.": "Current sign-ins are revoked: each app signs in again, now with the Google account.",
"Ou a cidade, fora do Brasil (ex.: Lisboa)": "Or the city, outside Brazil (e.g. Lisbon)",
"Outro app de IA": "Another AI app",
"Pacote enviado. O PC precisa estar no cabo e com Wake-on-LAN ligado.": "Packet sent. The PC must be wired and have Wake-on-LAN on.",
"Padrão": "Default",
"Padrão: de": "Default: from",
"Painel": "Panel",
"Palavras do Meu time": "My team keywords",
"Palavras separadas por vírgula (\"Flamengo, Fórmula 1\"). Todo programa do guia com uma delas no título vira lembrete: a TV avisa 2 min antes. No guia em grade da TV, OK num programa futuro marca ou desmarca à mão.": "Words separated by commas (\"Liverpool, Formula 1\"). Every guide program with one of them in the title becomes a reminder: the TV notifies 2 min before. In the TV's grid guide, OK on a future program marks or unmarks it by hand.",
"Para mostrar de novo um app que estava oculto, digite o PIN.": "To show again an app that was hidden, enter the PIN.",
"Para usar a foto da conta Google, abra na TV: Configurações do launcher > Conta > Buscar foto da conta.": "To use the Google account photo, open on the TV: launcher Settings > Account > Get account photo.",
"Parar": "Stop",
"Passo a passo": "Step by step",
"Pasta": "Folder",
"Pasta atual": "Current folder",
"Pasta grande: mostrando os 2000 primeiros.": "Big folder: showing the first 2000.",
"Pastas não são enviadas: arraste os arquivos de dentro delas.": "Folders aren't uploaded: drag the files inside them.",
"Pede ao roteador para tirar a TV do Wi-Fi por alguns segundos: ela volta sozinha e escolhe de novo o melhor ponto de acesso. Só acontece depois de alguém apertar OK no controle da TV.": "Asks the router to take the TV off the Wi-Fi for a few seconds: it comes back by itself and picks the best access point again. It only happens after someone presses OK on the TV remote.",
"Pedindo ao roteador…": "Asking the router…",
"Pedir": "Ask",
"Pedir o PIN para mudar a organização da HOME": "Ask for the PIN to change the HOME layout",
"Pedir à TV": "Ask the TV",
"Perguntando ao roteador…": "Asking the router…",
"Permissão de notificação negada no navegador.": "Notification permission denied in the browser.",
"Período": "Period",
"Por dia (vazio = segue o padrão acima; início igual ao fim = dia livre). O horário que passa da meia-noite vale para o dia em que começa: sexta 22:00 até 08:00 termina sábado às 8h.": "Per day (empty = follows the default above; start equal to end = free day). A time past midnight counts for the day it starts: Friday 22:00 to 08:00 ends Saturday at 8 AM.",
"Por mês (para cobrar o provedor)": "Per month (to complain to the provider)",
"Por segurança, digite o PIN de novo.": "For safety, enter the PIN again.",
"Preencha o endereço e/ou a chave.": "Fill in the address and/or the key.",
"Prefere usar uma chave do Tailscale?": "Prefer using a Tailscale key?",
"Presença": "Presence",
"Presença: apagar a TV quando todos saírem (roteador UniFi)": "Presence: turn the TV off when everyone leaves (UniFi router)",
"Previsão do tempo": "Weather forecast",
"Procurando o endereço…": "Looking up the address…",
"Procurar app": "Find app",
"Procure \"Conectores\" ou \"MCP\" nas configurações, adicione um servidor remoto com o endereço e entre com o PIN.": "Look for \"Connectors\" or \"MCP\" in the settings, add a remote server with the address and sign in with the PIN.",
"Pronto. Não havia muito o que fechar.": "Done. There wasn't much to close.",
"Pronto: este aparelho recebe os avisos da TV.": "Done: this device gets the TV's notifications.",
"Próxima": "Next",
"Próximo": "Next",
"Próximo episódio": "Next episode",
"Próximo ›": "Next ›",
"Pôr no mural": "Pin to the board",
"QR code do endereço": "Address QR code",
"Qualquer projeto que publique o APK no GitHub Releases. A TV escolhe o arquivo da arquitetura dela.": "Any project that publishes its APK on GitHub Releases. The TV picks the file for its architecture.",
"Quanto tempo fica na tela": "How long it stays on screen",
"Quedas": "Outages",
"Quem depende dele (pela TV)": "Who depends on it (by the TV)",
"Quem mais ocupa memória": "What uses the most memory",
"Recado": "Message",
"Recado com foto": "Message with photo",
"Recado na TV": "Message on the TV",
"Recado na TV quando aparecer um aparelho nunca visto (confere a cada 5 min)": "Message on the TV when a never-seen device shows up (checks every 5 min)",
"Recado na TV.": "Message on the TV.",
"Recado na tela": "Message on screen",
"Recado na tela da TV.": "Message on the TV screen.",
"Recado por link": "Message by link",
"Recado por link (Home Assistant, TvOverlay)": "Message by link (Home Assistant, TvOverlay)",
"Receber avisos aqui": "Get notifications here",
"Reconectar a TV…": "Reconnecting to the TV…",
"Rede": "Network",
"Rede Tailscale": "Tailscale network",
"Rede e conexões": "Network and connections",
"Refazer a limpeza de apps depois de atualizar o sistema": "Redo the app cleanup after a system update",
"Reiniciando… o painel volta em cerca de 1 min.": "Restarting… the panel comes back in about 1 min.",
"Reiniciar TV": "Restart TV",
"Reiniciar a TV?": "Restart the TV?",
"Relatar no GitHub": "Report on GitHub",
"Remove os apps e os dados deles. Não tem volta.": "Removes the apps and their data. There's no undo.",
"Renomear, mover ou apagar": "Rename, move or delete",
"Repositório do GitHub": "GitHub repository",
"Restaurar": "Restore",
"Retroceder": "Rewind",
"Revogar os logins do Claude": "Revoke Claude's sign-ins",
"Rodar": "Run",
"Rodar uma cena quando algo acontecer": "Run a scene when something happens",
"Rode o comando abaixo e depois /mcp para fazer o login.": "Run the command below and then /mcp to sign in.",
"Roteador": "Router",
"Roteador UniFi": "UniFi router",
"Sair": "Sign out",
"Salvar": "Save",
"Salvar apps": "Save apps",
"Salvar atalhos": "Save shortcuts",
"Salvar campainha": "Save doorbell",
"Salvar com o mesmo nome substitui a cena e gera um link novo (o antigo para de funcionar).": "Saving with the same name replaces the scene and creates a new link (the old one stops working).",
"Salvar endereço": "Save address",
"Salvar escolha": "Save choice",
"Salvar eventos": "Save events",
"Salvar o horário, o teto do volume e os apps liberados": "Save the hours, the volume cap and the allowed apps",
"Salvar os atalhos?": "Save the shortcuts?",
"Salvar os eventos que rodam cenas?": "Save the events that run scenes?",
"Saúde": "Health",
"Se ocultar ou desinstalar:": "If hidden or uninstalled:",
"Seg a sex": "Mon to Fri",
"Selecionar vários": "Select several",
"Sem IA: entende frases curtas (canal, app, aviso em minutos, desligar). Dá para ditar pelo microfone do teclado.": "No AI: understands short phrases (channel, app, notice in minutes, turn off). You can dictate with the keyboard microphone.",
"Sem apps marcados: vão os mais usados.": "No apps checked: the most used go.",
"Sem resposta da TV.": "No answer from the TV.",
"Sem roteador UniFi: use o Home Assistant (o app dele no celular informa se a pessoa está em casa) ou o IP fixo de cada celular (reserve o IP no roteador). Basta um deles dizer \"em casa\".": "No UniFi router: use Home Assistant (its phone app reports whether the person is home) or each phone's fixed IP (reserve the IP on the router). One of them saying \"home\" is enough.",
"Senha": "Password",
"Senha guardada. Guarde-a num lugar seguro.": "Password saved. Keep it somewhere safe.",
"Sensor da campainha (ex.: binary_sensor.campainha)": "Doorbell sensor (e.g. binary_sensor.doorbell)",
"Servidores reserva": "Backup servers",
"Servidores reserva (opcional, separados por vírgula)": "Backup servers (optional, separated by commas)",
"Sessão expirada.": "Session expired.",
"Sessão expirada: entre de novo.": "Session expired: sign in again.",
"Seções do painel": "Panel sections",
"Sinal, banda e velocidade medidos pela própria TV; ponto de acesso, canal e retransmissões vêm do roteador. Nada é mudado no roteador: as dicas dizem o que ajustar no app do UniFi.": "Signal, band and speed measured by the TV itself; access point, channel and retransmissions come from the router. Nothing is changed on the router: the tips say what to adjust in the UniFi app.",
"Sistema": "System",
"Solte para enviar para": "Release to send to",
"Som de navegação nas telas do launcher": "Navigation sound on the launcher screens",
"Somar": "Add",
"Somar repositório": "Add repository",
"Sua semana e desempenho": "Your week and performance",
"Sua semana na TV (domingo, 20h)": "Your week on the TV (Sunday, 8 PM)",
"Subir uma pasta": "Up one folder",
"Substitui as configurações da TV pelas do arquivo e reinicia o launcher. O estado de antes fica guardado:": "Replaces the TV's settings with those in the file and restarts the launcher. The previous state is kept:",
"Sábado": "Saturday",
"Símbolos que a TV não digita são pulados.": "Symbols the TV can't type are skipped.",
"Só instale APK de fonte confiável: o app pode pedir permissões.": "Only install APKs from trusted sources: the app may ask for permissions.",
"Só leitura: a TV vê os aparelhos conectados e se o roteador responde. Ela não bloqueia nem muda nada no roteador. A chave fica cifrada na TV e nunca volta para esta página (só os 4 últimos caracteres).": "Read only: the TV sees the connected devices and whether the router answers. It doesn't block or change anything on the router. The key stays encrypted on the TV and never comes back to this page (only the last 4 characters).",
"TV ao vivo": "Live TV",
"Tela ao vivo": "Live screen",
"Tela da TV": "TV screen",
"Tela inicial da TV": "TV home screen",
"Tempo de tela por dia (acabou, só os apps liberados abrem)": "Screen time per day (when it runs out, only allowed apps open)",
"Terça": "Tuesday",
"Testador de teclas e diário de DNS": "Key tester and DNS diary",
"Testar agora": "Test now",
"Testar e recarregar canais": "Test and reload channels",
"Teto do volume": "Volume cap",
"Texto": "Text",
"Texto (opcional)": "Text (optional)",
"Texto para digitar na TV": "Text to type on the TV",
"Tipo da ação": "Action type",
"Tipo de conta": "Account type",
"Tirar": "Remove",
"Tocando agora": "Now playing",
"Tocando agora (lê o que está tocando na TV)": "Now playing (reads what is playing on the TV)",
"Tocar filme ou série": "Play movie or show",
"Tocar ou pausar": "Play or pause",
"Tocar/pausar": "Play/pause",
"Toda noite, com a TV parada, ela guarda um backup completo trancado com a senha abaixo (ficam as 7 últimas cópias) e, se quiser, uma cópia extra no pendrive ou numa pasta da rede. Anote a senha: sem ela as cópias não abrem. Trocar a senha ou a cópia extra pede o PIN de novo.": "Every night, with the TV idle, it keeps a full backup locked with the password below (the last 7 copies stay) and, if you want, an extra copy on a USB drive or a network folder. Write the password down: without it the copies don't open. Changing the password or the extra copy asks for the PIN again.",
"Todos os apps →": "All apps →",
"Todos saíram (presença)": "Everyone left (presence)",
"Token de longa duração": "Long-lived token",
"Touchpad: arraste para mover, toque para OK": "Touchpad: drag to move, tap for OK",
"Travar \"Forçar parada\" e \"Limpar dados\" do launcher e da VPN": "Lock \"Force stop\" and \"Clear data\" of the launcher and the VPN",
"Trocar": "Change",
"Trocar a cópia extra?": "Change the extra copy?",
"Trocar foto…": "Change photo…",
"Trocar o endereço apaga a chave e o certificado guardados (é preciso colar a chave de novo).": "Changing the address deletes the saved key and certificate (you need to paste the key again).",
"Tudo o que o backup acima leva e mais: contas de TV ao vivo, chave do UniFi, PIN do painel, controle parental, o Tailscale (a TV volta com o mesmo endereço) e os logins do Claude e dos outros apps no conector. Sai trancado com a senha abaixo: guarde-a, porque sem ela o arquivo não abre. Contas Google, Wi-Fi e logins de outros apps (Netflix, YouTube) o Android não deixa copiar.": "Everything the backup above carries and more: live TV accounts, UniFi key, panel PIN, parental controls, Tailscale (the TV comes back with the same address) and the sign-ins of Claude and the other apps in the connector. It comes out locked with the password below: keep it, because without it the file doesn't open. Android doesn't allow copying Google accounts, Wi-Fi and other apps' sign-ins (Netflix, YouTube).",
"Um app que não está na lista volta do login por um domínio dele (o \"redirect\" que ele mostra, ex.: app.exemplo.com). Libere o domínio aqui, sem esperar versão nova da TV. Pede o PIN, e a TV ainda pergunta \"cliente novo?\" no primeiro login.": "An app that isn't on the list returns from sign-in through a domain of its own (the \"redirect\" it shows, e.g. app.example.com). Allow the domain here, without waiting for a new TV version. Asks for the PIN, and the TV still asks \"new client?\" on the first sign-in.",
"Uma cena junta várias ações. Cada uma ganha um link secreto para os Atalhos do iPhone ou o Home Assistant (POST). O link só aparece ao criar: guarde-o. Criar e apagar pedem o PIN.": "A scene groups several actions. Each one gets a secret link for iPhone Shortcuts or Home Assistant (POST). The link only shows when created: save it. Creating and deleting ask for the PIN.",
"Usuário": "User",
"Vai só o tipo do erro e o ponto do código onde ele aconteceu. A mensagem do erro, a rede, as contas e os apps ficam na TV. O GitHub abre com o texto pronto e você revisa antes de enviar.": "Only the error type and the place in the code where it happened go. The error message, the network, the accounts and the apps stay on the TV. GitHub opens with the text ready and you review it before sending.",
"Ver": "View",
"Ver ao vivo": "Watch live",
"Ver aparelhos do roteador": "See router devices",
"Ver aqui": "View here",
"Ver como JSON (avançado)": "View as JSON (advanced)",
"Ver detalhes →": "See details →",
"Ver diário de DNS de hoje": "See today's DNS diary",
"Ver na TV": "Show on TV",
"Ver o diário": "View the diary",
"Ver os nomes que cada app consultou hoje?": "See the names each app looked up today?",
"Voltar": "Back",
"Voltar ao login com PIN?": "Go back to sign-in with PIN?",
"Voltar as configurações?": "Restore the settings?",
"Voltar e Início respondem na hora; as outras teclas levam cerca de 1 s.": "Back and Home answer right away; the other keys take about 1 s.",
"Voltar à foto": "Back to the photo",
"Vídeo de verdade da tela da TV (até 2 min por vez; clique de novo para continuar). Netflix e outros apps com proteção aparecem pretos: é o Android, não defeito. Com a tela parada, não chega quadro novo.": "Real video of the TV screen (up to 2 min at a time; click again to continue). Netflix and other protected apps show black: that's Android, not a defect. With a still screen, no new frame arrives.",
"Wi-Fi da TV": "TV Wi-Fi",
"Wi-Fi da TV está bom: nada a ajustar.": "TV Wi-Fi is fine: nothing to adjust.",
"Xtream (servidor, usuário e senha)": "Xtream (server, user and password)",
"a TV não apareceu na lista do roteador (sem dados do ponto de acesso)": "the TV didn't show up in the router's list (no access point data)",
"a memória interna": "internal storage",
"aberto na TV.": "opened on the TV.",
"acesso (shell e túnel)": "access (shell and tunnel)",
"ainda não rodou": "hasn't run yet",
"ao vivo": "live",
"aparelho(s) ou pessoa(s) contam como \"alguém em casa\".": "device(s) or person(s) count as \"someone home\".",
"app.exemplo.com": "app.example.com",
"apps do sistema": "system apps",
"até": "until",
"baixar o de antes da última restauração": "download the one from before the last restore",
"carregando…": "loading…",
"coloca na Globo, abre o YouTube…": "put on BBC One, open YouTube…",
"com bipe": "with beep",
"confirme o PIN": "confirm the PIN",
"criada no UniFi em Configurações → Integrações": "created in UniFi under Settings → Integrations",
"demora média (95% abaixo de)": "average delay (95% below)",
"dono/nome ou link do GitHub": "owner/name or GitHub link",
"e reinicia o launcher na TV.": "and restarts the launcher on the TV.",
"em espera": "standby",
"escolher ·": "choose ·",
"espaço livre": "free space",
"espaço livre, tendência": "free space, trend",
"essa foto não existe": "that photo doesn't exist",
"estado térmico": "thermal state",
"estável": "stable",
"ex.: 192.168.0.1": "e.g. 192.168.0.1",
"ex.: nas:8096": "e.g. nas:8096",
"falta entrar no Tailscale": "still needs to sign in to Tailscale",
"fazer ·": "do ·",
"fechar": "close",
"hoje": "today",
"https://… ou http://homeassistant.local:8123/api/webhook/…": "https://… or http://homeassistant.local:8123/api/webhook/…",
"internet no ar (no tempo medido)": "internet up (in the measured time)",
"internet ok (altura = demora, até 300 ms)": "internet ok (height = delay, up to 300 ms)",
"já aparece": "already shows",
"já está oculto": "already hidden",
"leite": "milk",
"ligada há": "on for",
"manutenção da noite": "nightly maintenance",
"menos de 1 min": "less than 1 min",
"min. Volte daqui a pouco.": "min. Come back shortly.",
"máximo de 5 ações": "at most 5 actions",
"na tela inicial": "on the home screen",
"nenhum cadastrado (a TV se acha pelo Wi-Fi ou pela internet)": "none added (the TV finds it over Wi-Fi or the internet)",
"no ar": "up",
"não concedida": "not granted",
"não dá para ocultar": "can't be hidden",
"não existe": "doesn't exist",
"números": "digits",
"o armazenamento enche, nesse ritmo": "storage fills up, at this pace",
"o launcher da TV e o painel podem estar em versões diferentes.": "the TV launcher and the panel may be on different versions.",
"o launcher oculta sozinho": "the launcher hides it by itself",
"o que está na TV": "what's on the TV",
"o sistema depende dele": "the system depends on it",
"permissões": "permissions",
"quedas de internet": "internet outages",
"quedas só de DNS": "DNS-only outages",
"segredo do cliente": "client secret",
"sem dados (TV desligada)": "no data (TV off)",
"sem internet": "no internet",
"sem leitura ainda": "no reading yet",
"sem nome": "no name",
"senha (só para o backup completo)": "password (only for the full backup)",
"senha do backup (8 ou mais caracteres)": "backup password (8 or more characters)",
"senha nova do backup noturno (8 ou mais caracteres)": "new nightly backup password (8 or more characters)",
"shell do launcher": "launcher shell",
"sinal médio do Wi-Fi": "average Wi-Fi signal",
"túnel do Claude": "Claude tunnel",
"veio com a TV (não se desinstala)": "came with the TV (can't be uninstalled)",
"voltar a este dia": "go back to this day",
"· foto da conta Google": "· Google account photo",
"· foto enviada por você": "· photo sent by you",
"· guia próprio": "· own guide",
"· marcado à mão": "· marked by hand",
"· sem foto": "· no photo",
"É esse: confiar": "That's it: trust",
"É o mesmo certificado já confirmado.": "It's the same certificate already confirmed.",
"Última chamada do link:": "Last call to the link:",
"Última vez que desligou sozinha:": "Last time it turned off by itself:",
"Últimas 24 h": "Last 24 h",
"— aparece por cima de qualquer app": "— shows over any app",
"‹ Anterior": "‹ Previous",
"▶ Vídeo ao vivo": "▶ Live video"
};
  const P = [
[
"^Substitui\\ as\\ configurações,\\ as\\ contas\\ de\\ TV\\ ao\\ vivo,\\ o\\ PIN,\\ o\\ Tailscale\\ e\\ os\\ logins\\ do\\ conector\\ pelos\\ de\\ \"(.+?)\"$",
"Replaces the settings, live TV accounts, PIN, Tailscale and connector sign-ins with those of \"$1\""
],
[
"^Substitui\\ as\\ configurações\\ do\\ launcher\\ pelas\\ de\\ \"(.+?)\"\\ e\\ reinicia\\ o\\ launcher\\ na\\ TV\\ \\(a\\ tela\\ pisca\\)\\.$",
"Replaces the launcher settings with those of \"$1\" and restarts the launcher on the TV (the screen flickers)."
],
[
"^Substitui\\ as\\ configurações\\ do\\ launcher\\ pelas\\ de\\ (.+?)\\ e\\ reinicia\\ o\\ launcher\\ na\\ TV\\ \\(a\\ tela\\ pisca\\)\\.$",
"Replaces the launcher settings with those of $1 and restarts the launcher on the TV (the screen flickers)."
],
[
"^A\\ TV\\ está\\ em\\ uso\\ agora\\ \\((.+?)\\)\\.\\ Ocultar\\ fecha\\ o\\ app\\ na\\ hora,\\ se\\ for\\ ele\\ que\\ está\\ na\\ tela\\.$",
"The TV is in use now ($1). Hiding closes the app right away, if it's the one on screen."
],
[
"^Fonte\\ da\\ descrição:\\ (.+?)\\.\\ Papéis\\ e\\ dependências\\ \"pela\\ TV\"\\ são\\ lidos\\ do\\ aparelho\\ agora\\.$",
"Description source: $1. Roles and dependencies \"by the TV\" are read from the device now."
],
[
"^Atenção:\\ a\\ TV\\ está\\ em\\ uso\\ agora\\ \\((.+?)\\);\\ atualizar\\ um\\ app\\ que\\ está\\ aberto\\ fecha\\ ele\\.$",
"Heads up: the TV is in use now ($1); updating an app that is open closes it."
],
[
"^O\\ nome\\ \"(.+?)\"\\ ainda\\ não\\ está\\ no\\ certificado:\\ toque\\ em\\ Gerar\\ certificado\\ novo\\.$",
"The name \"$1\" isn't in the certificate yet: tap Create new certificate."
],
[
"^Somar\\ (.+?)\\ à\\ loja\\?\\ A\\ TV\\ vai\\ aceitar\\ instalar\\ o\\ que\\ esse\\ projeto\\ publicar\\.$",
"Add $1 to the store? The TV will accept installing whatever this project publishes."
],
[
"^Outras\\ (.+?)\\ permissões\\ \\(o\\ Android\\ concede\\ na\\ instalação;\\ só\\ consulta\\)$",
"Other $1 permissions (Android grants them at install; for reference only)"
],
[
"^(.+?)\\ conta\\(s\\)\\ sem\\ nenhum\\ canal:\\ confira\\ o\\ link,\\ o\\ usuário\\ e\\ a\\ senha\\.$",
"$1 account(s) with no channels: check the link, user and password."
],
[
"^Instala\\ \\(ou\\ atualiza\\)\\ o\\ app\\ na\\ TV,\\ sem\\ pedir\\ confirmação\\ lá\\ \\((.+?)\\)\\.$",
"Installs (or updates) the app on the TV, without asking for confirmation there ($1)."
],
[
"^Baixar\\ (.+?)\\ do\\ GitHub\\ e\\ instalar\\ na\\ TV\\?\\ Pode\\ levar\\ alguns\\ minutos\\.$",
"Download $1 from GitHub and install it on the TV? It may take a few minutes."
],
[
"^Endereço\\ com\\ cadeado:\\ https://(.+?):8444/\\ \\(e\\ (.+?)\\.lan,\\ (.+?)\\.local\\ e\\ o\\ IP\\)\\.$",
"Padlock address: https://$1:8444/ (and $2.lan, $3.local and the IP)."
],
[
"^Nenhum\\ erro\\ nesta\\ versão\\.\\ (.+?)\\ guardado\\(s\\)\\ de\\ versões\\ anteriores\\.$",
"No errors in this version. $1 saved from earlier versions."
],
[
"^Vence\\ em\\ (.+?)\\ dias:\\ gere\\ um\\ novo\\ e\\ instale\\ de\\ novo\\ nos\\ aparelhos\\.$",
"Expires in $1 days: create a new one and install it again on the devices."
],
[
"^A\\ TV\\ está\\ em\\ uso\\ agora\\ \\((.+?)\\)\\.\\ Abrir\\ troca\\ o\\ que\\ está\\ na\\ tela\\.$",
"The TV is in use now ($1). Opening changes what's on screen."
],
[
"^Restaurado\\ \\((.+?)\\ configurações\\)\\.\\ O\\ launcher\\ está\\ reiniciando…$",
"Restored ($1 settings). The launcher is restarting…"
],
[
"^Restaurado\\ \\((.+?)\\ configurações\\)\\.\\ O\\ launcher\\ está\\ reiniciando…$",
"Restored ($1 settings). The launcher is restarting…"
],
[
"^Restaurado\\ \\((.+?)\\ configurações\\)\\.\\ O\\ launcher\\ está\\ reiniciando…$",
"Restored ($1 settings). The launcher is restarting…"
],
[
"^Link\\ \\(guarde\\ agora,\\ não\\ aparece\\ de\\ novo\\):\\ POST\\ (.+?)/cena/(.+?)$",
"Link (save it now, it won't show again): POST $1/cena/$2"
],
[
"^(.+?)\\ de\\ (.+?)\\ apps\\ na\\ TV\\ \\((.+?)\\ instalados\\ por\\ você,\\ (.+?)\\ do\\ sistema\\)$",
"$1 of $2 apps on the TV ($3 installed by you, $4 system)"
],
[
"^Remove\\ o\\ app\\ (.+?)\\ e\\ os\\ dados\\ dele\\ da\\ TV\\.\\ Não\\ tem\\ volta\\.$",
"Removes the app $1 and its data from the TV. There's no undo."
],
[
"^Já\\ existe\\(m\\)\\ na\\ pasta:\\ (.+?)\\.\\ Substituir\\ pelos\\ novos\\?$",
"Already in the folder: $1. Replace with the new ones?"
],
[
"^As\\ próximas\\ cópias\\ noturnas\\ vão\\ também\\ para:\\ (.+?)\\.$",
"The next nightly copies also go to: $1."
],
[
"^(.+?)\\ erro\\(s\\)\\ nesta\\ versão,\\ (.+?)\\ guardados\\ no\\ total\\.$",
"$1 error(s) in this version, $2 saved in total."
],
[
"^Apagar\\ \"(.+?)\"\\?\\ O\\ link\\ dela\\ para\\ de\\ funcionar\\.$",
"Delete \"$1\"? Its link stops working."
],
[
"^(.+?):\\ o\\ app\\ passa\\ a\\ ter\\ isso\\ sem\\ perguntar\\.$",
"$1: the app gets this without asking."
],
[
"^\\(mais\\ (.+?)\\ tentativa\\(s\\)\\ antes\\ de\\ bloquear\\)$",
"($1 more attempt(s) before locking)"
],
[
"^(.+?)\\ canais,\\ (.+?)\\ com\\ programação\\ agora\\ \\((.+?)\\ s\\)\\.$",
"$1 channels, $2 with a schedule now ($3 s)."
],
[
"^Endereço\\ com\\ cadeado:\\ (.+?)\\.\\ Válido\\ até\\ (.+?)\\.$",
"Padlock address: $1. Valid until $2."
],
[
"^Atenção:\\ a\\ TV\\ está\\ em\\ uso\\ agora\\ \\((.+?)\\)\\.$",
"Heads up: the TV is in use now ($1)."
],
[
"^sinal\\ pelo\\ roteador\\ indisponível\\ \\((.+?)\\)$",
"signal from the router unavailable ($1)"
],
[
"^Não\\ cabe:\\ são\\ (.+?)\\ e\\ a\\ TV\\ tem\\ (.+?)\\ livres\\.$",
"Doesn't fit: it's $1 and the TV has $2 free."
],
[
"^Com\\ risco\\ \\(veja\\ a\\ ficha\\ antes\\):\\ (.+?)\\.$",
"Risky (check the sheet first): $1."
],
[
"^(.+?):\\ é\\ um\\ poder\\ grande\\ para\\ um\\ app\\.$",
"$1: that's a big power for an app."
],
[
"^(.+?)\\ ·\\ senha\\ definida\\ ·\\ última\\ vez:\\ (.+?)$",
"$1 · password set · last time: $2"
],
[
"^A\\ TV\\ recusou\\ o\\ pedido\\ \\(código\\ (.+?)\\)\\.$",
"The TV refused the request (code $1)."
],
[
"^(.+?):\\ (.+?)\\ feito\\(s\\)\\ antes\\ de\\ cancelar\\.$",
"$1: $2 done before canceling."
],
[
"^Espaço:\\ app\\ (.+?)\\ ·\\ dados\\ (.+?)\\ \\(cache\\ (.+?)\\)$",
"Space: app $1 · data $2 (cache $3)"
],
[
"^Pronto:\\ (.+?)\\ a\\ mais\\ de\\ RAM\\ livre\\.$",
"Done: $1 more free RAM."
],
[
"^(.+?)\\ aparelho\\(s\\)\\ já\\ conhecidos\\.$",
"$1 device(s) already known."
],
[
"^Permissões\\ que\\ se\\ trocam\\ \\((.+?)\\)$",
"Permissions you can change ($1)"
],
[
"^Roteador\\ respondendo\\ em\\ (.+?)\\ ms$",
"Router answering in $1 ms"
],
[
"^A\\ TV\\ está\\ em\\ uso\\ agora\\ \\((.+?)\\)\\.$",
"The TV is in use now ($1)."
],
[
"^·\\ última\\ posição\\ usada:\\ (.+?),\\ (.+?)$",
"· last position used: $1, $2"
],
[
"^(.+?)\\ queda(.+?)\\ de\\ internet,\\ (.+?)\\ fora$",
"$1 internet outage(s), $3 down"
],
[
"^(.+?)\\ de\\ (.+?)\\ foto\\(s\\)\\ enviada\\(s\\)\\.$",
"$1 of $2 photo(s) sent."
],
[
"^Depois\\ de\\ (.+?)\\ min\\ sem\\ usar$",
"After $1 min without use"
],
[
"^Depois\\ de\\ (.+?)\\ h\\ sem\\ usar$",
"After $1 h without use"
],
[
"^Impressão\\ digital:\\ (.+?)…$",
"Fingerprint: $1…"
],
[
"^Enviando\\ (.+?)\\ de\\ (.+?):\\ (.+?)\\ —\\ (.+?)%$",
"Sending $1 of $2: $3 — $4%"
],
[
"^Apagar\\ a\\ conta\\ \"(.+?)\"\\?$",
"Delete the account \"$1\"?"
],
[
"^Horário\\ gravado:\\ (.+?)\\.$",
"Time saved: $1."
],
[
"^Livre\\ na\\ TV:\\ (.+?)\\ de\\ (.+?)$",
"Free on the TV: $1 of $2"
],
[
"^·\\ medido\\ (.+?)\\ em\\ (.+?)\\ dia(.+?)$",
"· measured $1 over $2 day(s)"
],
[
"^(.+?)%\\ retransmissões$",
"$1% retransmissions"
],
[
"^(.+?):\\ (.+?)\\ de\\ (.+?)\\ feito\\(s\\)\\.$",
"$1: $2 of $3 done."
],
[
"^A\\ TV\\ recusou\\ \\((.+?)\\)\\.$",
"The TV refused ($1)."
],
[
"^Criar\\ a\\ cena\\ \"(.+?)\"\\?$",
"Create the scene \"$1\"?"
],
[
"^Enviando\\ (.+?)\\ de\\ (.+?)…\\ (.+?)%$",
"Sending $1 of $2… $3%"
],
[
"^Ficam\\ de\\ fora:\\ (.+?)\\.$",
"Left out: $1."
],
[
"^Renomeado\\ para\\ (.+?)\\.$",
"Renamed to $1."
],
[
"^espaço\\ livre\\ de\\ (.+?)$",
"free space of $1"
],
[
"^(.+?)\\ foto\\(s\\)\\ na\\ TV\\.$",
"$1 photo(s) on the TV."
],
[
"^Erro\\ na\\ TV:\\ (.+?)\\ \\((.+?)\\)$",
"Error on the TV: $1 ($2)"
],
[
"^Pasta\\ da\\ rede:\\ (.+?)$",
"Network folder: $1"
],
[
"^Tirar\\ (.+?)\\ da\\ loja\\?$",
"Remove $1 from the store?"
],
[
"^o\\ roteador\\ vê:\\ (.+?)$",
"the router sees: $1"
],
[
"^(.+?)\\ ·\\ github\\.com/(.+?)$",
"$1 · github.com/$2"
],
[
"^swap\\ livre\\ de\\ (.+?)$",
"free swap of $1"
],
[
"^RAM\\ livre\\ de\\ (.+?)$",
"free RAM of $1"
],
[
"^,\\ (.+?)\\ só\\ de\\ DNS$",
", $1 DNS only"
],
[
"^em\\ \\~(.+?)\\ semanas$",
"in ~$1 weeks"
],
[
"^(.+?)\\ foi\\ para\\ (.+?)\\.$",
"$1 went to $2."
],
[
"^até\\ (.+?)\\ ações$",
"up to $1 actions"
],
[
"^ligada\\ em\\ (.+?)(.+?)$",
"connected to $1$2"
],
[
"^em\\ (.+?)\\ dias$",
"in $1 days"
],
[
"^fora\\ há\\ (.+?)$",
"down for $1"
],
[
"^Túnel:\\ (.+?)$",
"Tunnel: $1"
],
[
"^(.+?)\\ de\\ (.+?)$",
"$1 of $2"
]
].map(([r, e]) => [new RegExp(r), e]);
  // Pedaços longos para textos montados por partes ("… a cada " + n + " min"): troca dentro do texto.
  const PEDACOS = Object.keys(T).filter((k) => k.length >= 18 && k.includes(' ')).sort((a, b) => b.length - a.length);
  const memo = new Map();
  function traduzir(bruto) {
    const t = bruto.replace(/\s+/g, ' ').trim();
    if (!t || !/[A-Za-zÀ-ú]/.test(t)) return null;
    if (memo.has(t)) return memo.get(t);
    let r = T[t] || null;
    if (!r) for (const [rx, e] of P) { if (rx.test(t)) { r = t.replace(rx, e); break; } }
    if (!r) {
      let s = t;
      for (const k of PEDACOS) if (s.includes(k)) s = s.split(k).join(T[k]);
      if (s !== t) r = s;
    }
    if (memo.size > 5000) memo.clear();
    memo.set(t, r);
    return r;
  }
  function trocarTexto(n) {
    const p = n.parentNode;
    if (!p || /^(SCRIPT|STYLE|TEXTAREA)$/.test(p.nodeName) || p.isContentEditable) return;
    const r = traduzir(n.nodeValue);
    if (r == null) return;
    const ini = n.nodeValue.match(/^\s*/)[0], fim = n.nodeValue.match(/\s*$/)[0];
    const novo = ini + r + fim;
    if (novo !== n.nodeValue) n.nodeValue = novo;
  }
  const ATRS = ['placeholder', 'aria-label', 'title', 'alt'];
  function trocarAtributos(el) {
    for (const a of ATRS) {
      const v = el.getAttribute && el.getAttribute(a);
      if (!v) continue;
      const r = traduzir(v);
      if (r != null && r !== v) el.setAttribute(a, r);
    }
  }
  function percorrer(raiz) {
    if (raiz.nodeType === 3) { trocarTexto(raiz); return; }
    if (raiz.nodeType !== 1 && raiz.nodeType !== 9) return;
    if (raiz.nodeType === 1) trocarAtributos(raiz);
    const w = document.createTreeWalker(raiz, NodeFilter.SHOW_TEXT | NodeFilter.SHOW_ELEMENT);
    for (let n = w.nextNode(); n; n = w.nextNode()) n.nodeType === 3 ? trocarTexto(n) : trocarAtributos(n);
  }
  percorrer(document.body || document.documentElement);
  const tt = traduzir(document.title); if (tt) document.title = tt;
  new MutationObserver((lista) => {
    for (const m of lista) {
      if (m.type === 'characterData') trocarTexto(m.target);
      else if (m.type === 'attributes') trocarAtributos(m.target);
      else m.addedNodes.forEach(percorrer);
    }
  }).observe(document.documentElement, { subtree: true, childList: true, characterData: true, attributes: true, attributeFilter: ATRS });
  window.traduzirPainel = traduzir;   // para os testes e para o console
})();
