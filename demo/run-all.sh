#!/usr/bin/env bash
# 6종 수동 시연 통합 실행 — reset → 1 → 2 → 3 → 4 → 5 → 6
# 워커 시연(5,6)이 동작하려면 polling 단축 환경에서 띄워야 함:
#   RECON_ZOMBIE_INTERVAL=5000 RECON_WAIT_INTERVAL=5000 docker compose up -d --force-recreate app1 app2
set -euo pipefail
cd "$(dirname "$0")"

if ! curl -fs http://localhost/actuator/health > /dev/null; then
  echo "❌ http://localhost 응답 없음. docker compose up 먼저."
  exit 1
fi

bash reset.sh
echo; echo "════════ 시연 ① ════════"; bash 1-card-only.sh
echo; echo "════════ 시연 ② ════════"; bash 2-soldout-waiting.sh
echo; echo "════════ 시연 ③ ════════"; bash 3-wait-polling.sh
echo; echo "════════ 시연 ④ ════════"; bash 4-lb-distribution.sh
echo; echo "════════ 시연 ⑤ ════════"; bash 5-waitlist-expire.sh
echo; echo "════════ 시연 ⑥ ════════"; bash 6-zombie-cleanup.sh

echo
echo "════════ 시연 6/6 완료 ════════"
