#!/usr/bin/env bash
# vegeta JSON Lines targets 파일 생성
# 1000 req: customer 100~999 (900명, 일부 중복 허용) + idem-key 모두 unique
set -euo pipefail

OUT="${1:-load/targets.jsonl}"
COUNT="${2:-1000}"
URL="${URL:-http://localhost/booking}"

> "$OUT"
for i in $(seq 1 "$COUNT"); do
  cid=$(( (i - 1) % 900 + 100 ))
  body=$(printf '{"customerId":%d,"productId":1,"payments":[{"method":"CARD","amount":50000}]}' "$cid")
  b64=$(printf '%s' "$body" | base64 | tr -d '\n')
  idem="load-${i}-$(date +%s%N)"
  printf '{"method":"POST","url":"%s","header":{"Content-Type":["application/json"],"Idempotency-Key":["%s"]},"body":"%s"}\n' "$URL" "$idem" "$b64" >> "$OUT"
done

echo "[gen-targets] ${COUNT} reqs -> $OUT ($(wc -c < "$OUT" | tr -d ' ') bytes)"
