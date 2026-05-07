#!/usr/bin/env bash
# Esegue lo smoke test contro una matrice di immagini Keycloak.
# Stampa alla fine un report PASS/FAIL per versione.
#
# Override matrice via env:
#   IMAGES="quay.io/keycloak/keycloak:24.0 quay.io/keycloak/keycloak:25.0" ./scripts/test-matrix.sh

set -uo pipefail

cd "$(dirname "$0")/.."

DEFAULT_IMAGES=(
  "quay.io/keycloak/keycloak:22.0.5"
  "quay.io/keycloak/keycloak:23.0.7"
  "quay.io/keycloak/keycloak:24.0.5"
  "quay.io/keycloak/keycloak:25.0.6"
  "quay.io/keycloak/keycloak:26.0"
)

if [ -n "${IMAGES:-}" ]; then
  read -r -a IMAGES_ARR <<< "$IMAGES"
else
  IMAGES_ARR=("${DEFAULT_IMAGES[@]}")
fi

JAR="${PLUGIN_JAR:-fitp-enricher-0.2.0.jar}"
JAR_DIR="${JAR_DIR:-./build/libs}"
if [ ! -f "$JAR_DIR/$JAR" ]; then
  echo "ERRORE: $JAR_DIR/$JAR non trovato." >&2
  echo "Builda prima con 'gradle build' (build/libs/) o 'mvn clean package' + JAR_DIR=./target." >&2
  exit 1
fi

declare -a RESULTS

for IMG in "${IMAGES_ARR[@]}"; do
  echo
  echo "############################################################"
  echo "# TEST: $IMG"
  echo "############################################################"

  KEYCLOAK_IMAGE="$IMG" PLUGIN_JAR="$JAR" JAR_DIR="$JAR_DIR" \
    docker compose -f docker-compose.test.yml up \
      --abort-on-container-exit \
      --exit-code-from smoke-tests \
      --force-recreate \
      --renew-anon-volumes
  rc=$?

  if [ "$rc" = 0 ]; then
    RESULTS+=("PASS  $IMG")
  else
    RESULTS+=("FAIL  $IMG  (exit $rc)")
  fi

  KEYCLOAK_IMAGE="$IMG" PLUGIN_JAR="$JAR" JAR_DIR="$JAR_DIR" \
    docker compose -f docker-compose.test.yml down -v >/dev/null 2>&1 || true
done

echo
echo "============================================================"
echo "RIEPILOGO"
echo "============================================================"
fail_count=0
for r in "${RESULTS[@]}"; do
  echo "  $r"
  case "$r" in FAIL*) fail_count=$((fail_count+1));; esac
done
echo "============================================================"

[ "$fail_count" = 0 ] || exit 1
