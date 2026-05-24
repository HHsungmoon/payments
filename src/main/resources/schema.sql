-- payments 서비스 스키마 (개발용 단일 파일)
-- spring.sql.init.mode=always + ddl-auto=validate 조합으로 매 부팅 시 적용.
-- 모든 테이블은 IF NOT EXISTS — 멱등 실행.

-- ── 1. 고객 ─────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS customer (
    id         BIGINT       NOT NULL AUTO_INCREMENT,
    name       VARCHAR(64)  NOT NULL,
    created_at DATETIME(6)  NOT NULL,
    PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS customer_point (
    customer_id BIGINT      NOT NULL,
    balance     BIGINT      NOT NULL,
    version     BIGINT      NOT NULL,
    updated_at  DATETIME(6) NOT NULL,
    PRIMARY KEY (customer_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ── 2. 상품 ─────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS product (
    id           BIGINT       NOT NULL AUTO_INCREMENT,
    name         VARCHAR(255) NOT NULL,
    price        BIGINT       NOT NULL,
    stock_total  INT          NOT NULL,
    open_at      DATETIME(6)  NOT NULL,
    check_in_at  DATETIME(6)  NOT NULL,
    check_out_at DATETIME(6)  NOT NULL,
    created_at   DATETIME(6)  NOT NULL,
    updated_at   DATETIME(6)  NOT NULL,
    PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ── 3. 예약 (booking) — active_key generated column 으로 1인 1상품 보장 ─
CREATE TABLE IF NOT EXISTS booking (
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    customer_id     BIGINT       NOT NULL,
    product_id      BIGINT       NOT NULL,
    status          ENUM('WAITING','PENDING','PAID','FAILED','EXPIRED') NOT NULL,
    failed_reason   ENUM('CAPTURE_FAILED','INSUFFICIENT_POINT','INVALID_COMBINATION','LIMIT_EXCEEDED','PAYMENT_DECLINED','PG_TIMEOUT','PG_UNAVAILABLE','SLOT_TIMEOUT','SYSTEM_ERROR','TIMEOUT','USER_CANCELED','WAIT_TIMEOUT') DEFAULT NULL,
    total_amount    BIGINT       NOT NULL,
    idempotency_key VARCHAR(128) NOT NULL,
    request_hash    VARCHAR(64)  NOT NULL,
    wait_token      VARCHAR(64)  DEFAULT NULL,
    slot_token      VARCHAR(64)  DEFAULT NULL,
    enqueued_at     DATETIME(6)  DEFAULT NULL,
    promoted_at     DATETIME(6)  DEFAULT NULL,
    paid_at         DATETIME(6)  DEFAULT NULL,
    failed_at       DATETIME(6)  DEFAULT NULL,
    expired_at      DATETIME(6)  DEFAULT NULL,
    created_at      DATETIME(6)  NOT NULL,
    updated_at      DATETIME(6)  NOT NULL,
    -- DECISIONS §6 시나리오 ② — 1인 1자리 (WAITING/PENDING/PAID 만 활성)
    active_key      VARCHAR(64) GENERATED ALWAYS AS (
                        CASE WHEN status IN ('WAITING','PENDING','PAID')
                             THEN CONCAT(customer_id,'-',product_id) END
                    ) STORED,
    PRIMARY KEY (id),
    UNIQUE KEY uk_booking_idem        (idempotency_key),
    UNIQUE KEY uk_booking_wait_token  (wait_token),
    UNIQUE KEY uk_booking_active      (active_key),
    KEY        idx_booking_status_created     (status, created_at),
    KEY        idx_booking_customer_product   (customer_id, product_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ── 4. 결제 (payment) — booking 1 : N ──────────────────
CREATE TABLE IF NOT EXISTS payment (
    id                 BIGINT       NOT NULL AUTO_INCREMENT,
    booking_id         BIGINT       NOT NULL,
    method             ENUM('CARD','YPAY','POINT') NOT NULL,
    amount             BIGINT       NOT NULL,
    status             ENUM('REQUESTED','AUTHORIZED','CAPTURED','VOIDED','FAILED') NOT NULL,
    pg_idempotency_key VARCHAR(128) NOT NULL,
    pg_auth_id         VARCHAR(128) DEFAULT NULL,
    failed_reason      VARCHAR(255) DEFAULT NULL,
    requested_at       DATETIME(6)  NOT NULL,
    authorized_at      DATETIME(6)  DEFAULT NULL,
    captured_at        DATETIME(6)  DEFAULT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_payment_pg_idem         (pg_idempotency_key),
    UNIQUE KEY uk_payment_booking_method  (booking_id, method),
    KEY        idx_payment_status_authorized (status, authorized_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ── 5. 포인트 이력 (HOLD/COMMIT/RELEASE/REFUND/ADJUST) ───
CREATE TABLE IF NOT EXISTS point_transaction (
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    customer_id   BIGINT       NOT NULL,
    booking_id    BIGINT       DEFAULT NULL,
    delta         BIGINT       NOT NULL,
    reason        ENUM('BOOKING_HOLD','BOOKING_COMMIT','BOOKING_RELEASE','BOOKING_REFUND','ADMIN_ADJUST') NOT NULL,
    reference_key VARCHAR(128) NOT NULL,
    created_at    DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_point_tx_ref               (reference_key),
    KEY        idx_point_tx_customer_created (customer_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ── 6. Outbox 이벤트 ────────────────────────────────────
CREATE TABLE IF NOT EXISTS outbox_event (
    id              BIGINT NOT NULL AUTO_INCREMENT,
    aggregate_type  ENUM('BOOKING','PAYMENT') NOT NULL,
    aggregate_id    BIGINT NOT NULL,
    event_type      ENUM('BOOKING_PAID','BOOKING_FAILED','BOOKING_EXPIRED','WAIT_PROMOTED','COMPENSATION_VOID','COMPENSATION_POINT_RELEASE','COMPENSATION_STOCK_RESTORE','POINT_COMMIT_RETRY') NOT NULL,
    payload         JSON   NOT NULL,
    status          ENUM('PENDING','SENT','FAILED','DEAD_LETTER') NOT NULL,
    retry_count     INT    NOT NULL,
    next_attempt_at DATETIME(6) NOT NULL,
    sent_at         DATETIME(6) DEFAULT NULL,
    created_at      DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    KEY idx_outbox_status_next (status, next_attempt_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ── 7. Inbox 이벤트 (인터페이스 골격) ──────────────────
CREATE TABLE IF NOT EXISTS inbox_event (
    id                BIGINT       NOT NULL AUTO_INCREMENT,
    source            VARCHAR(64)  NOT NULL,
    external_event_id VARCHAR(128) NOT NULL,
    payload           JSON         NOT NULL,
    status            ENUM('RECEIVED','PROCESSED') NOT NULL,
    received_at       DATETIME(6)  NOT NULL,
    processed_at      DATETIME(6)  DEFAULT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_inbox_source_event (source, external_event_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
