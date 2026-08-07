# 06. 실측 — 방법과 결과

로그 개선의 효과는 체감이 아니라 수치로 확인한다.
이 문서의 모든 수치는 저장소 안의 스크립트로 재현할 수 있다.

## 재현 방법

```bash
# 1) 앱을 띄운다 (파일 로그는 dev/prod 에서만 생성된다)
SPRING_PROFILES_ACTIVE=dev ./gradlew bootRun

# 2) 다른 터미널에서 부하를 건다
./scripts/load.sh 100

# 3) 측정한다
./scripts/measure.sh logs/order-app.log
```

`load.sh` 는 동시 100건 + 잘못된 JSON 1건을 보낸다.
`productId` 를 `TV-$i` 로 돌리므로 `InventoryService` 의 해시 판정에 따라 약 1/4 이 재고 부족으로 거절된다.
결제는 `app.payment.decline-rate-percent`(기본 10) 확률로 거절된다.

Before 상태는 커밋을 따로 꺼내 실행했다.

```bash
git worktree add --detach /tmp/wt-println 9a8fefe   # System.out.println 단계
```

## 요청 구성 (dev / prod 동일)

101건 = 정상 주문 100건 + 잘못된 JSON 1건

| 결과 | 건수 |
|---|---:|
| 접수 (RECEIVED) | 67 |
| 재고 부족 거절 (OUT_OF_STOCK) | 24 |
| 결제 거절 (PG_DECLINED) | 9 |
| 요청 해석 실패 (400) | 1 |

## 결과

| 지표 | 측정 방법 | Before | After (dev) | After (prod) |
|---|---|---|---|---|
| 요청 흐름 복원율 | 요청 한 건의 로그 중 검색으로 회수한 줄의 비율 | **2 / 4줄 (50%)** | **9 / 9줄 (100%)** | **7 / 7줄 (100%)** |
| 복원에 필요한 검색 횟수 | 위 작업에 쓴 검색 명령 수 | **복원 불가** | **1회** | **1회** |
| 비동기 구간 추적 성공률 | `@Async` 로그 중 traceId 가 남은 비율 | **0%** | **100%** (67 / 67) | **100%** (71 / 71) |
| 검색 가능 필드 수 | JSON 최상위 키 (encoder 기본 + 애플리케이션) | **0개** | **22개** (7 + 15) | **21개** (7 + 14) |
| 민감정보 노출 | 카드번호 평문이 남은 줄 수 | **178줄** (기준이 다르다 — 아래) | **0줄** | **0줄** |
| 로그 유실 | 콘솔(동기) 대비 파일(비동기) 누락 줄 수 | — | **0줄** | **0줄** |
| 추적 사각지대 | 요청 처리 중 traceId 없이 남은 줄 | 전부 | **0줄** | **0줄** |
| 총 로그량 | 파일 줄 수 | — | 811줄 | 623줄 |

"복원 불가"의 의미는 [01-diagnosis.md](./01-diagnosis.md) 참고 —
검색 횟수의 문제가 아니라 로그에 정보가 없는 문제였다.

### 민감정보만 Before 기준이 다른 이유

다른 지표의 Before 는 `9a8fefe`(println 단계)다. 그런데 그 커밋으로는 마스킹의 효과를 잴 수 없다.
**카드번호를 로그에 찍는 코드가 아예 없기 때문이다.** 실제로 돌려서 확인했다.

```bash
$ grep -c '1234-5678-9012-3456' console.txt   # 9a8fefe 실행 결과
0
```

`OrderRequest` 에 `cardNumber` 필드는 있지만 어느 로그도 그 객체를 찍지 않는다.
그러니 이 커밋의 노출은 0줄이고, 그 0 은 마스킹이 막아낸 결과가 아니라 **찍을 코드가 없어서** 나온 0 이다.
Before 로 쓰면 마스킹이 아무것도 안 한 것처럼 보인다.

그래서 이 지표만 기준을 바꿨다. **현재 코드에서 마스킹 세 겹을 모두 걷어낸 상태**를 Before 로 삼는다.

