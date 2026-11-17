# Minutos do GitHub Actions (elevação 21.1, 17/11/2026)

O que mudou: o workflow "Build e Release" (`release.yml`) roda a suíte JVM (`:app:testReleaseUnitTest`) em **todo PR**,
antes de compilar o APK. Antes os testes só rodavam no Release manual. Junto, um push novo no mesmo PR cancela a
execução anterior.

## Números medidos (17/11/2026)

| O quê | Valor | De onde |
|---|---|---|
| Passo "Testes JVM" | 1 min 48 s | execução 43947373548 (Release de 16/11), passo 9 |
| PR só compilando | 5 a 12 min, típico 6 min | 40 últimas execuções de PR do `release.yml` |
| Execuções de PR | 286 entre 13/11 e 17/11 (~60 por dia, ritmo de desenvolvimento intenso) | API do Actions |

Estimativa por mês, no ritmo de hoje (~1.800 PRs/push por mês): os testes somam **~3.300 min/mês**; o build de PR
já gasta **~11.000 min/mês**. Num ritmo calmo (10 execuções por dia): ~550 min/mês a mais.
O cancelamento do push anterior devolve parte disso (não medido ainda: medir no fim do primeiro mês).

## Quanto custa

- **Repositório público (como está hoje): R$ 0.** Runner padrão em repositório público é grátis
  (docs.github.com/en/billing/reference/actions-runner-pricing).
- **Repositório privado (decisão de 15/11/2026, código fechado):** o plano Free inclui 2.000 min/mês e o Pro 3.000;
  passou disso, US$ 0,006 por minuto de Linux 2-core (mesma página, lida em 17/11/2026). No ritmo de hoje o build de
  PR sozinho já passa da franquia: ~11.000 + 3.300 = ~14.300 min/mês = ~US$ 74/mês no Free (12.300 × 0,006) ou
  ~US$ 68/mês no Pro (11.300 × 0,006). Só a parte dos testes: ~US$ 20/mês. No ritmo calmo cabe na franquia.
- Para baixar: juntar commits antes de abrir/atualizar PR, `[skip ci]` em commit só de texto, e PR em rascunho
  acumulando trabalho (como já fazemos).
