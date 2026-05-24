package com.platform.payments.booking;

import com.platform.payments.pg.PaymentDeclinedException;
import com.platform.payments.pg.PgTimeoutException;
import com.platform.payments.pg.PgUnavailableException;
import com.platform.payments.point.InsufficientPointException;
import io.github.resilience4j.bulkhead.BulkheadFullException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;

// 예외 → FailureReason → HTTP status 매핑 (BookingService / PromotionService 공용)
public final class FailureReasonMapper {

    private FailureReasonMapper() {}

    public static FailureReason fromException(Throwable t) {
        if (t instanceof PaymentDeclinedException)    return FailureReason.PAYMENT_DECLINED;
        if (t instanceof InsufficientPointException)  return FailureReason.INSUFFICIENT_POINT;
        if (t instanceof PgTimeoutException)          return FailureReason.PG_TIMEOUT;
        if (t instanceof PgUnavailableException)      return FailureReason.PG_UNAVAILABLE;
        if (t instanceof CallNotPermittedException)   return FailureReason.PG_UNAVAILABLE;
        if (t instanceof BulkheadFullException)       return FailureReason.PG_UNAVAILABLE;
        return FailureReason.SYSTEM_ERROR;
    }

    public static int toHttpStatus(FailureReason reason) {
        return switch (reason) {
            case INSUFFICIENT_POINT, PAYMENT_DECLINED, LIMIT_EXCEEDED -> 422;
            case PG_TIMEOUT, PG_UNAVAILABLE                            -> 503;
            default                                                     -> 422;
        };
    }
}
