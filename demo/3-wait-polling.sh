#!/usr/bin/env bash
# ③ /booking/wait/{token} 폴링 — WAITING booking 의 wait_token 으로 상태 조회
# 사전 환경: 2-soldout-waiting.sh 실행 후 (WAITING booking 존재)
set -euo pipefail

WT=$(docker exec payments-mysql mysql -uroot -proot payments -sNe "
  SELECT wait_token FROM booking WHERE status='WAITING' ORDER BY id DESC LIMIT 1;
" 2>/dev/null)

if [ -z "$WT" ]; then
  echo "❌ WAITING booking 없음. demo/2-soldout-waiting.sh 먼저 실행하세요."
  exit 1
fi

echo "── ③ wait_token=$WT 으로 폴링 ──"
resp=$(curl -s "http://localhost/booking/wait/$WT")
echo "응답: $resp"

status=$(echo "$resp" | grep -oE '"status":"[A-Z_]+"' | head -1 | cut -d'"' -f4)
[ "$status" = "WAITING" ] && echo "✅ WAITING + position 응답 확인" \
  || { echo "❌ expected WAITING, got $status"; exit 1; }
