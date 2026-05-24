#!/usr/bin/env bash
# ① CARD 단독 결제 — customer 1, 50,000원 → 200 PAID
set -euo pipefail

echo "── ① CARD 단독 결제 (customer 1) ──"
resp=$(curl -s -X POST http://localhost/booking \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: demo-1-$(date +%s%N)" \
  -d '{"customerId":1,"productId":1,"payments":[{"method":"CARD","amount":50000}]}')

echo "응답: $resp"
echo

status=$(echo "$resp" | grep -oE '"status":"[A-Z_]+"' | head -1 | cut -d'"' -f4)
[ "$status" = "PAID" ] && echo "✅ PAID 확인" || { echo "❌ expected PAID, got $status"; exit 1; }

echo
echo "── DB 검증 ──"
docker exec payments-mysql mysql -uroot -proot payments -e "
  SELECT b.id, b.customer_id, b.status, p.method, p.status AS pay_status, p.pg_auth_id
  FROM booking b JOIN payment p ON p.booking_id = b.id
  ORDER BY b.id DESC LIMIT 1;
" 2>/dev/null
