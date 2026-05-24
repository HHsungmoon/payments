#!/usr/bin/env bash
# 시연 전 — DB & Redis 초기화 (customer 1~99 영역)
set -euo pipefail

MYSQL="docker exec payments-mysql mysql -uroot -proot payments -e"
REDIS="docker exec payments-redis redis-cli"

echo "[reset] booking/payment/point_transaction/outbox 비우기"
$MYSQL "
  DELETE FROM payment;
  DELETE FROM point_transaction;
  DELETE FROM booking;
  DELETE FROM outbox_event;
" 2>/dev/null

echo "[reset] customer_point 잔액 100,000 복원 (1~99)"
$MYSQL "UPDATE customer_point SET balance = 100000 WHERE customer_id BETWEEN 1 AND 99;" 2>/dev/null

echo "[reset] Redis stock=10, waitlist 비우기, idem/wait_token 삭제"
$REDIS SET "stock:product:1" 10 > /dev/null
$REDIS DEL "waitlist:product:1" > /dev/null
for pat in "idem:*" "wait_token:*" "lock:*"; do
  keys=$($REDIS --scan --pattern "$pat" || true)
  if [ -n "$keys" ]; then
    echo "$keys" | xargs -I{} docker exec payments-redis redis-cli DEL {} > /dev/null
  fi
done

echo "[reset] OK  stock=$($REDIS GET stock:product:1) waitlist=$($REDIS ZCARD waitlist:product:1)"
