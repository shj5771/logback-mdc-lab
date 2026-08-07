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
    -d "{\"userId\":\"u$i\",\"productId\":\"TV-$i\",\"quantity\":1,\"cardNumber\":\"1234-5678-9012-3456\"}" &
done; wait
```

주문 한 건은 네 줄을 남긴다.

```
주문 접수 시작 orderId=ORD-9e37d142 userId=warmup productId=WARM quantity=1
재고 확인 요청 productId=WARM quantity=1
재고 확인 응답 productId=WARM enough=true
주문 접수 완료 orderId=ORD-9e37d142
```

## 관찰 1 — 동시 요청의 출력은 인터리브된다

요청을 하나씩 보내면 위와 같이 네 줄이 붙어서 나오지만, 동시 100건에서는 그렇지 않다.

```
주문 접수 시작 orderId=ORD-c93119e8 userId=u5 productId=TV-5 quantity=1
재고 확인 요청 productId=TV-5 quantity=1
주문 접수 시작 orderId=ORD-4ee9f779 userId=u3 productId=TV-3 quantity=1
재고 확인 요청 productId=TV-3 quantity=1
```

출력 순서는 요청 순서와 무관하다. 각 요청은 서로 다른 톰캣 워커 스레드에서 실행되고,
표준 출력은 그 스레드들이 공유하는 하나의 스트림이기 때문이다.
"위아래로 붙어 있으니 같은 요청일 것"이라는 가정은 성립하지 않는다.

## 관찰 2 — `orderId` 로 검색하면 네 줄 중 두 줄만 나온다

```bash
$ grep ORD-1662fe4e console.txt
주문 접수 시작 orderId=ORD-1662fe4e userId=u43 productId=TV-43 quantity=1
주문 접수 완료 orderId=ORD-1662fe4e
```

재고 확인 두 줄이 빠진다. `InventoryService` 가 `orderId` 를 모르기 때문이다.
`hasEnoughStock(productId, quantity)` 는 재고 확인에 필요한 값만 받는다.
`orderId` 는 이 클래스의 관심사가 아니므로 시그니처에 없고, 따라서 로그에도 남길 수 없다.

**복원율 2 / 4줄 (50%).**

## 관찰 3 — 남은 두 줄은 검색 횟수를 늘려도 회수할 수 없다

`productId` 로 다시 검색하는 방법을 생각할 수 있다. 그러나 관찰 1의 출력을 다시 보면,
`재고 확인 요청 productId=TV-5 quantity=1` 이라는 줄은 **같은 상품·수량을 주문한 다른 요청과 글자까지 동일하다.**

동일한 문자열이 여러 요청에서 생성되는 이상, 그중 어느 것이 내가 찾는 요청의 것인지 판별할 근거가 로그 안에 없다.
검색 방법의 문제가 아니라 정보의 문제다. **로그에 없는 정보는 검색으로 만들어낼 수 없다.**

## 진단 결과

| # | `println` 으로 안 되는 것 | 원인 | 해결한 단계 | 해결 방법 |
|---|---|---|---|---|
| 1 | 동시 요청의 로그를 요청 단위로 분리 | 식별자가 없다 | 4 | 요청 진입 시 `traceId` 발급 후 MDC 에 저장, 출력 패턴에 `%X{traceId}` |
| 2 | 계층을 넘어 흐름을 잇기 | 하위 계층이 상위 식별자를 모른다 | 4 | MDC 는 스레드에 붙으므로 파라미터 전달 없이 하위 계층 로그에도 실린다 |
| 3 | 비동기 구간까지 흐름을 잇기 | `@Async` 는 다른 스레드다 | 5 | `TaskDecorator` 로 호출 스레드의 MDC 를 작업 스레드에 복사 |
| 4 | 환경별로 다른 로그 정책 적용 | 출력 대상과 레벨이 코드에 고정 | 2, 3 | SLF4J 로 전환 후 `logback-spring.xml` 에서 프로파일별 분리 |
| 5 | 조건을 걸어 조회·집계 | 평문이라 필드가 없다 | 6 | 파일 출력을 JSON 으로 전환, 주요 값을 MDC · `StructuredArguments` 로 필드화 |

2번이 핵심이다. 1번은 모든 로그에 `orderId` 를 직접 넣으면 우회할 수 있지만,
그러려면 모든 하위 계층의 메서드 시그니처에 식별자를 흘려보내야 한다.
실제로 `hasEnoughStock(orderId, productId, quantity)` 로 바꿔 확인해 보면 복원율은 100% 가 되지만,
호출부가 전부 깨지고 계층이 늘어날수록 비용이 커진다.

그리고 이 방식으로는 애초에 해결되지 않는 경우가 있다.
잘못된 JSON(`{"quantity":"두개"}`)을 보내면 컨트롤러에 도달하기 전에 예외가 발생하고,
로그는 Spring 내부 클래스인 `DefaultHandlerExceptionResolver` 가 남긴다.
이 시점에 `orderId` 는 존재하지도 않으며, 남의 코드가 찍는 로그에 파라미터를 추가할 방법도 없다.

MDC 를 도입한 뒤에는 이 줄에도 `traceId` 가 함께 남는다.
요청 단위 식별자는 애플리케이션 코드가 아니라 요청 경계(Filter)에서 관리해야 한다는 근거다.
