# 01. 진단 — `System.out.println` 으로 요청을 추적할 수 없는 이유

이 문서는 개선 이전 상태를 실행해 무엇이 안 되는지 확인한 기록이다.
대상 커밋은 `9a8fefe`(주문 접수 + 재고 확인 두 계층, 출력은 `System.out.println`)이다.

## 재현

```bash
git worktree add --detach /tmp/wt-println 9a8fefe
cd /tmp/wt-println && ./gradlew bootRun
```

```bash
for i in $(seq 1 100); do
  curl -s -X POST localhost:8080/orders \
    -H 'Content-Type: application/json' \
    -d "{\"userId\":\"u$i\",\"productId\":\"TV-$((i % 5))\",\"quantity\":1,\"cardNumber\":\"1234-5678-9012-3456\"}" &
done; wait
```

> 상품을 5종으로 좁힌 것은 의도적이다. 요청마다 `productId` 를 다르게 주면
> 아래 관찰 3 — 로그 줄이 서로 구별되지 않는 상황 — 이 재현되지 않는다.
> 실제 서비스에서 인기 상품에 주문이 몰리는 상황에 해당한다.

주문 한 건은 네 줄을 남긴다. 요청을 하나만 보내면 이렇게 나온다.

```
주문 접수 시작 orderId=ORD-00cd7d0a userId=warmup productId=WARM quantity=1
재고 확인 요청 productId=WARM quantity=1
재고 확인 응답 productId=WARM enough=true
주문 접수 완료 orderId=ORD-00cd7d0a
```

재고가 부족하면 마지막 줄이 `주문 거절 ... reason=OUT_OF_STOCK` 으로 바뀐다.
어느 쪽이든 한 요청이 네 줄이라는 점은 같다.

## 관찰 1 — 동시 요청의 출력은 인터리브된다

요청을 하나씩 보내면 위와 같이 네 줄이 붙어서 나오지만, 동시 100건에서는 그렇지 않다.

```
주문 접수 시작 orderId=ORD-7647ee95 userId=u20 productId=TV-0 quantity=1
재고 확인 요청 productId=TV-0 quantity=1
주문 접수 시작 orderId=ORD-1c0cea8c userId=u21 productId=TV-1 quantity=1
재고 확인 요청 productId=TV-1 quantity=1
주문 접수 시작 orderId=ORD-8316a756 userId=u25 productId=TV-0 quantity=1
재고 확인 요청 productId=TV-0 quantity=1
주문 접수 시작 orderId=ORD-45f7e506 userId=u35 productId=TV-0 quantity=1
재고 확인 요청 productId=TV-0 quantity=1
주문 접수 시작 orderId=ORD-75aa0e4c userId=u28 productId=TV-3 quantity=1
재고 확인 요청 productId=TV-3 quantity=1
```

출력 순서는 요청 순서와 무관하다. 각 요청은 서로 다른 톰캣 워커 스레드에서 실행되고,
표준 출력은 그 스레드들이 공유하는 하나의 스트림이기 때문이다.
"위아래로 붙어 있으니 같은 요청일 것"이라는 가정은 성립하지 않는다.

이 열 줄 안에 `TV-0` 주문이 세 건(u20, u25, u35) 들어 있고,
그 셋이 남긴 재고 확인 줄은 서로 구별되지 않는다. 관찰 3 에서 다룬다.

## 관찰 2 — `orderId` 로 검색하면 네 줄 중 두 줄만 나온다

```bash
$ grep ORD-fd336464 console.txt
주문 접수 시작 orderId=ORD-fd336464 userId=u43 productId=TV-3 quantity=1
주문 거절 orderId=ORD-fd336464 reason=OUT_OF_STOCK
```

재고 확인 두 줄이 빠진다. `InventoryService` 가 `orderId` 를 모르기 때문이다.
`hasEnoughStock(productId, quantity)` 는 재고 확인에 필요한 값만 받는다.
`orderId` 는 이 클래스의 관심사가 아니므로 시그니처에 없고, 따라서 로그에도 남길 수 없다.

**복원율 2 / 4줄 (50%).**

## 관찰 3 — 남은 두 줄은 검색 횟수를 늘려도 회수할 수 없다

`productId` 로 다시 검색하는 방법을 생각할 수 있다. 그러나 그 줄은 **같은 상품·수량을 주문한
다른 요청이 남긴 줄과 글자까지 동일하다.** 동일한 줄이 몇 개나 되는지 세어보면 이렇다.