```bash
git worktree add --detach /tmp/wt-nomask HEAD
# 1) OrderRequest.toString() 재정의 삭제  — record 기본 toString 이 전 필드를 뱉는다
# 2) 콘솔 패턴 %maskedMsg{...} -> %msg
# 3) <jsonGeneratorDecorator> 블록 삭제
SPRING_PROFILES_ACTIVE=dev ./gradlew bootRun   # /tmp/wt-nomask 에서
./scripts/load.sh 100
grep -c '1234-5678-9012-3456' logs/order-app.log
```

결과는 **178줄**이고, 출처는 둘이다.

| 유출 줄 | 건수 | 막는 겹 |
|---|---:|---|
| `주문 요청 수신 OrderRequest[... cardNumber=1234-5678-9012-3456]` | 101 | 찍는 쪽 — `toString()` 재정의 |
| `PG 요청 페이로드 cardNumber=1234-5678-9012-3456` | 77 | 나가는 쪽 — encoder / 콘솔 컨버터 |

101 줄은 요청 전건이다. 77 줄은 재고 확인을 통과해 결제까지 간 요청 수다.
두 번째 줄([`PaymentService`](../src/main/java/com/example/logbackmdclab/order/PaymentService.java))은
**의도적으로 규약을 어긴 코드**다. 찍는 쪽 규약만으로는 부족하다는 것을 보이려면
규약을 어긴 줄이 하나는 있어야 나가는 쪽이 실제로 막는지 증명할 수 있다.

저장소 히스토리에 "마스킹 직전" 커밋은 없다. 요청 객체를 통째로 찍는 로그와 마스킹이
같은 커밋(`831eb28`)에 함께 들어왔기 때문이다. 위 절차는 그 상태를 되돌려 만든 것이다.

## 복원율이 dev 와 prod 에서 모두 100% 인 이유

줄 수가 다른 건(9 vs 7) 레벨 정책 때문이다. prod 에서 빠지는 두 줄은 DEBUG 다.

| | dev | prod |
|---|:---:|:---:|
| 주문 요청 수신 (INFO) | O | O |
| 주문 접수 시작 (INFO) | O | O |
| 재고 확인 요청 (**DEBUG**) | O | — |
| 재고 확인 응답 (INFO) | O | O |
| 결제 승인 요청 (INFO) | O | O |
| PG 요청 페이로드 (**DEBUG**) | O | — |
| 결제 승인 완료 (INFO) | O | O |
| 주문 접수 완료 (INFO) | O | O |
| 주문 확인 알림 발송 (INFO, `@Async`) | O | O |

빠지는 두 줄은 **분기를 결정하지 않는다.** 재고 판정의 근거는 `재고 확인 응답`(`enough` 필드)이고,
결제 결과는 `결제 승인 완료 / 거절`이다. 그래서 `재고 확인 응답`만 DEBUG 에서 INFO 로 올렸다.

> 모든 걸 INFO 로 올리지 않고 **판단 근거가 되는 줄만** 올린다 — 이게 이 프로젝트의 레벨 규칙이다.

성공 요청 20건을 표본으로 확인한 결과 dev 는 전부 9줄, prod 는 전부 7줄로 일정했다.

## 검색 한 번으로 복원한 흐름 (dev)

```bash
jq 'select(.traceId=="30c7ca5e25cc47e792a14405ba2a3f1a")' samples/dev-run.log
```

```
14:18:21.597  http-nio-8080-exec-16  OrderController       주문 요청 수신 OrderRequest[userId=u18, ..., cardNumber=****-3456]
14:18:21.598  http-nio-8080-exec-16  OrderService          주문 접수 시작 userId=u18 productId=TV-18 quantity=1
14:18:21.599  http-nio-8080-exec-16  InventoryService      재고 확인 요청 productId=TV-18 quantity=1
14:18:21.669  http-nio-8080-exec-16  InventoryService      재고 확인 응답 productId=TV-18 enough=true
14:18:21.669  http-nio-8080-exec-16  PaymentService        결제 승인 요청 amount=10000
14:18:21.669  http-nio-8080-exec-16  PaymentService        PG 요청 페이로드 cardNumber=****-****-****-****
14:18:21.746  http-nio-8080-exec-16  PaymentService        결제 승인 완료 paymentStatus=APPROVED amount=10000
14:18:21.746  http-nio-8080-exec-16  OrderService          주문 접수 완료 status=RECEIVED
14:18:21.746  task-3                 NotificationService   주문 확인 알림 발송
```

