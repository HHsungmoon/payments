package com.platform.payments.booking;

import com.platform.payments.payment.AuthorizedPayment;
import com.platform.payments.payment.Payment;
import com.platform.payments.payment.PaymentRepository;
import com.platform.payments.payment.PaymentStatus;
import com.platform.payments.payment.PaymentStrategy;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// DB tx 단위 분리 — BookingService(흐름 조립)와 분리되어 self-invocation 회피
@Service
@RequiredArgsConstructor
public class BookingPersistence {

    private final BookingRepository bookingRepo;
    private final PaymentRepository paymentRepo;

    // tx — booking WAITING 진입 + payment 사전 INSERT (REQUESTED)
    // payment row는 승격 후 결제 진행 시 그대로 사용 (사전 멱등 키 확보)
    @Transactional
    public Booking insertWaitingWithPayments(BookingCreateRequest req, String idemKey, String requestHash,
                                              long totalAmount, String waitToken) {
        Booking booking = bookingRepo.saveAndFlush(Booking.builder()
                .customerId(req.customerId())
                .productId(req.productId())
                .status(BookingStatus.WAITING)
                .totalAmount(totalAmount)
                .idempotencyKey(idemKey)
                .requestHash(requestHash)
                .waitToken(waitToken)
                .enqueuedAt(Instant.now())
                .build());

        List<Payment> payments = req.payments().stream()
                .map(p -> Payment.builder()
                        .bookingId(booking.getId())
                        .method(p.method())
                        .amount(p.amount())
                        .status(PaymentStatus.REQUESTED)
                        .pgIdempotencyKey(PaymentStrategy.pgIdempotencyKey(booking.getId(), p.method()))
                        .build())
                .toList();
        paymentRepo.saveAll(payments);

        return booking;
    }

    // tx1 — booking PENDING + payment 사전 INSERT (REQUESTED)
    @Transactional
    public Booking insertPending(BookingCreateRequest req, String idemKey, String requestHash,
                                 long totalAmount) {
        Booking booking = bookingRepo.saveAndFlush(Booking.builder()
                .customerId(req.customerId())
                .productId(req.productId())
                .status(BookingStatus.PENDING)
                .totalAmount(totalAmount)
                .idempotencyKey(idemKey)
                .requestHash(requestHash)
                .build());

        List<Payment> payments = req.payments().stream()
                .map(p -> Payment.builder()
                        .bookingId(booking.getId())
                        .method(p.method())
                        .amount(p.amount())
                        .status(PaymentStatus.REQUESTED)
                        .pgIdempotencyKey(PaymentStrategy.pgIdempotencyKey(booking.getId(), p.method()))
                        .build())
                .toList();
        paymentRepo.saveAll(payments);

        return booking;
    }

    // tx2 — payment AUTHORIZED 반영
    @Transactional
    public void markPaymentsAuthorized(Long bookingId, List<AuthorizedPayment> authorized) {
        List<Payment> payments = paymentRepo.findByBookingIdOrderById(bookingId);
        Instant now = Instant.now();
        for (Payment p : payments) {
            authorized.stream()
                    .filter(ap -> ap.method() == p.getMethod())
                    .findFirst()
                    .ifPresent(ap -> p.markAuthorized(ap.authReference(), now));
        }
    }

    // tx3 — booking PAID + payment CAPTURED
    @Transactional
    public void finalizePaid(Long bookingId) {
        Booking booking = bookingRepo.findById(bookingId).orElseThrow();
        List<Payment> payments = paymentRepo.findByBookingIdOrderById(bookingId);
        Instant now = Instant.now();
        for (Payment p : payments) {
            if (p.getStatus() == PaymentStatus.AUTHORIZED) {
                p.markCaptured(now);
            }
        }
        booking.markPaid(now);
    }

    // tx — booking FAILED (보상 후)
    @Transactional
    public void finalizeFailed(Long bookingId, FailureReason reason) {
        Booking booking = bookingRepo.findById(bookingId).orElseThrow();
        List<Payment> payments = paymentRepo.findByBookingIdOrderById(bookingId);
        for (Payment p : payments) {
            if (p.getStatus() == PaymentStatus.AUTHORIZED) {
                p.markVoided();
            } else if (p.getStatus() == PaymentStatus.REQUESTED) {
                p.markFailed(reason.name());
            }
        }
        booking.markFailed(reason, Instant.now());
    }

    public static String newWaitToken() {
        return "wt_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }

    public static String newSlotToken() {
        return "slot_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }
}
