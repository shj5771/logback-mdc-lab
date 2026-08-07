package com.example.logbackmdclab.common;

import com.example.logbackmdclab.order.PaymentDeclinedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.lang.Nullable;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import static net.logstash.logback.argument.StructuredArguments.kv;

/**
 * 실패를 로그와 응답 양쪽에 남긴다.
 *
 * <p>{@link ResponseEntityExceptionHandler} 를 상속한 이유는 컨트롤러에 도달하기 전에 깨지는 요청 때문이다.
 * 잘못된 JSON({@code HttpMessageNotReadableException}) 같은 것은 Spring MVC 가 자체 처리해
 * 본문 없는 기본 에러 응답으로 나간다 — 고객이 CS 에 건넬 식별자가 하나도 없다.
 * 여기서 가로채 traceId 를 실어 보낸다.
 *
 * <p>이 핸들러는 {@link TraceIdFilter} 스코프 <b>안에서</b> 실행되므로 모든 로그에 traceId 가 붙는다.
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /**
     * 결제 거절은 예상된 업무 실패다. 스택트레이스 없이 WARN 으로 남긴다.
     * traceId·orderId 는 MDC 에 실려 있으므로 메시지에 다시 적지 않는다.
     */
    @ExceptionHandler(PaymentDeclinedException.class)
    public ResponseEntity<ErrorResponse> handlePaymentDeclined(PaymentDeclinedException e) {
        log.warn("주문 실패 {} {}", kv("status", "FAILED"), kv("reason", e.getReason()));
        return ResponseEntity.status(HttpStatus.PAYMENT_REQUIRED)
                .body(ErrorResponse.of("PAYMENT_DECLINED", "결제가 승인되지 않았다."));
    }

    /** 예상하지 못한 실패. 여기만 스택트레이스를 남긴다. */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpected(Exception e) {
        log.error("주문 처리 실패 {} {}",
                kv("status", "ERROR"), kv("exceptionType", e.getClass().getSimpleName()), e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ErrorResponse.of("INTERNAL_ERROR", "요청을 처리하지 못했다."));
    }

    /** Spring MVC 가 자체 처리하는 예외(잘못된 JSON, 미지원 메서드 등)의 응답 본문을 갈아끼운다. */
    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception ex,
                                                             @Nullable Object body,
                                                             HttpHeaders headers,
                                                             HttpStatusCode statusCode,
                                                             WebRequest request) {
        log.warn("요청 거부 {} {} {}",
                kv("status", "REJECTED"),
                kv("httpStatus", statusCode.value()),
                kv("exceptionType", ex.getClass().getSimpleName()));

        ErrorResponse payload = ErrorResponse.of("BAD_REQUEST", "요청을 해석하지 못했다.");
        return super.handleExceptionInternal(ex, payload, headers, statusCode, request);
    }
}
