package com.platform.payments.common;

import com.platform.payments.booking.wait.WaitTokenNotFoundException;
import com.platform.payments.idempotency.IdempotencyKeyReusedException;
import com.platform.payments.idempotency.InvalidIdempotencyKeyException;
import com.platform.payments.payment.validation.InvalidPaymentCombinationException;
import com.platform.payments.point.InsufficientPointException;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    @ExceptionHandler(InvalidIdempotencyKeyException.class)
    public ResponseEntity<ErrorResponse> idemFormat(InvalidIdempotencyKeyException e) {
        return ResponseEntity.status(400)
                .body(ErrorResponse.of("INVALID_IDEMPOTENCY_KEY", e.getMessage()));
    }

    @ExceptionHandler(IdempotencyKeyReusedException.class)
    public ResponseEntity<ErrorResponse> idemReused(IdempotencyKeyReusedException e) {
        return ResponseEntity.status(422)
                .body(ErrorResponse.of("IDEMPOTENCY_KEY_REUSED", e.getMessage()));
    }

    @ExceptionHandler(InvalidPaymentCombinationException.class)
    public ResponseEntity<ErrorResponse> badCombo(InvalidPaymentCombinationException e) {
        return ResponseEntity.status(422)
                .body(ErrorResponse.of("INVALID_COMBINATION", e.getMessage()));
    }

    @ExceptionHandler(InsufficientPointException.class)
    public ResponseEntity<ErrorResponse> insufficientPoint(InsufficientPointException e) {
        return ResponseEntity.status(422)
                .body(ErrorResponse.of("INSUFFICIENT_POINT", e.getMessage()));
    }

    @ExceptionHandler(WaitTokenNotFoundException.class)
    public ResponseEntity<ErrorResponse> waitNotFound(WaitTokenNotFoundException e) {
        return ResponseEntity.status(410)
                .body(ErrorResponse.of("EXPIRED", e.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> validationFail(MethodArgumentNotValidException e) {
        String msg = e.getBindingResult().getFieldErrors().stream()
                .map(f -> f.getField() + ": " + f.getDefaultMessage())
                .collect(Collectors.joining(", "));
        return ResponseEntity.status(400)
                .body(ErrorResponse.of("VALIDATION_FAILED", msg));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorResponse> badRequest(IllegalArgumentException e) {
        return ResponseEntity.status(400)
                .body(ErrorResponse.of("BAD_REQUEST", e.getMessage()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> unknown(Exception e) {
        log.error("UNHANDLED_EXCEPTION", e);
        return ResponseEntity.status(500)
                .body(ErrorResponse.of("INTERNAL_ERROR", "Unexpected error"));
    }
}
