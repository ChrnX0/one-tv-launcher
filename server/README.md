# Servidor da Sofaly

Decisão do Paulo (17/11/2026): **API na Cloudflare (Workers + Durable Objects) + duas máquinas pequenas de DNS**,
começando com duas e somando máquinas/regiões conforme o app cresce. Levantamento de custo e termos:
`tv-launcher/estrategia-projectivy/sofaly-nuvem-passo0-custos-2026-11-17.md` (pasta do projeto).

Estado (18/11/2026): API **no ar** em `https://api.sofaly.tv` (Worker `sofaly-api`, plano grátis). As máquinas de DNS ainda não existem.
Nomes públicos e técnicos (domínios, Workers, rotas, campos, pastas, arquivos) ficam em **inglês** (regra do Paulo, 18/11/2026); comentários seguem em português.

## As duas peças

| Peça | Onde | O que faz | Guarda o quê |
|---|---|---|---|
| `api/` | Cloudflare, rede mundial | pareamento TV↔celular por código; caixa postal com a TV desligada; envelopes entre casas; desafio do certificado | envelopes cifrados (máx. 64 KiB, 7 dias, apagados ao entregar), remetente, horário |
| `dns/` | 2+ máquinas (ex.: São Paulo e Europa) | responde `192-168-1-153.<id>.lan.sofaly.tv` → `192.168.1.153` e o TXT `_acme-challenge` | nada (o IP está no nome; o TXT vem da API) |

Princípios (da especificação): o servidor carrega envelopes que não consegue ler; sem conta (cada aparelho é um par
de chaves Ed25519 gerado nele); o app funciona com o servidor fora do ar; `DISABLED=1` no Worker desliga tudo.

## Identidade e assinatura

- Id do aparelho = 20 letras base32 do SHA-256 da chave pública Ed25519. O mesmo id é o nome do certificado da TV.
- Todo pedido assinado leva `X-Sofaly-Key` (chave pública, base64url), `X-Sofaly-Time` (segundos Unix) e
  `X-Sofaly-Signature` sobre `MÉTODO\ncaminho\nhora\nsha256(corpo) em hex`. Relógio pode estar até 5 min fora.
- Reenvio do mesmo pedido dentro dos 5 min entrega o envelope de novo: o aparelho descarta repetidos pelo id que vai
  DENTRO do envelope cifrado (a fazer no app).

## Certificado por TV (ACME DNS-01)

A chave do certificado nasce e fica na TV. A TV pede o certificado `*.<id>.lan.sofaly.tv` ao Let's Encrypt, grava o
desafio com `PUT /v1/dns/txt` na API e as máquinas de DNS mostram o TXT. O servidor nunca vê a chave.
Limite do Let's Encrypt: 50 certificados novos por semana por domínio; pedir aumento antes de abrir para clientes.

## Crescer sem refazer

- **Mais regiões da API:** nada a fazer; cada caixa nasce perto da casa (exceto América do Sul, África e Oriente
  Médio, que hoje nascem no leste dos EUA).
- **Mais máquinas de DNS:** subir o mesmo binário em outra máquina e acrescentar o nome dela em `-ns` e no registro
  do domínio. Elas não conversam entre si.
- **Sair da Cloudflare, se preciso:** o protocolo é HTTP + WebSocket simples; dá para reescrever a API em Go e
  rodar nas máquinas de DNS (estimativa 1-2 dias, não medida).
- **Dados no Brasil (LGPD):** se o advogado exigir, a caixa das casas brasileiras pode ir para uma máquina em São
  Paulo com a API em Go, mantendo o mesmo protocolo.

## Pendente para o advogado

O contrato de dados da Cloudflare (DPA v6.4, 03/04/2026) cobre UE, Reino Unido e Suíça, mas não cita Brasil nem as
cláusulas-padrão da ANPD (Res. 19/2024). Caixas de casas brasileiras ficam nos EUA: definir a base do art. 33 da LGPD
(ex.: consentimento específico e destacado) ou pedir as cláusulas à Cloudflare.

## Testar

```sh
cd server/api && node --test                        # regras puras (assinatura, ids, códigos)
npx wrangler dev --local --port 8787 &               # API simulada na máquina
node e2e.mjs                http://127.0.0.1:8787    # pareamento, caixa, WebSocket, TXT, recusa sem assinatura
cd ../dns && go test ./...                           # nomes, servidor DNS de verdade, leitura do TXT
```

## Publicar: roteiro completo em `DEPLOY.md`

- API: `npx wrangler login` na conta sofalyapp (o Paulo aprova no navegador do Dell), `npx wrangler deploy`
  dentro de `server/api`. Plano grátis serve até ~1.000 TVs (estimativa); depois, Workers Paid (US$ 5/mês).
- DNS: `go build` (GOOS=linux), copiar para cada máquina, rodar como serviço com permissão só para a porta 53
  (`setcap cap_net_bind_service`), apontar `ns1`/`ns2.sofaly.tv` (registros A no domínio) e delegar `lan.sofaly.tv`.
