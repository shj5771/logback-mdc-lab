package com.example.logbackmdclab.order;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import java.util.UUID;

@RestController
public class OrderController {

    @PostMapping("/orders")
    public OrderResponse createOrder(@RequestBody
                                     OrderRequest request) {
        String orderId = "ORD-" +
                UUID.randomUUID().toString().substring(0, 8);

        System.out.println("주문 접수 시작 orderId=" +
                orderId
                + " userId=" + request.userId()
                + " productId=" + request.productId()
                + " quantity=" + request.quantity());

        System.out.println("주문 접수 완료 orderId=" +
                orderId);

        return new OrderResponse(orderId, "RECEIVED");
    }
}