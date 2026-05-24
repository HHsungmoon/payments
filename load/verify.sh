#!/usr/bin/env bash
# 부하 테스트 후 DB & Redis 상태 검증
# 기대: PAID=10, WAITING=30, 합계≈1000 (나머지는 SOLDOUT 응답)
set -euo pipefail

MYSQL="docker exec payments-mysql mysql -uroot -proot payments -sNe"
REDIS="docker exec payments-redis redis-cli"

paid=$($MYSQL "SELECT COUNT(*) FROM booking WHERE status='PAID';" 2>/dev/null)
waiting=$($MYSQL "SELECT COUNT(*) FROM booking WHERE status='WAITING';" 2>/dev/null)
pending=$($MYSQL "SELECT COUNT(*) FROM booking WHERE status='PENDING';" 2>/dev/null)
failed=$($MYSQL "SELECT COUNT(*) FROM booking WHERE status='FAILED';" 2>/dev/null)
total_booking=$($MYSQL "SELECT COUNT(*) FROM booking;" 2>/dev/null)

stock=$($REDIS GET stock:product:1)
wlen=$($REDIS ZCARD waitlist:product:1)

cap_payments=$($MYSQL "SELECT COUNT(*) FROM payment WHERE status='CAPTURED';" 2>/dev/null)
point_committed=$($MYSQL "SELECT COUNT(*) FROM point_tx WHERE status='COMMITTED';" 2>/dev/null)
outbox_pending=$($MYSQL "SELECT COUNT(*) FROM outbox_event WHERE status='PENDING';" 2>/dev/null)

echo
echo "──────── 부하 테스트 결과 검증 ────────"
printf "  booking   PAID    = %s  (기대: 10)\n" "$paid"
printf "  booking   WAITING = %s  (기대: 30)\n" "$waiting"
printf "  booking   PENDING = %s  (기대:  0)\n" "$pending"
printf "  booking   FAILED  = %s  (기대:  0)\n" "$failed"
printf "  booking   TOTAL   = %s\n" "$total_booking"
echo
printf "  redis     stock      = %s  (기대: 0)\n" "$stock"
printf "  redis     waitlist   = %s  (기대: 30)\n" "$wlen"
echo
printf "  payment   CAPTURED   = %s  (기대: 10)\n" "$cap_payments"
printf "  point_tx  COMMITTED  = %s\n" "$point_committed"
printf "  outbox    PENDING    = %s  (워커가 처리 중일 수 있음)\n" "$outbox_pending"
echo

# 핵심 검증
fail=0
[ "$paid" = "10" ]    || { echo "  ✗ PAID expected 10, got $paid"; fail=1; }
[ "$waiting" = "30" ] || { echo "  ✗ WAITING expected 30, got $waiting"; fail=1; }
[ "$stock" = "0" ]    || { echo "  ✗ stock expected 0, got $stock"; fail=1; }
[ "$wlen" = "30" ]    || { echo "  ✗ waitlist len expected 30, got $wlen"; fail=1; }
[ "$pending" = "0" ]  || { echo "  ✗ PENDING expected 0, got $pending"; fail=1; }

if [ $fail -eq 0 ]; then
  echo "  ✅ ALL CHECKS PASSED"
else
  echo "  ❌ FAILURES detected"
  exit 1
fi
