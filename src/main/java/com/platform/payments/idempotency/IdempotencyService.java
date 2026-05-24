package com.platform.payments.idempotency;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.platform.payments.common.properties.IdempotencyProperties;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

@Service
@Slf4j
public class IdempotencyService {

    private static final Pattern KEY_PATTERN = Pattern.compile("[A-Za-z0-9_\\-]+");

    private final StringRedisTemplate redis;
    private final IdempotencyProperties props;
    private final ObjectMapper cacheMapper;       // CachedResponse 직렬화 (Instant 지원)
    private final ObjectMapper canonicalMapper;   // request body → canonical JSON (정렬+정규화)

    public IdempotencyService(StringRedisTemplate redis, IdempotencyProperties props) {
        this.redis = redis;
        this.props = props;
        this.cacheMapper = JsonMapper.builder()
                .addModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .build();
        // canonical: 키 알파벳순 + null 제외 — 같은 의미의 다른 표기도 같은 hash
        // 우리 시스템은 모든 금액을 Long 으로 다뤄 BigDecimal 처리 불필요
        this.canonicalMapper = JsonMapper.builder()
                .addModule(new JavaTimeModule())
                .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
                .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .defaultPropertyInclusion(
                        JsonInclude.Value.empty().withValueInclusion(JsonInclude.Include.NON_NULL))
                .build();
    }

    public static String idemKey(String key) {
        return "idem:" + key;
    }

    // 헤더 검증 + 누락 시 server-{uuid} fallback
    public String resolveOrFallback(String headerValue) {
        if (headerValue == null || headerValue.isBlank()) {
            return props.serverFallbackPrefix() + UUID.randomUUID();
        }
        String trimmed = headerValue.trim();
        int len = trimmed.length();
        if (len < props.keyMinLength() || len > props.keyMaxLength()) {
            throw new InvalidIdempotencyKeyException(
                    "Idempotency-Key length out of range: " + len);
        }
        if (!KEY_PATTERN.matcher(trimmed).matches()) {
            throw new InvalidIdempotencyKeyException(
                    "Idempotency-Key has invalid characters");
        }
        return trimmed;
    }

    // request body → canonical JSON → SHA-256 hex
    public String hashCanonical(Object body) {
        try {
            String canonical = canonicalMapper.writeValueAsString(body);
            return sha256Hex(canonical);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("canonical serialization failed", e);
        }
    }

    // Redis 캐시 조회 + hash 검증
    // 있고 일치 → CachedResponse 반환
    // 있고 불일치 → IdempotencyKeyReusedException
    // 없음 → empty
    public Optional<CachedResponse> lookup(String key, String requestHash) {
        String json = redis.opsForValue().get(idemKey(key));
        if (json == null) {
            return Optional.empty();
        }
        CachedResponse cached;
        try {
            cached = cacheMapper.readValue(json, CachedResponse.class);
        } catch (JsonProcessingException e) {
            log.error("IDEM_CACHE_DESERIALIZE_FAILED key={}", key, e);
            return Optional.empty();
        }
        if (!cached.requestHash().equals(requestHash)) {
            throw new IdempotencyKeyReusedException(key, cached.requestHash(), requestHash);
        }
        return Optional.of(cached);
    }

    // 응답 캐시 저장 (TTL 24h). 호출자가 캐시 정책 (5xx·422 등) 책임.
    public void store(String key, String requestHash, int status, String body) {
        CachedResponse cached = new CachedResponse(status, body, requestHash, Instant.now());
        try {
            String json = cacheMapper.writeValueAsString(cached);
            redis.opsForValue().set(
                    idemKey(key),
                    json,
                    Duration.ofSeconds(props.ttlSeconds())
            );
        } catch (JsonProcessingException e) {
            log.error("IDEM_CACHE_SERIALIZE_FAILED key={}", key, e);
        }
    }

    // ── 내부 유틸 ─────────────────────────────────────────────

    private static String sha256Hex(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
