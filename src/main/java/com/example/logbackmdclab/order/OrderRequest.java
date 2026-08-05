package com.example.logbackmdclab.order;

public record OrderRequest(
        String userId,
        String productId,
        int quantity,
        String cardNumber
) {
    /**
     * record가 만들어주는 기본 toString은 모든 필드를 그대로 뱉는다.
     * 이 객체는 로그에 통째로 실릴 일이 많으므로 여기서 막는다.
     * 뒷 4자리는 남긴다 — CS 문의 때 어느 카드인지는 짚을 수 있어야 한다.
     */
    @Override
    public String toString() {
        return "OrderRequest[userId=%s, productId=%s, quantity=%d, cardNumber=%s]"
                .formatted(userId, productId, quantity, maskedCardNumber());
    }

    private String maskedCardNumber() {
        if (cardNumber == null || cardNumber.length() < 4) {
            return "****";
        }
        return "****-" + cardNumber.substring(cardNumber.length() - 4);
    }
}
