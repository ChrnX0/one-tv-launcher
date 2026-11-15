# Virar Device Owner num Chromecast com conta Google — 3 caminhos para testar

Roteiro de teste, passo a passo, para o **Chromecast HD de teste** (nunca na TV da Sala). Objetivo: descobrir qual dos
três caminhos deixa o One TV Launcher virar Device Owner num aparelho que **já tem conta Google**, sem apagar a conta e
sem reset de fábrica (menos o caminho 3, que usa o reset de propósito).

## Antes de tudo (vale para os três)

- **Só no Chromecast de teste.** Não rode nada disto na Sala nem no Quarto dos pais.
- Nunca desinstale o launcher nem limpe os dados dele; nunca rode `MASTER_CLEAR`, `wipe` ou `reboot recovery`.
- O APK tem que ser o **assinado com a chave Release** (a chave pública dos Releases), senão o `install -r` recusa pela
  assinatura — e aí **pare**, não desinstale para "consertar".
- Ligue a **Depuração por Wi-Fi** no Chromecast (Opções do desenvolvedor) e pareie o adb do PC uma vez.
- Confira que ainda não é dono antes de começar:
  ```
  adb shell dpm list-owners
  ```
  Se já aparecer `br.paulo.tvlauncher/.AdminReceiver`, o aparelho já é dono — o teste acabou.

Em todos os caminhos, se o `dpm` reclamar de **"several users"**, tire os usuários extras antes (isso APAGA os dados
deles, por isso é só no aparelho de teste):
```
adb shell pm list users            # anote todo usuário que não é o 0
adb shell pm remove-user <N>       # um por usuário extra
```

---

## Caminho 1 — "Tornar dono" que já existe (jeito Hail, VirarDono.kt)

Desliga o Google por alguns segundos para a conta sumir da lista; o `finally` religa o Google. **Não apaga a conta.**

**Pela TV (mais simples):** Configurações do launcher → Avançado → "Virar dono". Ele mostra os usuários extras que
sairiam, pede OK e faz tudo sozinho. Veja se termina com "pronto: o launcher agora é o dono".

**Pelo adb (o mesmo passo a passo por fora):**
```
adb shell pm list users
adb shell pm remove-user <N>                                   # se houver usuário extra
adb shell pm disable-user --user 0 com.google.android.gms      # desliga o Google
adb shell dpm set-device-owner --user 0 br.paulo.tvlauncher/.AdminReceiver
adb shell pm enable --user 0 com.google.android.gms            # SEMPRE, mesmo se a linha acima falhar
adb shell dpm list-owners                                      # confirma
```
Se `set-device-owner` falhar, o `pm enable` já devolve tudo. **Nunca** desinstale o Google nem limpe os dados dele.

**O que anotar:** o `set-device-owner` passou? A conta Google voltou sozinha (Config → Contas)? Quanto tempo o Google
ficou fora? Deu algum erro na tela?

---

## Caminho 2 — Conta própria "One TV" (ContaOneTv.kt, novo)

Não mexe no Google. O launcher registra um **tipo de conta próprio "One TV"** (não é `com.google`) que responde ao
Android que aceita Device Owner. A pessoa **troca** a conta Google pela One TV só durante o `dpm` e depois desfaz.

O tipo de conta vem **desligado de fábrica**: só liga quando você manda o comando abaixo, e desliga sozinho em 15 min.

```
# 1) Liga o tipo de conta One TV e cria a conta
adb shell am broadcast -a br.paulo.tvlauncher.CONTA --ez ligada true \
  -n br.paulo.tvlauncher/.ControleShizukuAdbReceiver
#   resposta esperada: ligada=true conta=true (desliga sozinha em 15 min)
```
```
# 2) NA TV: Configurações > Contas e acesso > remova a conta Google (sair da conta no aparelho; é reversível,
#    não apaga a conta no Google). Confira que ficou só a conta "One TV".
```
```
# 3) Agora roda o dpm (só a conta One TV presente = lista "compatível")
adb shell pm list users
adb shell pm remove-user <N>            # se o dpm reclamar de "several users"
adb shell dpm set-device-owner --user 0 br.paulo.tvlauncher/.AdminReceiver
adb shell dpm list-owners               # confirma
```
```
# 4) Desliga o tipo de conta One TV e apaga a conta
adb shell am broadcast -a br.paulo.tvlauncher.CONTA --ez ligada false \
  -n br.paulo.tvlauncher/.ControleShizukuAdbReceiver
#   resposta esperada: ligada=false conta=false
```
```
# 5) NA TV: Configurações > Contas > adicione a conta Google de volta.
```
Para só conferir o estado sem ligar/desligar:
```
adb shell am broadcast -a br.paulo.tvlauncher.CONTA -n br.paulo.tvlauncher/.ControleShizukuAdbReceiver
#   resposta: ativo=<true/false> conta=<true/false>
```

**O que anotar:** depois de remover o Google e deixar só a One TV, o `set-device-owner` passou? A mensagem de erro, se
houver, ainda fala em "already some accounts" (sinal de que o Android não aceitou a nossa conta como compatível)? A
conta Google reentrou sem problema no passo 5?

---

## Caminho 3 — Virar dono no intervalo antes do reset / na primeira configuração

A ideia: logo depois de um reset de fábrica, **antes de entrar com a conta Google**, o aparelho não tem conta nenhuma,
então o `dpm` passa direto (é o jeito "oficial", sem truque). O reset é de propósito, só no aparelho de teste.

```
# 1) NA TV: Configurações > Sistema > Redefinir (reset de fábrica). É o aparelho de teste, pode.
# 2) Na configuração inicial, PULE/ADIE o login da conta Google. Chegue na tela inicial SEM conta.
# 3) Ligue Opções do desenvolvedor e Depuração por Wi-Fi; pareie o adb do PC.
# 4) Instale o launcher (APK assinado com a chave Release):
adb install -r one-tv-launcher.apk        # ou -arm64 conforme o aparelho
# 5) Sem nenhuma conta no aparelho, roda o dpm:
adb shell dpm set-device-owner --user 0 br.paulo.tvlauncher/.AdminReceiver
adb shell dpm list-owners                 # confirma
# 6) Só DEPOIS de virar dono, entre com a conta Google em Configurações > Contas.
```

**O que anotar:** sem conta, o `set-device-owner` passou de primeira? Depois de virar dono, entrar com a conta Google
tirou o Device Owner (não deveria)? Precisou remover algum usuário extra?

---

## Ordem sugerida de teste

1. **Caminho 1** primeiro (é o que já existe e não apaga nada). Se funcionar neste Chromecast, ótimo — os outros são
   reserva.
2. Se o caminho 1 falhar (o Google não desliga, ou o erro "already some accounts" continua), teste o **caminho 2**.
3. O **caminho 3** é o mais garantido, mas custa um reset — deixe por último, para confirmar o jeito limpo.

Documente para cada caminho: passou ou não, a mensagem de erro exata, e qualquer coisa estranha na tela. Isso decide
qual caminho vira o padrão do app quando ele estiver redondo.
