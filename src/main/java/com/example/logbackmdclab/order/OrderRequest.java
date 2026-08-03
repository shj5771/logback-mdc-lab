package com.example.logbackmdclab.order;

public record OrderRequest(
        String userId,
        String productId,
        int quantity,
        String cardNumber
) {
}