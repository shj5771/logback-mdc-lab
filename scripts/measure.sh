#!/usr/bin/env bash
#
# README 측정 표의 각 행을 재현한다.
#
#   ./scripts/measure.sh [로그파일]
#   ./scripts/measure.sh logs/order-app.log
#
# 전제: dev 또는 prod 프로파일로 앱을 띄우고 ./scripts/load.sh 를 한 번 돌린 상태.
set -euo pipefail

LOG="${1:-logs/order-app.log}"
CARD="1234-5678-9012-3456"
CARD_PLAIN="1234567890123456"

if [[ ! -f "${LOG}" ]]; then
  echo "로그 파일이 없다: ${LOG}"
  echo "dev/prod 프로파일로 띄웠는지 확인할 것. local 은 파일을 남기지 않는다."
  exit 1
fi

command -v jq >/dev/null || { echo "jq 가 필요하다: brew install jq"; exit 1; }

echo "== 대상: ${LOG}"
echo "== 총 $(wc -l <"${LOG}" | tr -d ' ') 줄"
echo

# ─────────────────────────────────────────────────────────────
echo "[1] JSON 파싱 가능 여부"
# appender 를 거친 파일은 한 줄도 빠짐없이 JSON 이어야 한다.
# 스프링 배너 같은 표준출력은 애초에 이 파일로 들어오지 않는다.
if jq -e . "${LOG}" >/dev/null 2>&1; then
  echo "    전 줄 파싱 성공 — 전처리 없이 jq 를 걸 수 있다"
else
  echo "    파싱 실패 — JSON 이 아닌 줄이 섞여 있다"
fi
echo

# ─────────────────────────────────────────────────────────────
echo "[2] 요청 흐름 복원 — 검색 1회"
# 성공한 주문 하나를 골라 그 traceId 로 몇 줄이 회수되는지 센다.
TRACE=$(jq -r 'select(.message|test("주문 접수 완료")) | .traceId' "${LOG}" | head -1)
if [[ -n "${TRACE}" ]]; then
  LINES=$(jq -r --arg t "${TRACE}" 'select(.traceId==$t) | .logger_name' "${LOG}" | wc -l | tr -d ' ')
  THREADS=$(jq -r --arg t "${TRACE}" 'select(.traceId==$t) | .thread_name' "${LOG}" | sort -u | wc -l | tr -d ' ')
  echo "    traceId=${TRACE}"
  echo "    회수된 줄: ${LINES}줄 (검색 명령 1회)"
  echo "    관여한 스레드: ${THREADS}개 — 스레드가 바뀌어도 traceId 는 끊기지 않는다"
  echo "    재현: jq 'select(.traceId==\"${TRACE}\")' ${LOG}"
else
  echo "    성공한 주문이 없다"
fi
echo

# ─────────────────────────────────────────────────────────────
echo "[3] 비동기 구간 추적 성공률"
ASYNC_TOTAL=$(jq -r 'select(.logger_name|test("NotificationService")) | .thread_name' "${LOG}" | wc -l | tr -d ' ')
ASYNC_TRACED=$(jq -r 'select(.logger_name|test("NotificationService")) | select(.traceId!=null and .traceId!="") | .traceId' "${LOG}" | wc -l | tr -d ' ')
ASYNC_THREADS=$(jq -r 'select(.logger_name|test("NotificationService")) | .thread_name' "${LOG}" | sort -u | tr '\n' ' ')
if [[ "${ASYNC_TOTAL}" -gt 0 ]]; then
  echo "    @Async 로그 ${ASYNC_TOTAL}줄 중 traceId 가 남은 줄: ${ASYNC_TRACED}줄"
  echo "    실행 스레드: ${ASYNC_THREADS}"
else
  echo "    @Async 로그가 없다"
fi
echo

# ─────────────────────────────────────────────────────────────
echo "[4] 검색 가능 필드"
ALL_KEYS=$(jq -r 'keys[]' "${LOG}" | sort -u)
BUILTIN="@timestamp @version level level_value logger_name message thread_name"
echo "    전체 $(echo "${ALL_KEYS}" | wc -l | tr -d ' ')개"
echo "    encoder 기본 필드 (7): ${BUILTIN}"
printf "    애플리케이션 필드: "
for k in ${ALL_KEYS}; do
  case " ${BUILTIN} " in *" ${k} "*) ;; *) printf "%s " "${k}" ;; esac
done
echo
echo

# ─────────────────────────────────────────────────────────────
echo "[5] 민감정보 노출"
LEAK=$(grep -c -e "${CARD}" -e "${CARD_PLAIN}" "${LOG}" || true)
MASKED=$(grep -c '\*\*\*\*' "${LOG}" || true)
echo "    카드번호 평문이 남은 줄: ${LEAK}줄"
echo "    마스킹된 줄: ${MASKED}줄"
echo "    (형식: ${CARD} / ${CARD_PLAIN} 기준)"
echo

# ─────────────────────────────────────────────────────────────
echo "[6] traceId 없이 남은 줄 (추적 사각지대)"
NO_TRACE=$(jq -r 'select(.traceId==null) | .logger_name' "${LOG}" | wc -l | tr -d ' ')
echo "    ${NO_TRACE}줄"
if [[ "${NO_TRACE}" -gt 0 ]]; then
  echo "    내역:"
  jq -r 'select(.traceId==null) | "      " + .logger_name + " — " + .message' "${LOG}" | sort | uniq -c | sort -rn | head -5
fi
echo

# ─────────────────────────────────────────────────────────────
echo "[7] 실패 경로"
jq -r 'select(.level=="WARN" or .level=="ERROR") | .level + " " + (.reason // .exceptionType // "-")' "${LOG}" \
  | sort | uniq -c | sed 's/^/    /'
