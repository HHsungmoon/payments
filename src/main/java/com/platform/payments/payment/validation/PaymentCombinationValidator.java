package com.platform.payments.payment.validation;

import com.platform.payments.payment.PaymentRequest;
import java.util.List;
import org.springframework.stereotype.Component;

// 결제 수단 조합 규칙 검증
//   MAIN ≤ 1, SUPPLEMENT ≤ 1, 최소 1개, method 중복 X, Σ amount = total
@Component
public class PaymentCombinationValidator {

    public void validate(List<PaymentRequest> payments, long totalAmount) {
        if (payments == null || payments.isEmpty()) {
            throw new InvalidPaymentCombinationException("payments must not be empty");
        }

        // method 중복 X
        long uniqueMethods = payments.stream()
                .map(PaymentRequest::method)
                .distinct()
                .count();
        if (uniqueMethods != payments.size()) {
            throw new InvalidPaymentCombinationException("duplicate payment method");
        }

        // MAIN ≤ 1 (CARD + YPAY 자동 차단)
        long mainCount = payments.stream()
                .filter(p -> p.method().isMain())
                .count();
        if (mainCount > 1) {
            throw new InvalidPaymentCombinationException(
                    "MAIN methods are mutually exclusive (found " + mainCount + ")");
        }

        // SUPPLEMENT ≤ 1
        long supCount = payments.stream()
                .filter(p -> p.method().isSupplement())
                .count();
        if (supCount > 1) {
            throw new InvalidPaymentCombinationException(
                    "at most one SUPPLEMENT method allowed");
        }

        // amount > 0
        boolean nonPositive = payments.stream().anyMatch(p -> p.amount() <= 0);
        if (nonPositive) {
            throw new InvalidPaymentCombinationException("amount must be positive");
        }

        // 합계 일치
        long sum = payments.stream().mapToLong(PaymentRequest::amount).sum();
        if (sum != totalAmount) {
            throw new InvalidPaymentCombinationException(
                    "amount sum mismatch: payments=%d expected=%d".formatted(sum, totalAmount));
        }
    }
}
