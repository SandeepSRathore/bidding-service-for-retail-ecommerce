#!/usr/bin/env bash
# End-to-end demo against the running stack: 2 orders, 10 vendors, one sealed-bid window.
# Start the stack with a short window first:
#   mvn package -DskipTests && AUCTION_WINDOW_DURATION=PT2M docker compose up -d --build
set -euo pipefail

GATEWAY=${GATEWAY:-http://localhost:8080}
MAILPIT=${MAILPIT:-http://localhost:8025}
RUN=$(date +%s)

step() { printf '\n\033[1m== %s\033[0m\n' "$*"; }
fail() { printf '\033[31mFAIL: %s\033[0m\n' "$*"; exit 1; }
api() { # method path token [body]
  local args=(-sS -X "$1" "$GATEWAY$2" -H "Authorization: Bearer $3" -H 'Content-Type: application/json')
  [ $# -ge 4 ] && args+=(-d "$4")
  curl "${args[@]}"
}
token() { curl -sS -X POST "$GATEWAY/auth/dev-token" -H 'Content-Type: application/json' -d "$1" | jq -r .accessToken; }
mail_count() { # subject search
  curl -sS -G "$MAILPIT/api/v1/search" --data-urlencode "query=subject:\"$1\"" | jq '.messages_count'
}
wait_for_mail() { # subject expected
  for _ in $(seq 1 60); do
    [ "$(mail_count "$1")" -ge "$2" ] && return 0
    sleep 1
  done
  fail "expected $2 email(s) with subject \"$1\", found $(mail_count "$1")"
}

step "Clearing Mailpit inbox"
curl -sS -X DELETE "$MAILPIT/api/v1/messages" >/dev/null

ADMIN=$(token '{"role":"ADMIN"}')

step "Registering 10 vendors (deactivating any left over from earlier runs)"
for id in $(api GET "/vendors?status=ACTIVE" "$ADMIN" | jq -r '.[].id'); do
  api PATCH "/vendors/$id/status" "$ADMIN" '{"status":"INACTIVE"}' >/dev/null
done
VENDORS=()
for i in $(seq 0 9); do
  id=$(api POST /vendors "$ADMIN" "{\"name\":\"Vendor $i\",\"email\":\"vendor$i.$RUN@carriers.example\"}" | jq -r .id)
  VENDORS[$i]=$id
done
printf '  %s\n' "${VENDORS[@]}"

step "Creating 2 orders"
ORDERS=()
for i in 1 2; do
  id=$(api POST /orders "$ADMIN" \
    "{\"customerRef\":\"CUST-$i\",\"pickupAddress\":\"Warehouse 7, Pune\",\"dropAddress\":\"$i MG Road, Pune\",\"weightKg\":2.5}" \
    | jq -r .id)
  ORDERS[$((i - 1))]=$id
done
printf '  %s\n' "${ORDERS[@]}"

step "Opening a bidding window"
AUCTION_JSON=$(api POST /auctions "$ADMIN" "{\"orderIds\":[\"${ORDERS[0]}\",\"${ORDERS[1]}\"]}")
AUCTION=$(jq -r .id <<<"$AUCTION_JSON")
ENDS_AT=$(jq -r .endsAt <<<"$AUCTION_JSON")
echo "  auction $AUCTION closes at $ENDS_AT"

step "Checking all 10 vendors were emailed"
wait_for_mail "Bidding open" 10
echo "  10 'Bidding open' emails in Mailpit ($MAILPIT)"

step "Vendors bid 200, 190, ..., 110 per order; Vendor 0 then revises to 105"
for i in $(seq 0 9); do
  VT=$(token "{\"role\":\"VENDOR\",\"vendorId\":\"${VENDORS[$i]}\"}")
  price=$((200 - i * 10))
  api PUT "/auctions/$AUCTION/bid" "$VT" "{\"pricePerOrder\":$price}" | jq -c '{vendorId, pricePerOrder}'
done
V0=$(token "{\"role\":\"VENDOR\",\"vendorId\":\"${VENDORS[0]}\"}")
api PUT "/auctions/$AUCTION/bid" "$V0" '{"pricePerOrder":105}' | jq -c '{vendorId, pricePerOrder, revised: .updatedAt}'

step "Waiting for the window to close"
end_epoch=$(python3 -c "import datetime,sys; print(int(datetime.datetime.fromisoformat(sys.argv[1].replace('Z','+00:00')).timestamp()))" "$ENDS_AT")
remaining=$((end_epoch - $(date +%s) + 1))
[ "$remaining" -gt 600 ] && fail "window closes in ${remaining}s; restart with AUCTION_WINDOW_DURATION=PT2M"
[ "$remaining" -gt 0 ] && { echo "  sleeping ${remaining}s"; sleep "$remaining"; }

step "A late bid is rejected"
V9=$(token "{\"role\":\"VENDOR\",\"vendorId\":\"${VENDORS[9]}\"}")
late=$(api PUT "/auctions/$AUCTION/bid" "$V9" '{"pricePerOrder":1}')
echo "  $(jq -c '{status, code}' <<<"$late")"
[ "$(jq -r .code <<<"$late")" = "AUCTION_CLOSED" ] || fail "late bid was not rejected"

step "Waiting for the award"
for _ in $(seq 1 30); do
  status=$(api GET "/auctions/$AUCTION" "$ADMIN" | jq -r .status)
  [ "$status" = "AWARDED" ] && break
  sleep 1
done
api GET "/auctions/$AUCTION" "$ADMIN" | jq -c '{status, winnerVendorId, winningPricePerOrder}'
[ "$status" = "AWARDED" ] || fail "auction not awarded"

step "Both orders are assigned to Vendor 0 at 105 per order"
for o in "${ORDERS[@]}"; do
  for _ in $(seq 1 30); do
    order=$(api GET "/orders/$o" "$ADMIN")
    [ "$(jq -r .status <<<"$order")" = "ASSIGNED" ] && break
    sleep 1
  done
  echo "  $(jq -c '{id, status, vendorId, deliveryCharge}' <<<"$order")"
  [ "$(jq -r .vendorId <<<"$order")" = "${VENDORS[0]}" ] || fail "order $o not assigned to Vendor 0"
done

step "Checking result emails"
wait_for_mail "You won auction $AUCTION" 1
wait_for_mail "Auction $AUCTION result: not selected" 9
echo "  1 manifest email to the winner, 9 'not selected' emails"

printf '\n\033[32mDemo passed.\033[0m Browse the emails at %s\n' "$MAILPIT"
