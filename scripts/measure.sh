#!/usr/bin/env bash
#
# README 측정 표의 각 행을 재현한다.
#
#   ./scripts/measure.sh [로그파일] [콘솔로그]
#   ./scripts/measure.sh logs/order-app.log
#   ./scripts/measure.sh logs/order-app.log /tmp/console.log   # [8] 유실 비교까지
#
# 전제: dev 또는 prod 프로파일로 앱을 띄우고 ./scripts/load.sh 를 한 번 돌린 상태.
# 콘솔 로그가 필요하면 기동할 때 남겨둔다:
#   SPRING_PROFILES_ACTIVE=dev ./gradlew bootRun | tee /tmp/console.log
set -euo pipefail

LOG="${1:-logs/order-app.log}"
# 두 번째 인자로 콘솔 로그를 주면 [8] 유실 비교까지 한다 (선택).
CONSOLE="${2:-}"
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
#
# head -1 로 자르지 않는 이유: head 가 먼저 끝나면 jq 가 SIGPIPE 로 죽어 141 을 반환하고,
# set -o pipefail + set -e 가 그걸 받아 스크립트를 조용히 중단시킨다.
# 출력이 파이프 버퍼를 넘길 때부터, 즉 로그가 어느 정도 쌓이면 재현된다.
# jq 안에서 first() 로 끊으면 파이프 자체가 없다.
TRACE=$(jq -r -n 'first(inputs | select(.message | test("주문 접수 완료")) | .traceId) // ""' "${LOG}")
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
# 기동·종료 로그는 요청 컨텍스트 밖이라 traceId 가 없는 게 정상이다.
# 지표로 의미가 있는 건 "우리 코드가 요청을 처리하면서 남긴 줄" 이다. 둘을 나눠 센다.
NO_TRACE=$(jq -r 'select(.traceId==null) | .logger_name' "${LOG}" | wc -l | tr -d ' ')
APP_NO_TRACE=$(jq -r 'select(.traceId==null)
                      | select(.logger_name | startswith("com.example.logbackmdclab"))
                      | select(.thread_name | test("^http-nio-|^task-"))
                      | .logger_name' "${LOG}" | wc -l | tr -d ' ')
echo "    애플리케이션 로거 · 요청 스레드: ${APP_NO_TRACE}줄  ← 지표"
echo "    전체(기동·종료·서블릿 초기화 포함): ${NO_TRACE}줄"
if [[ "${NO_TRACE}" -gt 0 ]]; then
  echo "    전체 내역 (스레드별):"
  jq -r 'select(.traceId==null) | "      " + .thread_name + "  " + (.logger_name|split(".")|last)' "${LOG}" \
    | sort | uniq -c | sort -rn | head -6
fi
echo

# ─────────────────────────────────────────────────────────────
echo "[7] 실패 경로 (줄 수 — 결제 거절은 PaymentService·GlobalExceptionHandler 두 줄을 남긴다)"
jq -r 'select(.level=="WARN" or .level=="ERROR") | .level + " " + (.reason // .exceptionType // "-")' "${LOG}" \
  | sort | uniq -c | sed 's/^/    /'

# ─────────────────────────────────────────────────────────────
# 콘솔 로그를 함께 주면 비동기 파일 쓰기의 유실을 잰다.
#   SPRING_PROFILES_ACTIVE=dev ./gradlew bootRun | tee /tmp/console.log
#   ./scripts/measure.sh logs/order-app.log /tmp/console.log
if [[ -n "${CONSOLE:-}" && -f "${CONSOLE}" ]]; then
  echo "[8] 로그 유실 — 콘솔(동기) 대비 파일(비동기)"
  echo "    콘솔:"
  grep -E '\[(NO_TRACE|[0-9a-f]{32})\]' "${CONSOLE}" | grep 'c\.e\.l' | awk '{print $2}' \
    | sort | uniq -c | sed 's/^/      /'
  echo "    파일:"
  jq -r 'select(.logger_name|startswith("com.example.logbackmdclab")) | .level' "${LOG}" \
    | sort | uniq -c | sed 's/^/      /'
  C=$(grep -E '\[(NO_TRACE|[0-9a-f]{32})\]' "${CONSOLE}" | grep -c 'c\.e\.l' || true)
  F=$(jq -r 'select(.logger_name|startswith("com.example.logbackmdclab")) | .level' "${LOG}" | wc -l | tr -d ' ')
  echo "    합계 콘솔 ${C}줄 / 파일 ${F}줄 — 유실 $((C - F))줄"
  echo
fi
