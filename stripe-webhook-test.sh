#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────────────────────────
# stripe-webhook-test.sh
# Looks up Stripe subscription IDs for an APIM application UUID from the
# stripe_am database, then generates and optionally fires signed Stripe
# webhook test events.
#
# Usage:
#   ./stripe-webhook-test.sh <application-uuid> [--fire]
#
#   --fire   Actually send the curls (interactive prompt for which event type)
#            Without --fire it just prints the commands.
#
# Required env vars:
#   WEBHOOK_SECRET          e.g. WEBHOOK_SECRET=whsec_xxx
#
# Optional env vars (with defaults):
#   STRIPE_WEBHOOK_ENDPOINT  Default: https://localhost:9443/api/am/stripe/webhook
#   DB_HOST                  Default: localhost
#   DB_PORT                  Default: 3306
#   DB_USER                  Default: root
#   DB_PASS                  Default: (empty)
#   DB_NAME                  Default: stripe_am
# ─────────────────────────────────────────────────────────────────────────────

APP_UUID="${1}"
FIRE="${2}"

# ── Config ────────────────────────────────────────────────────────────────────
WEBHOOK_SECRET="${WEBHOOK_SECRET:-}"
ENDPOINT="${STRIPE_WEBHOOK_ENDPOINT:-https://localhost:9443/api/am/stripe/webhook}"

DB_HOST="${DB_HOST:-localhost}"
DB_PORT="${DB_PORT:-3306}"
DB_USER="${DB_USER:-root}"
DB_PASS="${DB_PASS:-}"
DB_NAME="${DB_NAME:-stripe_am}"
# ─────────────────────────────────────────────────────────────────────────────

if [[ -z "$APP_UUID" ]]; then
  echo "Usage: $0 <application-uuid> [--fire]"
  echo "  e.g. WEBHOOK_SECRET=whsec_xxx $0 0d5169e1-af20-4183-8980-8c9a24ec5298"
  echo "  e.g. WEBHOOK_SECRET=whsec_xxx $0 0d5169e1-af20-4183-8980-8c9a24ec5298 --fire"
  exit 1
fi

if [[ -z "$WEBHOOK_SECRET" ]]; then
  echo "Error: WEBHOOK_SECRET environment variable is not set."
  echo "  e.g. WEBHOOK_SECRET=whsec_xxx $0 $APP_UUID"
  exit 1
fi

if ! command -v mysql &>/dev/null; then
  echo "Error: 'mysql' CLI not found. Install it or add it to PATH."
  exit 1
fi

# ── DB lookup ─────────────────────────────────────────────────────────────────
MYSQL_OPTS=(-h "$DB_HOST" -P "$DB_PORT" -u "$DB_USER" --batch --skip-column-names)
[[ -n "$DB_PASS" ]] && MYSQL_OPTS+=("-p${DB_PASS}")

SQL="
SELECT ms.SUBSCRIPTION_ID, s.UUID, s.SUB_STATUS
FROM AM_MONETIZATION_SUBSCRIPTIONS ms
JOIN AM_SUBSCRIPTION s
  ON ms.SUBSCRIBED_API_ID = s.API_ID
 AND ms.SUBSCRIBED_APPLICATION_ID = s.APPLICATION_ID
WHERE s.APPLICATION_ID = (
  SELECT APPLICATION_ID FROM AM_APPLICATION WHERE UUID = '${APP_UUID}'
);"

echo ""
echo "Querying ${DB_NAME} for application UUID: ${APP_UUID} ..."

MYSQL_ERR_FILE=$(mktemp /tmp/stripe_mysql_err.XXXXXX)
ROWS=$(mysql "${MYSQL_OPTS[@]}" "$DB_NAME" -e "$SQL" 2>"$MYSQL_ERR_FILE")
MYSQL_EXIT=$?

if [[ $MYSQL_EXIT -ne 0 ]]; then
  echo "Error: MySQL query failed:"
  cat "$MYSQL_ERR_FILE"
  rm -f "$MYSQL_ERR_FILE"
  exit 1
fi
rm -f "$MYSQL_ERR_FILE"

if [[ -z "$ROWS" ]]; then
  echo "No subscriptions found for application UUID: ${APP_UUID}"
  exit 1
fi

# Parse rows into arrays (tab-separated: SUBSCRIPTION_ID UUID SUB_STATUS)
ROW_ARRAY=()
while IFS= read -r line; do
  [[ -n "$line" ]] && ROW_ARRAY+=("$line")
done <<< "$ROWS"

echo ""
echo "Found ${#ROW_ARRAY[@]} subscription(s):"
echo "────────────────────────────────────────────────────────────────────"
printf "  %-5s %-30s %-40s %s\n" "No." "Stripe Sub ID" "APIM Sub UUID" "Status"
echo "────────────────────────────────────────────────────────────────────"

declare -a STRIPE_IDS APIM_UUIDS SUB_STATUSES
i=0
for row in "${ROW_ARRAY[@]}"; do
  IFS=$'\t' read -r stripe_id apim_uuid sub_status <<< "$row"
  STRIPE_IDS[$i]="$stripe_id"
  APIM_UUIDS[$i]="$apim_uuid"
  SUB_STATUSES[$i]="$sub_status"
  printf "  %-5s %-30s %-40s %s\n" "$((i+1))" "$stripe_id" "$apim_uuid" "$sub_status"
  ((i++))
done
echo "────────────────────────────────────────────────────────────────────"
echo ""

