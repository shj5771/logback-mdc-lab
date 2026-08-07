#!/usr/bin/env bash
#
# 동시 요청을 발생시킨다. README 의 모든 수치는 이 스크립트로 만든 부하를 기준으로 한다.
#
#   ./scripts/load.sh [건수] [호스트]
#   ./scripts/load.sh 100 localhost:8080
#
# productId 를 TV-$i 로 돌리는 이유는 재고 유무를 섞기 위해서다.
# InventoryService 는 productId 의 해시로 재고를 판단하므로 약 1/4 이 재고 부족으로 거절된다.
set -euo pipefail

COUNT="${1:-100}"
HOST="${2:-localhost:8080}"
CARD="1234-5678-9012-3456"

echo "부하 시작: ${COUNT}건 동시 요청 -> ${HOST}"

for i in $(seq 1 "${COUNT}"); do
  curl -s -o /dev/null \
    -X POST "http://${HOST}/orders" \
    -H 'Content-Type: application/json' \
    -d "{\"userId\":\"u${i}\",\"productId\":\"TV-${i}\",\"quantity\":1,\"cardNumber\":\"${CARD}\"}" &
done
wait

# 잘못된 JSON 한 건. 컨트롤러에 도달하기 전에 깨지는 요청도 추적되는지 확인하기 위한 것이다.
curl -s -o /dev/null -X POST "http://${HOST}/orders" \
  -H 'Content-Type: application/json' -d '{"quantity":"두개"}'

echo "부하 완료"
