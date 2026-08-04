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
        MDC.put("traceId",traceId);

        chain.doFilter(request,response);
    }
}