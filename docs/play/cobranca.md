# Cobrança da Play (Play Console › Monetizar › Produtos › Assinaturas)

O código (`CobrancaPlay.kt`) espera exatamente estes IDs. Preço não mora no código: vem da Play, na moeda do país.

| Product ID | Nome | Planos-base sugeridos | Libera |
|---|---|---|---|
| `sofaly_pro` | Sofaly Pro | `mensal`, `anual` | tudo do Pro (`Planos.MINIMO`) |
| `sofaly_ultra` | Sofaly Ultra | `mensal`, `anual` | Pro + Ultra |

- A tela "Conhecer o Pro" usa o **primeiro** plano-base/oferta que a Play devolve. Para o anual aparecer primeiro, crie-o primeiro
  (ou ajuste `CobrancaPlay.comprar`).
- Toda compra é reconhecida (acknowledge) na hora; sem isso a Play estorna em 3 dias.
- Sem falar com a Play por 14 dias (TV sem internet), o plano volta ao Básico (`VALIDADE_MS`).
- Trocar Pro ↔ Ultra: hoje abre uma assinatura nova; upgrade com prorrata (`SubscriptionProductReplacementParams`) fica para
  depois de testar no teste interno.
- Verificação no servidor (RTDN / Play Developer API): não existe servidor; a compra é conferida no próprio aparelho.
  Risco aceito: aparelho com root pode falsificar o plano.
- Testar: adicionar contas de teste de licença no Console; o teste interno já cobra em modo de teste.
- Proposta de planos (não decidida): Básico Trakt+clima; Pro + sincronia Trakt; Ultra + TMDB pago. Ultra "até 3 TVs":
  a assinatura da Play já vale em toda TV com a mesma conta Google; limitar a 3 exige servidor (não feito).
