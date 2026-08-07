package com.example.logbackmdclab.common;

import org.slf4j.MDC;

import java.util.Map;

/**
 * MDC 의 생명주기를 try-with-resources 로 묶는다.
 *
 * <p>원칙은 "넣은 곳이 뺀다" 가 아니라 <b>"스코프를 연 곳이 닫는다"</b> 이다.
 * 요청 처리 도중 어느 계층이 키를 몇 개 더 얹든, 스코프를 연 지점에서 찍어둔 상태로 통째로 되돌린다.
 * 계층마다 자기 키를 remove 하게 만들면 예외로 빠져나가는 경로에서 순서가 어긋나고,
 * 무엇보다 실패 로그가 찍히기 <i>전에</i> 식별자가 지워진다.
 *
 * <p>스레드 풀 환경에서 이 클래스가 필요한 이유는 두 가지다.
 * <ul>
 *   <li>톰캣 스레드는 재사용된다. 되돌리지 않으면 다음 요청이 이전 요청의 값을 본다.</li>
 *   <li>{@code MDC.clear()} 로 지우면 이 스코프가 넣지 않은 값까지 걷어낸다.
 *       스냅샷 복원은 바깥 스코프가 심어둔 값을 보존한다.</li>
 * </ul>
 */
public final class MdcScope implements AutoCloseable {

    private final Map<String, String> snapshot;

    private MdcScope(Map<String, String> snapshot) {
        this.snapshot = snapshot;
    }

    /** 현재 MDC 상태를 찍어둔다. {@link #close()} 시점에 그대로 되돌린다. */
    public static MdcScope open() {
        return new MdcScope(MDC.getCopyOfContextMap());
    }

    /** 주어진 컨텍스트로 갈아끼운다. 비동기 경계에서 호출한 쪽의 MDC 를 이식할 때 쓴다. */
    public static MdcScope openWith(Map<String, String> context) {
        MdcScope scope = open();
        if (context == null) {
            MDC.clear();
        } else {
            MDC.setContextMap(context);
        }
        return scope;
    }

    @Override
    public void close() {
        if (snapshot == null) {
            MDC.clear();
        } else {
            MDC.setContextMap(snapshot);
        }
    }
}
