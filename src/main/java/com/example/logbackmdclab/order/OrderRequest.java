package com.example.logbackmdclab.order;

public record OrderRequest(
        String userId,
        String productId,
        int quantity,
        String cardNumber
) {
    /**
     * 뒷 4자리를 남기는 것이 의미가 있으려면 그 앞에 가릴 것이 남아야 한다.
     * 8자 미만이면 뒷 4자리가 값의 절반 이상을 차지하므로 전부 가린다 —
     * {@code "1234"} 를 {@code "****-1234"} 로 바꾸는 건 마스킹이 아니라 원문 출력이다.
     */
    private static final int MIN_MASKABLE_LENGTH = 8;

    /**
     * record 가 만들어주는 기본 toString 은 모든 필드를 그대로 뱉는다.
     * 이 객체는 로그에 통째로 실릴 일이 많으므로 여기서 막는다.
     * 뒷 4자리는 남긴다 — CS 문의 때 어느 카드인지는 짚을 수 있어야 한다.
     *
     * <p>이건 "찍는 쪽" 한 겹일 뿐이다. 규약을 어기는 코드는 언제든 생기므로
     * 나가는 쪽(콘솔 {@code %replace}, 파일 {@code MaskingJsonGeneratorDecorator})에도 걸어둔다.
     */
    @Override
    public String toString() {
        return "OrderRequest[userId=%s, productId=%s, quantity=%d, cardNumber=%s]"
                .formatted(userId, productId, quantity, maskedCardNumber());
    }

    private String maskedCardNumber() {
        if (cardNumber == null || cardNumber.length() < MIN_MASKABLE_LENGTH) {
            return "****";
        }
        return "****-" + cardNumber.substring(cardNumber.length() - 4);
    }
}
