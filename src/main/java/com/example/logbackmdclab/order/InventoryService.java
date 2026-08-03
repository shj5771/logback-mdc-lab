package com.example.logbackmdclab.order;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.concurrent.ThreadLocalRandom;


@Service
public class InventoryService {
    private static final Logger log = LoggerFactory.getLogger(InventoryService.class);
    public boolean hasEnoughStock(String productId, int quantity) {
        log.debug("재고 확인 요청 productId={} quantity={}", productId, quantity);

        try {
            // 외부 재고 시스템 호출 지연을 흉내 낸다
            Thread.sleep(ThreadLocalRandom.current().nextInt(30, 100));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        boolean enough = Math.abs(productId.hashCode()) % 4 != 0;

        log.debug("재고 확인 응답 productId={} enough={}", productId, enough);
        return enough;
    }
}