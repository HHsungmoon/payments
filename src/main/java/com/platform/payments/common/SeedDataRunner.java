package com.platform.payments.common;

import com.platform.payments.product.ProductRepository;
import com.platform.payments.stock.StockService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

// data.sql 이 customer/customer_point/product 시드 (멱등 INSERT IGNORE).
// 본 Runner는 Redis stock 초기화만 담당.
@Component
@RequiredArgsConstructor
@Slf4j
public class SeedDataRunner implements CommandLineRunner {

    private final ProductRepository productRepo;
    private final StockService stockService;

    @Override
    public void run(String... args) {
        productRepo.findAll().forEach(p -> {
            Integer current = stockService.getStock(p.getId());
            if (current == null) {
                stockService.setStock(p.getId(), p.getStockTotal());
                log.info("SEED_REDIS_STOCK productId={} stock={}", p.getId(), p.getStockTotal());
            } else {
                log.info("SEED_REDIS_STOCK skipped productId={} current={}", p.getId(), current);
            }
        });
    }
}
