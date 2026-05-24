#!/usr/bin/env bash
# ⑤ 대기열 만료 워커 — WAITING booking 의 enqueued_at 을 2시간 전으로 update →
#     waitlist-cleanup 워커가 임계(1h) 초과 인지 → EXPIRED + Redis 정리
# 사전 환경:
#   1) 워커 polling 단축 (시연용): RECON_WAIT_INTERVAL=5000 docker compose up -d app1 app2
#   2) WAITING booking 존재 (2-soldout-waiting.sh 후)
set -euo pipefail

ID=$(docker exec payments-mysql mysql -uroot -proot payments -sNe "
  SELECT id FROM booking WHERE status='WAITING' ORDER BY id DESC LIMIT 1;
" 2>/dev/null)

if [ -z "$ID" ]; then
  echo "❌ WAITING booking 없음. demo/2-soldout-waiting.sh 먼저 실행하세요."
  exit 1
fi

echo "── ⑤ booking id=$ID (WAITING) → enqueued_at = NOW - 2h ──"
docker exec payments-mysql mysql -uroot -proot payments -e "
  UPDATE booking SET enqueued_at = NOW(3) - INTERVAL 2 HOUR WHERE id=$ID;
" 2>/dev/null

WAIT_SEC="${WAIT_SEC:-10}"
echo "── 워커 polling 대기 ${WAIT_SEC}s ──"
sleep "$WAIT_SEC"

echo
echo "── 결과 확인 ──"
docker exec payments-mysql mysql -uroot -proot payments -e "
  SELECT id, status, failed_reason, expired_at FROM booking WHERE id=$ID;
" 2>/dev/null

status=$(docker exec payments-mysql mysql -uroot -proot payments -sNe "
  SELECT status FROM booking WHERE id=$ID;
" 2>/dev/null)

reason=$(docker exec payments-mysql mysql -uroot -proot payments -sNe "
  SELECT failed_reason FROM booking WHERE id=$ID;
" 2>/dev/null)

[ "$status" = "EXPIRED" ] && [ "$reason" = "WAIT_TIMEOUT" ] \
  && echo "✅ EXPIRED + WAIT_TIMEOUT 전환 확인" \
  || { echo "❌ expected EXPIRED/WAIT_TIMEOUT, got $status/$reason (워커 polling 너무 김? RECON_WAIT_INTERVAL 단축 확인)"; exit 1; }