811줄이 뒤섞인 파일에서 이 9줄만 나온다.
마지막 줄에서 스레드가 `exec-16` → `task-3` 로 바뀌지만 traceId 는 끊기지 않는다.

## 결제 거절 흐름

```
INFO   OrderController         주문 요청 수신 ... cardNumber=****-3456
INFO   OrderService            주문 접수 시작 userId=u77 productId=TV-77 quantity=1
DEBUG  InventoryService        재고 확인 요청 productId=TV-77 quantity=1
INFO   InventoryService        재고 확인 응답 productId=TV-77 enough=true
INFO   PaymentService          결제 승인 요청 amount=10000
DEBUG  PaymentService          PG 요청 페이로드 cardNumber=****-****-****-****
WARN   PaymentService          결제 승인 거절 paymentStatus=DECLINED reason=PG_DECLINED
WARN   GlobalExceptionHandler  주문 실패 status=FAILED reason=PG_DECLINED
```

마지막 줄은 `OrderService` **밖**에서 찍힌다. 그런데도 `orderId` 가 붙어 있다 —
MDC 스코프를 계층이 아니라 요청 경계가 소유하기 때문이다([03](./03-mdc-propagation.md) 참고).

## 컨트롤러에 도달하기 전에 깨진 요청

```json
{"traceId":"003c843b6d8547119d86c90a3b42c6a0","orderId":null,"level":"WARN",
 "status":"REJECTED","httpStatus":400,"logger_name":"...GlobalExceptionHandler"}
```

`orderId` 는 없다. 만들어지기 전에 실패했기 때문이다.
이 요청에 대해 고객이 CS 에 건넬 수 있는 값은 응답 헤더·본문으로 나간 traceId 뿐이다.

## 검색 가능 필드

```
encoder 기본 (7)  @timestamp @version level level_value logger_name message thread_name
식별      (2)     traceId orderId                        ← MDC
수집기용  (2)     service env                             ← customFields
도메인    (11)    userId productId quantity enough amount paymentStatus
                  status reason cardNumber httpStatus exceptionType   ← kv()
```

Before 는 0개였다. `System.out.println` 이 만든 평문에는 필드라는 개념이 없다.

지표를 "필드 수"로 잡으면 encoder 기본 필드까지 세게 되어 실제 개선폭이 부풀려진다.
그래서 위처럼 출처별로 나눠 적는다. 개발자가 만든 것은 **15개**다.

`cardNumber` 필드가 prod 에 없는 이유는 그걸 찍는 줄이 DEBUG 이기 때문이다.
민감정보가 운영 로그에 아예 들어가지 않는다는 뜻이기도 하다.

## 파일은 전 줄이 JSON 이다

```bash
jq -e . logs/order-app.log    # 전 줄 파싱 성공
```

스프링 배너나 Gradle 출력은 표준출력으로 나가고, 이 파일은 appender 를 거친 것만 담는다.
`grep '^{'` 같은 전처리가 필요 없다. 콘솔과 파일에 다른 encoder 를 붙인 이유가 이것이다.

## AsyncAppender 유실 실험

별도 문서로 분리했다 — [04-async-appender-loss.md](./04-async-appender-loss.md).

## 원본

[`samples/dev-run.log`](../samples/dev-run.log) 에 dev 실행 로그를 커밋해뒀다(마스킹 적용 상태).
전체 811줄 중 결과 유형별로 요청을 **통째로** 골라낸 303줄이다 —
단순히 앞에서 잘라내면 요청이 중간에 끊겨 복원 예시를 재현할 수 없다.

| 구성 | 건수 |
|---|---:|
| 접수 | 25 |
| 재고 부족 거절 | 6 |
| 결제 거절 | 4 |
| 요청 해석 실패 | 1 |

이 문서와 README 의 모든 `jq` 예시는 앱을 띄우지 않고 이 파일에 그대로 실행할 수 있다.
