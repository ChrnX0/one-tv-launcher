# Roteiro de publicação (quando sofaly.tv estiver registrado)

Quem faz o quê: **[Paulo]** = conta, pagamento ou clique que só o dono faz. **[Claude]** = comando ou conferência que
o Claude faz (pelo Dell ou pela nuvem) depois que a conta existir. Nada aqui é feito sem o Paulo dar o sinal.
Tempo estimado: ~1 h de trabalho + espera de propagação do DNS (minutos a 24 h). Custo: US$ 0 no carteiro (plano
grátis) + ~US$ 5 + €5,49 por mês nas duas máquinas.

## 1. Domínio na Cloudflare (DNS do sofaly.tv)

1. [Paulo] Porkbun: confirmar o registro de `sofaly.tv`; desligar o "parking"/encaminhamento padrão do Porkbun.
2. [Paulo] Criar conta grátis na Cloudflare (e-mail contact@sofaly.tv só depois do passo 4; até lá, o Gmail) e
   "Add a domain" → `sofaly.tv` → plano **Free**.
3. [Paulo] No Porkbun, trocar os servidores de nome pelos dois que a Cloudflare mostrar (`xxx.ns.cloudflare.com`).
   [Claude] confere: `dig NS sofaly.tv +short` mostra os da Cloudflare.
4. [Paulo] E-mail `contact@sofaly.tv`: Cloudflare > Email > Email Routing → encaminhar para o Gmail (cria os MX
   sozinho). Alternativa: encaminhamento do Porkbun, mas ele para de valer quando os servidores de nome mudam.
5. [Claude] Registro CAA (só o Let's Encrypt emite certificado para sofaly.tv e subdomínios):
   `sofaly.tv CAA 0 issue "letsencrypt.org"` e `sofaly.tv CAA 0 iodef "mailto:contact@sofaly.tv"`.

## 2. Carteiro (Cloudflare Workers)

1. [Paulo] `npx wrangler login` no Dell (abre o navegador; ele aprova).
2. [Claude] Em `servidor/carteiro/wrangler.toml`, descomentar a rota `carteiro.sofaly.tv` e rodar
   `npx wrangler deploy` (o domínio próprio cria o registro de DNS sozinho).
3. [Claude] `node ponta-a-ponta.mjs https://carteiro.sofaly.tv` tem que dizer "tudo certo".

## 3. Duas máquinas de DNS

1. [Paulo] Criar as máquinas (Ubuntu 24.04, menor plano com IPv4):
   - AWS Lightsail, região São Paulo, US$ 5/mês; anexar um **IP estático** (grátis enquanto anexado).
   - Hetzner Cloud, Helsinki, CPX02 €5,49/mês (o painel dizia "indisponível" em 17/11; se continuar, outro
     fornecedor na Europa ou EUA serve, desde que não seja a AWS, para não cair tudo junto).
   - Firewall das duas: liberar **UDP 53 e TCP 53** de qualquer lugar e SSH só do IP de casa.
2. [Claude] Em cada uma: `GOOS=linux GOARCH=amd64 go build` (ou arm64), copiar `sofaly-dns` para
   `/usr/local/bin/`, `dns/sofaly-dns.service` para `/etc/systemd/system/`, `systemctl enable --now sofaly-dns`,
   atualização automática do Ubuntu ligada (`unattended-upgrades`).
3. [Claude] Conferir direto em cada IP antes de delegar:
   `dig @<IP> 192-168-1-153.abcdefghijklmnopqrst.lan.sofaly.tv A +short` → `192.168.1.153`.

## 4. Delegar lan.sofaly.tv às duas máquinas (no DNS da Cloudflare)

| Nome | Tipo | Valor | Proxy |
|---|---|---|---|
| `ns1` | A | IP da máquina de São Paulo | desligado (nuvem cinza) |
| `ns2` | A | IP da máquina da Europa | desligado (nuvem cinza) |
| `lan` | NS | `ns1.sofaly.tv` | — |
| `lan` | NS | `ns2.sofaly.tv` | — |

Delegação de subdomínio existe em todos os planos, inclusive o grátis, até 10 NS por nome (li em
developers.cloudflare.com/dns/manage-dns-records/how-to/subdomains-outside-cloudflare). Glue não é preciso porque
`ns1`/`ns2` ficam fora de `lan`. [Claude] confere: `dig 192-168-1-153.abcdefghijklmnopqrst.lan.sofaly.tv +short`
de um DNS público (1.1.1.1 e 8.8.8.8).

**Somar uma região depois:** nova máquina igual ao passo 3, registro `ns3` A, mais um `lan NS ns3.sofaly.tv`, e
acrescentar `ns3.sofaly.tv` no `-ns` do serviço das três máquinas.

## 5. Antes de abrir para clientes

- [Paulo] Pedir ao Let's Encrypt aumento do limite de 50 certificados novos por semana para `sofaly.tv`
  (formulário de rate limit; leva semanas). Só quando o app (cliente) estiver pronto.
- [Paulo + advogado] Base legal das caixas de casas brasileiras nos EUA (ver README.md).
- [Claude] Política de privacidade e formulário de segurança de dados da Play passam a citar IP e horário.
- [Claude] Monitor externo grátis batendo em `https://carteiro.sofaly.tv/v1/saude` e no DNS (a escolher).