# ── Choose subscription ───────────────────────────────────────────────────────
SELECTED_IDX=0
if [[ ${#ROW_ARRAY[@]} -gt 1 ]]; then
  read -rp "Multiple subscriptions found. Enter number to use [1-${#ROW_ARRAY[@]}] or 'a' for all: " sel
  if [[ "$sel" == "a" ]]; then
    SELECTED_IDX="all"
  elif [[ "$sel" =~ ^[0-9]+$ ]] && (( sel >= 1 && sel <= ${#ROW_ARRAY[@]} )); then
    SELECTED_IDX=$((sel - 1))
  else
    echo "Invalid selection. Aborting."
    exit 1
  fi
fi

# ── Build & emit curl commands for one or all subscriptions ───────────────────
sign() {
  local payload="$1"
  local ts
  ts=$(date +%s)
  local signed="${ts}.${payload}"
  local sig
  sig=$(printf '%s' "$signed" | openssl dgst -sha256 -hmac "$WEBHOOK_SECRET" | awk '{print $2}')
  echo "curl -sk -X POST \"${ENDPOINT}\" \\
  -H \"Content-Type: application/json\" \\
  -H \"Stripe-Signature: t=${ts},v1=${sig}\" \\
  -d '${payload}'"
}

build_curls() {
  local idx="$1"
  local stripe_id="${STRIPE_IDS[$idx]}"
  local TS
  TS=$(date +%s)

  local PAYLOAD_BLOCK="{\"id\":\"evt_test_block_${TS}\",\"type\":\"invoice.payment_failed\",\"data\":{\"object\":{\"id\":\"in_test_001\",\"object\":\"invoice\",\"subscription\":\"${stripe_id}\",\"status\":\"open\"}}}"
  local PAYLOAD_UNBLOCK="{\"id\":\"evt_test_unblock_${TS}\",\"type\":\"customer.subscription.updated\",\"data\":{\"object\":{\"id\":\"${stripe_id}\",\"object\":\"subscription\",\"status\":\"active\"}}}"

  CURL_BLOCK=$(sign "$PAYLOAD_BLOCK")
  CURL_UNBLOCK=$(sign "$PAYLOAD_UNBLOCK")

  echo ""
  echo "  Stripe Sub: ${stripe_id}  |  APIM UUID: ${APIM_UUIDS[$idx]}  |  Status: ${SUB_STATUSES[$idx]}"
  echo "╔══════════════════════════════════════════════════════════════════╗"
  echo "║  invoice.payment_failed  →  BLOCK subscription                  ║"
  echo "╚══════════════════════════════════════════════════════════════════╝"
  echo "$CURL_BLOCK"
  echo ""
  echo "╔══════════════════════════════════════════════════════════════════╗"
  echo "║  subscription.updated status=active  →  UNBLOCK                 ║"
  echo "╚══════════════════════════════════════════════════════════════════╝"
  echo "$CURL_UNBLOCK"
  echo ""
}

fire_curls() {
  local idx="$1"
  local stripe_id="${STRIPE_IDS[$idx]}"
  local TS
  TS=$(date +%s)

  local PAYLOAD_BLOCK="{\"id\":\"evt_test_block_${TS}\",\"type\":\"invoice.payment_failed\",\"data\":{\"object\":{\"id\":\"in_test_001\",\"object\":\"invoice\",\"subscription\":\"${stripe_id}\",\"status\":\"open\"}}}"
  local PAYLOAD_UNBLOCK="{\"id\":\"evt_test_unblock_${TS}\",\"type\":\"customer.subscription.updated\",\"data\":{\"object\":{\"id\":\"${stripe_id}\",\"object\":\"subscription\",\"status\":\"active\"}}}"

  local CURL_BLOCK CURL_UNBLOCK
  CURL_BLOCK=$(sign "$PAYLOAD_BLOCK")
  CURL_UNBLOCK=$(sign "$PAYLOAD_UNBLOCK")

  echo ""
  echo "  Stripe Sub: ${stripe_id}"
  echo "Which event do you want to fire?"
  echo "  1) BLOCK   (invoice.payment_failed)"
  echo "  2) UNBLOCK (subscription.updated active)"
  echo "  3) Both (BLOCK first, then UNBLOCK)"
  echo "  q) Skip / Quit"
  read -rp "Choice [1/2/3/q]: " choice
  case "$choice" in
    1)
      echo "▶ Firing BLOCK..."
      eval "$CURL_BLOCK"
      echo ""
      ;;
    2)
      echo "▶ Firing UNBLOCK..."
      eval "$CURL_UNBLOCK"
      echo ""
      ;;
    3)
      echo "▶ Firing BLOCK..."
      eval "$CURL_BLOCK"
      echo ""
      sleep 1
      echo "▶ Firing UNBLOCK..."
      eval "$CURL_UNBLOCK"
      echo ""
      ;;
    *)
      echo "Skipped."
      ;;
  esac
}

# ── Main dispatch ─────────────────────────────────────────────────────────────
if [[ "$SELECTED_IDX" == "all" ]]; then
  for idx in "${!ROW_ARRAY[@]}"; do
    build_curls "$idx"
  done
  if [[ "$FIRE" == "--fire" ]]; then
    for idx in "${!ROW_ARRAY[@]}"; do
      fire_curls "$idx"
    done
  fi
else
  build_curls "$SELECTED_IDX"
  if [[ "$FIRE" == "--fire" ]]; then
    fire_curls "$SELECTED_IDX"
  fi
fi
