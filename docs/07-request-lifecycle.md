# 07. 요청 하나의 전 구간

01~06 은 관심사를 하나씩 다룬다. 이 문서는 그것들이 **요청 하나에서 어떤 순서로 맞물리는지** 꿴다.
새 결정을 담지 않는다 — 이미 내린 결정들이 실행 시점에 어디서 만나는지만 적는다.

기준은 `local` 프로파일에서 성공하는 주문 한 건이다.

```
POST /orders
{ "userId":"u-1", "productId":"P-100", "quantity":2, "cardNumber":"4111111111111111" }
```

---

## 1부. 기동 — 요청이 오기 전에 끝나는 일

아래 다섯 단계는 애플리케이션 수명 동안 한 번만 실행된다. 요청마다 도는 코드는 없다.
그런데 2부의 모든 동작이 여기서 정해진 값에 의존한다.

### B1 — Environment 준비

`application.yml` + `application-{profile}.yml` 을 읽는다.
`spring.profiles.default: local`(`application.yml:7`) 이 여기서 적용된다.

읽기가 끝나면 `ApplicationEnvironmentPreparedEvent` 가 발행되고, `LoggingApplicationListener` 가 B2 를 시작한다.

### B2 — `logback-spring.xml` 을 읽는다

B1 이 **먼저** 끝났기 때문에 `<springProperty>` 가 값을 찾을 수 있다.
반대 순서였다면 전부 `defaultValue` 로 떨어진다.

```xml
<!-- :15~24 — B1 이 올려둔 Environment 에서 꺼낸다 -->
<springProperty name="LOG_FILE" source="app.logging.file"/>

<!-- :27 — 모든 로그 줄의 앞부분. MDC 두 칸이 여기서 뚫린다 -->
<property name="LOG_PREFIX" value=
  "%d{HH:mm:ss.SSS} %-5level [%thread] [%X{traceId:-NO_TRACE}] [%X{orderId:-}] %logger{36}"/>
```

같은 파일에서 `<springProfile name="dev | prod">`(`:79`) 가 판정된다.
`local` 이므로 파일 appender 가 통째로 빠지고 콘솔만 남는다.

파일 이름이 `logback.xml` 이 아닌 이유가 이 순서다.
`logback.xml` 은 Logback 이 클래스로딩 시점에 직접 읽는다 — Spring Environment 가 존재하기도 전이라
`springProperty` 도 `springProfile` 도 해석할 수 없다.
`-spring` 접미사는 "Logback 은 넘어가라, Spring 이 대신 읽는다"는 표시이고,
그것이 **yml 먼저 → xml 나중**을 보장한다.

### B3 — 레벨을 그 위에 덮어쓴다

