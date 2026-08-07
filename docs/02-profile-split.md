# 02. 프로파일 분리 — 무엇을 yml 이 갖고 무엇을 XML 이 갖나

환경별 로깅 정책을 나눌 때 설정이 두 곳에 존재하게 된다.
`application-{env}.yml` 과 `logback-spring.xml` 이다. 경계를 정하지 않으면 같은 값이 양쪽에 생긴다.

## 소유권

| 무엇 | 어디 | 이유 |
|---|---|---|
| 레벨 (`logging.level.*`) | `application-{env}.yml` | Boot 가 logback 설정을 읽은 **뒤에** 적용한다. 두 곳에 적으면 항상 yml 이 이긴다. 그럼 XML 쪽은 죽은 코드다 |
| 보관 정책 값 (일수·총량·파일 크기) | `application-{env}.yml` | 환경마다 다른 "값"이다 |
| appender / encoder 구조 | `logback-spring.xml` | 환경이 달라도 "모양"은 같다 |
| 마스킹 | `logback-spring.xml` | 보안 통제는 한 벌만 존재해야 한다 |

XML 은 `<springProperty>` 로 yml 값을 읽는다.

```xml
<springProperty scope="context" name="LOG_MAX_HISTORY" source="app.logging.max-history" defaultValue="3"/>
```

그 결과 `<springProfile>` 블록은 **하나뿐**이다.

```xml
<springProfile name="dev | prod">
    ... FILE + ASYNC_FILE 정의 (한 벌) ...
</springProfile>
```

이전에는 이 블록이 dev 와 prod 에 통째로 복사돼 있었고, 그 복사본 안에 마스킹 설정이 들어 있었다.
보안 통제가 두 군데 있으면 언젠가 한쪽만 고친다.

## 프로파일을 주지 않은 실행

`spring.profiles.default: local` 을 명시했다. 이게 없으면 프로파일 없이 뜬 앱은
local 도 dev 도 prod 도 아닌 **네 번째 상태**가 된다. 그 상태에서만 나는 버그를 만들지 않기 위해서다.

프로파일 이름은 컴파일러도 Spring 도 검증하지 않는다.
`--spring.profiles.active=devv` 같은 오타는 예외도 경고도 없이 파일 로깅을 통째로 빼놓고 정상 기동한다.
이 프로젝트가 [AsyncAppender](./04-async-appender-loss.md)에서 논증한 "조용한 실패"와 같은 종류다.
현재는 기본값 명시로 무프로파일 상태만 없앤 상태이고, 오타 자체는 여전히 막지 못한다.

## `logback.xml` 에서 `<springProfile>` 이 무시된다는 설명은 사실과 다르다

파일명을 `logback.xml` 로 두어도 프로파일 분리는 **정상 동작한다**(Spring Boot 3.5.0 / Logback 1.5.18 기준).
설정 파일이 두 번 읽히기 때문이다. Logback 이 먼저 자체 파서로 읽으면서
`Ignoring unknown property [springProfile]` 경고를 남기고, 이어서 Spring Boot 가
`SpringBootJoranConfigurator` 로 다시 읽으며 프로파일을 적용한다.

관측되는 차이는 동작 여부가 아니라 **기동 시 경고 3줄**이다.
`logback-spring.xml` 을 쓰면 Logback 이 그 이름을 찾지 않으므로 첫 번째 패스 자체가 없고 경고도 사라진다.

## 프로파일 블록 안에 `appender-ref` 만 두면 안 된다

환경별 설정을 나눌 때 `<root>` 의 `appender-ref` 를 각 `<springProfile>` 안에만 두었더니,
프로파일을 지정하지 않고 실행했을 때 **로그가 한 줄도 출력되지 않았다.**
공통 `root` 와 콘솔 appender 는 프로파일 밖에 두고, 프로파일 블록에는 환경별로 달라지는 것만 적는다.

## 운영 중 레벨 변경

`logback-spring.xml` 에 `scan="true"` 를 걸지 않았다. 대신 actuator 를 넣었다.

```bash
curl -X POST localhost:8080/actuator/loggers/com.example.logbackmdclab.order \
  -H 'Content-Type: application/json' -d '{"configuredLevel":"DEBUG"}'
```

실측: `204` 응답 직후 요청 한 건을 보내면 prod 에서 보이지 않던 `재고 확인 요청`(DEBUG) 이
파일에 다시 남는다. `{"configuredLevel":null}` 로 되돌리면 사라진다.

`scan` 대신 actuator 를 택한 이유는 파일 교체가 필요 없고 변경 이력이 요청으로 남기 때문이다.
대신 이 엔드포인트는 인증·네트워크 제한이 반드시 함께 가야 한다 —
지금 저장소는 학습용이라 열어두었고, 그대로 배포하면 안 된다.

## 프로파일별 실행

```bash
./gradlew bootRun                                                  # local (기본값)
SPRING_PROFILES_ACTIVE=dev  ./gradlew bootRun                      # dev  — 파일 생성
SPRING_PROFILES_ACTIVE=prod ./gradlew bootRun                      # prod — 파일 생성
```

`logs/order-app.log` 는 dev / prod 에서만 만들어진다.
README 의 `jq` 시연을 재현하려면 local 이 아니라 dev 로 띄워야 한다.
