#!/usr/bin/env bash
# ② 매진 → WAITING — 1번 PAID + 9건 추가 PAID = 매진 → 11번째 호출은 대기열 진입
# 사전 환경: stock=9 (시연 ① 직후 권장 — customer 1 이 이미 PAID)
# active_key UNIQUE 로 같은 customer 재사용 차단되므로 customer 2~10 사용
set -euo pipefail

echo "── ② 9건 동시 결제 (customer 2~10) — 매진 ──"
for i in $(seq 2 10); do
  ( curl -s -X POST http://localhost/booking \
      -H "Content-Type: application/json" \
      -H "Idempotency-Key: demo-2-c${i}-$(date +%s%N)" \
      -d "{\"customerId\":${i},\"productId\":1,\"payments\":[{\"method\":\"CARD\",\"amount\":50000}]}" > /dev/null ) &
done
wait

echo "── Redis 상태 ──"
stock=$(docker exec payments-redis redis-cli GET "stock:product:1")
echo "stock=$stock  (기대 0)"
[ "$stock" = "0" ] || { echo "❌ stock expected 0"; exit 1; }

echo
echo "── 11번째 호출 (customer 17) → WAITING ──"
resp=$(curl -s -X POST http://localhost/booking \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: demo-2-c17-$(date +%s%N)" \
  -d '{"customerId":17,"productId":1,"payments":[{"method":"CARD","amount":50000}]}')
echo "응답: $resp"

status=$(echo "$resp" | grep -oE '"status":"[A-Z_]+"' | head -1 | cut -d'"' -f4)
[ "$status" = "WAITING" ] && echo "✅ WAITING 확인" || { echo "❌ expected WAITING, got $status"; exit 1; }

wlen=$(docker exec payments-redis redis-cli ZCARD "waitlist:product:1")
echo "waitlist len=$wlen  (기대 1)"

echo
echo "── DB 검증 (booking 상태별 카운트) ──"
docker exec payments-mysql mysql -uroot -proot payments -e "
  SELECT status, COUNT(*) cnt FROM booking GROUP BY status ORDER BY status;
" 2>/dev/null
