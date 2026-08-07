package com.example.logbackmdclab.order;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.concurrent.ThreadLocalRandom;

import static net.logstash.logback.argument.StructuredArguments.kv;

@Service
public class InventoryService {

    private static final Logger log = LoggerFactory.getLogger(InventoryService.class);

    public boolean hasEnoughStock(String productId, int quantity) {
        // 요청 줄은 DEBUG. 응답 줄만 있으면 흐름 복원에 지장이 없다.
        log.debug("재고 확인 요청 {} {}", kv("productId", productId), kv("quantity", quantity));

        try {
            // 외부 재고 시스템 호출 지연을 흉내 낸다
            Thread.sleep(ThreadLocalRandom.current().nextInt(30, 100));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("재고 확인 중단 {} {}", kv("productId", productId), kv("reason", "INTERRUPTED"));
            return false;
        }

        boolean enough = Math.abs(productId.hashCode()) % 4 != 0;

        // 응답 줄은 INFO. 이 줄이 분기를 결정하므로 운영에서 빠지면 흐름이 끊긴다.
        // "모든 걸 INFO 로 올리지 않고 판단 근거가 되는 줄만 올린다" 가 이 프로젝트의 레벨 규칙이다.
        log.info("재고 확인 응답 {} {}", kv("productId", productId), kv("enough", enough));
        return enough;
    }
}
