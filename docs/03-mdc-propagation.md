# 03. MDC 전파 — 어디서 넣고, 어디까지 가고, 언제 지우나

MDC 는 `ThreadLocal` 이다. 그 한 문장에서 이 문서의 모든 내용이 따라 나온다.
스레드에 붙으므로 파라미터 없이 하위 계층까지 따라가고, 스레드에 붙으므로 스레드가 바뀌면 끊기고,
스레드가 재사용되므로 지우지 않으면 다음 요청이 이전 값을 본다.

## 스코프는 누가 여나

원칙은 "넣은 곳이 뺀다" 가 **아니라** "스코프를 연 곳이 닫는다" 이다.

```java
// TraceIdFilter
try (MdcScope ignored = MdcScope.open()) {   // 현재 상태를 찍어둔다
    MDC.put(TRACE_ID, traceId);
    chain.doFilter(request, response);
}                                            // 찍어둔 상태로 통째로 되돌린다
```

`OrderService` 는 그 스코프 안에서 `MDC.put("orderId", ...)` 만 한다. 지우지 않는다.

계층마다 자기 키를 `remove` 하게 만들면 **예외로 빠져나가는 경로에서 실패 로그가 찍히기 전에
식별자가 사라진다.** 결제가 거절되면 `PaymentDeclinedException` 이 `OrderService` 를 통과해
`GlobalExceptionHandler` 로 가는데, 그 핸들러는 서비스 밖이다.
서비스가 자기 `orderId` 를 지우고 나갔다면 정작 필요한 그 로그에 orderId 가 없다.

`OrderFlowTest.PaymentDeclined.failureLogStillCarriesOrderId()` 가 이 불변식을 고정한다.

`MDC.clear()` 를 쓰지 않고 스냅샷 복원을 쓰는 이유도 같다.
`clear()` 는 이 스코프가 넣지 않은 값까지 걷어낸다. 바깥 스코프가 심어둔 값을 보존해야 한다.

한계: 웹 요청의 스코프는 `TraceIdFilter` 가 연다.
배치나 스케줄러처럼 다른 진입점을 만들 때는 **그 진입점이 자기 경계를 그어야 한다.**

## traceId 는 어디서 오나

우선순위는 세 단계다.

1. ERROR 디스패치로 되돌아온 원래 값 (request attribute)
2. 인바운드 `X-Trace-Id` 헤더
3. 신규 발급

2번이 있어야 게이트웨이나 상위 서비스가 붙인 값을 덮어쓰지 않는다.
다만 헤더는 **외부 입력**이다. 개행을 섞어 보내면 로그에 가짜 줄을 만들 수 있으므로
영숫자 8~64자만 통과시킨다.

발급 형식은 하이픈을 걷어낸 **32 hex** 다. 이전에는 UUID 앞 8자를 썼는데 바꿨다.
32 hex 는 W3C trace-context 의 trace-id 폭이고,
Spring Boot `CorrelationIdFormatter` 의 기본 스펙(`traceId(32),spanId(16)`)과도 같다.
8자(32비트)는 콘솔에서 읽기 편하지만, 30일 보관 규모에서는 충돌쌍이 생긴다 —
그러면 "traceId 하나로 요청 하나를 복원한다"는 전제 자체가 흔들린다.
읽기 편함보다 그 전제를 지키는 쪽을 택했다.

반대로 `orderId` 는 절단하지 않는다. 그건 로그 식별자가 아니라 **업무 식별자**이고,
고객이 CS 에 건네는 값이다.

## traceId 는 어디로 나가나

응답 헤더 `X-Trace-Id` 와, 실패 응답의 본문(`ErrorResponse.traceId`)이다.

```bash
$ curl -i -X POST localhost:8080/orders -d '{"quantity":"두개"}' -H 'Content-Type: application/json'
HTTP/1.1 400
X-Trace-Id: 8415c1cc195e478b8f8f217cd867f156

{"traceId":"8415c1cc195e478b8f8f217cd867f156","code":"BAD_REQUEST","message":"요청을 해석하지 못했다."}
```

이게 없으면 "문의 접수 → 로그 검색" 서사의 첫 단추가 없다.
성공한 요청은 `orderId` 로 찾을 수 있지만, **컨트롤러에 도달하기 전에 깨진 요청은
업무 식별자 자체가 만들어지지 않는다.** 그때 고객이 쥔 값은 traceId 뿐이다.

## 필터는 왜 `OncePerRequestFilter` 인가

`Filter` 를 직접 구현하면 Spring Boot 의 `AbstractFilterRegistrationBean.determineDispatcherTypes()` 가
`EnumSet.of(DispatcherType.REQUEST)` 를 반환한다. `OncePerRequestFilter` 면 `EnumSet.allOf(...)` 다.

