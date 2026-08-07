# logback-mdc-lab

> 요청 추적이 불가능한 주문 API 에 MDC 기반 요청 추적과 환경별 로깅 전략을 도입하고,
> 그 효과를 실측한 프로젝트.

---

## 배경

주문 요청 하나는 내부적으로 4개의 계층을 거친다.

```
POST /orders
  │
  ├─ 1. 주문 접수      OrderService
  ├─ 2. 재고 확인      InventoryService
  ├─ 3. 결제 승인      PaymentService  ──▶ 외부 PG (지연 + 간헐적 실패)
  └─ 4. 알림 발송      NotificationService   @Async (별도 스레드)
```

여기에 다음 문의가 들어온 상황을 가정한다.

> "카드는 빠져나갔는데 주문 내역이 없어요."

로그를 열어 이 요청 하나의 처리 흐름을 복원해야 한다. 그런데 복원할 수가 없다.

> 이 문의는 가정한 상황이다. 다만 아래 문제들은 가정이 아니라, 동시 요청을 발생시키면 로컬에서 그대로 재현되는 현상이다.

## 문제

| # | 문제 | 원인 |
|---|---|---|
| 1 | 어느 로그 줄이 그 고객의 요청인지 알 수 없다 | 동시 요청의 출력이 뒤섞인다 |
| 2 | 계층 간 로그를 이어 붙일 수 없다 | `InventoryService` 는 `orderId` 를 모른다. 자기 관심사가 아니므로 로그에 남기지 않는다 |
| 3 | 알림 발송 로그는 흐름에서 완전히 이탈한다 | `@Async` 라 다른 스레드에서 실행된다 |
| 4 | 환경별로 로그 정책을 다르게 가져갈 수 없다 | 설정이 코드에 고정되어 있다 |
| 5 | 로그를 조건으로 검색할 수 없다 | 평문 문자열이라 필드 단위 조회가 불가능하다 |

핵심은 2번이다. 모든 로그에 개발자가 식별자를 직접 넣어주지 않으면 요청 하나의 흐름이 끊긴다.
그리고 계층이 늘어날수록 그 방식은 유지되지 않는다.

## 접근

| 단계 | 과제 | 대응하는 문제 | 상태 |
|:---:|---|:---:|:---:|
| 1 | 현행 로깅의 한계 진단 및 Before 실측 | 1, 2 | 완료 |
| 2 | SLF4J / Logback 기반 로깅 체계 도입 | 4 | 완료 |
| 3 | `logback-spring.xml` 환경별 설정 분리 (local / dev / prod) | 4 | 완료 |
| 4 | MDC 기반 `traceId` 발급 및 계층 간 전파 | 1, 2 | 완료 |
| 5 | 비동기 구간의 `traceId` 유실 해결 | 3 | 완료 |
| 6 | JSON 구조화 로그 전환 및 민감정보 마스킹 | 5 | 완료 |
| 7 | After 실측 및 결과 정리 | 전체 | 완료 |

## 측정

로그 개선의 효과는 체감이 아니라 수치로 확인한다.
Before / After 모두 **동시 100건** 부하를 건 뒤 같은 방식으로 측정했다.

| 지표 | 측정 방법 | Before | After |
|---|---|---|---|
| 요청 흐름 복원율 | 특정 요청 한 건의 로그 중 검색으로 회수한 줄의 비율 | **2 / 4줄 (50%)** | **6 / 6줄 (100%)** |
| 복원에 필요한 검색 횟수 | 위 작업에 사용한 grep / 검색 명령 수 | **복원 불가** (아래 참고) | **1회** |
| 비동기 구간 추적 성공률 | 알림 발송 로그(`@Async`) 중 `traceId` 가 남은 비율 | **0%** | **100%** (77 / 77줄) |
| 검색 가능 필드 수 | 구조화 로그에서 필드로 조회 가능한 키 개수 | **0개** | **12개** |
| 민감정보 노출 | 카드번호가 평문으로 남는 로그 줄 수 | **101줄** (콘솔·파일 모두) | **0줄** |

