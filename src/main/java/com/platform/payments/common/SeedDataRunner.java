package com.platform.payments.common;

import com.platform.payments.customer.Customer;
import com.platform.payments.customer.CustomerRepository;
import com.platform.payments.point.CustomerPoint;
import com.platform.payments.point.CustomerPointRepository;
import com.platform.payments.product.Product;
import com.platform.payments.product.ProductRepository;
import com.platform.payments.stock.StockService;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

// 평가 시나리오용 시드 — 멱등 (이미 있으면 skip)
//   Customer 100명 (id 1~100, 각 100,000 포인트)
//   Product 1개 (id 1, 재고 10, 가격 50,000)
//   Redis stock:product:1 = 10
@Component
@RequiredArgsConstructor
@Slf4j
public class SeedDataRunner implements CommandLineRunner {

    private static final int CUSTOMER_COUNT = 100;
    private static final long INITIAL_POINT_BALANCE = 100_000L;
    private static final long PRODUCT_PRICE = 50_000L;
    private static final int PRODUCT_STOCK = 10;

    private final CustomerRepository customerRepo;
    private final CustomerPointRepository customerPointRepo;
    private final ProductRepository productRepo;
    private final StockService stockService;

    @Override
    public void run(String... args) {
        seedCustomers();
        seedProduct();
        seedRedisStock();
    }

    @Transactional
    void seedCustomers() {
        if (customerRepo.count() > 0) {
            log.info("SEED_CUSTOMERS skipped (already exists, count={})", customerRepo.count());
            return;
        }
        for (int i = 1; i <= CUSTOMER_COUNT; i++) {
            Customer c = customerRepo.save(Customer.builder()
                    .name("customer-" + i)
                    .build());
            customerPointRepo.save(CustomerPoint.builder()
                    .customerId(c.getId())
                    .balance(INITIAL_POINT_BALANCE)
                    .version(0L)
                    .build());
        }
        log.info("SEED_CUSTOMERS created {} customers with point={}",
                CUSTOMER_COUNT, INITIAL_POINT_BALANCE);
    }

    @Transactional
    void seedProduct() {
        if (productRepo.count() > 0) {
            log.info("SEED_PRODUCT skipped (already exists, count={})", productRepo.count());
            return;
        }
        Product p = productRepo.save(Product.builder()
                .name("한정 특가 숙소")
                .price(PRODUCT_PRICE)
                .checkInAt(LocalDateTime.now().plusDays(1))
                .checkOutAt(LocalDateTime.now().plusDays(2))
                .stockTotal(PRODUCT_STOCK)
                .openAt(LocalDateTime.now())
                .build());
        log.info("SEED_PRODUCT created id={} stockTotal={}", p.getId(), p.getStockTotal());
    }

    void seedRedisStock() {
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
