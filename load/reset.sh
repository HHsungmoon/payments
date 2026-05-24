#!/usr/bin/env bash
# 부하 테스트 직전 — DB & Redis 깨끗하게 초기화
set -euo pipefail

MYSQL="docker exec payments-mysql mysql -uroot -proot payments -e"
REDIS="docker exec payments-redis redis-cli"

echo "[reset] booking/payment/point_transaction/outbox 정리..."
$MYSQL "
  DELETE FROM payment;
  DELETE FROM point_transaction;
  DELETE FROM booking;
  DELETE FROM outbox_event;
" 2>/dev/null

echo "[reset] customer_point 잔액 100,000 복원 (100~999)..."
$MYSQL "UPDATE customer_point SET balance = 100000 WHERE customer_id BETWEEN 100 AND 999;" 2>/dev/null

echo "[reset] Redis stock + waitlist + idem 정리..."
$REDIS SET "stock:product:1" 10 > /dev/null
$REDIS DEL "waitlist:product:1" > /dev/null
# idem 캐시 + wait_token 전체 제거
for pattern in "idem:*" "wait_token:*" "lock:*"; do
  keys=$($REDIS --scan --pattern "$pattern" || true)
  if [ -n "$keys" ]; then
    echo "$keys" | xargs -I{} docker exec payments-redis redis-cli DEL {} > /dev/null
  fi
done

echo "[reset] 완료. 재고=$($REDIS GET stock:product:1), 대기열=$($REDIS ZCARD waitlist:product:1)"
