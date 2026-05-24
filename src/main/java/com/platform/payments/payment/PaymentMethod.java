package com.platform.payments.payment;

// 결제 수단 + 카테고리 (모델 D)
// MAIN (CARD/YPAY) ≤ 1 + SUPPLEMENT (POINT) ≤ 1
public enum PaymentMethod {

    CARD(Category.MAIN),
    YPAY(Category.MAIN),
    POINT(Category.SUPPLEMENT);

    public enum Category { MAIN, SUPPLEMENT }

    private final Category category;

    PaymentMethod(Category category) {
        this.category = category;
    }

    public Category category() {
        return category;
    }

    public boolean isMain() {
        return category == Category.MAIN;
    }

    public boolean isSupplement() {
        return category == Category.SUPPLEMENT;
    }
}