```bash
$ grep '재고 확인 요청' console.txt | sort | uniq -c | sort -rn
  20 재고 확인 요청 productId=TV-4 quantity=1
  20 재고 확인 요청 productId=TV-3 quantity=1
  20 재고 확인 요청 productId=TV-2 quantity=1
  20 재고 확인 요청 productId=TV-1 quantity=1
  20 재고 확인 요청 productId=TV-0 quantity=1
```

`ORD-fd336464`(`TV-3`)의 재고 확인 줄은 저 20줄 중 하나다. 그런데 그 20줄은 서로 완전히 같다.
어느 것이 이 주문의 것인지 판별할 근거가 로그 안에 없다.

시각으로 좁히는 방법도 통하지 않는다. 이 커밋의 출력에는 타임스탬프조차 없고,
설령 있더라도 동시 요청은 같은 밀리초에 겹친다.

검색 방법의 문제가 아니라 정보의 문제다. **로그에 없는 정보는 검색으로 만들어낼 수 없다.**

## 진단 결과

| # | `println` 으로 안 되는 것 | 원인 | 해결한 단계 | 해결 방법 |
|---|---|---|---|---|
| 1 | 동시 요청의 로그를 요청 단위로 분리 | 식별자가 없다 | 4 | 요청 진입 시 `traceId` 발급 후 MDC 에 저장, 출력 패턴에 `%X{traceId}` |
| 2 | 계층을 넘어 흐름을 잇기 | 하위 계층이 상위 식별자를 모른다 | 4 | MDC 는 스레드에 붙으므로 파라미터 전달 없이 하위 계층 로그에도 실린다 |
| 3 | 비동기 구간까지 흐름을 잇기 | `@Async` 는 다른 스레드다 | 5 | `TaskDecorator` 로 호출 스레드의 MDC 를 작업 스레드에 복사 |
| 4 | 환경별로 다른 로그 정책 적용 | 출력 대상과 레벨이 코드에 고정 | 2, 3 | SLF4J 로 전환 후 `logback-spring.xml` 에서 프로파일별 분리 |
| 5 | 조건을 걸어 조회·집계 | 평문이라 필드가 없다 | 7 | 파일 출력을 JSON 으로 전환, 주요 값을 MDC · `StructuredArguments` 로 필드화 |
| 6 | 실패한 요청의 검색 키 확보 | 식별자가 서버 밖으로 나가지 않는다 | 8 | 응답 헤더 `X-Trace-Id` 와 `ErrorResponse.traceId` 로 노출 |

2번이 핵심이다. 1번은 모든 로그에 `orderId` 를 직접 넣으면 우회할 수 있지만,
그러려면 모든 하위 계층의 메서드 시그니처에 식별자를 흘려보내야 한다.
실제로 `hasEnoughStock(orderId, productId, quantity)` 로 바꿔 확인해 보면 복원율은 100% 가 되지만,
호출부가 전부 깨지고 계층이 늘어날수록 비용이 커진다.

그리고 이 방식으로는 애초에 해결되지 않는 경우가 있다.
잘못된 JSON(`{"quantity":"두개"}`)을 보내면 컨트롤러에 도달하기 전에 예외가 발생한다.
이 시점에 `orderId` 는 **존재하지도 않는다.** 채번하는 코드가 아직 실행되지 않았기 때문이다.
식별자를 파라미터로 흘려보내는 전략은 여기서 원리적으로 막힌다.

MDC 를 도입한 뒤에는 이 줄에도 `traceId` 가 남는다. 요청 경계(Filter)가 컨트롤러보다 먼저 실행되기 때문이다.

```
14:26:23.360 WARN [http-nio-8080-exec-1] [0d5b4efc...] GlobalExceptionHandler
             - 요청 거부 status=REJECTED httpStatus=400 exceptionType=HttpMessageNotReadableException
```

요청 단위 식별자는 애플리케이션 코드가 아니라 요청 경계에서 관리해야 한다는 근거다.

> 이 줄을 남기는 주체는 이후 단계에서 바뀌었다. 처음에는 Spring 내부의
> `DefaultHandlerExceptionResolver` 가 찍었고, 8단계에서 `@RestControllerAdvice` 를 추가한 뒤로는
> 우리 코드(`GlobalExceptionHandler`)가 찍는다. 프레임워크가 찍는 로그에도 traceId 가 실린다는
> 사실은 그대로다 — `local` 프로파일에서
> `logging.level.org.springframework.web.servlet.mvc.method.annotation.ExceptionHandlerExceptionResolver=DEBUG`
> 로 확인할 수 있다.

## 이 문서 이후

여기서 진단한 다섯 가지 문제의 해결 과정과 실측은 [`docs/`](.) 의 나머지 문서에 있다.
Before / After 수치 전체는 [06-measurement](./06-measurement.md) 에 정리했다.
