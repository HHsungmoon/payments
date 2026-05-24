-- 평가용 시드 (멱등: INSERT IGNORE)
-- customer 1~1000 + customer_point 1~1000 + product 1
-- Spring Boot: spring.jpa.defer-datasource-initialization=true, spring.sql.init.mode=always
--
-- 1000명 이유: 명세 1000 TPS × 1~5분 시뮬을 위해
--   1인1상품 제약이 있어 부하 테스트 시 각자 다른 customer_id 사용 필요.

INSERT IGNORE INTO customer (id, name, created_at)
WITH RECURSIVE seq(n) AS (
    SELECT 1 UNION ALL SELECT n + 1 FROM seq WHERE n < 1000
)
SELECT n, CONCAT('customer-', n), NOW(3) FROM seq;

INSERT IGNORE INTO customer_point (customer_id, balance, version, updated_at)
WITH RECURSIVE seq(n) AS (
    SELECT 1 UNION ALL SELECT n + 1 FROM seq WHERE n < 1000
)
SELECT n, 100000, 0, NOW(3) FROM seq;

INSERT IGNORE INTO product (id, name, price, check_in_at, check_out_at, stock_total, open_at, created_at, updated_at)
VALUES (1, '한정 특가 숙소', 50000,
        DATE_ADD(NOW(), INTERVAL 1 DAY),
        DATE_ADD(NOW(), INTERVAL 2 DAY),
        10, NOW(), NOW(3), NOW(3));
