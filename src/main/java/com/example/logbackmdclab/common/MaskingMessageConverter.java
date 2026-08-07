package com.example.logbackmdclab.common;

import ch.qos.logback.classic.pattern.ThrowableHandlingConverter;
import ch.qos.logback.classic.pattern.ThrowableProxyConverter;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.CoreConstants;

import java.util.List;
import java.util.regex.Pattern;

/**
 * 콘솔 출력의 메시지와 스택트레이스를 함께 마스킹한다.
 *
 * <p>{@code %replace(%msg){...}} 로는 부족하다. 예외는 {@code %msg} 밖이고,
 * 패턴 체인에 예외 컨버터가 없으면 Logback 이 끝에 {@code ExtendedThrowableProxyConverter} 를
 * 자동으로 붙인다({@code EnsureExceptionHandling}). 그 자동 추가분은 어떤 치환도 거치지 않는다.
 * {@code %replace(%msg%n%ex){...}} 로 감싸도 자동 추가 여부를 판정하는
 * {@code chainHandlesThrowable()} 은 최상위 체인만 훑기 때문에 스택트레이스가 두 번 —
 * 한 번은 가려진 채로, 한 번은 원문 그대로 — 찍힌다.
 *
 * <p>그래서 {@link ThrowableHandlingConverter} 를 직접 상속한다. 이 타입이 체인에 있으면
 * Logback 은 예외 처리가 끝났다고 보고 자동 추가를 하지 않는다.
 *
 * <p>정규식과 마스크 문자열은 패턴에 직접 쓰지 않고 <b>LoggerContext 프로퍼티 이름</b>으로 받는다.
 * 정규식에는 {@code {15,16}} 처럼 중괄호와 쉼표가 들어가는데, 그건 패턴 옵션 구분자와 같은 문자다.
 * 이름으로 넘기면 그 충돌 자체가 생기지 않고, 정규식은 XML 에 한 번만 적힌다.
 *
 * <pre>{@code %maskedMsg{CARD_REGEX_BROAD,CARD_MASK}}</pre>
 */
public class MaskingMessageConverter extends ThrowableHandlingConverter {

    private static final String DEFAULT_MASK = "****";

    private final ThrowableProxyConverter throwableConverter = new ThrowableProxyConverter();

    private Pattern pattern;
    private String mask = DEFAULT_MASK;

    @Override
    public void start() {
        List<String> options = getOptionList();

        if (options != null && !options.isEmpty()) {
            String regex = resolve(options.get(0));
            if (regex != null && !regex.isBlank()) {
                pattern = Pattern.compile(regex);
            }
        }
        if (options != null && options.size() > 1) {
            String configured = resolve(options.get(1));
            if (configured != null && !configured.isBlank()) {
                mask = configured;
            }
        }
        if (pattern == null) {
            addWarn("마스킹 정규식을 찾지 못했다. 콘솔 출력은 마스킹 없이 나간다.");
        }

        throwableConverter.setContext(getContext());
        throwableConverter.start();
        super.start();
    }

    @Override
    public void stop() {
        throwableConverter.stop();
        super.stop();
    }

    @Override
    public String convert(ILoggingEvent event) {
        String rendered = event.getFormattedMessage();

        String throwable = throwableConverter.convert(event);
        if (!throwable.isEmpty()) {
            rendered = rendered + CoreConstants.LINE_SEPARATOR + stripTrailingNewline(throwable);
        }

        return pattern == null ? rendered : pattern.matcher(rendered).replaceAll(mask);
    }

    /** 옵션은 LoggerContext 프로퍼티 이름이다. 그런 프로퍼티가 없으면 값 자체로 취급한다. */
    private String resolve(String nameOrLiteral) {
        if (nameOrLiteral == null) {
            return null;
        }
        String fromContext = getContext() == null ? null : getContext().getProperty(nameOrLiteral);
        return fromContext != null ? fromContext : nameOrLiteral;
    }

    private String stripTrailingNewline(String text) {
        return text.endsWith(CoreConstants.LINE_SEPARATOR)
                ? text.substring(0, text.length() - CoreConstants.LINE_SEPARATOR.length())
                : text;
    }
}
