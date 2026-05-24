package com.platform.payments.booking;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class WaitPollingController {

    private final WaitPollingService waitPollingService;
    private final BookingRepository bookingRepo;

    @GetMapping("/booking/wait/{token}")
    public ResponseEntity<WaitStatusResponse> poll(@PathVariable String token) {
        WaitStatusResponse response = waitPollingService.poll(token);
        // FAILED 만 422, 그 외 (WAITING/PROCESSING/PAID) 200
        if ("FAILED".equals(response.status())) {
            return ResponseEntity.status(422).body(response);
        }
        return ResponseEntity.ok(response);
    }

    @GetMapping("/booking/{id}")
    public ResponseEntity<Booking> get(@PathVariable Long id) {
        return bookingRepo.findById(id)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
