# 04. AsyncAppender 는 기본 설정만으로도 로그를 버린다

파일 쓰기는 디스크 I/O 라 요청 스레드를 붙잡는다. `AsyncAppender` 로 큐에 넘기면 해결되지만,
이 appender 는 큐가 차면 로그를 **말없이 버린다.**

## 실측

동시 100건(`./scripts/load.sh 100`), dev 프로파일.
콘솔은 동기 appender 라 유실이 없으므로 이걸 기준선으로 삼는다.
비교 대상은 애플리케이션 로거(`com.example.logbackmdclab`)가 요청 처리 중 남긴 줄이다.

### 위험한 설정

```bash
./gradlew bootRun --args='--spring.profiles.active=dev \
  --app.logging.async.queue-size=16 \
  --app.logging.async.discarding-threshold=3 \
  --app.logging.async.never-block=true'
```

| 레벨 | 콘솔(동기) | 파일(비동기) | 유실 |
|---|---:|---:|---:|
| DEBUG | 176 | 127 | **49** |
| INFO | 583 | 498 | **85** |
| WARN | 39 | 39 | 0 |
| **합계** | **798** | **664** | **134** |

### 안전한 설정 (현재 기본값)

```bash
SPRING_PROFILES_ACTIVE=dev ./gradlew bootRun
```

| 레벨 | 콘솔(동기) | 파일(비동기) | 유실 |
|---|---:|---:|---:|
| DEBUG | 176 | 176 | 0 |
| INFO | 574 | 574 | 0 |
| WARN | 45 | 45 | 0 |
| **합계** | **795** | **795** | **0** |

두 실행 모두 **예외도 경고도 없었고**(`ch.qos.logback` 상태 로그 0줄), 응답은 전부 정상이었다.
콘솔에는 전부 보이므로 개발 중에는 드러나지 않는다.

## WARN 만 살아남은 것이 핵심이다

유실의 원인은 둘인데, 더 위험한 쪽은 명시하지 않은 **기본값**이다.

`discardingThreshold` 의 기본값은 `queueSize / 5` 다. 큐 여유가 그 아래로 떨어지면
**WARN 미만(INFO · DEBUG)** 이벤트를 버린다. 위 표에서 WARN 이 39/39 로 온전한 이유가 이것이다.

이 선택적 폐기가 왜 위험한가. 장애를 분석할 때 필요한 건 WARN 한 줄이 아니라
**그 앞에 무슨 일이 있었는가**다. 부하가 걸린 순간, 즉 장애가 나는 바로 그때
정확히 그 맥락만 골라서 사라진다.

`neverBlock=false` 는 큐가 가득 찰 때 버리는 대신 대기하겠다는 선택이다.
로그 유실보다 응답 지연이 낫다고 판단했으나, 유실이 허용되는 로그라면 반대가 맞다.
정답이 아니라 트레이드오프다.

## 명시한 값 네 개

```xml
<appender name="ASYNC_FILE" class="ch.qos.logback.classic.AsyncAppender">
    <queueSize>2048</queueSize>
    <discardingThreshold>0</discardingThreshold>
    <neverBlock>false</neverBlock>
    <maxFlushTime>5000</maxFlushTime>
</appender>
```

네 번째 `maxFlushTime` 은 종료 시 큐에 남은 것을 비우는 데 허용하는 시간이다.
기본값 1초는 큐 2048건을 비우기에 빠듯하다.
앞의 세 값을 "기본값을 믿지 않는다"는 이유로 명시해 놓고 이것만 비워두면 같은 실수를 반복하는 것이다.

`LogbackProfileConfigTest` 가 dev / prod 양쪽에서 네 값을 모두 단언한다.
XML 은 컴파일러가 보지 않으므로, 오타 하나로 기본값으로 돌아가도 알려주는 신호가 없다.

## 범위

이 실험은 정상 운영 구간(정상 종료 포함) 기준이다.
`SIGKILL` 이나 `maxFlushTime` 초과는 범위 밖이며, 그 경우 큐에 남은 로그는 사라진다.
유실을 0으로 만들 수는 없고, 유실이 **조용히** 일어나지 않게 만든 것이다.

## 같은 종류의 실수 — 롤링 정책

`totalSizeCap` 은 롤오버 시점에 **아카이브 파일들의 합계**만 본다.
지금 쓰이고 있는 활성 파일 `logs/order-app.log` 는 그 합계에 들어가지 않는다.
`TimeBasedRollingPolicy` 만 쓰면 롤오버 트리거가 자정뿐이라,
트래픽이 튀는 날엔 활성 파일 하나가 디스크가 찰 때까지 커진다.

"디스크가 터지지 않게 총량을 막는다"는 주석은 아카이브에 대해서만 참이었다.
`SizeAndTimeBasedRollingPolicy` + `maxFileSize` 로 바꿨다.
파일 하나의 상한과 총량 상한은 서로 다른 문제다.

```xml
<rollingPolicy class="ch.qos.logback.core.rolling.SizeAndTimeBasedRollingPolicy">
    <fileNamePattern>${LOG_FILE}.%d{yyyy-MM-dd}.%i.gz</fileNamePattern>
    <maxFileSize>100MB</maxFileSize>
    <maxHistory>30</maxHistory>
    <totalSizeCap>3GB</totalSizeCap>
</rollingPolicy>
```

`%i` 는 `SizeAndTimeBased` 에서 필수다.
