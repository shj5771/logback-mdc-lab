package com.example.logbackmdclab.order;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.concurrent.ThreadLocalRandom;

import static net.logstash.logback.argument.StructuredArguments.kv;

/**
 * 외부 PG 호출을 흉내 낸다. 지연을 주고 확률적으로 거절한다.
 * "카드는 빠져나갔는데 주문 내역이 없어요" 라는 문의를 로컬에서 재현하기 위한 계층이다.
 */
@Service
public class PaymentService {

    private static final Logger log = LoggerFactory.getLogger(PaymentService.class);

    private static final int UNIT_PRICE = 10_000;

    /** 거절률은 설정으로 뺀다. 테스트에서 0 으로 두면 결제 성공 경로를 결정적으로 재현할 수 있다. */
    private final int declineRatePercent;

    public PaymentService(@Value("${app.payment.decline-rate-percent:10}") int declineRatePercent) {
        this.declineRatePercent = declineRatePercent;
    }

    public void approve(String cardNumber, int quantity) {
        int amount = UNIT_PRICE * quantity;
        log.info("결제 승인 요청 {}", kv("amount", amount));

        // [의도적 시연] 마스킹이 "찍는 쪽 규약 + 나가는 쪽 설정" 두 겹이라고 주장하려면,
        // 규약을 어긴 줄이 하나는 있어야 나가는 쪽이 실제로 막는지 증명할 수 있다.
        // 이 줄은 카드번호 원문을 그대로 넘긴다. 콘솔은 %replace, 파일은 MaskingJsonGeneratorDecorator 가 막는다.
        // prod 는 이 로거가 INFO 라 아예 찍히지 않는다.
        log.debug("PG 요청 페이로드 {}", kv("cardNumber", cardNumber));

        sleep(50, 200);

        if (ThreadLocalRandom.current().nextInt(100) < declineRatePercent) {
            log.warn("결제 승인 거절 {} {}", kv("paymentStatus", "DECLINED"), kv("reason", "PG_DECLINED"));
            throw new PaymentDeclinedException("PG_DECLINED");
        }

        log.info("결제 승인 완료 {} {}", kv("paymentStatus", "APPROVED"), kv("amount", amount));
    }

    private void sleep(int minMillis, int maxMillis) {
        try {
            Thread.sleep(ThreadLocalRandom.current().nextInt(minMillis, maxMillis));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            // 인터럽트를 삼키면 "왜 이 요청만 응답이 빨랐나" 를 나중에 설명할 수 없다.
            log.warn("결제 승인 대기 중단 {}", kv("reason", "INTERRUPTED"));
            throw new PaymentDeclinedException("INTERRUPTED");
        }
    }
}
