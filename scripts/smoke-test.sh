#!/usr/bin/env bash
# Smoke test: verifica che il jar fitp-enricher sia stato caricato correttamente
# da Keycloak e che l'AuthenticatorFactory sia binary-compatible con la versione
# di Keycloak in esecuzione.
#
# La verifica e robusta: invece di fidarsi dei log, interroga la Admin REST API
# per controllare che (a) il provider id "fitp-enricher" sia registrato e
# (b) la factory esponga TUTTE le 7 ProviderConfigProperty attese.
# Se uno qualsiasi metodo SPI cambia firma in una versione futura, la factory
# non viene caricata e il test fallisce.

set -euo pipefail

KC_URL="${KC_URL:-http://keycloak:8080}"
USER="${KC_ADMIN_USER:-admin}"
PASS="${KC_ADMIN_PASSWORD:-admin}"
PROVIDER_ID="fitp-enricher"
IMAGE="${KEYCLOAK_IMAGE:-unknown}"

EXPECTED_PROPS=(
  graph.tenantId
  graph.clientId
  graph.clientSecret
  graph.timeoutMs
  graph.retryCount
  graph.failOnError
  graph.trustEmail
)

log()  { printf '[smoke-test] %s\n' "$*"; }
fail() { printf '[smoke-test][FAIL] %s\n' "$*" >&2; exit 1; }

log "Target image: $IMAGE"
log "Target URL:   $KC_URL"

log "[1/4] Attendo che Keycloak risponda ..."
ready=0
for i in $(seq 1 180); do
  if curl -fsS "$KC_URL/realms/master/.well-known/openid-configuration" >/dev/null 2>&1; then
    ready=1
    log "       ready dopo ${i}s"
    break
  fi
  sleep 1
done
[ "$ready" = 1 ] || fail "Keycloak non raggiungibile dopo 180s"

log "[2/4] Ottengo token admin ..."
TOKEN_JSON=$(curl -fsS -X POST \
  -d "client_id=admin-cli" \
  -d "username=$USER" \
  -d "password=$PASS" \
  -d "grant_type=password" \
  "$KC_URL/realms/master/protocol/openid-connect/token") \
  || fail "token endpoint non risponde"

TOKEN=$(echo "$TOKEN_JSON" | jq -r .access_token)
[ -n "$TOKEN" ] && [ "$TOKEN" != "null" ] || fail "access_token vuoto"
log "       ok"

log "[3/4] Verifico che '$PROVIDER_ID' sia tra gli authenticator-providers ..."
PROVIDERS=$(curl -fsS -H "Authorization: Bearer $TOKEN" \
  "$KC_URL/admin/realms/master/authentication/authenticator-providers") \
  || fail "non riesco a leggere authenticator-providers"

if ! echo "$PROVIDERS" | jq -e --arg id "$PROVIDER_ID" '.[] | select(.id == $id)' >/dev/null; then
  log "Provider registrati:"
  echo "$PROVIDERS" | jq -r '.[].id' | sed 's/^/  - /' >&2
  fail "'$PROVIDER_ID' non registrato (jar non caricato o SPI incompatibile)"
fi
DISPLAY=$(echo "$PROVIDERS" | jq -r --arg id "$PROVIDER_ID" '.[] | select(.id==$id) | .displayName')
log "       ok (displayName: $DISPLAY)"

log "[4/4] Verifico le ProviderConfigProperty esposte dalla factory ..."
CFG=$(curl -fsS -H "Authorization: Bearer $TOKEN" \
  "$KC_URL/admin/realms/master/authentication/config-description/$PROVIDER_ID") \
  || fail "config-description non disponibile (factory non risponde)"

missing=0
for name in "${EXPECTED_PROPS[@]}"; do
  if echo "$CFG" | jq -e --arg n "$name" '.properties[] | select(.name==$n)' >/dev/null; then
    log "       ok: $name"
  else
    log "       MANCA: $name"
    missing=$((missing+1))
  fi
done
[ "$missing" = 0 ] || fail "$missing ProviderConfigProperty mancanti"

KC_VERSION=$(echo "$TOKEN_JSON" | jq -r '.access_token' \
  | awk -F. '{print $2}' \
  | base64 -d 2>/dev/null \
  | jq -r '.iss' 2>/dev/null || echo "$KC_URL")

cat <<EOF

============================================================
PASS: fitp-enricher caricato e binary-compatible
       image:    $IMAGE
       provider: $PROVIDER_ID
       props:    ${#EXPECTED_PROPS[@]} / ${#EXPECTED_PROPS[@]}
============================================================
EOF
