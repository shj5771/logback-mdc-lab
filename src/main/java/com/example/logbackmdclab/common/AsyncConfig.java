package com.example.logbackmdclab.common;

import org.slf4j.MDC;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskDecorator;

import java.util.Map;

@Configuration
public class AsyncConfig {

    /**
     * @Async 작업을 다른 스레드로 넘길 때 호출한 쪽의 MDC를 함께 복사한다.
     *
     * decorate()는 작업을 "넘기는 쪽"(톰캣 스레드)에서 실행되고,
     * 반환한 Runnable의 run()은 "받는 쪽"(task 스레드)에서 실행된다.
     */
    @Bean
    public TaskDecorator mdcTaskDecorator() {
        return runnable -> {
            Map<String, String> callerContext = MDC.getCopyOfContextMap();

            return () -> {
                if (callerContext != null) {
                    MDC.setContextMap(callerContext);
                } else {
                    // 호출한 쪽에 MDC가 없으면(예: 배치) 이 스레드에 남은 값을 걷어낸다
                    MDC.clear();
                }

                try {
                    runnable.run();
                } finally {
                    MDC.clear();
                }
            };
        };
    }
}
