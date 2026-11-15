# A TV como unidade de rede (14/11/2026)

Pedido: a TV aparecer no explorador de arquivos do Windows (e no Mac, Linux, celulares e outras Android TV) como uma
unidade de rede, para trocar arquivos arrastando. Senha: o PIN do launcher.

## O que foi feito: WebDAV no painel

- Servidor: `PainelDav.kt` (Android) + `DavLogica.kt` (puro, testado em `DavLogicaTest`). O servidor inteiro roda também
  na JVM numa pasta temporária (`DavServidorTest`), com pedidos escritos à mão como Windows, Finder e davfs2 mandam.
- Endereço: `https://<nome da TV>:8444/dav/` (mesma porta, certificado e nomes do painel https; o IP também serve).
  Só https: a senha vai em HTTP Basic (Base64, não é cifra). Só rede de casa, Host conferido (barra DNS rebinding).
- Senha: o PIN do painel, conferido com o MESMO contador do login (bloqueio crescente; 20 erros desligam o painel).
  `DavLogica.CacheSenha` lembra a senha certa por 15 min (o PBKDF2 é lento de propósito e o Windows manda a senha em
  todo pedido); a mesma senha errada repetida pelo cliente conta uma vez. Trocar o PIN derruba o cache na hora.
- Pastas: as mesmas da aba Arquivos (memória interna, sem `Android/`), pelas mesmas barreiras de caminho.
- Métodos: OPTIONS, PROPFIND (Depth 0/1), GET/HEAD com Range, PUT em fluxo (Content-Length ou chunked do Finder,
  `Expect: 100-continue`, espaço livre conferido antes e durante), DELETE, MKCOL, MOVE, COPY, LOCK/UNLOCK de mentira
  (Windows e Finder só gravam com LOCK), PROPPATCH (aplica só a data de modificação do Windows).
- Nada do que se baixa roda no endereço do painel: CSP `sandbox` em toda resposta; .html/.svg saem como download.
- Chave "Mostrar a TV como unidade de rede": Configurações › Acesso remoto e aba Configurações do painel. Desligada
  por padrão. Manual: assunto "Arquivos pela rede" (`aj_unidade_*`).
- Anúncios: mDNS `_webdavs._tcp` com `path=/dav/` (`AnuncioDav`), e SSDP/UPnP (`Ssdp.kt` + `AnuncioUpnp.kt`) para a
  TV aparecer em "Rede" do Windows como "<nome> · arquivos"; o duplo clique abre `/unidade` (endereço + passo a passo).
  O Chromecast embutido (mediashell, uid 10055) já escuta na UDP 1900 (medido na Sala em 14/11/2026): tentamos dividir a
  porta (reuseAddress); se o sistema não deixar, ficam só os anúncios NOTIFY a cada 10 min.

Testado: 1220 testes JVM; rclone (cópia de 50 MB, nomes com acento, check sem diferenças, mover, apagar, senha errada =
401) e cadaver (mkcol, put, copy, move, lock, unlock, propset, get, delete) contra o servidor do teste. NÃO testado:
na TV, no Windows, no Finder, em app Android, e o SSDP na rede de verdade.

Limites conhecidos (não verificados por mim, ler antes de prometer):
- Windows: o cliente WebDAV ("WebClient") foi marcado como descontinuado pela Microsoft em nov/2023 e ainda funciona.
  Copia no máximo 50 MB por arquivo por padrão (`FileSizeLimitInBytes`; o manual ensina a aumentar). Precisa confiar
  na CA da TV; talvez no repositório da Máquina Local, não só do Usuário.
- Android: apps de arquivos podem não confiar em certificados instalados pelo usuário (Android 7+); alguns têm a opção
  de aceitar o certificado do servidor.

## Estudo: SMB numa porta alta (sem código)

Por que não SMB "de verdade": o Windows só abre como pasta, em "Rede", quem fala SMB na porta 445; portas abaixo de
1024 são proibidas para apps no Android, e root está fora de questão.

1. Servidor SMB2/3 em Java/Kotlin. O JFileServer (FileSysOrg, LGPL) tem SMB, mas SMB2 e SMB3 só na versão Enterprise,
   paga com licença (li em filesys.org/wiki "Configuring JFileServer" e no Docker Hub filesysorg/jfileserver-enterprise).
   A versão livre fala SMB1, que o Windows 10/11 vem com o cliente desligado (insegura). jNQ e smb-kotlin são
   CLIENTES, não servidores. Não achei servidor SMB2/3 livre e mantido em Java. Escrever um (SMB 3.1.1 com
   NTLMv2, assinatura e cifra AES) é um projeto de semanas, num aparelho 32 bits, com risco de segurança alto.
2. Porta não padrão nos clientes:
   - Windows: só Windows 11 24H2+ e Server 2025: `NET USE T: \\tv\pasta /TCPPORT:<porta>` (li em
     techcommunity.microsoft.com "SMB alternative ports" e em learn.microsoft.com, Configure alternative SMB ports).
     Não aparece sozinho em "Rede" (a navegação de "Rede" não sabe a porta).
   - Mac: `smb://host:porta/pasta` (não verifiquei).
   - Linux: `mount -t cifs -o port=<porta>` e `smb://host:porta` nos gerenciadores (não verifiquei).
   - Android: vários apps de SMB deixam escolher a porta (não verifiquei um a um).
3. Custo estimado: com biblioteca paga, licença + integração (~1 semana); sem ela, semanas de protocolo. Em troca, o
   ganho sobre o WebDAV seria só "pasta em Rede" no Windows 11 24H2+, e mesmo assim digitando o comando com a porta.

Recomendação: ficar com WebDAV + o atalho em "Rede" (SSDP). Reabrir SMB só se surgir servidor SMB2/3 livre.
O Paulo decide.


## Windows: MTP pela rede (14/11/2026, depois do primeiro teste real)

O primeiro teste no Windows 11 (build 26300) mostrou que o WebDAV não é "ligar e usar": o certificado da TV precisa
estar em Máquina Local e o serviço WebClient vem parado e não liga sozinho. O dono pediu que a TV aparecesse sozinha,
como o celular dele aparece em Este Computador.

- **Medido no Windows:** `wpdmtp.inf` traz o driver "MTP IP Device" (`wpdmtpip.dll`) para o id
  `umb\urn:microsoft-com:device:mtp:1`. É MTP dentro de PTP/IP (CIPA DC-005), porta TCP 15740.
- **Medido na TV:** `ip_unprivileged_port_start` = 1024. SMB (porta 445) continua impossível.
- **Feito:** `MtpIp.kt` (respondedor MTP puro, testado com o gphoto2), `MtpNaRede.kt` (porta, permissão na tela),
  segundo aparelho no anúncio SSDP com o tipo acima e a descrição em `/upnp/onetv-mtp.xml`.
- **Segurança:** o MTP/IP não tem senha nem criptografia. Decisão do dono: sem PIN, com "Permitir?" na tela da TV uma
  vez por computador. O identificador do computador viaja aberto na rede e pode ser copiado por quem a escuta.
- **Não verificado ainda:** o Windows de verdade (descoberta, instalação do driver, o que ele pede além do básico).
  O conteúdo exato que o Windows espera na descrição UPnP (pnpx, MtpNullService) veio de memória da documentação
  antiga da Microsoft, não de um teste.
- **Limites:** sem eventos (arquivo criado na TV aparece no Windows ao atualizar a pasta); leitura aos pedaços só até
  4 GB de deslocamento (o arquivo inteiro baixa sem limite); sem miniaturas.
