# logback-mdc-lab

> 요청 추적이 불가능한 주문 API 에 MDC 기반 요청 추적과 환경별 로깅 전략을 도입하고,
> 그 효과를 실측한 프로젝트.

---

## 배경

주문 요청 하나는 내부적으로 네 개의 계층을 거친다.

```
POST /orders
  │
  ├─ 1. 주문 접수      OrderService
  ├─ 2. 재고 확인      InventoryService
  ├─ 3. 결제 승인      PaymentService  ──▶ 외부 PG (지연 + 확률적 거절)
  └─ 4. 알림 발송      NotificationService   @Async (별도 스레드)
```

여기에 다음 문의가 들어온 상황을 가정한다.

> "카드는 빠져나갔는데 주문 내역이 없어요."

로그를 열어 이 요청 하나의 처리 흐름을 복원해야 한다. 그런데 복원할 수가 없다.

> 문의는 가정한 상황이다. 다만 아래 문제들은 가정이 아니라, 동시 요청을 발생시키면 그대로 재현되는 현상이다.

## 문제

| # | 문제 | 원인 |
|---|---|---|
| 1 | 어느 로그 줄이 그 고객의 요청인지 알 수 없다 | 동시 요청의 출력이 뒤섞인다 |
| 2 | 계층 간 로그를 이어 붙일 수 없다 | `InventoryService` 는 `orderId` 를 모른다. 자기 관심사가 아니므로 로그에 남기지 않는다 |
| 3 | 알림 발송 로그는 흐름에서 완전히 이탈한다 | `@Async` 라 다른 스레드에서 실행된다 |
| 4 | 환경별로 로그 정책을 다르게 가져갈 수 없다 | 설정이 코드에 고정되어 있다 |
| 5 | 로그를 조건으로 검색할 수 없다 | 평문 문자열이라 필드 단위 조회가 불가능하다 |
| 6 | 실패한 요청은 고객이 건넬 검색 키조차 없다 | 식별자가 서버 밖으로 나가지 않는다 |

핵심은 2번이다. 모든 로그에 개발자가 식별자를 직접 넣어주지 않으면 요청 하나의 흐름이 끊긴다.
그리고 계층이 늘어날수록 그 방식은 유지되지 않는다.

## 접근

| 단계 | 과제 | 대응 | 기록 |
|:---:|---|:---:|---|
| 1 | 현행 로깅의 한계 진단 및 Before 실측 | 1, 2 | [01-diagnosis](./docs/01-diagnosis.md) |
| 2 | SLF4J / Logback 기반 로깅 체계 도입 | 4 | — |
| 3 | 환경별 설정 분리 (local / dev / prod) | 4 | [02-profile-split](./docs/02-profile-split.md) |
| 4 | MDC 기반 `traceId` 발급 및 계층 간 전파 | 1, 2 | [03-mdc-propagation](./docs/03-mdc-propagation.md) |
| 5 | 비동기 구간의 `traceId` 유실 해결 | 3 | [03-mdc-propagation](./docs/03-mdc-propagation.md) |
| 6 | 비동기 파일 쓰기의 로그 유실 방지 | — | [04-async-appender-loss](./docs/04-async-appender-loss.md) |
| 7 | JSON 구조화 로그 전환 및 민감정보 마스킹 | 5 | [05-masking](./docs/05-masking.md) |
| 8 | 실패 경로 추적 — 예외 응답에 `traceId` 노출 | 6 | [03-mdc-propagation](./docs/03-mdc-propagation.md) |
| 9 | After 실측 및 결과 정리 | 전체 | [06-measurement](./docs/06-measurement.md) |

## 측정

동시 100건 + 잘못된 JSON 1건. 전 과정을 [`scripts/`](./scripts) 로 재현할 수 있다.

```bash
SPRING_PROFILES_ACTIVE=dev ./gradlew bootRun    # 터미널 1
./scripts/load.sh 100 && ./scripts/measure.sh   # 터미널 2
```

| 지표 | Before | After (dev) | After (prod) |
|---|---|---|---|
| 요청 흐름 복원율 | **2 / 4줄 (50%)** | **9 / 9줄 (100%)** | **7 / 7줄 (100%)** |
| 복원에 필요한 검색 횟수 | **복원 불가** | **1회** | **1회** |
| 비동기 구간 추적 성공률 | **0%** | **100%** (67 / 67) | **100%** (71 / 71) |
| 검색 가능 필드 수 | **0개** | **22개** (기본 7 + 직접 15) | **21개** (기본 7 + 직접 14) |
| 민감정보 노출 | **178줄** | **0줄** | **0줄** |
| 로그 유실 (콘솔 대비) | — | **0줄** | **0줄** |
| 요청 처리 중 traceId 없이 남은 줄 | 전부 | **0줄** | **0줄** |

