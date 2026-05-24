#!/usr/bin/env bash
# ④ nginx LB 분산 — booking 여러 건이 app1/app2 양쪽에 나뉘었는지 docker logs 로 검증
# 사전 환경: 시연 1~2 실행 후 (또는 임의 booking N건 발생 후)
set -euo pipefail

a1=$(docker logs --since 30m payments-app1 2>&1 | grep -cE "BOOKING_PAID|BOOKING_WAITLIST" || true)
a2=$(docker logs --since 30m payments-app2 2>&1 | grep -cE "BOOKING_PAID|BOOKING_WAITLIST" || true)
total=$(( a1 + a2 ))

echo "── ④ nginx LB 분산 검증 (최근 30분) ──"
printf "  app1: %s 건\n" "$a1"
printf "  app2: %s 건\n" "$a2"
printf "  TOTAL: %s\n\n" "$total"

if [ "$total" -lt 4 ]; then
  echo "⚠️  최근 booking 이 4건 미만. 1-card-only / 2-soldout-waiting 먼저 실행 권장."
  exit 0
fi

if [ "$a1" -gt 0 ] && [ "$a2" -gt 0 ]; then
  echo "✅ 양쪽 노드 모두 booking 처리 — LB 분산 OK"
else
  echo "❌ 한 쪽 노드만 처리됨 — nginx upstream 또는 health 확인 필요"
  exit 1
fi