B2 가 끝난 **뒤** `logging.level.*` 이 적용된다.
그래서 XML 에 `<logger level>` 을 적어도 yml 이 항상 이긴다. 소유권 분리의 근거다 → [02-profile-split](./02-profile-split.md#소유권)

| 순서 | 읽는 것 | 소유하는 것 |
|:---:|---|---|
| B1 | `application*.yml` | 프로파일, 값 |
| B2 | `logback-spring.xml` | appender / encoder 구조 — *어디에 어떤 모양으로* |
| B3 | `logging.level.*` | 레벨 — *무엇을 남길지* |

`local` 은 `com.example.logbackmdclab: DEBUG` 다.
2부에서 `log.debug()` 줄이 실제로 찍히는 이유이며, dev·prod 에서는 같은 줄이 사라진다.

### B4 — 빈 배선

`AsyncConfig:53` 이 `@Async` 실행기를 직접 정의하고 데코레이터를 명시적으로 건다.
자동설정에 맡기지 않은 이유는 [03-mdc-propagation](./03-mdc-propagation.md#배선을-명시하는-이유) 에 있다.

`ProfileValidator` 가 `@PostConstruct` 로 활성 프로파일을 검사한다.
오타 난 프로파일로 뜨면 기동을 세운다 — 조용한 로깅 누락을 기동 실패로 바꾼다.

### B5 — 필터를 톰캣 체인 맨 앞에 등록한다

```java
// LoggingConfig.java:28~31
registration.setOrder(Ordered.HIGHEST_PRECEDENCE);          // = Integer.MIN_VALUE
registration.setDispatcherTypes(REQUEST, ASYNC, ERROR);
registration.addUrlPatterns("/*");
```

order 값이 체인 순서가 되는 경로는 세 단계다.

1. Spring 이 모든 `ServletContextInitializer` 를 `AnnotationAwareOrderComparator` 로 **오름차순 정렬**한다.
2. 그 순서대로 `ServletContext.addFilter()` 를 호출한다.
3. Jakarta Servlet 스펙 §6.2.4 — URL 패턴으로 매칭된 필터는 **등록된 순서대로** 체인을 이룬다.

`order` 는 Spring 개념이라 톰캣은 모른다. Spring 이 정렬해서 넘겨주면 톰캣은 받은 순서를 따를 뿐이다.

Boot 3.5.0 내장 필터의 값을 `javap` 로 확인했다.

| 필터 | order |
|---|---:|
| `OrderedCharacterEncodingFilter` | −2,147,483,648 |
| **`TraceIdFilter`** | **−2,147,483,648** |
| `OrderedHiddenHttpMethodFilter` | −10,000 |
| `OrderedFormContentFilter` | −9,900 |
| `OrderedRequestContextFilter` | −105 |

**`OrderedCharacterEncodingFilter` 가 같은 값이다.**
동점일 때 안정 정렬이 수집 순서를 유지하지만, 그 순서는 스펙으로 보장되지 않는다.
실질적 영향은 없다 — `CharacterEncodingFilter` 는 로그를 남기지 않으므로 앞이든 뒤든 `NO_TRACE` 줄이 생기지 않는다.
다만 `MIN_VALUE` 보다 작은 int 는 없으므로, 단독 최선두가 필요해지면 상대를 뒤로 미루는 수밖에 없다.

`DispatcherType.ERROR` 를 명시한 이유는 [03-mdc-propagation](./03-mdc-propagation.md#필터는-왜-onceperrequestfilter-인가) 에 있다.

---

## 2부. 요청 — 성공 경로

1 과 9 는 같은 `try` 블록의 여닫이다. 2~8 은 전부 그 안에서 일어난다.

### 1 — 필터 진입

```java
// TraceIdFilter.java:45~54
String traceId = resolveTraceId(request);         // :45  헤더 없으면 32 hex 신규 발급
request.setAttribute(CARRIED_TRACE_ID, traceId);  // :46  ERROR 재디스패치 대비
response.setHeader("X-Trace-Id", traceId);        // :49  고객이 CS 에 건넬 값

try (MdcScope ignored = MdcScope.open()) {        // :51  스코프 열림 · snapshot = null
    MDC.put("traceId", traceId);                  // :52  MDC = {traceId}
    chain.doFilter(request, response);            // :53  아래 2~8 전부가 이 안
}                                                 // :54  → 9 로
```

### 2 — 체인 통과 → 디스패치 → 역직렬화

이 구간에 이 프로젝트 코드는 없다.

```
chain.doFilter()
  └─ CharacterEncoding → HiddenHttpMethod → FormContent → RequestContext
       └─ DispatcherServlet.doDispatch()
            ├─ RequestMappingHandlerMapping   POST /orders → OrderController#createOrder
            └─ RequestResponseBodyMethodProcessor
                 └─ Jackson: JSON → OrderRequest      ← 컨트롤러보다 먼저
```

전부 `chain.doFilter()` 의 스택 안에서, 같은 톰캣 스레드에서 실행된다.
MDC 는 ThreadLocal 이므로 1 에서 넣은 traceId 가 이 구간 전체에서 보인다 —
내 코드가 아닌 Spring 내부 로그에도 traceId 가 붙는 이유다.

역직렬화가 컨트롤러보다 **먼저**라는 점이 실패 경로를 가른다.
본문이 깨져 있으면 컨트롤러에 진입조차 못 하므로 orderId 가 아예 없다. 그때 남는 단서는 traceId 하나다.

### 3 — 컨트롤러

```java
// OrderController.java:23~24
log.info("주문 요청 수신 {}", request);
return orderService.place(request);
```

`request` 를 통째로 찍는데도 카드번호가 노출되지 않는다.
`OrderRequest.toString()`(`:26~27`) 이 `cardNumber=****-1111` 로 바꾼다 — 찍는 쪽 방어. → [05-masking](./05-masking.md)

### 4 — orderId 부여

```java
// OrderService.java:40~44
String orderId = "ORD-" + UUID.randomUUID();
MDC.put("orderId", orderId);                   // MDC = {traceId, orderId}
log.info("주문 접수 시작 {} {} {}", kv(...), kv(...), kv(...));
```

여기서부터 모든 로그 줄에 orderId 가 붙는다. 메서드 인자로 넘기지 않는다.

이 클래스에 `remove` 도 `finally` 도 없다. 정리는 9 가 한 번에 한다. → [03-mdc-propagation](./03-mdc-propagation.md#스코프는-누가-여나)

### 5 — 재고 확인

```java
// InventoryService.java:18~33
log.debug("재고 확인 요청 …");                  // 요청 줄은 DEBUG
Thread.sleep(30~100ms);
boolean enough = Math.abs(productId.hashCode()) % 4 != 0;
log.info("재고 확인 응답 … enough=true");       // 응답 줄은 INFO — 분기 근거
```

판정이 `hashCode` 기반이라 같은 productId 면 항상 같은 결과다. 재현 가능한 테스트 데이터가 된다.

### 6 — 결제 승인

```java
// PaymentService.java:31~47
int amount = 10_000 * quantity;
log.info("결제 승인 요청 amount=20000");
log.debug("PG 요청 페이로드 {}", kv("cardNumber", cardNumber));   // 원문 그대로 — 의도적
Thread.sleep(50~200ms);
if (random(100) < declineRatePercent) { ... }                    // 기본 10 → 통과
log.info("결제 승인 완료 APPROVED amount=20000");
```

`:38` 은 의도적으로 규약을 어긴다. 나가는 쪽이 실제로 막는지 증명하려면 규약을 어긴 줄이 하나는 있어야 한다.
콘솔은 `MaskingMessageConverter`, 파일은 `MaskingJsonGeneratorDecorator` 가 막는다. → [05-masking](./05-masking.md)

### 7 — 알림 발송 (스레드 전환)

```java
// AsyncConfig.java:28~34
Map<String,String> callerContext = MDC.getCopyOfContextMap();  // 넘기는 쪽(톰캣)에서 실행
return () -> {
    try (MdcScope ignored = MdcScope.openWith(callerContext)) { // 받는 쪽(task)에서 실행
        runnable.run();
    }
};
```

```
[exec-1]  MDC = {traceId, orderId}
   │  getCopyOfContextMap() ─ 복사
   ├──────────────┐
   ▼              ▼
[exec-1] 계속   [task-1]  {} → openWith() 이식 → {traceId, orderId}
                          log.info("주문 확인 알림 발송")
                          close() 복원 → {}
```

`sendOrderConfirmation()` 은 인자가 하나도 없다. 그래도 로그에 orderId 가 찍힌다. → [03-mdc-propagation](./03-mdc-propagation.md)

전파 범위는 이 실행기뿐이다. 직접 만든 스레드풀이나 `CompletableFuture` 의 기본 ForkJoinPool 에는 적용되지 않는다.

### 8 — 응답 생성

```java
// OrderService.java:60~61
log.info("주문 접수 완료 {}", kv("status", "RECEIVED"));
return new OrderResponse(orderId, "RECEIVED", null);
```

`OrderResponse` 는 `@JsonInclude(NON_NULL)` 이라 `reason: null` 이 JSON 에서 빠진다.

### 9 — MDC 정리

`try` 의 **괄호 안**에 자원을 선언했으므로 컴파일러가 `finally` 를 만들어 넣는다.
`MdcScope` 가 `AutoCloseable` 을 구현한 것이 전제다.

```java
// javac 가 실제로 펼치는 형태
MdcScope ignored = MdcScope.open();
try { ... } finally { ignored.close(); }   // 소스에 없지만 바이트코드에 있다
```

정상 리턴이든 예외 탈출이든 `return` 이든 반드시 실행된다.

```java
// MdcScope.java:47~52 — 지우는 게 아니라 되돌린다
if (snapshot == null) MDC.clear();          // 필터는 보통 이쪽
else MDC.setContextMap(snapshot);           // 중첩된 경우
```

둘 중 **하나만** 실행된다.
`MDC.setContextMap(map)` 은 병합이 아니라 맵 전체 치환이다 — 중간에 추가된 키는 사라지고, 중간에 지워진 키는 되살아난다.
몇 개를 넣었는지 셀 필요가 없다는 것이 핵심이다.

`MDC.clear()` 로 통일하지 않은 이유는 중첩이다. 바깥 스코프가 심어둔 값까지 걷어내면 안 된다.

```
{}  →  {traceId}  →  {traceId, orderId}  ─── 5~8 아무도 건드리지 않음 ───→  {}
       1 put         4 put                                                  9 close
```

키를 넣는 곳은 둘, 지우는 곳은 하나다.

---

## 나가는 것

```
HTTP/1.1 200 OK
X-Trace-Id: a3f9c1d2e4b67890a3f9c1d2e4b67890

{ "orderId": "ORD-7c2e4f81-…", "status": "RECEIVED" }
```

콘솔 출력의 형식은 다음과 같다. 시각과 ID 는 실행마다 다르다.

```
14:22:01.100 INFO  [exec-1] [a3f9…] [        ] c.OrderController     - 주문 요청 수신 OrderRequest[…, cardNumber=****-1111]
14:22:01.101 INFO  [exec-1] [a3f9…] [ORD-7c2e] c.OrderService        - 주문 접수 시작 userId=u-1 productId=P-100 quantity=2
14:22:01.101 DEBUG [exec-1] [a3f9…] [ORD-7c2e] c.InventoryService    - 재고 확인 요청 productId=P-100 quantity=2
14:22:01.168 INFO  [exec-1] [a3f9…] [ORD-7c2e] c.InventoryService    - 재고 확인 응답 productId=P-100 enough=true
14:22:01.169 INFO  [exec-1] [a3f9…] [ORD-7c2e] c.PaymentService      - 결제 승인 요청 amount=20000
14:22:01.169 DEBUG [exec-1] [a3f9…] [ORD-7c2e] c.PaymentService      - PG 요청 페이로드 cardNumber=****-****-****-****
14:22:01.312 INFO  [exec-1] [a3f9…] [ORD-7c2e] c.PaymentService      - 결제 승인 완료 paymentStatus=APPROVED amount=20000
14:22:01.313 INFO  [task-1] [a3f9…] [ORD-7c2e] c.NotificationService - 주문 확인 알림 발송
14:22:01.313 INFO  [exec-1] [a3f9…] [ORD-7c2e] c.OrderService        - 주문 접수 완료 status=RECEIVED
```

읽을 지점이 셋이다.

- **첫 줄만 orderId 칸이 비어 있다.** 아직 4 에 도달하기 전이다. 그 칸의 폭이 `%X{orderId:-}` 의 기본값이다.
- **여덟째 줄만 스레드가 다른데 두 칸은 그대로다.** 7 의 `TaskDecorator` 가 한 일이다.
- **마지막 두 줄의 순서는 매번 달라질 수 있다.** 알림은 별도 스레드라 경합이다.

dev·prod 였다면 `ASYNC_FILE` 로 JSON 한 벌이 더 나간다. DEBUG 두 줄은 사라진다.

---

## 실패는 어디서 갈라지나

1 과 9 는 어느 경로에서도 실행된다. 그래서 네 경우 모두 응답 본문에 traceId 가 실린다.

| 갈라지는 곳 | 응답 | 로그 | 되돌릴 수 있나 |
|---|---|---|---|
| **5** 재고 부족<br>`OrderService:50` | `200` REJECTED / OUT_OF_STOCK | WARN, 예외 아님 | 결제 전 |
| **6** 결제 거절<br>`PaymentService:44` | `402` PAYMENT_REQUIRED | WARN, 스택트레이스 없음 | 결제 전 |
| **2** 본문 파싱 실패<br>`GlobalExceptionHandler:55` | `400` BAD_REQUEST | WARN, **orderId 없음** | 아무것도 안 일어남 |
| **6~8** 예상 못한 예외<br>`GlobalExceptionHandler:46` | `500` INTERNAL_ERROR | ERROR + 스택트레이스 | **결제 취소 로직 없음** |

`GlobalExceptionHandler` 는 `DispatcherServlet` **안에서**, 즉 아직 1 의 스코프 안에서 실행된다.
그래서 `ErrorResponse.of()`(`ErrorResponse.java:15`) 의 `MDC.get("traceId")` 가 값을 찾는다.

### 이 저장소가 다루지 않는 것

- **결제 승인 후 실패하면 되돌릴 수단이 없다.** `PaymentService:47` 에서 APPROVED 가 찍힌 뒤 예외가 나면 500 이 나가는데 돈은 이미 나갔다. 주문을 저장하는 저장소도 없다. 이 저장소는 그 상황을 *로그로 복원 가능하게 만드는 데까지* 다룬다.
- **알림 실패는 응답에 영향을 주지 않는다.** 7 에서 이미 `200 RECEIVED` 가 나간 뒤다. Spring 기본 `SimpleAsyncUncaughtExceptionHandler` 가 ERROR 를 남기고, 그 줄에도 traceId·orderId 가 붙는다.
- **`InventoryService:23~26` 의 인터럽트는 `false` 를 반환한다.** 로그에는 `reason=INTERRUPTED` 로 남지만 응답은 `OUT_OF_STOCK` 이다. 같은 상황에서 `PaymentService.sleep()` 은 예외를 던지므로 두 계층의 처리가 어긋나 있다.

---

## 조용히 깨지는 지점

위 흐름에서 깨져도 기동은 성공하고 예외도 나지 않는 것들이다. 그래서 흐름이 아니라 **배선 조건**을 테스트로 고정했다.

| 테스트 | 잠근 것 | 깨지면 |
|---|---|---|
| `TraceIdFilterRegistrationTest` | order = `MIN_VALUE`, ERROR·ASYNC 커버 | 앞선 필터 로그가 `NO_TRACE`, 장애 순간만 추적 끊김 |
| `MdcTaskDecoratorTest`<br>`AsyncTracePropagationTest` | 스레드 전환 시 MDC 전파 | 7 의 로그가 `NO_TRACE` |
| `ProfileValidatorTest` | 프로파일 오타 차단 | 어느 환경도 아닌 상태로 기동 |
| `MaskingTest`<br>`OrderRequestMaskingTest` | 두 겹 마스킹 | 카드번호 원문이 로그에 남음 |
| `LogbackProfileConfigTest` | 프로파일별 appender 구성 | 운영에서 파일 로그 누락 |
| `OrderFlowTest` | 주문 흐름 전 구간 | — |
