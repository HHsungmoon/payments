package com.platform.payments.booking.wait;

// wait:token 만료 또는 무효 → 410 Gone
public class WaitTokenNotFoundException extends RuntimeException {

    public WaitTokenNotFoundException(String waitToken) {
        super("wait token not found or expired: " + waitToken);
    }
}
