#!/usr/bin/env bash
# ⑥ 좀비 워커 — PAID booking 1건 강제로 PENDING + AUTHORIZED + authorized_at = NOW - 10m
#     zombie-cleanup 워커가 임계(5m) 초과 인지 → VOID + 재고복구 + FAILED
# 사전 환경:
#   1) 워커 polling 단축: RECON_ZOMBIE_INTERVAL=5000 docker compose up -d app1 app2
#   2) PAID booking 1건 이상 (1-card-only.sh 또는 2-soldout-waiting.sh 실행 후)
set -euo pipefail

ID=$(docker exec payments-mysql mysql -uroot -proot payments -sNe "
  SELECT id FROM booking WHERE status='PAID' ORDER BY id ASC LIMIT 1;
" 2>/dev/null)

if [ -z "$ID" ]; then
  echo "❌ PAID booking 없음. demo/1-card-only.sh 먼저 실행하세요."
  exit 1
fi

echo "── ⑥ booking id=$ID (PAID) → 강제 좀비化 (PENDING + AUTHORIZED + authorized_at=NOW-10m) ──"
before_stock=$(docker exec payments-redis redis-cli GET "stock:product:1")
echo "  before stock=$before_stock"

docker exec payments-mysql mysql -uroot -proot payments -e "
  UPDATE booking SET status='PENDING', paid_at=NULL WHERE id=$ID;
  UPDATE payment SET status='AUTHORIZED', authorized_at = NOW(3) - INTERVAL 10 MINUTE, captured_at=NULL WHERE booking_id=$ID;
" 2>/dev/null

WAIT_SEC="${WAIT_SEC:-15}"
echo "── 워커 polling 대기 ${WAIT_SEC}s ──"
sleep "$WAIT_SEC"

echo
echo "── 결과 확인 ──"
docker exec payments-mysql mysql -uroot -proot payments -e "
  SELECT id, status, failed_reason FROM booking WHERE id=$ID;
  SELECT id, booking_id, status FROM payment WHERE booking_id=$ID;
" 2>/dev/null
after_stock=$(docker exec payments-redis redis-cli GET "stock:product:1")
echo "  after  stock=$after_stock"

bstatus=$(docker exec payments-mysql mysql -uroot -proot payments -sNe "
  SELECT status FROM booking WHERE id=$ID;
" 2>/dev/null)
pstatus=$(docker exec payments-mysql mysql -uroot -proot payments -sNe "
  SELECT status FROM payment WHERE booking_id=$ID;
" 2>/dev/null)

if [ "$bstatus" = "FAILED" ] && [ "$pstatus" = "VOIDED" ] && [ "$after_stock" -gt "$before_stock" ]; then
  echo "✅ booking FAILED + payment VOIDED + stock 복구 ($before_stock → $after_stock) 확인"
else
  echo "❌ expected FAILED/VOIDED + stock 증가, got $bstatus/$pstatus stock=$after_stock"
  echo "   (워커 polling 너무 김? RECON_ZOMBIE_INTERVAL 단축 확인)"
  exit 1
fi
