package com.example.logbackmdclab.order;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import java.util.UUID;

@RestController
public class OrderController {

    private static final Logger log = LoggerFactory.getLogger(OrderController.class);

    private final InventoryService inventoryService;
    private final NotificationService notificationService;

    public OrderController(InventoryService inventoryService,
                           NotificationService notificationService) {
        this.inventoryService = inventoryService;
        this.notificationService = notificationService;
    }

    @PostMapping("/orders")
    public OrderResponse createOrder(@RequestBody OrderRequest request) {
        String orderId = "ORD-" + UUID.randomUUID().toString().substring(0, 8);

        log.info("주문 접수 시작 orderId={} userId={} productId={} quantity={}",
                orderId, request.userId(), request.productId(), request.quantity());

        boolean enoughStock = inventoryService.hasEnoughStock(request.productId(),
                request.quantity());
        if (!enoughStock) {
            log.warn("주문 거절 orderId={} reason=OUT_OF_STOCK", orderId);
            return new OrderResponse(orderId, "REJECTED");
        }

        notificationService.sendOrderConfirmation(orderId);
        log.info("주문 접수 완료 orderId={}", orderId);
        return new OrderResponse(orderId, "RECEIVED");
    }
}