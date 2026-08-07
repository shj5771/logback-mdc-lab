package com.example.logbackmdclab.common;

import ch.qos.logback.classic.spi.ILoggingEvent;
import com.example.logbackmdclab.support.LogCapture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.core.task.TaskDecorator;
import org.springframework.http.MediaType;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * README 의 헤드라인 수치("비동기 구간 추적 성공률 0% → 100%")를 코드로 고정한다.
 *
 * <p>{@link MdcTaskDecoratorTest} 는 데코레이터 자체를 검증하고, 여기서는 <b>배선</b>을 검증한다.
 * 데코레이터가 아무리 정확해도 {@code @Async} 실행기에 붙지 않으면 아무 일도 일어나지 않는다 —
 * 그리고 그 실패는 예외도 경고도 없이 조용하다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = "app.payment.decline-rate-percent=0")
// Logback LoggerContext 는 JVM 전역이다. 컨텍스트를 열어둔 채 두면 뒤따르는 프로파일 테스트가
// logback-spring.xml 을 다시 읽지 못한다. 사유는 LogbackProfileConfigTest 참고.
@DirtiesContext
class AsyncTracePropagationTest {

    private static final String ORDER_JSON = """
            {"userId":"u1","productId":"TV-1","quantity":1,"cardNumber":"1234-5678-9012-3456"}
            """;

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ThreadPoolTaskExecutor applicationTaskExecutor;

    @Autowired
    TaskDecorator mdcTaskDecorator;

    LogCapture logs;

    @BeforeEach
    void attachAppender() {
        logs = LogCapture.attachTo("com.example.logbackmdclab");
    }

    @AfterEach
    void detachAppender() {
        logs.close();
        MDC.clear();
    }

    @Test
    @DisplayName("@Async 로 스레드가 바뀌어도 traceId 와 orderId 가 끊기지 않는다")
    void traceSurvivesThreadSwitch() throws Exception {
        mockMvc.perform(post("/orders").contentType(MediaType.APPLICATION_JSON).content(ORDER_JSON));

        ILoggingEvent asyncEvent = logs.awaitEvent(
                e -> e.getLoggerName().endsWith("NotificationService"), Duration.ofSeconds(5));
        ILoggingEvent webEvent = logs.events().stream()
                .filter(e -> e.getLoggerName().endsWith("OrderController"))
                .findFirst()
                .orElseThrow();

        // 스레드는 분명히 바뀌었는데
        assertThat(asyncEvent.getThreadName()).isNotEqualTo(webEvent.getThreadName());
        // traceId 와 orderId 는 이어진다
        assertThat(asyncEvent.getMDCPropertyMap().get("traceId"))
                .isEqualTo(webEvent.getMDCPropertyMap().get("traceId"));
        assertThat(asyncEvent.getMDCPropertyMap().get("orderId")).startsWith("ORD-");
    }

    @Test
    @DisplayName("실행기에 MDC 데코레이터가 명시적으로 걸려 있다")
    void decoratorIsWiredExplicitly() {
        // getIfUnique() 관례에 기대지 않고 AsyncConfig 가 직접 건다.
        // TaskDecorator 빈이 하나 더 생겨도 이 단언은 깨지지 않아야 한다.
        Object wired = org.springframework.test.util.ReflectionTestUtils
                .getField(applicationTaskExecutor, "taskDecorator");

        assertThat(wired).isSameAs(mdcTaskDecorator);
    }
}
