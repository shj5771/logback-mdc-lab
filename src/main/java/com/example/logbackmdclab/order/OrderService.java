package com.example.logbackmdclab.order;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;

import java.util.UUID;

import static net.logstash.logback.argument.StructuredArguments.kv;

/**
 * 주문 접수의 오케스트레이션. 컨트롤러는 HTTP 만, 여기는 흐름만 담당한다.
 *
 * <p>MDC 규칙: <b>스코프를 여는 곳은 요청 경계 하나뿐이다.</b>
 * 각 계층은 그 스코프 안에 자기 식별자를 얹기만 하고 지우지 않는다.
 * 계층마다 remove 하게 만들면 예외로 빠져나가는 경로에서 실패 로그가 찍히기 <i>전에</i>
 * orderId 가 사라진다 — 정작 필요한 순간에 식별자가 없는 셈이다.
 * 웹 요청의 스코프는 {@code TraceIdFilter} 가 연다. 배치처럼 다른 진입점을 만들 때는
 * 그 진입점이 {@code MdcScope.open()} 으로 자기 경계를 그어야 한다.
 */
@Service
public class OrderService {

    private static final Logger log = LoggerFactory.getLogger(OrderService.class);

    private final InventoryService inventoryService;
    private final PaymentService paymentService;
    private final NotificationService notificationService;

    public OrderService(InventoryService inventoryService,
                        PaymentService paymentService,
                        NotificationService notificationService) {
        this.inventoryService = inventoryService;
        this.paymentService = paymentService;
        this.notificationService = notificationService;
    }

    public OrderResponse place(OrderRequest request) {
        String orderId = "ORD-" + UUID.randomUUID();
        MDC.put("orderId", orderId);

        // orderId 는 MDC 에 실려 있으므로 메시지에 다시 적지 않는다.
        log.info("주문 접수 시작 {} {} {}",
                kv("userId", request.userId()),
                kv("productId", request.productId()),
                kv("quantity", request.quantity()));

        if (!inventoryService.hasEnoughStock(request.productId(), request.quantity())) {
            log.warn("주문 거절 {} {}", kv("status", "REJECTED"), kv("reason", "OUT_OF_STOCK"));
            return new OrderResponse(orderId, "REJECTED", "OUT_OF_STOCK");
        }

        // 거절되면 PaymentDeclinedException 이 여기를 그대로 통과해 GlobalExceptionHandler 로 간다.
        // MDC 스코프는 요청 경계까지 살아 있으므로 그 실패 로그에도 orderId 가 붙는다.
        paymentService.approve(request.cardNumber(), request.quantity());

        notificationService.sendOrderConfirmation();

        log.info("주문 접수 완료 {}", kv("status", "RECEIVED"));
        return new OrderResponse(orderId, "RECEIVED", null);
    }
}
