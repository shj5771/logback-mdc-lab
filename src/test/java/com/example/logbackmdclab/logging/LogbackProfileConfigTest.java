package com.example.logbackmdclab.logging;

import ch.qos.logback.classic.AsyncAppender;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.core.rolling.RollingFileAppender;
import ch.qos.logback.core.rolling.SizeAndTimeBasedRollingPolicy;
import ch.qos.logback.core.util.FileSize;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * XML 은 컴파일러가 보지 않는다. 오타 하나로 {@code discardingThreshold} 가 기본값으로 돌아가면
 * 큐가 찰 때 INFO 로그가 조용히 사라지는데, 그 사실을 알려주는 신호가 어디에도 없다.
 * README 가 "동시 100건에서 50줄이 사라졌다" 로 논증한 그 설정값들을 여기서 고정한다.
 *
 * <p><b>왜 모든 {@code @SpringBootTest} 에 {@code @DirtiesContext} 가 붙어 있는가.</b>
 * Logback 의 {@code LoggerContext} 는 JVM 전역이고, {@code LogbackLoggingSystem.initialize()} 는
 * 그 컨텍스트가 이미 초기화돼 있으면 <b>아무것도 하지 않고 반환한다</b>. 스프링 테스트 컨텍스트는
 * 기본적으로 캐시되어 클래스가 끝나도 열려 있으므로, 앞선 테스트가 초기화해둔 설정이 남아 있으면
 * 프로파일이 다른 컨텍스트가 새로 떠도 {@code logback-spring.xml} 을 다시 읽지 않는다.
 * 그 상태에서는 레벨({@code logging.level.*})만 yml 에서 뒤늦게 적용돼, 레벨 검증은 통과하고
 * appender 검증만 실패한다 — 원인을 찾기 어려운 형태로 깨진다.
 *
 * <p>정리 시점은 <b>컨텍스트가 닫힐 때</b>다({@code LoggingApplicationListener} 의 종료 훅이
 * {@code LoggingSystem.cleanUp()} 을 호출한다). 그래서 이 스위트의 모든 스프링 테스트가
 * 자기 컨텍스트를 클래스 종료 시 닫는다. 하나라도 빠지면 그 뒤에 오는 프로파일 테스트가 깨진다.
 */
class LogbackProfileConfigTest {

    private static final String APP_LOGGER = "com.example.logbackmdclab";

    private static LoggerContext loggerContext() {
        return (LoggerContext) LoggerFactory.getILoggerFactory();
    }

    private static Logger rootLogger() {
        return loggerContext().getLogger(Logger.ROOT_LOGGER_NAME);
    }

    private static AsyncAppender asyncFileAppender() {
        return (AsyncAppender) rootLogger().getAppender("ASYNC_FILE");
    }

    /** dev 와 prod 가 공유하는 유실 방지 설정. 한쪽만 고치는 드리프트를 막는다. */
    private static void assertLossPreventionSettings() {
        AsyncAppender async = asyncFileAppender();

        assertThat(async).as("ASYNC_FILE appender 가 root 에 붙어 있어야 한다").isNotNull();
        assertThat(async.getQueueSize()).isEqualTo(2048);
        assertThat(async.getDiscardingThreshold())
                .as("기본값 queueSize/5 로 돌아가면 큐가 찰 때 INFO·DEBUG 가 말없이 버려진다")
                .isZero();
        assertThat(async.isNeverBlock())
                .as("유실보다 대기를 택한다")
                .isFalse();
        assertThat(async.getMaxFlushTime())
                .as("종료 시 큐 2048건을 비우기에 기본값 1초는 빠듯하다")
                .isEqualTo(5000);
    }

    /** maxFileSize/totalSizeCap 은 getter 가 없다. 값을 확인하려면 필드를 직접 본다. */
    private static FileSize fileSizeField(String name) {
        return (FileSize) ReflectionTestUtils.getField(rollingPolicy(), name);
    }

