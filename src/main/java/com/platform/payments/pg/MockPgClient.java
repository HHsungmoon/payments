package com.platform.payments.pg;

import com.platform.payments.common.properties.MockPgProperties;
import io.github.resilience4j.bulkhead.annotation.Bulkhead;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import java.net.http.HttpClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

@Component
@Slf4j
public class MockPgClient implements PaymentGateway {

    private static final String CB_NAME = "pgGateway";
    private static final String BH_NAME = "pgGateway";

    private final RestClient client;

    public MockPgClient(MockPgProperties props) {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(props.connectTimeout())
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(props.readTimeout());

        this.client = RestClient.builder()
                .baseUrl(props.baseUrl())
                .requestFactory(factory)
                .build();
    }

    // ── Authorize ─────────────────────────────────────────────

    @CircuitBreaker(name = CB_NAME)
    @Bulkhead(name = BH_NAME)
    @Override
    public AuthorizeResult authorize(AuthorizeRequest req) {
        try {
            return client.post()
                    .uri("/pg/authorize")
                    .body(req)
                    .retrieve()
                    .body(AuthorizeResult.class);
        } catch (HttpClientErrorException e) {
            throw new PaymentDeclinedException(e.getStatusCode(),
                    "PG authorize declined: " + e.getStatusCode(), e);
        } catch (HttpServerErrorException e) {
            throw new PgTimeoutException("PG authorize server error: " + e.getStatusCode(), e);
        } catch (RestClientException e) {
            throw new PgTimeoutException("PG authorize call failed", e);
        }
    }

    // ── Capture ───────────────────────────────────────────────

    @CircuitBreaker(name = CB_NAME)
    @Bulkhead(name = BH_NAME)
    @Override
    public CaptureResult capture(String authId, String idempotencyKey) {
        try {
            return client.post()
                    .uri("/pg/capture")
                    .body(new CaptureRequest(authId, idempotencyKey))
                    .retrieve()
                    .body(CaptureResult.class);
        } catch (HttpClientErrorException e) {
            throw new PaymentDeclinedException(e.getStatusCode(),
                    "PG capture declined: " + e.getStatusCode(), e);
        } catch (HttpServerErrorException e) {
            throw new PgTimeoutException("PG capture server error: " + e.getStatusCode(), e);
        } catch (RestClientException e) {
            throw new PgTimeoutException("PG capture call failed", e);
        }
    }

    // ── Void ──────────────────────────────────────────────────

    @CircuitBreaker(name = CB_NAME)
    @Bulkhead(name = BH_NAME)
    @Override
    public VoidResult voidAuth(String authId, String idempotencyKey) {
        try {
            return client.post()
                    .uri("/pg/void")
                    .body(new VoidRequest(authId, idempotencyKey))
                    .retrieve()
                    .body(VoidResult.class);
        } catch (HttpClientErrorException e) {
            // VOID 4xx 는 보통 "이미 capture됨" 같은 상태 충돌 — 호출자가 PG query로 확인
            throw new PaymentDeclinedException(e.getStatusCode(),
                    "PG void rejected: " + e.getStatusCode(), e);
        } catch (HttpServerErrorException e) {
            throw new PgTimeoutException("PG void server error: " + e.getStatusCode(), e);
        } catch (RestClientException e) {
            throw new PgTimeoutException("PG void call failed", e);
        }
    }

    // ── Query (시나리오 ⑥ 복구용) ─────────────────────────────

    @CircuitBreaker(name = CB_NAME)
    @Bulkhead(name = BH_NAME)
    @Override
    public QueryResult query(String idempotencyKey) {
        try {
            return client.get()
                    .uri("/pg/transactions/{key}", idempotencyKey)
                    .retrieve()
                    .body(QueryResult.class);
        } catch (HttpClientErrorException.NotFound e) {
            return new QueryResult("NOT_FOUND", null);
        } catch (HttpServerErrorException e) {
            throw new PgTimeoutException("PG query server error: " + e.getStatusCode(), e);
        } catch (RestClientException e) {
            throw new PgTimeoutException("PG query call failed", e);
        }
    }
}