차이가 나는 지점은 **ERROR 디스패치**다. 미처리 예외는 필터 체인을 빠져나간 뒤
컨테이너가 `/error` 로 다시 디스패치하는데, 이건 별도의 디스패치라 REQUEST 에만 매핑된 필터는 타지 않는다.
바꾸기 전 실측:

```
13:26:23.360 WARN  [exec-1] [0d5b4efc] DefaultHandlerExceptionResolver - Resolved [HttpMessageNotReadableException...]
13:26:23.363 DEBUG [exec-1] [NO_TRACE] DispatcherServlet - "ERROR" dispatch for POST "/error"
```

장애 분석용 로깅인데 정작 장애 순간만 `NO_TRACE` 로 빠진다.

상속만으로는 부족하다. `shouldNotFilterErrorDispatch()` 기본값이 `true` 라서
ERROR 디스패치에서 필터 본문이 건너뛰어진다. `false` 로 뒤집어야 실제로 동작한다.

그리고 등록은 `@Component` 가 아니라 `FilterRegistrationBean` 으로 명시한다.
`@Component` 필터는 순서를 지정하지 않으면 체인 최후미에 붙고, 앞선 필터의 로그에는 traceId 가 없다.
디스패치 타입도 상속 관계에 기대지 않고 직접 적는다 —
`TraceIdFilterRegistrationTest` 가 둘 다 고정한다.

> 지금은 `GlobalExceptionHandler` 가 모든 예외를 잡으므로 `/error` 까지 가는 경로가 거의 없다.
> ERROR 디스패치 커버리지는 그 핸들러가 놓치는 경우를 위한 보험이다.

## 비동기 경계

`@Async` 는 다른 스레드다. `TaskDecorator` 로 호출 스레드의 MDC 를 작업 스레드에 옮긴다.

```java
return runnable -> {
    Map<String, String> callerContext = MDC.getCopyOfContextMap();   // 넘기는 쪽에서 실행
    return () -> {                                                    // 받는 쪽에서 실행
        try (MdcScope ignored = MdcScope.openWith(callerContext)) {
            runnable.run();
        }
    };
};
```

### 배선을 명시하는 이유

이 빈이 없어도 전파는 동작한다. Boot 의 `ThreadPoolTaskExecutorBuilderConfiguration` 이
`ObjectProvider<TaskDecorator>.getIfUnique()` 로 주워가기 때문이다.

문제는 `getIfUnique()` 의 의미다 — **후보가 둘 이상이면 null 을 반환한다.**
나중에 SecurityContext 전파용 데코레이터를 하나 더 추가하는 순간 MDC 전파가 통째로 빠진다.
그런데 기동 실패도 예외도 경고도 없고, 로그에는 패턴 기본값 `NO_TRACE` 만 조용히 찍힌다.
README 가 가장 앞세우는 수치가 아무 신호 없이 0% 로 되돌아갈 수 있는 구조였다.

그래서 실행기를 직접 정의하고 데코레이터를 직접 건다.

```java
@Bean(name = {"applicationTaskExecutor", "taskExecutor"})
ThreadPoolTaskExecutor applicationTaskExecutor(ThreadPoolTaskExecutorBuilder builder) {
    return builder.taskDecorator(mdcTaskDecorator()).build();
}
```

Boot 의 자동설정은 `@ConditionalOnMissingBean(Executor.class)` 라 이 빈이 있으면 물러난다.

### 적용 범위

이 전파는 위 실행기를 쓰는 경로에만 적용된다.
직접 만든 스레드풀, `CompletableFuture` 의 기본 ForkJoinPool, `WebClient` 의 리액터 스레드에는
적용되지 않는다. 100% 라는 숫자에 조건을 붙이는 게 숫자를 깎는 게 아니라 범위를 정확히 하는 것이다.

## 다음 단계 — Micrometer Tracing 으로 가면

이 코드는 확장되는 게 아니라 **대체된다.** 알고 좁힌 것이므로 여기 적어둔다.

- 트레이서가 MDC 의 `traceId` / `spanId` 를 직접 채운다. `TraceIdFilter` 는 제거 대상이 된다.
- MDC 키 `traceId` 의 소유자가 바뀐다. 지금 32 hex 를 쓰는 이유가 그때 폭이 맞기 때문이다.
- `logback-spring.xml` 은 이미 `defaults.xml` 을 include 해 `%correlationId` 컨버터를 확보해뒀다.
  패턴만 바꾸면 된다.

직접 만들어 본 이유는 encoder / appender / decorator 의 관계와 MDC 의 생명주기를
손으로 다뤄보는 것이 이 랩의 목적이기 때문이다. 분산 추적은 이 프로젝트의 범위 밖이다.
