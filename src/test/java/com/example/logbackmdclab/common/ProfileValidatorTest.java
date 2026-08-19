package com.example.logbackmdclab.common;

import jakarta.annotation.PostConstruct;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 프로파일 이름은 컴파일러도 스프링도 검증하지 않는다.
 * {@code SPRING_PROFILES_ACTIVE=prodd} 는 경고도 예외도 없이 정상 기동하고,
 * {@code <springProfile name="dev | prod">} 에 걸리지 않아 파일 로깅이 통째로 빠진다.
 * 실측(2026-08-20, 이 클래스를 넣기 전): 주문 API 는 200 을 반환했고 logs/ 아래 파일은 생기지 않았다.
 *
 * <p>여기서 검증하는 것은 "예외를 던지는가" 가 아니라 <b>기동이 실패하는가</b> 다.
 * 그래서 {@code validate()} 를 직접 부르지 않고 컨텍스트를 실제로 띄운다 —
 * {@code @Component} 나 {@code @PostConstruct} 가 빠지면 검사 자체가 실행되지 않는데,
 * 메서드를 직접 부르는 테스트는 그 사고를 통과시킨다.
 *
 * <p>{@code ApplicationContextRunner} 를 쓰는 이유는 웹 서버 없이 컨텍스트만 굴리기 위해서다.
 * {@code @SpringBootTest} 로 프로파일을 바꿔가며 띄우면 JVM 전역인 Logback {@code LoggerContext}
 * 캐시 문제에 걸린다({@link com.example.logbackmdclab.logging.LogbackProfileConfigTest} 참고).
 */
class ProfileValidatorTest {

    private final ApplicationContextRunner runner =
            new ApplicationContextRunner().withUserConfiguration(ProfileValidator.class);

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"local", "dev", "prod"})
    @DisplayName("아는 프로파일은 통과한다")
    void 아는_프로파일은_통과한다(String profile) {
        runner.withPropertyValues("spring.profiles.active=" + profile)
                .run(context -> assertThat(context).hasNotFailed());
    }

    /**
     * 회귀 방지의 핵심. 활성 프로파일이 없으면 {@code getActiveProfiles()} 는 빈 배열을 준다.
     * {@code spring.profiles.default: local} 이 걸려 있어도 마찬가지다 — default 는 active 가 아니다.
     * 이 경우를 걸러내지 않으면 인자 없는 {@code ./gradlew bootRun} 이 기동에 실패한다.
     */
    @Test
    @DisplayName("프로파일을 하나도 주지 않은 실행은 통과한다")
    void 프로파일을_주지_않으면_통과한다() {
        runner.run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    @DisplayName("오타는 조용히 넘어가지 않고 기동을 실패시킨다")
    void 오타는_기동을_실패시킨다() {
        runner.withPropertyValues("spring.profiles.active=prodd")
                .run(context -> assertThat(context)
                        .hasFailed()
                        .getFailure()
                        .rootCause()
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessageContaining("prodd"));
    }

    /**
     * 메시지에 허용 목록이 없으면 띄운 사람이 무엇으로 고쳐야 하는지 알 수 없다.
     * 조용한 실패를 시끄러운 실패로 바꾸는 것이 목적이므로, 메시지 내용도 계약이다.
     */
    @Test
    @DisplayName("실패 메시지가 허용 목록을 알려준다")
    void 실패_메시지가_허용_목록을_알려준다() {
        runner.withPropertyValues("spring.profiles.active=prodd")
                .run(context -> assertThat(context)
                        .getFailure()
                        .rootCause()
                        .hasMessageContainingAll("local", "dev", "prod"));
    }

    /**
     * 프로파일은 여러 개를 동시에 켤 수 있다. 하나만 틀려도 그 프로파일의 설정이 통째로 빠지므로
     * "하나라도 모르는 이름이면 실패" 여야 한다.
     */
    @Test
    @DisplayName("여러 프로파일 중 하나만 틀려도 실패한다")
    void 여러_프로파일_중_하나만_틀려도_실패한다() {
        runner.withPropertyValues("spring.profiles.active=dev,prodd")
                .run(context -> assertThat(context)
                        .hasFailed()
                        .getFailure()
                        .rootCause()
                        .hasMessageContaining("prodd"));
    }

    /**
     * 위 테스트들은 컨텍스트에 빈으로 등록해 굴리므로 {@code @Component} 가 없어도 통과한다
     * ({@code withUserConfiguration} 이 클래스를 직접 등록하기 때문이다).
     * 실제 앱에서 검사가 도는 것은 컴포넌트 스캔에 잡히기 때문이므로 그 조건을 따로 고정한다.
     */
    @Test
    @DisplayName("컴포넌트 스캔과 생명주기 훅에 걸리는 형태여야 한다")
    void 스캔과_생명주기_훅에_걸린다() throws NoSuchMethodException {
        assertThat(ProfileValidator.class.isAnnotationPresent(Component.class))
                .as("@Component 가 빠지면 스캔되지 않아 검사가 아예 실행되지 않는다")
                .isTrue();

        Method validate = ProfileValidator.class.getDeclaredMethod("validate");
        assertThat(validate.isAnnotationPresent(PostConstruct.class))
                .as("@PostConstruct 가 빠지면 아무도 validate() 를 부르지 않는다")
                .isTrue();
    }
}
