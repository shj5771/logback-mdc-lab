package com.example.logbackmdclab.order;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 마스킹의 불변식은 "형식이 이렇게 나온다" 가 아니라 <b>"원문이 절대 나오지 않는다"</b> 이다.
 * 뒷 4자리를 남기는 규칙은 그 불변식을 지키는 선에서만 유효하다.
 */
class OrderRequestMaskingTest {

    @ParameterizedTest(name = "cardNumber={0}")
    @NullSource
    @ValueSource(strings = {"", "1", "1234", "1234567", "1234567890123456", "1234-5678-9012-3456"})
    @DisplayName("toString 에는 어떤 경우에도 카드번호 원문이 통째로 실리지 않는다")
    void toStringNeverContainsRawCardNumber(String rawCardNumber) {
        OrderRequest request = new OrderRequest("u1", "TV-1", 1, rawCardNumber);

        String rendered = request.toString();

        if (rawCardNumber != null && !rawCardNumber.isEmpty()) {
            // 원문이 짧으면("1") 다른 필드 값과 우연히 겹친다. userId=u1 의 "1" 을 유출로 셀 수는 없으므로
            // 불변식은 cardNumber 구간에 한정해 확인한다.
            assertThat(cardNumberSegment(rendered)).doesNotContain(rawCardNumber);
        }
        assertThat(rendered).contains("****");
    }

    /** {@code cardNumber=} 이후 구간. 마스킹 불변식이 실제로 적용되는 범위다. */
    private static String cardNumberSegment(String rendered) {
        return rendered.substring(rendered.indexOf("cardNumber="));
    }

    @Test
    @DisplayName("8자 미만은 뒷 4자리도 남기지 않는다 — 그 경우 뒷 4자리가 값의 절반을 넘는다")
    void tooShortToPartiallyMask() {
        assertThat(new OrderRequest("u1", "TV-1", 1, "1234").toString())
                .contains("cardNumber=****")
                .doesNotContain("1234,")   // userId/productId 쪽 값과 섞이지 않게 구분자까지 확인
                .doesNotContain("****-1234");
    }

    @Test
    @DisplayName("정상 길이는 뒷 4자리만 남긴다 — CS 문의 때 어느 카드인지 짚을 수 있어야 한다")
    void keepsLastFourDigits() {
        String rendered = new OrderRequest("u1", "TV-1", 1, "1234-5678-9012-3456").toString();

        assertThat(rendered).contains("cardNumber=****-3456");
    }

    @Test
    @DisplayName("나머지 필드는 그대로 보인다 — 마스킹이 로그의 쓸모를 없애면 안 된다")
    void keepsNonSensitiveFields() {
        String rendered = new OrderRequest("u42", "TV-7", 3, "1234-5678-9012-3456").toString();

        assertThat(rendered)
                .contains("userId=u42")
                .contains("productId=TV-7")
                .contains("quantity=3");
    }
}
