package com.example.logbackmdclab.order;

public record OrderResponse(
        String orderId,
        String status
) {
}