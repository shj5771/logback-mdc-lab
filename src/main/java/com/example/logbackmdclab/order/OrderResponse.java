package com.example.logbackmdclab.order;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * orderId 는 업무 식별자다. 로그 식별자(traceId)와 달리 절단하지 않는다 —
 * 고객이 CS 에 건네는 값이고, 이 값 하나로 로그 전체를 되짚을 수 있어야 한다.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record OrderResponse(
        String orderId,
        String status,
        String reason
) {
}
