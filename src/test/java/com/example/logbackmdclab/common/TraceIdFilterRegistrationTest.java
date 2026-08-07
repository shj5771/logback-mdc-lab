package com.example.logbackmdclab.common;

import jakarta.servlet.DispatcherType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.core.Ordered;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 필터의 <b>등록 조건</b>을 고정한다. 필터 본문이 아무리 정확해도
 * 체인 뒤쪽에 붙거나 REQUEST 디스패치에만 걸리면 그만큼의 로그가 traceId 없이 나간다.
 * 둘 다 조용한 실패라 눈으로는 알아채기 어렵다.
 */
@SpringBootTest
// Logback LoggerContext 는 JVM 전역이다. 컨텍스트를 열어둔 채 두면 뒤따르는 프로파일 테스트가
// logback-spring.xml 을 다시 읽지 못한다. 사유는 LogbackProfileConfigTest 참고.
@DirtiesContext
class TraceIdFilterRegistrationTest {

    @Autowired
    FilterRegistrationBean<TraceIdFilter> registration;

    @Test
    @DisplayName("체인 최선두에 등록된다 — 앞선 필터가 남기는 로그에는 traceId 가 없다")
    void registeredFirstInChain() {
        assertThat(registration.getOrder()).isEqualTo(Ordered.HIGHEST_PRECEDENCE);
    }

    @Test
    @DisplayName("ERROR 디스패치까지 커버한다 — 장애 순간의 로그가 NO_TRACE 로 빠지지 않도록")
    void coversErrorDispatch() {
        assertThat(registration.getFilter().shouldNotFilterErrorDispatch()).isFalse();
    }

    @Test
    @DisplayName("ASYNC 디스패치까지 커버한다")
    void coversAsyncDispatch() {
        assertThat(registration.getFilter().shouldNotFilterAsyncDispatch()).isFalse();
    }

    @Test
    @DisplayName("디스패치 타입이 명시 등록되어 있다 — OncePerRequestFilter 상속에 기대지 않는다")
    @SuppressWarnings("unchecked")
    void dispatcherTypesAreExplicit() {
        // Boot 는 OncePerRequestFilter 면 알아서 allOf 로 잡아준다. 그 암묵 규칙에 기대면
        // 나중에 상속을 바꾸는 순간 ERROR 커버리지가 조용히 사라진다. LoggingConfig 에서 직접 적어둔다.
        Set<DispatcherType> types = (Set<DispatcherType>) ReflectionTestUtils
                .getField(registration, "dispatcherTypes");

        assertThat(types).containsExactlyInAnyOrder(
                DispatcherType.REQUEST, DispatcherType.ASYNC, DispatcherType.ERROR);
    }
}
