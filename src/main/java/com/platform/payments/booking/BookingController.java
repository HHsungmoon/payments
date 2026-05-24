package com.platform.payments.booking;

import com.platform.payments.booking.dto.BookingCreateRequest;
import com.platform.payments.booking.dto.BookingOutput;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class BookingController {

    private final BookingService bookingService;

    @PostMapping("/booking")
    public ResponseEntity<?> book(
            @RequestHeader(value = "Idempotency-Key", required = false) String idemHeader,
            @Valid @RequestBody BookingCreateRequest req) {
        BookingOutput out = bookingService.book(req, idemHeader);
        return toResponse(out);
    }

    static ResponseEntity<?> toResponse(BookingOutput out) {
        ResponseEntity.BodyBuilder builder = ResponseEntity.status(out.httpStatus())
                .header("Idempotency-Key", out.idemKey());
        if (out.retryAfterSeconds() != null) {
            builder.header("Retry-After", out.retryAfterSeconds().toString());
        }
        if (out.isRawBody()) {
            return builder.contentType(MediaType.APPLICATION_JSON).body(out.body());
        }
        return builder.body(out.body());
    }
}
