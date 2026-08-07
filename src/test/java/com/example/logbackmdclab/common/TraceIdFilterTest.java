package com.example.logbackmdclab.common;

import com.example.logbackmdclab.support.LogCapture;
import ch.qos.logback.classic.spi.ILoggingEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

@SpringBootTest
@AutoConfigureMockMvc
// Logback LoggerContext 는 JVM 전역이다. 컨텍스트를 열어둔 채 두면 뒤따르는 프로파일 테스트가
// logback-spring.xml 을 다시 읽지 못한다. 사유는 LogbackProfileConfigTest 참고.
@DirtiesContext
class TraceIdFilterTest {

    private static final String ORDER_JSON = """
            {"userId":"u1","productId":"TV-1","quantity":1,"cardNumber":"1234-5678-9012-3456"}
            """;

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

    @Test
    @DisplayName("요청 하나의 모든 로그가 같은 traceId 를 갖는다")
    void allLogsOfOneRequestShareOneTraceId() throws Exception {
        mockMvc.perform(post("/orders").contentType(MediaType.APPLICATION_JSON).content(ORDER_JSON));

        List<ILoggingEvent> events = logs.events();
        assertThat(events).isNotEmpty();

        Set<String> traceIds = events.stream()
                .map(e -> e.getMDCPropertyMap().get("traceId"))
                .collect(Collectors.toSet());

        assertThat(traceIds).hasSize(1);
        assertThat(traceIds.iterator().next()).isNotNull();
    }

    @Test
    @DisplayName("발급하는 traceId 는 32 hex — W3C trace-context 및 Boot correlationId 규약과 같은 폭")
    void issuesThirtyTwoHexTraceId() throws Exception {
        MvcResult result = mockMvc.perform(
                post("/orders").contentType(MediaType.APPLICATION_JSON).content(ORDER_JSON)).andReturn();

        String traceId = result.getResponse().getHeader(TraceIdFilter.TRACE_ID_HEADER);

        assertThat(traceId).matches("[0-9a-f]{32}");
    }

    @Test
    @DisplayName("traceId 를 응답 헤더로 돌려준다 — 고객 문의에서 로그로 이어지는 유일한 키다")
    void returnsTraceIdInResponseHeader() throws Exception {
        MvcResult result = mockMvc.perform(
                post("/orders").contentType(MediaType.APPLICATION_JSON).content(ORDER_JSON)).andReturn();

        String header = result.getResponse().getHeader(TraceIdFilter.TRACE_ID_HEADER);
        String logged = logs.events().getFirst().getMDCPropertyMap().get("traceId");

        assertThat(header).isEqualTo(logged);
    }

    @Test
    @DisplayName("인바운드 X-Trace-Id 를 계승한다 — 게이트웨이가 붙인 값을 새로 발급해 덮지 않는다")
    void inheritsInboundTraceId() throws Exception {
        mockMvc.perform(post("/orders")
                .header(TraceIdFilter.TRACE_ID_HEADER, "abcdef0123456789")
                .contentType(MediaType.APPLICATION_JSON)
                .content(ORDER_JSON));

        assertThat(logs.events())
                .isNotEmpty()
                .allSatisfy(e ->
                        assertThat(e.getMDCPropertyMap()).containsEntry("traceId", "abcdef0123456789"));
    }

    @Test
    @DisplayName("형식에 맞지 않는 인바운드 값은 버리고 새로 발급한다 — MDC 는 로그 인젝션 경로다")
    void rejectsMalformedInboundTraceId() throws Exception {
        MvcResult result = mockMvc.perform(post("/orders")
                .header(TraceIdFilter.TRACE_ID_HEADER, "bad\nINFO fake log line")
                .contentType(MediaType.APPLICATION_JSON)
                .content(ORDER_JSON)).andReturn();

        assertThat(result.getResponse().getHeader(TraceIdFilter.TRACE_ID_HEADER))
                .matches("[0-9a-f]{32}");
    }

    @Test
    @DisplayName("요청이 끝나면 스레드의 MDC 가 비워진다 — 톰캣 스레드는 재사용된다")
    void clearsMdcAfterRequest() throws Exception {
        // MockMvc 는 호출 스레드에서 필터 체인을 실행하므로, 호출 직후 이 스레드의 MDC 를 그대로 볼 수 있다
        mockMvc.perform(post("/orders").contentType(MediaType.APPLICATION_JSON).content(ORDER_JSON));

        assertThat(MDC.get("traceId")).isNull();
        assertThat(MDC.get("orderId")).isNull();
    }

    @Test
    @DisplayName("컨트롤러에 도달하기 전에 깨진 요청도 traceId 를 응답 본문으로 돌려준다")
    void malformedRequestStillCarriesTraceId() throws Exception {
        MvcResult result = mockMvc.perform(post("/orders")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"quantity\":\"두개\"}")).andReturn();

        String header = result.getResponse().getHeader(TraceIdFilter.TRACE_ID_HEADER);

        assertThat(result.getResponse().getStatus()).isEqualTo(400);
        assertThat(header).matches("[0-9a-f]{32}");
        assertThat(result.getResponse().getContentAsString()).contains(header);
    }
}
