package com.platform.payments.stock;

// restore_stock_and_promote Lua 결과
public record PromotionResult(
        Type type,
        String waitToken        // PROMOTED 일 때만
) {
    public enum Type { RESTORED, PROMOTED, OVERFLOW }

    public static PromotionResult restored()              { return new PromotionResult(Type.RESTORED, null); }
    public static PromotionResult promoted(String token)  { return new PromotionResult(Type.PROMOTED, token); }
    public static PromotionResult overflow()              { return new PromotionResult(Type.OVERFLOW, null); }

    public boolean isPromoted() { return type == Type.PROMOTED; }
    public boolean isRestored() { return type == Type.RESTORED; }
    public boolean isOverflow() { return type == Type.OVERFLOW; }
}
