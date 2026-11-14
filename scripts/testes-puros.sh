#!/usr/bin/env bash
# Roda os testes JVM que NÃO dependem do Android, sem SDK e sem Gradle (ex.: na nuvem, sem o PC de build). 09/11/2026.
# Baixa uma vez o compilador Kotlin (mesma versão do projeto) e os jars de teste para $CACHE e compila cada teste
# só com os arquivos de que ele precisa (compilar todos os "puros" juntos falha: alguns usam o Config, que é Android).
# Uso: bash scripts/testes-puros.sh          (da raiz do repo)
set -euo pipefail
RAIZ="$(cd "$(dirname "$0")/.." && pwd)"
CACHE="${CACHE:-$HOME/.cache/tv-launcher-testes}"
KOTLIN=2.2.20
MAVEN=https://maven-central.storage-download.googleapis.com/maven2   # espelho: o Maven Central às vezes dá 429
M="$RAIZ/app/src/main/kotlin/br/paulo/tvlauncher"
T="$RAIZ/app/src/test/kotlin/br/paulo/tvlauncher"

mkdir -p "$CACHE"
if [ ! -x "$CACHE/kotlinc/bin/kotlinc" ]; then
  curl -sSfL -o "$CACHE/kc.zip" "https://github.com/JetBrains/kotlin/releases/download/v$KOTLIN/kotlin-compiler-$KOTLIN.zip"
  (cd "$CACHE" && unzip -qo kc.zip && rm kc.zip)
fi
for j in junit/junit/4.13.2/junit-4.13.2.jar org/hamcrest/hamcrest-core/1.3/hamcrest-core-1.3.jar org/json/json/20240303/json-20240303.jar; do
  [ -f "$CACHE/$(basename $j)" ] || curl -sSfL -o "$CACHE/$(basename $j)" "$MAVEN/$j"
done
CP="$CACHE/junit-4.13.2.jar:$CACHE/hamcrest-core-1.3.jar:$CACHE/json-20240303.jar"

# teste -> arquivos de produção que ele usa (acrescente aqui quando um teste novo for puro)
declare -A FONTES=(
  [FimDoVideoTest]="FimDoVideo"
  [PainelHttpTest]="PainelHttp"
  [PainelSegurancaTest]="PainelSeguranca PainelHttp JsonSeguro"
  [McpAutorizacaoTest]="McpAutorizacao"
  [McpProtocoloTest]="McpProtocolo"
  [PainelHtmlTest]=""
  [ConfirmacoesTest]="Confirmacoes"
  [CertificadoX509Test]="CertificadoX509"
  [PaginaCertificadoTest]="PaginaCertificado"
  [DriveRegrasTest]="DriveRegras BackupNoturnoRegras"
  [CanalAtualizacaoTest]="CanalAtualizacao"
  [TeclaFalsaTest]="TeclaFalsa"
  [RoteiroConferenciaTest]="RoteiroConferencia"
  [LgNetcastTest]="LgNetcast"
  [FrasesAppsTest]="FrasesApps"
)

falhou=0
cd "$RAIZ/app"   # o PainelHtmlTest lê src/main/assets com caminho relativo, como no Gradle
for teste in "${!FONTES[@]}"; do
  saida="$CACHE/out-$teste"; rm -rf "$saida"
  arquivos=("$T/$teste.kt"); for f in ${FONTES[$teste]}; do arquivos+=("$M/$f.kt"); done
  "$CACHE/kotlinc/bin/kotlinc" "${arquivos[@]}" -cp "$CP" -d "$saida" -nowarn 2>&1 | grep -v JAVA_TOOL_OPTIONS || true
  if java -cp "$saida:$CP:$CACHE/kotlinc/lib/kotlin-stdlib.jar" org.junit.runner.JUnitCore "br.paulo.tvlauncher.$teste" > "$saida.log" 2>&1; then
    echo "ok     $teste: $(grep -E '^OK' "$saida.log")"
  else
    echo "FALHOU $teste (detalhes em $saida.log)"; falhou=1
  fi
done
exit $falhou
