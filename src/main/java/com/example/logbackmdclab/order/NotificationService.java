package com.example.logbackmdclab.order;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

@Service
public class NotificationService {

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);

    /**
     * 인자가 없다는 것이 이 프로젝트의 결론이다.
     *
     * <p>이전에는 로그에 찍을 목적만으로 {@code orderId} 를 받았다. 그건 진단 문서가 "문제"라고 규정한
     * 바로 그 방식이다 — 식별자를 계층마다 손으로 전달하는 것. 지금은 orderId 도 traceId 도
     * {@code TaskDecorator} 가 옮긴 MDC 에서 나온다. 스레드가 바뀌어도 로그는 끊기지 않는다.
     *
     * <p>실제 알림 전송에 업무상 필요한 값이 생기면 그때 파라미터로 받되,
     * <b>로그용 식별자는 MDC 에 맡긴다</b>는 구분은 유지한다.
     */
    @Async
    public void sendOrderConfirmation() {
        log.info("주문 확인 알림 발송");
    }
}
