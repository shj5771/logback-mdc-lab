package com.example.logbackmdclab.support;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.function.Predicate;

/**
 * 테스트 중 실제로 찍힌 로그 이벤트를 잡아둔다.
 *
 * <p>로그가 산출물인 프로젝트에서는 "로그가 이렇게 찍혔다" 를 단언할 수단이 있어야 한다.
 * {@link ListAppender} 는 Logback 이 기본 제공하는 메모리 appender 다.
 */
public final class LogCapture implements AutoCloseable {

    private final Logger logger;
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();

    private LogCapture(String loggerName) {
        LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
        this.logger = context.getLogger(loggerName);
        appender.setContext(context);
        appender.start();
        logger.addAppender(appender);
    }

    public static LogCapture attachTo(String loggerName) {
        return new LogCapture(loggerName);
    }

    public List<ILoggingEvent> events() {
        synchronized (appender) {
            return List.copyOf(appender.list);
        }
    }

    /** 비동기 로그를 기다린다. Awaitility 를 쓰지 않는 이유는 의존성을 늘리지 않기 위해서다. */
    public ILoggingEvent awaitEvent(Predicate<ILoggingEvent> matcher, Duration timeout) {
        Instant deadline = Instant.now().plus(timeout);
        while (Instant.now().isBefore(deadline)) {
            for (ILoggingEvent event : events()) {
                if (matcher.test(event)) {
                    return event;
                }
            }
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("로그 대기 중 인터럽트", e);
            }
        }
        throw new AssertionError("제한 시간 안에 조건을 만족하는 로그가 나오지 않았다. 수집된 이벤트: " + events());
    }

    @Override
    public void close() {
        logger.detachAppender(appender);
        appender.stop();
    }
}
