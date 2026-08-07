package com.example.logbackmdclab.order;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class OrderController {

    private static final Logger log = LoggerFactory.getLogger(OrderController.class);

    private final OrderService orderService;

    public OrderController(OrderService orderService) {
        this.orderService = orderService;
    }

    @PostMapping("/orders")
    public OrderResponse createOrder(@RequestBody OrderRequest request) {
        // request 를 통째로 찍는다. 카드번호는 OrderRequest.toString() 이 가린다 — "찍는 쪽" 방어.
        log.info("주문 요청 수신 {}", request);
        return orderService.place(request);
    }
}
