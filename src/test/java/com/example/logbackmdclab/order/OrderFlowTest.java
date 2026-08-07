package com.example.logbackmdclab.order;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import com.example.logbackmdclab.support.LogCapture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 주문 흐름의 세 갈래(승인 · 재고 거절 · 결제 거절)가 각각 어떤 로그와 응답을 남기는지 고정한다.
 *
 * <p>가장 중요한 것은 <b>결제 거절</b> 케이스다. 이 프로젝트는 MDC 스코프를
 * "각 계층이 자기 키를 지운다" 가 아니라 "요청 경계가 통째로 되돌린다" 로 설계했는데,
 * 그 선택의 근거가 정확히 여기 있다 — 예외로 빠져나가는 경로에서
 * 실패 로그가 찍히기 <i>전에</i> orderId 가 사라지면 안 된다.
 */
@DirtiesContext
class OrderFlowTest {

    private static final String IN_STOCK = "TV-1";
    private static final String OUT_OF_STOCK = "TV-3";

    private static String orderJson(String productId) {
        return """
                {"userId":"u1","productId":"%s","quantity":1,"cardNumber":"1234-5678-9012-3456"}
                """.formatted(productId);
    }

    abstract static class Base {

        @Autowired
        MockMvc mockMvc;

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

        MvcResult post(String productId) throws Exception {
            return mockMvc.perform(
                            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/orders")
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(orderJson(productId)))
                    .andReturn();
        }

        ILoggingEvent eventContaining(String fragment) {
            return logs.events().stream()
                    .filter(e -> e.getFormattedMessage().contains(fragment))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError(
                            "'" + fragment + "' 로그가 없다. 수집: " + messages()));
        }

        List<String> messages() {
            return logs.events().stream().map(ILoggingEvent::getFormattedMessage).toList();
        }
    }

    @Nested
    @SpringBootTest
    @AutoConfigureMockMvc
    @TestPropertySource(properties = "app.payment.decline-rate-percent=0")
    @DirtiesContext
    @DisplayName("결제가 승인되는 경우")
    class PaymentApproved extends Base {

        @Test
        @DisplayName("RECEIVED 를 반환하고 흐름 전체가 하나의 traceId 로 이어진다")
        void receivesOrder() throws Exception {
            MvcResult result = post(IN_STOCK);

            assertThat(result.getResponse().getStatus()).isEqualTo(200);
            assertThat(result.getResponse().getContentAsString())
                    .contains("\"status\":\"RECEIVED\"")
                    .contains("\"orderId\":\"ORD-");

            assertThat(messages()).anyMatch(m -> m.contains("주문 접수 완료"));
            assertThat(logs.events()).allSatisfy(e ->
                    assertThat(e.getMDCPropertyMap().get("traceId")).isNotNull());
        }
    }

    @Nested
    @SpringBootTest
    @AutoConfigureMockMvc
    @TestPropertySource(properties = "app.payment.decline-rate-percent=0")
    @DirtiesContext
    @DisplayName("재고가 부족한 경우")
    class OutOfStock extends Base {

        @Test
        @DisplayName("결제까지 가지 않고 거절한다 — 거절 사유가 필드로 남는다")
        void rejectsBeforePayment() throws Exception {
            MvcResult result = post(OUT_OF_STOCK);

            assertThat(result.getResponse().getContentAsString())
                    .contains("\"status\":\"REJECTED\"")
                    .contains("\"reason\":\"OUT_OF_STOCK\"");

            ILoggingEvent rejection = eventContaining("주문 거절");
            assertThat(rejection.getLevel()).isEqualTo(Level.WARN);
            assertThat(rejection.getMDCPropertyMap().get("orderId")).startsWith("ORD-");
            assertThat(messages()).noneMatch(m -> m.contains("결제 승인"));
        }
    }

    @Nested
    @SpringBootTest
    @AutoConfigureMockMvc
    @TestPropertySource(properties = "app.payment.decline-rate-percent=100")
    @DirtiesContext
    @DisplayName("결제가 거절되는 경우")
    class PaymentDeclined extends Base {

        @Test
        @DisplayName("402 와 함께 traceId 를 본문으로 돌려준다")
        void returnsTraceIdInBody() throws Exception {
            MvcResult result = post(IN_STOCK);

            String header = result.getResponse().getHeader("X-Trace-Id");

            assertThat(result.getResponse().getStatus()).isEqualTo(402);
            assertThat(result.getResponse().getContentAsString())
                    .contains("\"code\":\"PAYMENT_DECLINED\"")
                    .contains(header);
        }

        @Test
        @DisplayName("예외로 빠져나간 뒤 찍히는 실패 로그에도 orderId 가 남아 있다")
        void failureLogStillCarriesOrderId() throws Exception {
            post(IN_STOCK);

            // GlobalExceptionHandler 는 OrderService 밖에서 실행된다.
            // 계층마다 자기 키를 remove 했다면 이 단언이 깨진다.
            ILoggingEvent failure = eventContaining("주문 실패");

            assertThat(failure.getLevel()).isEqualTo(Level.WARN);
            assertThat(failure.getMDCPropertyMap().get("orderId")).startsWith("ORD-");
            assertThat(failure.getMDCPropertyMap().get("traceId")).isNotNull();
        }

        @Test
        @DisplayName("예상된 업무 실패라 스택트레이스를 남기지 않는다 — ERROR 는 예상 못한 것에만 쓴다")
        void doesNotLogStackTraceForBusinessFailure() throws Exception {
            post(IN_STOCK);

            assertThat(eventContaining("주문 실패").getThrowableProxy()).isNull();
            assertThat(logs.events()).noneMatch(e -> e.getLevel() == Level.ERROR);
        }
    }
}
