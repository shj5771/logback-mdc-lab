# 05. 마스킹 — 두 겹, 그리고 정규식이 두 개인 이유

## 두 겹

**찍는 쪽** — `OrderRequest.toString()` 을 재정의해 객체 자체가 카드번호를 노출하지 않게 한다.

```java
return "OrderRequest[userId=%s, ..., cardNumber=%s]".formatted(userId, ..., maskedCardNumber());
```

**나가는 쪽** — 콘솔은 커스텀 컨버터, 파일은 `MaskingJsonGeneratorDecorator`.
출력 경로가 둘이라 나가는 쪽 통제도 둘이다. 걷어내려면 **세 지점**을 손대야 한다.

한 겹만으로는 부족하다. 찍는 쪽은 설정이 강제하는 통제가 아니라 **코딩 규약**이다.
누군가 `log.info("card={}", request.cardNumber())` 라고 쓰면 그 겹은 없는 것과 같다.

그래서 이 저장소에는 규약을 일부러 어긴 줄이 하나 있다.

```java
// PaymentService — [의도적 시연]
log.debug("PG 요청 페이로드 {}", kv("cardNumber", cardNumber));
```

나가는 쪽이 실제로 막는지 증명하려면 어기는 줄이 있어야 한다.
실측 결과 콘솔·파일 양쪽 모두 `cardNumber=****-****-****-****` 로 나간다.
(prod 는 이 로거가 INFO 라 아예 찍히지 않는다.)

## 콘솔은 왜 커스텀 컨버터인가

처음 시도한 것은 `%replace(%msg){...}` 였다. 두 가지가 안 된다.

**하나.** 예외는 `%msg` 밖이다. 패턴 체인에 예외 컨버터가 없으면 Logback 의
`EnsureExceptionHandling` 이 끝에 `ExtendedThrowableProxyConverter` 를 자동으로 붙인다.
그 자동 추가분은 어떤 치환도 거치지 않는다. 스택트레이스에 섞인 값은 그대로 나간다.

**둘.** `%replace(%msg%n%ex){...}` 로 감싸도 소용없다.
자동 추가 여부를 판정하는 `chainHandlesThrowable()` 은 **최상위 체인만 훑고 composite 안으로 들어가지 않는다.**
그래서 예외 컨버터가 하나 더 붙고, 스택트레이스가 두 번 찍힌다 —
한 번은 가려진 채로, 한 번은 원문 그대로.

해법은 `ThrowableHandlingConverter` 를 직접 상속하는 것이다.
이 타입이 체인에 있으면 Logback 은 예외 처리가 끝났다고 보고 자동 추가를 하지 않는다.

```xml
<conversionRule conversionWord="maskedMsg"
                class="com.example.logbackmdclab.common.MaskingMessageConverter"/>
...
<pattern>${LOG_PREFIX} - %maskedMsg{CARD_REGEX_BROAD,CARD_MASK}%n</pattern>
```

> `<conversionRule>` 의 `converterClass` 속성은 Logback 1.5 에서 deprecated 다.
> `class` 를 쓰지 않으면 기동 시 경고가 남는다.

정규식은 패턴에 직접 쓰지 않고 **LoggerContext 프로퍼티 이름**으로 넘긴다.
정규식에는 `{15,16}` 처럼 중괄호와 쉼표가 들어가는데, 그게 패턴 옵션 구분자와 같은 문자다.
이름으로 넘기면 충돌이 없고, 정규식은 XML 에 한 번만 적힌다.

`MaskingTest.Console.printsStackTraceExactlyOnce()` 가 두 번 찍히는 회귀를 막는다.

## 정규식이 두 개인 이유

적용 대상이 다르다.

| | 대상 | 정규식 |
|---|---|---|
| 콘솔 (BROAD) | 메시지 + 스택트레이스 | 구분자 유무 무관, 15~16자리까지 |
| 파일 (STRICT) | JSON 의 **모든 문자열 값** | 구분자 있는 형태 + hex 경계로 감싼 15~16자리 |

콘솔은 넓게 잡아도 안전하다. `traceId` 와 `orderId` 는 `%X{}` 로 따로 찍혀 치환 범위 밖이기 때문이다.

파일은 다르다. `MaskingJsonGeneratorDecorator` 의 값 정규식은 JSON 의 모든 문자열 값에 걸린다.
`traceId` 는 32 hex 인데, 여기에 `(?<!\d)\d{15,16}(?!\d)` 를 걸면
**하필 숫자만 16자리 연속으로 나온 traceId 의 그 구간이 통째로 가려진다.**
이 프로젝트의 핵심 지표를 설정으로 스스로 깨는 셈이다.

그렇다고 구분자 있는 형태만 잡으면 구분자 없는 16자리가 `message` 에 남는다.
`<path>cardNumber</path>` 는 필드 값만 가릴 뿐, `kv()` 가 message 에 함께 렌더한
`"cardNumber=1234567890123456"` 텍스트는 건드리지 못하기 때문이다.

그래서 경계를 숫자가 아니라 **hex** 로 잡았다.

```
(?<![0-9a-fA-F])\d{15,16}(?![0-9a-fA-F])
```

32 hex 안의 16자리 숫자 구간은 한쪽 이웃이 반드시 `a-f` 다
(이웃도 숫자라면 연속 길이가 16을 넘어 애초에 매칭되지 않는다).
자유 텍스트의 카드번호는 이웃이 `=` 나 공백이라 그대로 걸린다.

`MaskingTest.File.doesNotCorruptTraceId()` 가 이 경계를 고정한다.

## 경로 마스킹을 함께 쓴다

```xml
<jsonGeneratorDecorator class="net.logstash.logback.mask.MaskingJsonGeneratorDecorator">
    <defaultMask>${CARD_MASK}</defaultMask>
    <path>cardNumber</path>                         <!-- 필드 단위 -->
    <valueMask>
        <value>${CARD_REGEX_STRICT}</value>         <!-- 값 단위 -->
        <mask>${CARD_MASK}</mask>
    </valueMask>
</jsonGeneratorDecorator>
```

필드 단위는 형식과 무관하게 가리므로 값 정규식을 좁게 유지할 수 있다.
값 정규식은 자유 텍스트에 섞인 경우를 담당한다. 둘은 서로를 보완한다.

`<valueMask>` 에 `<mask>` 를 생략하면 위 `defaultMask` 가 아니라
라이브러리 자체 기본값(`****`)이 쓰인다. 경로 마스킹과 결과가 달라지므로 명시했다.

## 한계

- 커버 범위는 **구분자 없는 15~16자리**와 **4-4-4-4 형식(16자리)** 이다.
  13·14자리(구형 카드), 17자리 이상, Amex 의 4-6-5 구분자 형식(`3782 822463 10005`)은 잡지 않는다.
  주민번호·전화번호·이메일도 대상이 아니다.
- 값 정규식은 넓힐수록 다른 식별자를 훼손한다. 그 트레이드오프가 위 hex 경계의 이유다.
- 근본적으로 정규식 마스킹은 사후 방어다. 애초에 민감정보를 로그에 넘기지 않는 것이 먼저이고,
  이 설정은 그 규약이 깨졌을 때를 위한 것이다.