### 복원 불가의 의미

Before 에서 `orderId` 로 검색하면 4줄 중 2줄만 나온다. `InventoryService` 가 `orderId` 를 모르기 때문이다.
남은 2줄은 검색 횟수를 늘려도 회수할 수 없다.

```
주문 접수 시작 orderId=ORD-c93119e8 userId=u5 productId=TV-5 quantity=1
재고 확인 요청 productId=TV-5 quantity=1     ← u5 의 것
주문 접수 시작 orderId=ORD-4ee9f779 userId=u3 productId=TV-3 quantity=1
재고 확인 요청 productId=TV-3 quantity=1     ← u3 의 것
```

`productId` 로 좁혀도 마찬가지다. 같은 상품·수량 요청이 둘 이상이면 재고 로그는 글자까지 동일해
어느 주문의 것인지 판별할 근거가 로그 안에 없다. 검색 횟수의 문제가 아니라 정보의 문제다.

### After — 검색 한 번으로 복원한 흐름

```bash
jq 'select(.traceId=="1cfd2f5a")' logs/order-app.log
```

```
13:11:39.997  exec-40  OrderController      주문 요청 수신 ... cardNumber=****-3456
13:11:39.997  exec-40  OrderController      주문 접수 시작 orderId=ORD-896a235a userId=u43
13:11:39.997  exec-40  InventoryService     재고 확인 요청 productId=TV-43 quantity=1
13:11:40.047  exec-40  InventoryService     재고 확인 응답 productId=TV-43 enough=true
13:11:40.048  task-1   NotificationService  주문 확인 알림 발송 orderId=ORD-896a235a
13:11:40.048  exec-40  OrderController      주문 접수 완료 orderId=ORD-896a235a
```

599줄이 뒤섞인 파일에서 이 6줄만 나온다. 5번째 줄에서 스레드가 `exec-40` → `task-1` 로 바뀌지만
`traceId` 는 끊기지 않는다. `TaskDecorator` 로 MDC 를 전파했기 때문이다.

> Before 측정은 각 상태의 커밋을 `git worktree` 로 따로 꺼내 실행했다.
> 흐름 복원은 `9a8fefe`(println 단계), 민감정보는 마스킹 도입 직전 상태를 기준으로 했다.

## 설계 결정

측정 지표로는 드러나지 않지만, 직접 재현해 확인한 뒤 내린 판단들이다.

### AsyncAppender 는 기본 설정만으로도 로그를 버린다

파일 쓰기는 디스크 I/O 라 요청 스레드를 붙잡는다. `AsyncAppender` 로 큐에 넘기면 해결되지만,
이 appender 는 큐가 차면 로그를 **말없이 버린다**. 동시 100건으로 확인한 결과는 다음과 같다.

| 설정 | 콘솔 | 파일 | 유실 |
|---|---|---|---|
| `queueSize=16`, `neverBlock=true` | 101줄 | **51줄** | **50줄** |
| `queueSize=2048`, `discardingThreshold=0`, `neverBlock=false` | 101줄 | 101줄 | 0줄 |

예외도 경고도 발생하지 않았고 응답은 정상이었다. 콘솔에는 전부 보이므로 개발 중에는 드러나지 않는다.

유실의 원인은 둘인데, 더 위험한 쪽은 명시하지 않은 **기본값**이다.
`discardingThreshold` 의 기본값은 `queueSize / 5` 이고, 큐 여유가 그 아래로 떨어지면
WARN 미만(INFO · DEBUG) 이벤트를 버린다. 위에서 사라진 재고 확인 로그가 DEBUG 라 여기에 해당했다.

