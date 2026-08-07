package com.example.logbackmdclab.order;

/** 외부 PG 가 승인을 거절했다. 예상된 업무 실패이므로 스택트레이스를 남길 대상이 아니다. */
public class PaymentDeclinedException extends RuntimeException {

    private final String reason;

    public PaymentDeclinedException(String reason) {
        super("결제 거절: " + reason);
        this.reason = reason;
    }

    public String getReason() {
        return reason;
    }
}
