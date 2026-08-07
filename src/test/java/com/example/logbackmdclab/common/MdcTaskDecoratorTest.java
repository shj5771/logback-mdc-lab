package com.example.logbackmdclab.common;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.core.task.TaskDecorator;

import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * TaskDecorator 단독 검증. Spring 컨텍스트가 필요 없어 1초 안에 끝난다.
 * 이 프로젝트에서 코드량 대비 가치가 가장 높은 테스트다 —
 * README 의 헤드라인 수치("비동기 구간 추적 성공률 100%")가 곧 이 클래스의 동작이다.
 */
class MdcTaskDecoratorTest {

    private final TaskDecorator decorator = new AsyncConfig().mdcTaskDecorator();

    @AfterEach
    void clearCallerMdc() {
        MDC.clear();
    }

    @Test
    @DisplayName("호출한 쪽의 MDC 가 작업 스레드로 옮겨진다")
    void copiesCallerContextToWorkerThread() throws Exception {
        MDC.put("traceId", "abc123");
        MDC.put("orderId", "ORD-1");

        AtomicReference<Map<String, String>> seenByWorker = new AtomicReference<>();
        Runnable decorated = decorator.decorate(() -> seenByWorker.set(MDC.getCopyOfContextMap()));

        runOnOtherThread(decorated);

        assertThat(seenByWorker.get())
                .containsEntry("traceId", "abc123")
                .containsEntry("orderId", "ORD-1");
    }

    @Test
    @DisplayName("작업이 끝나면 작업 스레드의 MDC 는 실행 전 상태로 되돌아간다")
    void restoresWorkerThreadAfterRun() throws Exception {
        MDC.put("traceId", "abc123");
        Runnable decorated = decorator.decorate(() -> { });

        AtomicReference<Map<String, String>> leftBehind = new AtomicReference<>();
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            // 같은 스레드를 두 번 쓴다. 두 번째 작업이 첫 번째의 잔재를 보면 안 된다.
            pool.submit(decorated).get();
            pool.submit(() -> leftBehind.set(MDC.getCopyOfContextMap())).get();
        } finally {
            pool.shutdownNow();
        }

        assertThat(leftBehind.get()).isNullOrEmpty();
    }

    @Test
    @DisplayName("호출한 쪽에 MDC 가 없으면 작업 스레드에 남아 있던 값을 걷어낸다")
    void clearsStaleContextWhenCallerHasNone() throws Exception {
        // 호출 시점에 MDC 없음 (@AfterEach 로 비어 있는 상태)
        Runnable decorated = decorator.decorate(() -> { });

        AtomicReference<Map<String, String>> seenByWorker = new AtomicReference<>();
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            // 작업 스레드에 이전 요청의 잔재를 미리 심어둔다
            pool.submit(() -> MDC.put("traceId", "STALE")).get();
            pool.submit(decorator.decorate(() -> seenByWorker.set(MDC.getCopyOfContextMap()))).get();
            pool.submit(decorated).get();
        } finally {
            pool.shutdownNow();
        }

        assertThat(seenByWorker.get()).isNullOrEmpty();
    }

    @Test
    @DisplayName("작업이 예외로 끝나도 MDC 는 복구된다")
    void restoresContextEvenWhenTaskThrows() {
        MDC.put("traceId", "abc123");
        Runnable decorated = decorator.decorate(() -> {
            throw new IllegalStateException("boom");
        });

        AtomicReference<Map<String, String>> leftBehind = new AtomicReference<>();
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            assertThatThrownBy(() -> pool.submit(decorated).get())
                    .hasRootCauseInstanceOf(IllegalStateException.class);
            pool.submit(() -> leftBehind.set(MDC.getCopyOfContextMap())).get();
        } catch (Exception e) {
            throw new AssertionError(e);
        } finally {
            pool.shutdownNow();
        }

        assertThat(leftBehind.get()).isNullOrEmpty();
    }

    private void runOnOtherThread(Runnable runnable) throws Exception {
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            pool.submit(runnable).get();
        } finally {
            pool.shutdownNow();
        }
    }
}
