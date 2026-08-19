package com.example.logbackmdclab.common;

import jakarta.annotation.PostConstruct;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;
import java.util.Set;

/**
 * 활성 프로파일이 아는 이름인지 기동 시 검사하고, 아니면 기동을 실패시킨다.
 *
 * <p>{@code spring.profiles.default} 는 프로파일을 <b>하나도</b> 주지 않은 경우만 막는다.
 * {@code prodd} 처럼 <b>틀리게</b> 준 경우는 경고도 예외도 없이 정상 기동한다.
 * 실측(2026-08-20): {@code SPRING_PROFILES_ACTIVE=prodd} 로 띄우면
 * 주문 API 는 200 을 반환하는데 {@code <springProfile name="dev | prod">} 에 걸리지 않아
 * 파일 로깅이 통째로 빠지고, DEBUG 줄도 사라진다(application-local.yml 이 안 읽히므로).
 * 즉 local 도 dev 도 prod 도 아닌 네 번째 상태로 돈다.
 */
@Component
public class ProfileValidator {

    private static final Set<String> ALLOWED = Set.of("local", "dev", "prod");

    private final Environment environment;

    public ProfileValidator(Environment environment) {
        this.environment = environment;
    }

    /**
     * 프로파일을 하나도 주지 않은 실행은 통과시킨다.
     * 그 경우 활성 프로파일은 빈 배열이고, application.yml 의 {@code spring.profiles.default} 가 맡는다.
     * 여기서 막으면 인자 없는 {@code ./gradlew bootRun} 이 기동에 실패한다.
     */
    @PostConstruct
    void validate() {
        String[] active = environment.getActiveProfiles();
        if (active.length == 0) {
            return;
        }

        List<String> unknown = Arrays.stream(active)
                .filter(profile -> !ALLOWED.contains(profile))
                .toList();

        if (!unknown.isEmpty()) {
            throw new IllegalStateException(
                    "알 수 없는 프로파일: " + unknown + ". 허용: " + ALLOWED
                            + ". 오타로 뜨면 파일 로깅이 조용히 빠진다.");
        }
    }
}
