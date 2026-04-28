#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────────────────────────
# stripe-webhook-test.sh
# Generates and optionally fires signed Stripe webhook test events.
#
# Usage:
#   ./stripe-webhook-test.sh <stripe-subscription-id> [--fire]
#
#   --fire   Actually send the curls (interactive prompt for which one)
#            Without --fire it just prints the commands.
# ─────────────────────────────────────────────────────────────────────────────

STRIPE_SUB_ID="${1}"
FIRE="${2}"

# ── Config ────────────────────────────────────────────────────────────────────
# Set WEBHOOK_SECRET via environment variable or pass it as the first argument
# before the subscription ID.
# Example: WEBHOOK_SECRET=whsec_xxx ./stripe-webhook-test.sh sub_xxx [--fire]
WEBHOOK_SECRET="${WEBHOOK_SECRET:-}"
ENDPOINT="${STRIPE_WEBHOOK_ENDPOINT:-https://localhost:9443/api/am/stripe/webhook}"
# ─────────────────────────────────────────────────────────────────────────────

if [[ -z "$STRIPE_SUB_ID" ]]; then
  echo "Usage: $0 <stripe-subscription-id> [--fire]"
  echo "  e.g. WEBHOOK_SECRET=whsec_xxx $0 sub_1TR3Iv0IpsFtSoJnErT7mwnn"
  echo "  e.g. WEBHOOK_SECRET=whsec_xxx $0 sub_1TR3Iv0IpsFtSoJnErT7mwnn --fire"
  exit 1
fi

if [[ -z "$WEBHOOK_SECRET" ]]; then
  echo "Error: WEBHOOK_SECRET environment variable is not set."
  echo "  e.g. WEBHOOK_SECRET=whsec_xxx $0 $STRIPE_SUB_ID"
  exit 1
fi

sign() {
  local event_type="$1"
  local payload="$2"
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

TS=$(date +%s)

# ── Payload: invoice.payment_failed (BLOCK) ───────────────────────────────────
PAYLOAD_BLOCK="{\"id\":\"evt_test_block_${TS}\",\"type\":\"invoice.payment_failed\",\"data\":{\"object\":{\"id\":\"in_test_001\",\"object\":\"invoice\",\"subscription\":\"${STRIPE_SUB_ID}\",\"status\":\"open\"}}}"

# ── Payload: customer.subscription.updated status=active (UNBLOCK) ───────────
PAYLOAD_UNBLOCK="{\"id\":\"evt_test_unblock_${TS}\",\"type\":\"customer.subscription.updated\",\"data\":{\"object\":{\"id\":\"${STRIPE_SUB_ID}\",\"object\":\"subscription\",\"status\":\"active\"}}}"

CURL_BLOCK=$(sign "invoice.payment_failed" "$PAYLOAD_BLOCK")
CURL_UNBLOCK=$(sign "customer.subscription.updated" "$PAYLOAD_UNBLOCK")

echo ""
echo "╔══════════════════════════════════════════════════════════════════╗"
echo "║  Fix 2 — invoice.payment_failed  →  BLOCK subscription          ║"
echo "╚══════════════════════════════════════════════════════════════════╝"
echo "$CURL_BLOCK"
echo ""
echo "╔══════════════════════════════════════════════════════════════════╗"
echo "║  Fix 3 — subscription.updated status=active  →  UNBLOCK         ║"
echo "╚══════════════════════════════════════════════════════════════════╝"
echo "$CURL_UNBLOCK"
echo ""

if [[ "$FIRE" == "--fire" ]]; then
  echo "Which curl do you want to fire?"
  echo "  1) BLOCK   (invoice.payment_failed)"
  echo "  2) UNBLOCK (subscription.updated active)"
  echo "  3) Both (BLOCK first, then UNBLOCK)"
  echo "  q) Quit"
  read -rp "Choice [1/2/3/q]: " choice
  case "$choice" in
    1)
      echo ""
      echo "▶ Firing BLOCK..."
      eval "$CURL_BLOCK"
      echo ""
      ;;
    2)
      echo ""
      echo "▶ Firing UNBLOCK..."
      eval "$CURL_UNBLOCK"
      echo ""
      ;;
    3)
      echo ""
      echo "▶ Firing BLOCK..."
      eval "$CURL_BLOCK"
      echo ""
      sleep 1
      echo "▶ Firing UNBLOCK..."
      eval "$CURL_UNBLOCK"
      echo ""
      ;;
    *)
      echo "Aborted."
      ;;
  esac
fi