측정 방법, dev/prod 줄 수가 다른 이유, 필드 출처별 내역은 [06-measurement](./docs/06-measurement.md) 에 있다.

> 민감정보 행만 Before 기준이 다르다. `9a8fefe` 는 카드번호를 로그에 찍는 코드 자체가 없어
> 노출이 0줄이고, 그 0 은 마스킹이 막아낸 결과가 아니다. 그래서 이 지표는
> **현재 코드에서 마스킹 세 겹을 걷어낸 상태**를 Before 로 삼았다 — [근거와 절차](./docs/06-measurement.md#민감정보만-before-기준이-다른-이유).

### 검색 한 번으로 복원한 흐름

조건은 `traceId` 하나뿐이다.

```bash
jq 'select(.traceId=="30c7ca5e25cc47e792a14405ba2a3f1a")' samples/dev-run.log
```

이대로 실행하면 JSON 9건이 그대로 나온다. 흐름을 눈으로 따라가려고 네 필드만 뽑으면 이렇다.

```bash
jq -r 'select(.traceId=="30c7ca5e25cc47e792a14405ba2a3f1a")
       | "\(.["@timestamp"][11:23])  \(.thread_name)  \(.logger_name | split(".") | last)  \(.message)"' \
   samples/dev-run.log
```

```
14:18:21.597  http-nio-8080-exec-16  OrderController  주문 요청 수신 OrderRequest[userId=u18, productId=TV-18, quantity=1, cardNumber=****-3456]
14:18:21.598  http-nio-8080-exec-16  OrderService  주문 접수 시작 userId=u18 productId=TV-18 quantity=1
14:18:21.599  http-nio-8080-exec-16  InventoryService  재고 확인 요청 productId=TV-18 quantity=1
14:18:21.669  http-nio-8080-exec-16  InventoryService  재고 확인 응답 productId=TV-18 enough=true
14:18:21.669  http-nio-8080-exec-16  PaymentService  결제 승인 요청 amount=10000
14:18:21.669  http-nio-8080-exec-16  PaymentService  PG 요청 페이로드 cardNumber=****-****-****-****
14:18:21.746  http-nio-8080-exec-16  PaymentService  결제 승인 완료 paymentStatus=APPROVED amount=10000
14:18:21.746  http-nio-8080-exec-16  OrderService  주문 접수 완료 status=RECEIVED
14:18:21.746  task-3  NotificationService  주문 확인 알림 발송
```

여러 요청이 뒤섞인 파일에서 이 9줄만 나온다.
마지막 줄에서 스레드가 `exec-16` → `task-3` 로 바뀌지만 traceId 는 끊기지 않는다.

> 실행 로그 원본을 [`samples/dev-run.log`](./samples/dev-run.log) 에 커밋해뒀다(마스킹 적용 상태).
> 위 명령은 앱을 띄우지 않고 그 파일에 그대로 실행해 볼 수 있다.
> 측정에 쓴 전체 실행은 811줄이고, 그중 결과 유형별로 요청을 통째로 골라낸 303줄이다 —
> 요청이 중간에 잘리지 않게 했다.

## 설계 결정

측정 지표로는 드러나지 않지만, 직접 재현해 확인한 뒤 내린 판단들이다.
각 항목의 실측과 근거는 링크된 문서에 있다.

**[MDC 스코프는 계층이 아니라 요청 경계가 소유한다](./docs/03-mdc-propagation.md#스코프는-누가-여나)**
계층마다 자기 키를 지우게 하면, 예외로 빠져나가는 경로에서 실패 로그가 찍히기 **전에** `orderId` 가 사라진다.
정작 필요한 순간에 식별자가 없다.

**[`@Async` 전파를 자동설정 관례에 맡기지 않는다](./docs/03-mdc-propagation.md#배선을-명시하는-이유)**
Boot 는 `TaskDecorator` 빈을 `getIfUnique()` 로 주워간다. 빈이 하나 더 생기면 `null` 이 되어
전파가 통째로 빠지는데 예외도 경고도 없다. 실행기를 직접 정의하고 데코레이터를 직접 건다.

**[`traceId` 는 32 hex — 읽기 편함보다 전제를 지킨다](./docs/03-mdc-propagation.md#traceid-는-어디서-오나)**
UUID 앞 8자(32비트)는 콘솔에서 읽기 편하지만 30일 보관 규모에서 충돌쌍이 생긴다.
그러면 "traceId 하나로 요청 하나를 복원한다"는 전제 자체가 흔들린다.
32 hex 는 W3C trace-context 및 Boot `CorrelationIdFormatter` 규약과 같은 폭이다.

**[필터는 ERROR 디스패치까지 덮어야 한다](./docs/03-mdc-propagation.md#필터는-왜-onceperrequestfilter-인가)**
`Filter` 를 직접 구현하면 REQUEST 디스패치에만 매핑되어, 500 을 만드는 로그가 전부 `NO_TRACE` 로 빠진다.
장애 분석용 로깅인데 정작 장애 순간만 추적이 끊긴다.

**[AsyncAppender 는 기본 설정만으로도 로그를 버린다](./docs/04-async-appender-loss.md)**
동시 100건에서 134줄이 사라졌다. 예외도 경고도 없었고 응답은 정상이었다.
사라진 것은 전부 WARN **미만**이다 — 장애 분석에 필요한 건 WARN 한 줄이 아니라 그 앞의 맥락인데.

**[마스킹 정규식이 두 개인 이유](./docs/05-masking.md#정규식이-두-개인-이유)**
파일 쪽 값 마스킹은 JSON 의 모든 문자열 값에 걸린다. 넓은 정규식을 쓰면
숫자가 길게 이어진 `traceId` 의 그 구간이 통째로 가려진다 — 핵심 지표를 설정으로 스스로 깨는 셈이다.
경계를 숫자가 아니라 hex 로 잡아 해결했다.

**[콘솔 마스킹에는 커스텀 컨버터가 필요하다](./docs/05-masking.md#콘솔은-왜-커스텀-컨버터인가)**
`%replace(%msg)` 는 스택트레이스를 덮지 못하고, `%replace(%msg%n%ex)` 는 스택트레이스를 두 번 찍는다.
`ThrowableHandlingConverter` 를 직접 상속해야 한다.

**[레벨은 yml 이, 구조는 XML 이 소유한다](./docs/02-profile-split.md#소유권)**
Boot 는 logback 설정을 읽은 뒤에 `logging.level.*` 을 적용한다. 두 곳에 적으면 항상 yml 이 이기고,
XML 쪽은 죽은 코드가 된다. 그래서 `<springProfile>` 블록이 하나뿐이고 마스킹 설정도 한 벌만 존재한다.

**[판단 근거가 되는 줄만 INFO 로 올린다](./docs/06-measurement.md#복원율이-dev-와-prod-에서-모두-100-인-이유)**
`재고 확인 응답`은 분기를 결정하므로 INFO, `재고 확인 요청`은 DEBUG.
그래서 prod 에서도 줄 수는 줄지만(9 → 7) 흐름 복원은 100% 를 유지한다.

**[Boot 내장 구조화 로깅 · Micrometer Tracing 을 쓰지 않은 이유](./docs/03-mdc-propagation.md#다음-단계--micrometer-tracing-으로-가면)**
Boot 3.4+ 는 `logging.structured.format.file=logstash` 한 줄로 같은 JSON 을 만든다.
그럼에도 `logstash-logback-encoder` 를 직접 쓴 건 encoder / appender / decorator 의 관계와
마스킹 지점을 손으로 다뤄보는 것이 이 랩의 목적이기 때문이다.
분산 추적은 범위 밖이며, 그쪽으로 가면 이 코드는 확장되는 게 아니라 **대체된다** — 알고 좁혔다.

## 구조

```
src/main/java/com/example/logbackmdclab/
├── common/                          횡단 관심사
│   ├── TraceIdFilter                요청 경계에서 traceId 발급·계승·응답 노출
│   ├── MdcScope                     MDC 생명주기를 try-with-resources 로 묶는다
│   ├── LoggingConfig                필터 등록 (순서·디스패치 타입 명시)
│   ├── AsyncConfig                  @Async 실행기 + MDC TaskDecorator 명시 배선
│   ├── MaskingMessageConverter      콘솔 메시지·스택트레이스 마스킹
│   ├── GlobalExceptionHandler       실패를 로그와 응답 양쪽에 남긴다
│   └── ErrorResponse                실패 응답에 traceId 를 실어 보낸다
└── order/                           도메인
    ├── OrderController              HTTP 만
    ├── OrderService                 흐름만 (orderId 채번 · 오케스트레이션)
    ├── InventoryService
    ├── PaymentService               지연 + 확률적 거절
    ├── NotificationService          @Async — 인자가 없다는 것이 결론이다
    ├── OrderRequest / OrderResponse
    └── PaymentDeclinedException
```

## 테스트

```bash
./gradlew test      # 53개
```

로그가 산출물인 프로젝트라 "로그가 이렇게 찍혔다"를 단언할 수단이 필요하다.
Logback 의 `ListAppender` 로 실제 이벤트를 잡아 검증한다.

| 대상 | 무엇을 고정하나 |
|---|---|
| `MdcTaskDecoratorTest` | MDC 복사·복구·예외 경로. Spring 컨텍스트 불필요 |
| `AsyncTracePropagationTest` | 스레드가 바뀌어도 traceId 가 이어지는지 + 데코레이터가 실행기에 실제로 걸렸는지 |
| `TraceIdFilterTest` | 발급·계승·응답 헤더·요청 후 정리·로그 인젝션 차단 |
| `TraceIdFilterRegistrationTest` | 필터 순서와 디스패치 타입 |
| `LogbackProfileConfigTest` | 프로파일별 appender·레벨·유실 방지 4개 값·롤링 상한 |
| `MaskingTest` | 콘솔/파일 양쪽 마스킹, 스택트레이스 1회 출력, traceId 훼손 없음 |
| `OrderRequestMaskingTest` | 마스킹 불변식 — 원문이 절대 나오지 않는다 |
| `OrderFlowTest` | 승인·재고 거절·결제 거절 세 갈래의 로그와 응답 |

> 모든 `@SpringBootTest` 에 `@DirtiesContext` 가 붙어 있다.
> Logback 의 `LoggerContext` 는 JVM 전역이고 `LogbackLoggingSystem.initialize()` 는
> `if (isAlreadyInitialized(loggerContext)) return;` 으로 시작한다.
> 앞 클래스의 컨텍스트가 캐시에 남아 있으면 다음 클래스는 프로파일을 바꿔도
> `logback-spring.xml` 을 다시 읽지 못한다. 사유는 `LogbackProfileConfigTest` javadoc 에 적었다.

## 기술 스택

| 항목 | 버전 |
|---|---|
| Java | 21 |
| Spring Boot | 3.5.0 |
| SLF4J | 2.0.17 |
| Logback | 1.5.18 |
| logstash-logback-encoder | 8.0 |
| Gradle | 8.14 (Wrapper) |

Logback 은 직접 추가한 의존성이 아니다.
`spring-boot-starter-web` → `spring-boot-starter-logging` 을 통해 전이 의존성으로 들어온다.
`log4j-to-slf4j` 와 `jul-to-slf4j` 브리지도 함께 포함되므로, 다른 로깅 API 를 쓰는 서드파티
라이브러리의 출력까지 Logback 설정 하나로 통제할 수 있다.

## 실행

```bash
./gradlew bootRun                                  # local — 콘솔만, 앱 로거 DEBUG
SPRING_PROFILES_ACTIVE=dev  ./gradlew bootRun      # dev  — 콘솔 + JSON 파일, 3일 보관
SPRING_PROFILES_ACTIVE=prod ./gradlew bootRun      # prod — 앱 INFO / 프레임워크 WARN, 30일 보관
```

기본 포트는 `8080`. `logs/order-app.log` 는 **dev / prod 에서만** 생성된다.

```bash
# 주문
curl -i -X POST localhost:8080/orders -H 'Content-Type: application/json' \
  -d '{"userId":"u1","productId":"TV-1","quantity":1,"cardNumber":"1234-5678-9012-3456"}'

# 운영 중 레벨 변경 (재배포 없이)
curl -X POST localhost:8080/actuator/loggers/com.example.logbackmdclab.order \
  -H 'Content-Type: application/json' -d '{"configuredLevel":"DEBUG"}'
```

> actuator 엔드포인트는 학습 편의를 위해 열어두었다. 실제 운영이라면 인증·네트워크 제한이 함께 가야 한다.
