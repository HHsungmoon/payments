package com.platform.payments.booking;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.platform.payments.outbox.OutboxEventRepository;
import com.platform.payments.payment.PaymentRepository;
import com.platform.payments.point.PointTransactionRepository;
import com.platform.payments.stock.StockService;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

// 7개 결제 시나리오 통합 테스트
// 외부 인프라(mysql, redis, mock-pg) 가 docker compose 로 실행 중이어야 함
@SpringBootTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class BookingScenarioTest {

    private static final long PRODUCT_ID = 1L;

    @Autowired WebApplicationContext context;
    @Autowired StockService stockService;
    @Autowired JdbcTemplate jdbc;
    MockMvc mockMvc;
    @Autowired BookingRepository bookingRepo;
    @Autowired PaymentRepository paymentRepo;
    @Autowired PointTransactionRepository pointTxRepo;
    @Autowired OutboxEventRepository outboxRepo;
    @Autowired StringRedisTemplate redis;

    @BeforeEach
    void resetState() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).build();
        // 이전 테스트 잔존 데이터 정리 (외래키 순서 주의)
        paymentRepo.deleteAll();
        pointTxRepo.deleteAll();
        bookingRepo.deleteAll();
        outboxRepo.deleteAll();
        // 테스트 customer 포인트 잔액 복원 (반복 실행 시 누적 차감 방지)
        jdbc.update("UPDATE customer_point SET balance = 100000 WHERE customer_id BETWEEN 10 AND 99");
        // Redis stock 초기화
        stockService.setStock(PRODUCT_ID, 10);
        // idem 캐시 정리 (이전 테스트 키 잔존 방지)
        var keys = redis.keys("idem:*");
        if (keys != null && !keys.isEmpty()) redis.delete(keys);
    }

    // ── 시나리오 1: CARD 단독 결제 → 200 PAID ─────────────────
    @Test
    @DisplayName("S1 — CARD 단독 (포인트 안 씀) → 200 PAID")
    void cardOnly() throws Exception {
        long customerId = 10L;
        String body = """
                {"customerId":%d,"productId":%d,"payments":[{"method":"CARD","amount":50000}]}
                """.formatted(customerId, PRODUCT_ID);

        mockMvc.perform(post("/booking")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PAID"))
                .andExpect(jsonPath("$.bookingId").exists())
                .andExpect(jsonPath("$.payments[0].method").value("CARD"))
                .andExpect(jsonPath("$.payments[0].status").value("CAPTURED"));
    }

    // ── 시나리오 2: YPAY + POINT 복합 → 200 PAID ────────────
    @Test
    @DisplayName("S2 — YPAY + POINT 복합결제 → 200 PAID")
    void ypayPlusPoint() throws Exception {
        long customerId = 11L;
        String body = """
                {"customerId":%d,"productId":%d,"payments":[
                    {"method":"YPAY","amount":45000},
                    {"method":"POINT","amount":5000}
                ]}
                """.formatted(customerId, PRODUCT_ID);

        mockMvc.perform(post("/booking")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PAID"))
                .andExpect(jsonPath("$.totalAmount").value(50000))
                .andExpect(jsonPath("$.payments.length()").value(2));
    }

    // ── 시나리오 3: POINT 단독 결제 → 200 PAID ──────────────
    @Test
    @DisplayName("S3 — POINT 단독 결제 → 200 PAID")
    void pointOnly() throws Exception {
        long customerId = 12L;
        String body = """
                {"customerId":%d,"productId":%d,"payments":[{"method":"POINT","amount":50000}]}
                """.formatted(customerId, PRODUCT_ID);

        mockMvc.perform(post("/booking")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PAID"))
                .andExpect(jsonPath("$.payments[0].method").value("POINT"))
                .andExpect(jsonPath("$.payments[0].status").value("CAPTURED"));
    }

    // ── 시나리오 4: 같은 Idem-Key 재호출 → 캐시 응답 재생 ─────
    @Test
    @DisplayName("S4 — 같은 Idem-Key 재호출 → 캐시 응답 재생")
    void idempotencyReplay() throws Exception {
        long customerId = 13L;
        String idemKey = UUID.randomUUID().toString();
        String body = """
                {"customerId":%d,"productId":%d,"payments":[{"method":"POINT","amount":50000}]}
                """.formatted(customerId, PRODUCT_ID);

        // 첫 호출
        MvcResult first = mockMvc.perform(post("/booking")
                        .header("Idempotency-Key", idemKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PAID"))
                .andReturn();
        String firstResponse = first.getResponse().getContentAsString();

        // 같은 키 재호출 — bookingId 동일해야 (캐시 재생)
        com.fasterxml.jackson.databind.ObjectMapper om = new com.fasterxml.jackson.databind.ObjectMapper();
        Long firstBookingId = om.readTree(firstResponse).get("bookingId").asLong();

        mockMvc.perform(post("/booking")
                        .header("Idempotency-Key", idemKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(header().string("Idempotency-Key", idemKey))
                .andExpect(jsonPath("$.status").value("PAID"))
                .andExpect(jsonPath("$.bookingId").value(firstBookingId));
    }

    // ── 시나리오 5: 같은 Idem-Key + 다른 body → 422 ─────────
    @Test
    @DisplayName("S5 — 같은 Idem-Key + 다른 body → 422 IDEMPOTENCY_KEY_REUSED")
    void idempotencyReusedWithDifferentBody() throws Exception {
        long customerId = 14L;
        String idemKey = UUID.randomUUID().toString();
        String firstBody = """
                {"customerId":%d,"productId":%d,"payments":[{"method":"POINT","amount":50000}]}
                """.formatted(customerId, PRODUCT_ID);
        String secondBody = """
                {"customerId":%d,"productId":%d,"payments":[{"method":"CARD","amount":50000}]}
                """.formatted(customerId, PRODUCT_ID);

        mockMvc.perform(post("/booking")
                        .header("Idempotency-Key", idemKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(firstBody))
                .andExpect(status().isOk());

        mockMvc.perform(post("/booking")
                        .header("Idempotency-Key", idemKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(secondBody))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.reason").value("IDEMPOTENCY_KEY_REUSED"));
    }

    // ── 시나리오 6: CARD+YPAY 조합 → 422 ────────────────────
    @Test
    @DisplayName("S6 — CARD + YPAY (MAIN 2개) → 422 INVALID_COMBINATION")
    void invalidCombination() throws Exception {
        long customerId = 15L;
        String body = """
                {"customerId":%d,"productId":%d,"payments":[
                    {"method":"CARD","amount":30000},
                    {"method":"YPAY","amount":20000}
                ]}
                """.formatted(customerId, PRODUCT_ID);

        mockMvc.perform(post("/booking")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.reason").value("INVALID_COMBINATION"));
    }

    // ── 시나리오 7: 포인트 잔액 초과 → 422 ──────────────────
    @Test
    @DisplayName("S7 — 포인트 잔액 초과 (POINT 150,000 vs balance 100,000) → 422 INSUFFICIENT_POINT")
    void insufficientPoint() throws Exception {
        long customerId = 16L;
        // POINT 단독 150,000 — customer 16의 잔액 100,000 초과
        String body = """
                {"customerId":%d,"productId":%d,"payments":[{"method":"POINT","amount":150000}]}
                """.formatted(customerId, PRODUCT_ID);

        mockMvc.perform(post("/booking")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.reason").value("INSUFFICIENT_POINT"));
    }

    // ── 시나리오 8: 같은 customer + product 재호출 → 409 ─────
    @Test
    @DisplayName("S8 — 같은 customer + product 재호출 (다른 idem-key) → 409 ALREADY_RESERVED")
    void alreadyReserved() throws Exception {
        long customerId = 17L;
        String body = """
                {"customerId":%d,"productId":%d,"payments":[{"method":"CARD","amount":50000}]}
                """.formatted(customerId, PRODUCT_ID);

        // 첫 호출 — PAID
        mockMvc.perform(post("/booking")
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PAID"));

        // 다른 idem-key 재호출 — active_key 충돌 (사전 existsBy 또는 DB UNIQUE)
        mockMvc.perform(post("/booking")
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.reason").value("ALREADY_RESERVED"));
    }
}
