# Build da Play

```
./gradlew bundleRelease -Pplay          # AAB para o Play Console (app/build/outputs/bundle/release/)
./gradlew assembleRelease -Pplay        # APKs para testar na TV antes de subir
```

Sem flavor de propósito: com flavor, todas as tarefas (`assembleRelease`, `testReleaseUnitTest`) mudariam de nome e os
workflows da Sala quebrariam. O `-Pplay` muda (ver `app/build.gradle.kts`):

| O quê | Site / Sala | Play |
|---|---|---|
| applicationId | `br.paulo.tvlauncher` | `tv.sofaly.launcher` |
| `REQUEST_INSTALL_PACKAGES` | sim (atualizador, painel) | **não**: painel instala pelo shell (`pm install`) |
| `MANAGE_EXTERNAL_STORAGE` | sim | **não** (ver pendências: aba Arquivos e unidade de rede) |
| `QUERY_ALL_PACKAGES` | sim | **não**: `<queries>` LAUNCHER + LEANBACK_LAUNCHER + plugins |
| `USE_EXACT_ALARM` | sim | **não**: `SCHEDULE_EXACT_ALARM` (o shell libera; sem ela o alarme toca com folga) |
| Atualização | GitHub (Atualizador.kt) | a própria Play |
| Cobrança | tudo liberado (Ultra) | Play Billing 8.3 (CobrancaPlay.kt) |
| Clima | igual à Play (19/11/2026, DataSources.kt) | MET Norway no Básico; WeatherAPI pelo api.sofaly.tv no Pro; Open-Meteo paga no Ultra só com contrato |
| Cidade / lugar | lista GeoNames do APK (desde 19/11/2026), IP (ipwho.is) | lista GeoNames dentro do APK (CidadesOffline.kt), fuso horário |
| Fichas | igual à Play (19/11/2026, DataSources.kt) | Trakt no Básico; TMDB no Pro só com contrato (chave vazia na Play); Simkl com a conta da pessoa, nota sem conta só com licença (Pro) |
| Família (78/79 automáticos) | sim | não; "Estou bem" (a pessoa aperta) e o plugin Extras |
| Licenças | `assets/licencas.txt` | seção "Dados" trocada por `src/play/assets/licencas-dados-play.txt` |

Assinatura: a Play usa **Play App Signing**. A chave de upload é nova e só do Paulo; nunca a chave pública dos Releases.
