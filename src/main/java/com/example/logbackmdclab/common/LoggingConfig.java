package com.example.logbackmdclab.common;

import jakarta.servlet.DispatcherType;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

/**
 * 로깅 횡단 관심사의 배선을 한곳에 모은다.
 *
 * <p>{@link TraceIdFilter} 를 {@code @Component} 로 두지 않고 여기서 명시 등록하는 이유:
 * <ul>
 *   <li><b>순서</b> — {@code @Component} 필터는 순서를 지정하지 않으면 체인 최후미에 붙는다.
 *       앞선 필터가 남기는 로그에는 traceId 가 없다.</li>
 *   <li><b>디스패치 타입</b> — Boot 는 {@code OncePerRequestFilter} 면 알아서
 *       {@code EnumSet.allOf(DispatcherType.class)} 로 잡아준다. 동작은 하지만
 *       "왜 ERROR 까지 타는가" 가 코드 어디에도 없다. 여기 적어두면 상속 관계를 바꿔도 깨지지 않는다.</li>
 * </ul>
 */
@Configuration
public class LoggingConfig {

    @Bean
    public FilterRegistrationBean<TraceIdFilter> traceIdFilterRegistration() {
        FilterRegistrationBean<TraceIdFilter> registration =
                new FilterRegistrationBean<>(new TraceIdFilter());
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        registration.setDispatcherTypes(
                DispatcherType.REQUEST, DispatcherType.ASYNC, DispatcherType.ERROR);
        registration.addUrlPatterns("/*");
        return registration;
    }
}