    private static SizeAndTimeBasedRollingPolicy<?> rollingPolicy() {
        AsyncAppender async = asyncFileAppender();
        RollingFileAppender<?> file = (RollingFileAppender<?>) async.getAppender("FILE");
        return (SizeAndTimeBasedRollingPolicy<?>) file.getRollingPolicy();
    }

    @Nested
    @SpringBootTest
    @ActiveProfiles("local")
    @DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
    @DisplayName("local")
    class LocalProfile {

        @Test
        @DisplayName("파일 appender 를 붙이지 않는다 — 개발 PC 에 로그 파일을 쌓지 않는다")
        void hasNoFileAppender() {
            assertThat(asyncFileAppender()).isNull();
            assertThat(rootLogger().getAppender("CONSOLE")).isNotNull();
        }

        @Test
        @DisplayName("내부 흐름까지 콘솔로 본다")
        void applicationLoggerIsDebug() {
            assertThat(loggerContext().getLogger(APP_LOGGER).getLevel()).isEqualTo(Level.DEBUG);
        }
    }

    @Nested
    @SpringBootTest
    @ActiveProfiles("dev")
    @TestPropertySource(properties = "app.logging.file=build/test-logs/dev.log")
    @DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
    @DisplayName("dev")
    class DevProfile {

        @Test
        @DisplayName("유실 방지 설정 네 개가 명시되어 있다")
        void lossPreventionIsExplicit() {
            assertLossPreventionSettings();
        }

        @Test
        @DisplayName("콘솔과 파일 양쪽에 남긴다")
        void logsToBothConsoleAndFile() {
            assertThat(rootLogger().getAppender("CONSOLE")).isNotNull();
            assertThat(asyncFileAppender()).isNotNull();
        }

        @Test
        @DisplayName("보관은 3일, 총량 1GB")
        void retainsThreeDays() {
            assertThat(rollingPolicy().getMaxHistory()).isEqualTo(3);
            assertThat(fileSizeField("totalSizeCap")).isEqualTo(FileSize.valueOf("1GB"));
        }

        @Test
        @DisplayName("파일 하나의 크기 상한이 있다 — totalSizeCap 은 아카이브만 보므로 활성 파일을 막지 못한다")
        void capsSingleFileSize() {
            assertThat(fileSizeField("maxFileSize")).isEqualTo(FileSize.valueOf("100MB"));
        }

        @Test
        @DisplayName("내부 흐름까지 파일에 남긴다")
        void applicationLoggerIsDebug() {
            assertThat(loggerContext().getLogger(APP_LOGGER).getLevel()).isEqualTo(Level.DEBUG);
        }
    }

    @Nested
    @SpringBootTest
    @ActiveProfiles("prod")
    @TestPropertySource(properties = "app.logging.file=build/test-logs/prod.log")
    @DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
    @DisplayName("prod")
    class ProdProfile {

        @Test
        @DisplayName("유실 방지 설정은 dev 와 같다 — 운영에서 유실이 더 치명적이다")
        void lossPreventionIsExplicit() {
            assertLossPreventionSettings();
        }

        @Test
        @DisplayName("우리 코드는 INFO, 프레임워크 소음은 WARN 이상만")
        void applicationInfoFrameworkWarn() {
            assertThat(loggerContext().getLogger(APP_LOGGER).getLevel()).isEqualTo(Level.INFO);
            assertThat(rootLogger().getLevel()).isEqualTo(Level.WARN);
        }

        @Test
        @DisplayName("보관은 30일, 총량 3GB, 파일 하나는 100MB")
        void retainsThirtyDaysWithCaps() {
            assertThat(rollingPolicy().getMaxHistory()).isEqualTo(30);
            assertThat(fileSizeField("totalSizeCap")).isEqualTo(FileSize.valueOf("3GB"));
            assertThat(fileSizeField("maxFileSize")).isEqualTo(FileSize.valueOf("100MB"));
        }

        @Test
        @DisplayName("운영에서도 재고 확인 응답은 남는다 — 분기를 결정하는 줄은 INFO 로 올렸다")
        void inventoryResponseSurvivesProdLevel() {
            assertThat(loggerContext().getLogger(APP_LOGGER).isInfoEnabled()).isTrue();
        }
    }
}