`neverBlock=false` 는 큐가 가득 찰 때 버리는 대신 대기하겠다는 선택이다.
로그 유실보다 응답 지연이 낫다고 판단했으나, 유실이 허용되는 로그라면 반대가 맞다.
정답이 아니라 트레이드오프다.

### 마스킹은 찍는 쪽과 나가는 쪽 양쪽에 건다

`OrderRequest.toString()` 을 재정의해 객체 자체가 카드번호를 노출하지 않게 하고,
JSON encoder 에 `MaskingJsonGeneratorDecorator` 를 걸어 출력 직전에 한 번 더 막는다.

한 겹만으로는 부족하다. encoder 쪽만 걸었을 때 파일에는 남지 않았지만
**콘솔에는 평문이 그대로 출력됐다.** 콘솔은 텍스트 encoder 라 그 decorator 를 거치지 않기 때문이다.

### 콘솔은 텍스트, 파일만 JSON 으로 둔다

`appender` 는 어디에 쓸지, `encoder` 는 어떤 모양으로 쓸지를 정한다. 둘은 분리되어 있다.
콘솔은 사람이 눈으로 읽으므로 모든 환경에서 텍스트 패턴을 유지하고,
수집기가 읽는 파일에만 `LogstashEncoder` 를 적용했다.

JSON 로그 파일은 한 줄이라도 JSON 이 아니면 파싱이 통째로 깨진다.
이 프로젝트에서도 파일 앞부분의 스프링 배너 때문에 `jq` 가 실패했고, `grep '^{'` 로 걸러야 했다.

### `logback.xml` 에서 `<springProfile>` 이 무시된다는 설명은 사실과 다르다

파일명을 `logback.xml` 로 두어도 프로파일 분리는 **정상 동작한다**(Spring Boot 3.5.0 / Logback 1.5.18 기준).
설정 파일이 두 번 읽히기 때문이다. Logback 이 먼저 자체 파서로 읽으면서
`Ignoring unknown property [springProfile]` 경고를 남기고, 이어서 Spring Boot 가
`SpringBootJoranConfigurator` 로 다시 읽으며 프로파일을 적용한다.

관측되는 차이는 동작 여부가 아니라 **기동 시 경고 3줄**이다.
`logback-spring.xml` 을 쓰면 Logback 이 그 이름을 찾지 않으므로 첫 번째 패스 자체가 없고 경고도 사라진다.

### 프로파일 블록 안에 `appender-ref` 만 두면 안 된다

환경별 설정을 나눌 때 `<root>` 의 `appender-ref` 를 각 `<springProfile>` 안에만 두었더니,
프로파일을 지정하지 않고 실행했을 때 **로그가 한 줄도 출력되지 않았다.**
공통 `root` 와 콘솔 appender 는 프로파일 밖에 두고, 프로파일 블록에는 환경별로 달라지는 것만 적는다.

## 기술 스택

| 항목 | 버전 |
|---|---|
| Java | 21 |
| Spring Boot | 3.5.0 |
| SLF4J | 2.0.17 |
| Logback | 1.5.18 |
| Gradle | 8.14 (Wrapper) |

Logback 은 직접 추가한 의존성이 아니다.
`spring-boot-starter-web` → `spring-boot-starter-logging` 을 통해 전이 의존성으로 들어온다.
`log4j-to-slf4j` 와 `jul-to-slf4j` 브리지도 함께 포함되므로, 다른 로깅 API 를 쓰는 서드파티
라이브러리의 출력까지 Logback 설정 하나로 통제할 수 있다.

## 실행

```bash
./gradlew bootRun
```

기본 포트는 `8080` 이다. 프로파일을 지정해 실행하려면 다음과 같이 한다.

```bash
./gradlew bootRun --args='--spring.profiles.active=local'
```

## 설계 기록

각 단계에서 왜 그 설정을 선택했는지, 그리고 어떤 것이 예상과 다르게 동작했는지는 [`docs/`](./docs) 에 남긴다.
