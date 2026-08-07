package com.example.logbackmdclab.logging;

import ch.qos.logback.classic.AsyncAppender;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.LoggingEvent;
import ch.qos.logback.core.rolling.RollingFileAppender;
import com.example.logbackmdclab.common.MaskingMessageConverter;
import net.logstash.logback.encoder.LogstashEncoder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static net.logstash.logback.argument.StructuredArguments.kv;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 마스킹은 "찍는 쪽"({@code OrderRequest.toString()}) 과 "나가는 쪽"(encoder/pattern) 두 겹이다.
 * 찍는 쪽은 {@code OrderRequestMaskingTest} 가 본다. 여기서는 나가는 쪽을 본다 —
 * 즉 <b>규약을 어긴 코드가 있어도 출력에는 남지 않는가</b>.
 */
class MaskingTest {

    private static final String SEPARATED = "1234-5678-9012-3456";
    private static final String PLAIN_16 = "1234567890123456";
    private static final String MASK = "****-****-****-****";

    @Nested
    @DisplayName("콘솔 (%maskedMsg 컨버터)")
    class Console {

        private MaskingMessageConverter converter;
        private LoggerContext context;

        @BeforeEach
        void setUp() {
            context = new LoggerContext();
            context.putProperty("CARD_REGEX_BROAD",
                    "(?<!\\d)(?:\\d{4}[ -]?){3}\\d{4}(?!\\d)|(?<!\\d)\\d{15,16}(?!\\d)");
            context.putProperty("CARD_MASK", MASK);

            converter = new MaskingMessageConverter();
            converter.setContext(context);
            converter.setOptionList(List.of("CARD_REGEX_BROAD", "CARD_MASK"));
            converter.start();
        }

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {SEPARATED, PLAIN_16, "1234 5678 9012 3456"})
        @DisplayName("메시지 안의 카드번호는 형식과 무관하게 가려진다")
        void masksCardNumberInMessage(String card) {
            String rendered = converter.convert(event("PG 요청 페이로드 cardNumber=" + card, null));

            assertThat(rendered).doesNotContain(card).contains(MASK);
        }

        @Test
        @DisplayName("스택트레이스 안의 카드번호도 가려진다 — 예외 메시지는 %msg 밖이다")
        void masksCardNumberInStackTrace() {
            Throwable boom = new IllegalStateException("PG 응답 오류 card=" + SEPARATED);

            String rendered = converter.convert(event("결제 실패", boom));

            assertThat(rendered).doesNotContain(SEPARATED).contains(MASK);
        }

        @Test
        @DisplayName("스택트레이스를 정확히 한 번만 찍는다 — %replace 로 감싸면 두 번 나온다")
        void printsStackTraceExactlyOnce() {
            String rendered = converter.convert(event("결제 실패", new IllegalStateException("boom")));

            assertThat(rendered.split("java.lang.IllegalStateException", -1)).hasSize(2);
        }

        @Test
        @DisplayName("카드번호가 아닌 숫자는 건드리지 않는다 — 마스킹이 다른 식별자를 훼손하면 안 된다")
        void leavesOtherNumbersAlone() {
            String rendered = converter.convert(event("결제 승인 완료 amount=10000 quantity=3", null));

            assertThat(rendered).contains("amount=10000").contains("quantity=3");
        }

        private ILoggingEvent event(String message, Throwable throwable) {
            Logger logger = context.getLogger("test");
            return new LoggingEvent("test", logger, Level.INFO, message, throwable, null);
        }
    }

    @Nested
    @SpringBootTest
    @ActiveProfiles("dev")
    @TestPropertySource(properties = "app.logging.file=build/test-logs/masking.log")
    @DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
    @DisplayName("파일 (LogstashEncoder + MaskingJsonGeneratorDecorator)")
    class File {

        /**
         * 실제로 설정된 encoder 를 꺼내 직접 인코딩한다.
         * 테스트에서 encoder 를 새로 조립하면 XML 이 아니라 테스트 코드를 검증하게 된다.
         */
        private LogstashEncoder configuredEncoder() {
            LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
            AsyncAppender async =
                    (AsyncAppender) context.getLogger(Logger.ROOT_LOGGER_NAME).getAppender("ASYNC_FILE");
            RollingFileAppender<?> file = (RollingFileAppender<?>) async.getAppender("FILE");
            return (LogstashEncoder) file.getEncoder();
        }

        private String encode(String message, Object... arguments) {
            LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
            Logger logger = context.getLogger("com.example.logbackmdclab.order.PaymentService");
            ILoggingEvent event =
                    new LoggingEvent("t", logger, Level.INFO, message, null, arguments);
            return new String(configuredEncoder().encode(event), StandardCharsets.UTF_8);
        }

        @Test
        @DisplayName("구조화 필드로 찍은 카드번호는 경로 마스킹이 가린다 — 형식과 무관하게")
        void masksStructuredCardNumberField() {
            String json = encode("PG 요청 페이로드 {}", kv("cardNumber", PLAIN_16));

            assertThat(json).doesNotContain(PLAIN_16).contains("\"cardNumber\":\"" + MASK + "\"");
        }

        @Test
        @DisplayName("자유 텍스트에 섞인 구분자 있는 카드번호는 값 마스킹이 가린다")
        void masksSeparatedCardNumberInFreeText() {
            String json = encode("PG 응답 오류 card=" + SEPARATED);

            assertThat(json).doesNotContain(SEPARATED).contains(MASK);
        }

        @Test
        @DisplayName("32 hex traceId 는 훼손되지 않는다 — 파일 쪽 값 정규식을 좁게 잡은 이유")
        void doesNotCorruptTraceId() {
            // 하필 숫자만 16자리 연속으로 나온 traceId. 넓은 정규식을 쓰면 이 구간이 통째로 가려진다.
            String traceId = "ab1234567890123456ef0123456789ab";
            org.slf4j.MDC.put("traceId", traceId);
            try {
                String json = encode("주문 접수 완료 {}", kv("status", "RECEIVED"));
                assertThat(json).contains(traceId);
            } finally {
                org.slf4j.MDC.remove("traceId");
            }
        }

        @Test
        @DisplayName("수집기가 앱과 환경을 거를 수 있는 필드가 있다")
        void carriesServiceAndEnvFields() {
            String json = encode("주문 접수 완료 {}", kv("status", "RECEIVED"));

            assertThat(json)
                    .contains("\"service\":\"logback-mdc-lab\"")
                    .contains("\"env\":\"dev\"");
        }
    }
}
