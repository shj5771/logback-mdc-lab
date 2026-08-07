package com.example.logbackmdclab.common;

import org.slf4j.MDC;

/**
 * 실패 응답에 traceId 를 실어 보낸다.
 *
 * <p>이 필드 하나가 "문의 접수 → 로그 검색 → 흐름 복원" 서사의 첫 단추다.
 * 성공한 요청은 orderId 로 찾을 수 있지만, 컨트롤러에 도달하기 전에 깨진 요청(잘못된 JSON 등)은
 * 업무 식별자 자체가 만들어지지 않는다. 그때 고객이 CS 에 건넬 수 있는 값은 이것뿐이다.
 */
public record ErrorResponse(String traceId, String code, String message) {

    public static ErrorResponse of(String code, String message) {
        return new ErrorResponse(MDC.get(TraceIdFilter.TRACE_ID), code, message);
    }
}
