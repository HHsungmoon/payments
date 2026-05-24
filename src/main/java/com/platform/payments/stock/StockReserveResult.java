package com.platform.payments.stock;

// conditional_decr_or_wait Lua 결과
public record StockReserveResult(
        Type type,
        Integer remaining,      // SLOT 일 때 차감 후 잔량
        Integer position        // WAITLIST 일 때 1-based 위치
) {
    public enum Type { SLOT, WAITLIST, FULL }

    public static StockReserveResult slot(int remaining) {
        return new StockReserveResult(Type.SLOT, remaining, null);
    }

    public static StockReserveResult waitlist(int position) {
        return new StockReserveResult(Type.WAITLIST, null, position);
    }

    public static StockReserveResult full() {
        return new StockReserveResult(Type.FULL, null, null);
    }

    public boolean isSlot()     { return type == Type.SLOT; }
    public boolean isWaitlist() { return type == Type.WAITLIST; }
    public boolean isFull()     { return type == Type.FULL; }
}
