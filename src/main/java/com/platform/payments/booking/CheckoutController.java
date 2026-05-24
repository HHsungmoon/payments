package com.platform.payments.booking;

import com.platform.payments.point.CustomerPoint;
import com.platform.payments.point.CustomerPointRepository;
import com.platform.payments.product.Product;
import com.platform.payments.product.ProductRepository;
import com.platform.payments.stock.StockService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class CheckoutController {

    private final ProductRepository productRepo;
    private final CustomerPointRepository customerPointRepo;
    private final StockService stockService;

    @GetMapping("/checkout")
    public CheckoutResponse checkout(
            @RequestParam Long productId,
            @RequestParam Long customerId) {
        Product product = productRepo.findById(productId)
                .orElseThrow(() -> new IllegalArgumentException("product not found: " + productId));
        long balance = customerPointRepo.findById(customerId)
                .map(CustomerPoint::getBalance).orElse(0L);
        Integer remainingStock = stockService.getStock(productId);

        return new CheckoutResponse(
                product.getId(),
                product.getName(),
                product.getPrice(),
                product.getCheckInAt(),
                product.getCheckOutAt(),
                product.getStockTotal(),
                remainingStock,
                customerId,
                balance
        );
    }
}
