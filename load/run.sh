#!/usr/bin/env bash
# 1000 TPS × 1s 부하 테스트 통합 실행
#   reset → generate → vegeta attack → report → verify
set -euo pipefail
cd "$(dirname "$0")/.."

# vegeta 설치 확인
if ! command -v vegeta > /dev/null 2>&1; then
  echo "❌ vegeta 미설치. 다음 명령으로 설치하세요:"
  echo "   brew install vegeta"
  exit 1
fi

# 컨테이너 헬스 확인
if ! curl -fs http://localhost/actuator/health > /dev/null; then
  echo "❌ http://localhost 응답 없음. docker compose up 먼저."
  exit 1
fi

RATE="${RATE:-1000}"
DURATION="${DURATION:-1s}"
COUNT="${COUNT:-1000}"
TARGETS="load/targets.jsonl"
RESULTS="load/results.bin"

echo "──────── 1. 환경 초기화 ────────"
bash load/reset.sh

echo
echo "──────── 2. targets 생성 ($COUNT req) ────────"
bash load/gen-targets.sh "$TARGETS" "$COUNT"

echo
echo "──────── 3. vegeta attack ($RATE TPS × $DURATION) ────────"
vegeta attack \
  -format=json \
  -targets="$TARGETS" \
  -rate="$RATE" \
  -duration="$DURATION" \
  -max-workers=200 \
  -timeout=10s \
  > "$RESULTS"

echo
echo "──────── 4. vegeta report ────────"
vegeta report -type=text < "$RESULTS"
echo
vegeta report -type='hist[0,100ms,300ms,500ms,1s,3s]' < "$RESULTS"

echo
echo "──────── 5. 결과 검증 (DB / Redis 상태) ────────"
# vegeta 끝난 직후에 일부 결제가 still in-flight 일 수 있으니 잠시 대기
sleep 3
bash load/verify.sh
