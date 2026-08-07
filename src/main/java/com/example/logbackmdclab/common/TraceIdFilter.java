package com.example.logbackmdclab.common;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 요청 하나에 traceId 를 하나 붙이고, 요청이 끝나면 MDC 를 원상복구한다.
 *
 * <p>{@code Filter} 를 직접 구현하지 않고 {@link OncePerRequestFilter} 를 상속한 이유는
 * ERROR 디스패치 때문이다. 미처리 예외는 필터 체인을 빠져나간 뒤 컨테이너가 {@code /error} 로
 * 다시 디스패치하는데, 이 구간은 별도의 디스패치라 REQUEST 에만 매핑된 필터는 타지 않는다.
 * 그 결과 500 응답을 만드는 로그가 전부 {@code NO_TRACE} 로 찍힌다 —
 * 장애 분석용 로깅에서 정작 장애 순간만 추적이 끊기는 셈이다.
 *
 * <p>{@code shouldNotFilterErrorDispatch()} 기본값이 {@code true} 라서 상속만으로는 부족하다.
 * 아래에서 {@code false} 로 뒤집고, 원래 요청의 traceId 를 request attribute 로 이어받아
 * ERROR 디스패치가 <b>새 traceId 를 발급하지 않게</b> 한다.
 */
public class TraceIdFilter extends OncePerRequestFilter {

    public static final String TRACE_ID = "traceId";
    public static final String TRACE_ID_HEADER = "X-Trace-Id";

    private static final String CARRIED_TRACE_ID = TraceIdFilter.class.getName() + ".traceId";

    /**
     * 인바운드 헤더는 외부 입력이다. 그대로 MDC 에 넣으면 개행을 섞어 가짜 로그 줄을 만들 수 있다.
     * 영숫자 8~64자만 통과시킨다.
     */
    private static final Pattern SAFE_TRACE_ID = Pattern.compile("[0-9a-zA-Z]{8,64}");

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {

        String traceId = resolveTraceId(request);
        request.setAttribute(CARRIED_TRACE_ID, traceId);

        // 고객이 화면에서 읽어 CS 에 전달할 수 있어야 "문의 -> 로그 검색" 이 코드로 닫힌다.
        response.setHeader(TRACE_ID_HEADER, traceId);

        try (MdcScope ignored = MdcScope.open()) {
            MDC.put(TRACE_ID, traceId);
            chain.doFilter(request, response);
        }
    }

    /**
     * 우선순위: ERROR 디스패치로 되돌아온 원래 값 &gt; 인바운드 헤더 &gt; 신규 발급.
     *
     * <p>발급 형식은 하이픈을 걷어낸 32 hex 다. W3C trace-context 의 trace-id 폭이자
     * Spring Boot {@code CorrelationIdFormatter} 의 기본 스펙({@code traceId(32),spanId(16)})과 같다.
     * 나중에 Micrometer Tracing 으로 옮길 때 키 이름도 폭도 그대로 맞물린다.
     */
    private String resolveTraceId(HttpServletRequest request) {
        if (request.getAttribute(CARRIED_TRACE_ID) instanceof String carried) {
            return carried;
        }
        String incoming = request.getHeader(TRACE_ID_HEADER);
        if (incoming != null && SAFE_TRACE_ID.matcher(incoming).matches()) {
            return incoming;
        }
        return UUID.randomUUID().toString().replace("-", "");
    }

    @Override
    protected boolean shouldNotFilterErrorDispatch() {
        return false;
    }

    @Override
    protected boolean shouldNotFilterAsyncDispatch() {
        return false;
    }
}
