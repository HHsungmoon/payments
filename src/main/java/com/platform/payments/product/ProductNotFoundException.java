package com.platform.payments.product;

// 존재하지 않는 productId 조회 → 404 NOT_FOUND
public class ProductNotFoundException extends RuntimeException {

    public ProductNotFoundException(Long productId) {
        super("product not found: " + productId);
    }
}
