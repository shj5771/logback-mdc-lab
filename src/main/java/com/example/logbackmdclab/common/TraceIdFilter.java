package com.example.logbackmdclab.common;

import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.UUID;

@Component
public class TraceIdFilter implements Filter {

    @Override
    public void doFilter(ServletRequest request, ServletResponse response,FilterChain chain)
        throws IOException, ServletException {

        String traceId = UUID.randomUUID().toString().substring(0, 8);
        MDC.put("traceId", traceId);

        try {
            chain.doFilter(request, response);
        } finally {
            // 스레드는 풀로 돌아가 재사용된다. 지우지 않으면 다음 작업이 이 값을 본다.
            MDC.clear();
        }
    }
}